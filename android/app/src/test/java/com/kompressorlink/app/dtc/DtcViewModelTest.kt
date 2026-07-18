package com.kompressorlink.app.dtc

import com.kompressorlink.app.telemetry.DtcReport
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcViewModelTest {

    private val repo = DtcRepository { name -> File("src/main/assets/$name").readText() }

    @Test
    fun nullReport_meansNoDataYet() {
        val state = DtcViewModel.build(null, emptySet(), repo)
        assertFalse(state.hasReport)
        assertTrue(state.stored.isEmpty() && state.pending.isEmpty())
    }

    @Test
    fun faultScenarioShape_curatedAndStructural() {
        val state = DtcViewModel.build(
            DtcReport(stored = listOf("P0171"), pending = listOf("P1570")), emptySet(), repo,
        )
        assertTrue(state.hasReport)
        assertEquals(DtcLayer.W203, state.stored[0].layer)
        assertEquals("Check soon", state.stored[0].urgency!!.label)
        assertTrue(state.stored[0].checkFirst.first().contains("breather hoses", ignoreCase = true))
        assertEquals(DtcLayer.STRUCTURAL, state.pending[0].layer)
        // P1570: digit 3 is '5' -> "vehicle speed & idle control" family.
        assertEquals("Manufacturer-specific powertrain code, vehicle speed & idle control group",
            state.pending[0].title)
    }

    @Test
    fun genericCode_getsGenericLayer() {
        val state = DtcViewModel.build(
            DtcReport(stored = listOf("P0031"), pending = emptyList()), emptySet(), repo,
        )
        assertEquals(DtcLayer.GENERIC, state.stored[0].layer)
    }

    @Test
    fun sameCodeInStoredAndPending_expandIndependently() {
        // A code can legitimately be pending on the current drive cycle while
        // still stored from a prior one, before it's evicted. DtcScreen keys
        // its expand-state by "s-"/"p-" prefix + code, not the bare code, so
        // expanding the stored copy must not also expand the pending copy.
        val report = DtcReport(stored = listOf("P0171"), pending = listOf("P0171"))
        val expandedOnlyStored = setOf("s-P0171")
        val state = DtcViewModel.build(report, expandedOnlyStored, repo)

        val storedKey = "s-${state.stored[0].code}"
        val pendingKey = "p-${state.pending[0].code}"
        assertTrue(storedKey in state.expandedCodes)
        assertFalse(pendingKey in state.expandedCodes)
    }
}
