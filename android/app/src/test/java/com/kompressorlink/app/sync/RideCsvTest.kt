package com.kompressorlink.app.sync

import com.kompressorlink.app.telemetry.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideCsvTest {

    private val ride = """
        #fw_version=0.4.0
        #init_mode=logical-init
        t_ms,signal,value
        1000,RPM,812.000
        1100,ECT,21.000
        #dtc_stored=P0170,P0300
        #dtc_pending=
        #time_sync=2000:1752700000000
        2000,RPM,815.500
    """.trimIndent()

    @Test
    fun `parses headers rows dtcs and time sync`() {
        val r = RideCsv.parse(ride.toByteArray())
        assertEquals("logical-init", r.initMode)
        assertEquals("0.4.0", r.fwVersion)
        assertEquals(3, r.rows.size)
        assertEquals(RideCsv.Row(1000, Signal.RPM, 812.000f), r.rows[0])
        assertEquals(listOf("P0170", "P0300"), r.storedDtcs)
        assertTrue(r.pendingDtcs.isEmpty())
        assertEquals(2000L to 1752700000000L, r.timeSync)
    }

    @Test
    fun `torn tail row is dropped not fatal`() {
        val torn = "$ride\n2100,MAF_G"           // power cut mid-line
        val r = RideCsv.parse(torn.toByteArray())
        assertEquals(3, r.rows.size)             // the torn row vanished
    }

    @Test
    fun `unknown signal names and junk rows are skipped`() {
        val weird = "$ride\n2200,NOT_A_SIGNAL,1.0\ngarbage line\n2300,RPM,820.000"
        val r = RideCsv.parse(weird.toByteArray())
        assertEquals(4, r.rows.size)
        assertEquals(Signal.RPM, r.rows.last().signal)
    }

    @Test
    fun `no time sync marker yields null`() {
        val bare = "#fw_version=x\n#init_mode=logical-init\nt_ms,signal,value\n10,RPM,800.000"
        assertNull(RideCsv.parse(bare.toByteArray()).timeSync)
    }

    @Test
    fun `non-finite values are dropped not fatal`() {
        val weird = "$ride\n2500,RPM,NaN\n2600,RPM,Infinity\n2700,RPM,-Infinity"
        val r = RideCsv.parse(weird.toByteArray())
        assertEquals(3, r.rows.size)             // all three non-finite rows vanished
    }

    @Test
    fun `torn time sync marker is ignored not fatal`() {
        val bare = "#fw_version=x\n#init_mode=logical-init\nt_ms,signal,value\n10,RPM,800.000\n#time_sync=2000"
        val r = RideCsv.parse(bare.toByteArray())
        assertNull(r.timeSync)                   // power cut mid-header, no ":epoch" half
        assertEquals(1, r.rows.size)              // parsing continues past the torn header
    }
}
