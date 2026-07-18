package com.kompressorlink.app.reference

import com.kompressorlink.app.telemetry.Signal
import org.junit.Assert.assertEquals
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
    fun `bandsFor filters by signal`() {
        val r = repo()
        assertEquals(1, r.bandsFor(Signal.MAF_GS).size)
        assertTrue(r.bandsFor(Signal.RPM).isEmpty())  // deliberately unbanded
    }
}
