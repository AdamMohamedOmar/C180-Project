package com.kompressorlink.app.health

import org.junit.Assert.assertEquals
import org.junit.Test

class DtcSubsystemMapTest {
    @Test
    fun specTable_spotChecks() {
        assertEquals(Subsystem.FUELING, DtcSubsystemMap.subsystemFor("P0171"))
        assertEquals(Subsystem.FUELING, DtcSubsystemMap.subsystemFor("P0455"))   // EVAP -> fueling
        assertEquals(Subsystem.FUELING, DtcSubsystemMap.subsystemFor("P0130"))   // pre-cat O2
        assertEquals(Subsystem.AIR_INTAKE, DtcSubsystemMap.subsystemFor("P0101"))
        assertEquals(Subsystem.AIR_INTAKE, DtcSubsystemMap.subsystemFor("P0506"))
        assertEquals(Subsystem.COOLING, DtcSubsystemMap.subsystemFor("P0117"))
        assertEquals(Subsystem.IGNITION, DtcSubsystemMap.subsystemFor("P0016"))  // cam adjusters
        assertEquals(Subsystem.IGNITION, DtcSubsystemMap.subsystemFor("P0301"))
        assertEquals(Subsystem.EXHAUST_CAT, DtcSubsystemMap.subsystemFor("P0420"))
        assertEquals(Subsystem.EXHAUST_CAT, DtcSubsystemMap.subsystemFor("P0136"))  // post-cat O2
        assertEquals(Subsystem.BATTERY, DtcSubsystemMap.subsystemFor("P0562"))
    }

    @Test
    fun everythingElse_isOther() {
        assertEquals(Subsystem.OTHER, DtcSubsystemMap.subsystemFor("P1570"))  // manufacturer
        assertEquals(Subsystem.OTHER, DtcSubsystemMap.subsystemFor("P0700"))  // transmission group
        assertEquals(Subsystem.OTHER, DtcSubsystemMap.subsystemFor("C1234"))
        assertEquals(Subsystem.OTHER, DtcSubsystemMap.subsystemFor("U0100"))
        assertEquals(Subsystem.OTHER, DtcSubsystemMap.subsystemFor("garbage"))
        assertEquals(Subsystem.OTHER, DtcSubsystemMap.subsystemFor("P0XYZ"))
    }
}
