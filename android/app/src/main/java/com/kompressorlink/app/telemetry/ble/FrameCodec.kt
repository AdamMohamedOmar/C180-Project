package com.kompressorlink.app.telemetry.ble

import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Kotlin mirror of docs/ble_protocol.md v1 (the C++ mirror is
// firmware/src/telemetry_frame.h/.cpp). Golden-vector-tested; pure JVM, no
// Android imports — deliberately unit-testable without a device.
object FrameCodec {
    const val PROTOCOL_VERSION = 0x01
    const val TELEMETRY_FRAME_LEN = 88
    private const val OP_TIME_SYNC: Byte = 0x01

    // docs/ble_protocol.md's DTC report cap; mirrors firmware/src/dtc_list.h's
    // kMaxDtcs = 8. No shared source of truth across languages for this
    // literal (unlike the frame layout, which golden vectors keep in sync) —
    // keep the two in agreement by hand.
    private const val MAX_DTCS = 8

    /** Returns null for wrong length or unknown version — caller surfaces
     *  ConnectionState.ProtocolMismatch rather than mis-parsing. */
    fun parseTelemetry(bytes: ByteArray): TelemetrySnapshot? {
        if (bytes.size != TELEMETRY_FRAME_LEN) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.get().toInt() and 0xFF
        if (version != PROTOCOL_VERSION) return null
        val flags = buf.get().toInt() and 0xFF
        val seq = buf.short.toInt() and 0xFFFF
        val uptimeMs = buf.int.toLong() and 0xFFFFFFFFL
        val mask = buf.int
        val values = FloatArray(SIGNAL_COUNT) { buf.float }
        return TelemetrySnapshot(values, mask, flags, seq, uptimeMs)
    }

    fun parseDtcReport(bytes: ByteArray): DtcReport? {
        if (bytes.size < 3) return null
        if (bytes[0].toInt() and 0xFF != PROTOCOL_VERSION) return null
        val stored = bytes[1].toInt() and 0xFF
        val pending = bytes[2].toInt() and 0xFF
        // Reject a count that exceeds the cap outright, independent of
        // whether the frame's length happens to agree with it — never trust
        // an attacker-or-bug-constructed buffer long enough to satisfy the
        // length check below.
        if (stored > MAX_DTCS || pending > MAX_DTCS) return null
        if (bytes.size != 3 + 2 * (stored + pending)) return null
        fun codeAt(offset: Int) =
            decodeDtc(bytes[offset].toInt() and 0xFF, bytes[offset + 1].toInt() and 0xFF)
        return DtcReport(
            stored = (0 until stored).map { codeAt(3 + 2 * it) },
            pending = (0 until pending).map { codeAt(3 + 2 * stored + 2 * it) },
        )
    }

    fun buildTimeSync(epochMs: Long): ByteArray =
        ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .put(OP_TIME_SYNC)
            .putLong(epochMs)
            .array()

    /** SAE J2012 2-byte decode — the inverse of kl_sim/protocol.py's
     *  encode_dtc and the twin of firmware's dtc_code_to_string. The three
     *  4-bit "digits" render as uppercase hex (P0A0B-style codes exist). */
    fun decodeDtc(byte1: Int, byte2: Int): String {
        val letter = "PCBU"[(byte1 ushr 6) and 0x3]
        val d1 = (byte1 ushr 4) and 0x3
        val d2 = (byte1 and 0xF).toString(16).uppercase()
        val d3 = ((byte2 ushr 4) and 0xF).toString(16).uppercase()
        val d4 = (byte2 and 0xF).toString(16).uppercase()
        return "$letter$d1$d2$d3$d4"
    }
}
