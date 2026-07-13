package com.kompressorlink.app.reference

import com.kompressorlink.app.telemetry.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReferenceRepositoryTest {

    // Gradle runs JVM unit tests with the module dir as CWD [Likely]; the
    // fallback covers a root-project CWD. If both miss, the failure is
    // loud and the fix is mechanical (adjust the relative path).
    private fun asset(name: String): String =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .first { it.exists() }
            .readText()

    private fun repo() = ReferenceRepository { asset(it) }

    @Test
    fun `bands parse and reference only real schema signals`() {
        val bands = repo().bands
        assertTrue(bands.isNotEmpty())
        val names = Signal.entries.map { it.name }.toSet()
        for (band in bands) {
            assertTrue("unknown signal ${band.signal}", band.signal in names)
            assertTrue(band.lo < band.hi)
            assertTrue(band.confidence.isNotBlank())
            assertTrue(band.hint.isNotBlank())
        }
    }

    @Test
    fun `every dashboard-banded signal from the reference doc is covered`() {
        // docs/w203_reference.md rows that map onto dashboard signals.
        val banded = repo().bands.map { it.signal }.toSet()
        assertEquals(setOf("MAF_GS", "STFT1", "LTFT1", "ECT", "BATT_V_ADC"), banded)
    }

    @Test
    fun `p0171 interpretation leads with breather hoses`() {
        val entry = repo().dtcFor("P0171")
        assertNotNull(entry)
        assertTrue(entry!!.likely_causes.first().contains("breather", ignoreCase = true))
    }

    @Test
    fun `note field is populated for the P0300 family and null elsewhere`() {
        val r = repo()
        assertNotNull(r.dtcFor("P0300")!!.note)
        assertTrue(r.dtcFor("P0300")!!.note!!.contains("FR6MPP332"))
        assertNull(r.dtcFor("P0170")!!.note)
    }

    @Test
    fun `unknown code has no entry`() {
        assertNull(repo().dtcFor("P9999"))
    }

    @Test
    fun `bandsFor filters by signal`() {
        val r = repo()
        assertEquals(1, r.bandsFor(Signal.MAF_GS).size)
        assertTrue(r.bandsFor(Signal.RPM).isEmpty())  // deliberately unbanded
    }
}
