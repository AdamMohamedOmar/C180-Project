package com.kompressorlink.app.telemetry.ble

import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.SourceChoice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BleSessionTest {

    private class CountingLink : GattLink {
        var connects = 0
        var disconnects = 0
        override val events: SharedFlow<GattEvent> = MutableSharedFlow(extraBufferCapacity = 4)
        override fun connect() { connects++ }
        override fun disconnect() { disconnects++ }
        override fun requestMtu(mtu: Int) {}
        override fun discoverServices() {}
        override fun enableNotifications(charUuid: String) {}
        override fun writeControl(value: ByteArray) {}
    }

    @Test
    fun `stays idle while a simulated source is selected`() = runTest {
        val link = CountingLink()
        val choice = MutableStateFlow(SourceChoice.SIMULATED_HEALTHY)
        val mac = MutableStateFlow<String?>("AA:BB:CC:DD:EE:FF")
        val session = BleSession(backgroundScope, mac, choice) { link }
        runCurrent()
        assertEquals(ConnectionState.NotAssociated, session.connectionState.value)
        assertEquals(0, link.connects)
    }

    @Test
    fun `real ble with no association reports NotAssociated`() = runTest {
        val link = CountingLink()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        val mac = MutableStateFlow<String?>(null)
        val session = BleSession(backgroundScope, mac, choice) { link }
        runCurrent()
        assertEquals(ConnectionState.NotAssociated, session.connectionState.value)
        assertEquals(0, link.connects)
    }

    @Test
    fun `real ble with an association starts connecting`() = runTest {
        val link = CountingLink()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        val mac = MutableStateFlow<String?>("AA:BB:CC:DD:EE:FF")
        val session = BleSession(backgroundScope, mac, choice) { link }
        runCurrent()
        assertEquals(1, link.connects)
        assertEquals(ConnectionState.Connecting, session.connectionState.value)
    }

    @Test
    fun `changing the mac after connecting stops the old link and connects a new one`() = runTest {
        val firstLink = CountingLink()
        val secondLink = CountingLink()
        var callCount = 0
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        val mac = MutableStateFlow<String?>("AA:BB:CC:DD:EE:FF")
        val session = BleSession(backgroundScope, mac, choice) {
            callCount++
            if (callCount == 1) firstLink else secondLink
        }
        runCurrent()
        assertEquals(1, firstLink.connects)
        assertEquals(ConnectionState.Connecting, session.connectionState.value)

        mac.value = "11:22:33:44:55:66"
        runCurrent()
        assertEquals(1, secondLink.connects)
        assertEquals(1, firstLink.disconnects)
        assertEquals(ConnectionState.Connecting, session.connectionState.value)
    }
}
