package com.kompressorlink.app.telemetry.ble

import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleTelemetrySourceTest {

    private class FakeLink : GattLink {
        val calls = mutableListOf<String>()
        private val _events = MutableSharedFlow<GattEvent>(extraBufferCapacity = 32)
        override val events: SharedFlow<GattEvent> = _events
        fun emit(e: GattEvent) { check(_events.tryEmit(e)) }
        override fun connect() { calls += "connect" }
        override fun disconnect() { calls += "disconnect" }
        override fun requestMtu(mtu: Int) { calls += "mtu:$mtu" }
        override fun discoverServices() { calls += "discover" }
        override fun enableNotifications(charUuid: String) { calls += "notify:$charUuid" }
        override fun writeControl(value: ByteArray) { calls += "write:${value.size}" }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex.substring(2 * it, 2 * it + 2)).toInt(16)).toByte() }

    // Golden vector T2 (sparse, demo flag) — mirror-verbatim of
    // docs/ble_protocol.md.
    private val t2Hex =
        "0101ffff0000000001080000" +
            "00004844" + "00000000" + "00000000" + "00000000" + "00000000" + "00000000" +
            "00000000" + "00000000" + "00000000" + "00000000" + "00000000" + "0000ae42" +
            "00000000" + "00000000" + "00000000" + "00000000" + "00000000" + "00000000" +
            "00000000"

    private fun happyPathToReady(link: FakeLink) {
        link.emit(GattEvent.Connected)
        link.emit(GattEvent.MtuChanged(517))
        link.emit(GattEvent.ServicesDiscovered)
        link.emit(GattEvent.NotifyEnabled(KlUuids.TELEMETRY))
        link.emit(GattEvent.NotifyEnabled(KlUuids.DTC))
    }

    @Test
    fun `walks the linear connect flow to Ready and sends time sync`() = runTest {
        val link = FakeLink()
        val source = BleTelemetrySource(backgroundScope, link, now = { 42L })
        source.start()
        runCurrent()
        assertEquals(listOf("connect"), link.calls)
        assertEquals(ConnectionState.Connecting, source.connectionState.value)

        link.emit(GattEvent.Connected); runCurrent()
        assertEquals("mtu:517", link.calls.last())
        link.emit(GattEvent.MtuChanged(517)); runCurrent()
        assertEquals("discover", link.calls.last())
        link.emit(GattEvent.ServicesDiscovered); runCurrent()
        assertEquals("notify:${KlUuids.TELEMETRY}", link.calls.last())
        link.emit(GattEvent.NotifyEnabled(KlUuids.TELEMETRY)); runCurrent()
        assertEquals("notify:${KlUuids.DTC}", link.calls.last())
        link.emit(GattEvent.NotifyEnabled(KlUuids.DTC)); runCurrent()

        assertTrue(source.connectionState.value is ConnectionState.Ready)
        assertEquals("write:9", link.calls.last())  // time sync control frame
    }

    @Test
    fun `telemetry frames update state from the demo flag and emit snapshots`() = runTest {
        val link = FakeLink()
        val source = BleTelemetrySource(backgroundScope, link, now = { 42L })
        source.start(); runCurrent()
        happyPathToReady(link); runCurrent()

        var received: TelemetrySnapshot? = null
        val job = launch { source.telemetry.collect { received = it } }
        runCurrent()
        link.emit(GattEvent.CharacteristicChanged(KlUuids.TELEMETRY, hexToBytes(t2Hex)))
        runCurrent()
        assertEquals(0xFFFF, received!!.seq)
        assertEquals(ConnectionState.Ready(demo = true, klineConnected = false),
                     source.connectionState.value)
        job.cancel()
    }

    @Test
    fun `unknown protocol version surfaces ProtocolMismatch`() = runTest {
        val link = FakeLink()
        val source = BleTelemetrySource(backgroundScope, link, now = { 42L })
        source.start(); runCurrent()
        happyPathToReady(link); runCurrent()
        val bad = hexToBytes(t2Hex); bad[0] = 0x7F
        link.emit(GattEvent.CharacteristicChanged(KlUuids.TELEMETRY, bad)); runCurrent()
        assertEquals(ConnectionState.ProtocolMismatch, source.connectionState.value)
    }

    @Test
    fun `dtc frames update the report`() = runTest {
        val link = FakeLink()
        val source = BleTelemetrySource(backgroundScope, link, now = { 42L })
        source.start(); runCurrent()
        happyPathToReady(link); runCurrent()
        link.emit(GattEvent.CharacteristicChanged(KlUuids.DTC, hexToBytes("0101000171")))
        runCurrent()
        assertEquals(listOf("P0171"), source.dtcReport.value!!.stored)
    }

    @Test
    fun `mtu below 91 aborts the connection instead of truncating`() = runTest {
        val link = FakeLink()
        val source = BleTelemetrySource(backgroundScope, link, now = { 42L })
        source.start(); runCurrent()
        link.emit(GattEvent.Connected); runCurrent()
        link.emit(GattEvent.MtuChanged(23)); runCurrent()
        assertEquals("disconnect", link.calls.last())
    }

    @Test
    fun `disconnect schedules a backoff retry`() = runTest {
        val link = FakeLink()
        val source = BleTelemetrySource(backgroundScope, link, now = { 42L })
        source.start(); runCurrent()
        link.emit(GattEvent.Disconnected(status = 133)); runCurrent()
        assertEquals(ConnectionState.Disconnected, source.connectionState.value)
        val connectsBefore = link.calls.count { it == "connect" }
        advanceTimeBy(1_001); runCurrent()  // first backoff step is 1 s
        assertEquals(connectsBefore + 1, link.calls.count { it == "connect" })
    }

    @Test
    fun `OperationFailed triggers disconnect, which the link (once truly disconnected) uses to retry`() = runTest {
        val link = FakeLink()
        val source = BleTelemetrySource(backgroundScope, link, now = { 42L })
        source.start(); runCurrent()

        link.emit(GattEvent.OperationFailed("something went wrong")); runCurrent()
        assertEquals("disconnect", link.calls.last())
        // A dumb FakeLink doesn't auto-synthesize Disconnected the way the
        // real GattClient does after disconnect() -- simulate that here to
        // prove the rest of the retry chain actually fires once it does.
        link.emit(GattEvent.Disconnected(status = -1)); runCurrent()
        assertEquals(ConnectionState.Disconnected, source.connectionState.value)

        val connectsBefore = link.calls.count { it == "connect" }
        advanceTimeBy(1_001); runCurrent()
        assertEquals(connectsBefore + 1, link.calls.count { it == "connect" })
    }
}
