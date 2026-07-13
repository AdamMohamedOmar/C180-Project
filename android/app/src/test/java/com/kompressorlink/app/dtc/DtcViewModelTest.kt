package com.kompressorlink.app.dtc

import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.DtcReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DtcViewModelTest {

    private fun asset(name: String): String =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .first { it.exists() }
            .readText()

    private val refs = ReferenceRepository { asset(it) }

    @Test
    fun `known code is enriched with the W203 interpretation`() {
        val state = DtcViewModel.build(DtcReport(listOf("P0171"), emptyList()), refs)
        val ui = state.stored.single()
        assertEquals("P0171", ui.code)
        assertEquals("System too lean (bank 1)", ui.title)
        assertTrue(ui.causes.first().contains("breather", ignoreCase = true))
    }

    @Test
    fun `unknown code renders raw with no interpretation`() {
        val state = DtcViewModel.build(DtcReport(listOf("P1234"), emptyList()), refs)
        val ui = state.stored.single()
        assertEquals("P1234", ui.code)
        assertNull(ui.title)
        assertTrue(ui.causes.isEmpty())
    }

    @Test
    fun `null report means no data yet`() {
        val state = DtcViewModel.build(null, refs)
        assertFalse(state.hasReport)
        assertTrue(state.stored.isEmpty())
    }

    @Test
    fun `empty report is a real (clean) report`() {
        val state = DtcViewModel.build(DtcReport(emptyList(), emptyList()), refs)
        assertTrue(state.hasReport)
        assertTrue(state.stored.isEmpty())
        assertTrue(state.pending.isEmpty())
    }
}
