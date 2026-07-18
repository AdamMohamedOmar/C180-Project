package com.kompressorlink.app.dtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtcFamilyDecoderTest {
    @Test
    fun manufacturerTransmissionCode() {
        val d = DtcFamilyDecoder.decode("P1863")
        assertEquals("Manufacturer-specific powertrain code, transmission group", d.title)
        assertEquals(DtcLayer.STRUCTURAL, d.layer)
        assertNull(d.urgency)
    }

    @Test
    fun genericFamilies() {
        assertEquals("Generic powertrain code, ignition system / misfire group",
            DtcFamilyDecoder.decode("P0399").title)
        assertEquals("Generic powertrain code, vehicle speed & idle control group",
            DtcFamilyDecoder.decode("P0599").title)
        assertEquals("Generic chassis code", DtcFamilyDecoder.decode("C0035").title)
        assertEquals("Manufacturer-specific network code", DtcFamilyDecoder.decode("U1100").title)
    }

    @Test
    fun garbage_isCalledOut() {
        assertEquals("Unrecognized code", DtcFamilyDecoder.decode("XX!!").title)
        assertEquals("Unrecognized code", DtcFamilyDecoder.decode("P01").title)
    }
}
