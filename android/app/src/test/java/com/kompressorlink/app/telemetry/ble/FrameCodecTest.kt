package com.kompressorlink.app.telemetry.ble

import com.kompressorlink.app.telemetry.Signal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCodecTest {

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex.substring(2 * it, 2 * it + 2)).toInt(16)).toByte() }

    // Golden vector T1 — mirror-verbatim of docs/ble_protocol.md (one
    // string per doc line).
    private val t1Hex =
        "01023412a0860100ffff0700" +
            "00803b44" + "00000000" + "00008040" + "00004841" + "0000c841" + "000020c0" +
            "0000a040" + "0000c442" + "00002041" + "0000003f" + "0000403f" + "0000b442" +
            "00000c42" + "00000040" + "00007041" + "00007041" + "00004041" + "00006041" +
            "00006441"

    // Golden vector T2 — mirror-verbatim of docs/ble_protocol.md.
    private val t2Hex =
        "0101ffff0000000001080000" +
            "00004844" + "00000000" + "00000000" + "00000000" + "00000000" + "00000000" +
            "00000000" + "00000000" + "00000000" + "00000000" + "00000000" + "0000ae42" +
            "00000000" + "00000000" + "00000000" + "00000000" + "00000000" + "00000000" +
            "00000000"

    @Test
    fun `parses golden T1 fully populated frame`() {
        val s = FrameCodec.parseTelemetry(hexToBytes(t1Hex))!!
        assertEquals(0x1234, s.seq)
        assertEquals(100000L, s.uptimeMs)
        assertEquals(0x0007FFFF, s.availMask)
        assertTrue(s.klineConnected)
        assertFalse(s.isDemo)
        val expected = floatArrayOf(
            750f, 0f, 4f, 12.5f, 25f, -2.5f, 5f, 98f, 10f, 0.5f, 0.75f,
            90f, 35f, 2f, 15f, 15f, 12f, 14f, 14.25f,
        )
        for (sig in Signal.entries) {
            assertEquals("value of ${sig.name}", expected[sig.ordinal], s.value(sig))
        }
    }

    @Test
    fun `parses golden T2 sparse frame with mask semantics`() {
        val s = FrameCodec.parseTelemetry(hexToBytes(t2Hex))!!
        assertEquals(0xFFFF, s.seq)
        assertTrue(s.isDemo)
        assertEquals(800f, s.value(Signal.RPM))
        assertEquals(87f, s.value(Signal.ECT))
        assertNull(s.value(Signal.SPEED))       // masked out => no reading
        assertNull(s.value(Signal.BATT_V_ADC))  // Untested contract on the wire
    }

    @Test
    fun `rejects wrong protocol version`() {
        val bytes = hexToBytes(t1Hex)
        bytes[0] = 0x02
        assertNull(FrameCodec.parseTelemetry(bytes))
    }

    @Test
    fun `rejects wrong length`() {
        assertNull(FrameCodec.parseTelemetry(hexToBytes(t1Hex).copyOf(87)))
    }

    @Test
    fun `parses golden D1 dtc report`() {
        val r = FrameCodec.parseDtcReport(hexToBytes("0101000171"))!!
        assertEquals(listOf("P0171"), r.stored)
        assertTrue(r.pending.isEmpty())
    }

    @Test
    fun `parses golden D2 empty dtc report`() {
        val r = FrameCodec.parseDtcReport(hexToBytes("010000"))!!
        assertTrue(r.stored.isEmpty())
        assertTrue(r.pending.isEmpty())
    }

    @Test
    fun `rejects dtc report whose counts disagree with its length`() {
        // Claims 2 stored but carries bytes for 1.
        assertNull(FrameCodec.parseDtcReport(hexToBytes("0102000171")))
    }

    @Test
    fun `rejects dtc report whose count exceeds 8 even if length is internally consistent`() {
        // stored=9, with 9 DTC pairs actually present (length checks out) —
        // must still be rejected per docs/ble_protocol.md's explicit cap.
        val nineDtcs = (1..9).joinToString("") { "0171" }  // 9 fake P0171-style pairs
        val frame = hexToBytes("010900$nineDtcs")  // version=1, stored=9, pending=0
        assertNull(FrameCodec.parseDtcReport(frame))
    }

    @Test
    fun `builds golden C1 time sync frame`() {
        assertArrayEquals(
            hexToBytes("01efcdab8967452301"),
            FrameCodec.buildTimeSync(0x0123456789ABCDEFL),
        )
    }

    @Test
    fun `decodes non-P letter dtc`() {
        // C0300: byte1=0x43 (letter C=01, digits 0,3), byte2=0x00 — mirrors
        // firmware test test_dtc_code_to_string_renders_c_letter_code.
        assertEquals("C0300", FrameCodec.decodeDtc(0x43, 0x00))
    }

    @Test
    fun `start wifi sync frame is opcode 3 with zero payload`() {
        val frame = FrameCodec.buildStartWifiSync()
        assertEquals(9, frame.size)
        assertEquals(0x03.toByte(), frame[0])
        assertTrue(frame.drop(1).all { it == 0.toByte() })
    }
}
