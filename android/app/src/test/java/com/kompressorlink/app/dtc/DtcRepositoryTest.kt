package com.kompressorlink.app.dtc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcRepositoryTest {

    private val repo = DtcRepository { name -> File("src/main/assets/$name").readText() }

    @Test
    fun curated_meetsCountAndShape() {
        val entries = repo.curated.values
        assertTrue("need >= 50 curated codes, have ${entries.size}", entries.size >= 50)
        entries.forEach { e ->
            assertTrue("${e.code} malformed", Regex("^P0\\d{3}$").matches(e.code))
            assertTrue("${e.code} needs a meaning", e.meaning.isNotBlank())
            assertTrue("${e.code} needs check_first", e.check_first.isNotEmpty())
            assertTrue("${e.code} needs can_i_drive", e.can_i_drive.isNotBlank())
            DtcUrgency.valueOf(e.urgency)  // throws on invalid urgency
            assertTrue("${e.code} needs confidence", e.confidence.isNotBlank())
        }
    }

    @Test
    fun leanCodes_keepBreatherHosesFirst() {
        listOf("P0170", "P0171").forEach { code ->
            val first = repo.curated.getValue(code).check_first.first()
            assertTrue("$code first check must stay breather hoses", first.contains("breather hoses", ignoreCase = true))
        }
    }

    @Test
    fun generic_meetsCountAndShape_noOverlapWithCurated() {
        val entries = repo.generic.values
        assertTrue("need >= 220 generic codes, have ${entries.size}", entries.size >= 220)
        entries.forEach { e ->
            assertTrue("${e.code} malformed", Regex("^P0[0-9A-F]{3}$").matches(e.code))
            assertTrue("${e.code} needs a meaning", e.meaning.isNotBlank())
            DtcUrgency.valueOf(e.urgency)
        }
        val overlap = repo.generic.keys intersect repo.curated.keys
        assertTrue("generic must not duplicate curated: $overlap", overlap.isEmpty())
    }

    @Test
    fun generic_pinnedSamplesPresent() {
        val expected = mapOf(
            "P0031" to "O2 sensor heater control low (bank 1, sensor 1)",
            "P0105" to "MAP sensor circuit",
            "P0148" to "Fuel delivery error",
            "P0190" to "Fuel rail pressure sensor circuit",
            "P0230" to "Fuel pump primary circuit",
            "P0261" to "Injector circuit low — cylinder 1",
            "P0305" to "Misfire — cylinder 5",
            "P0401" to "EGR flow insufficient",
            "P0441" to "EVAP purge flow fault",
            "P0500" to "Vehicle speed sensor",
            "P0532" to "A/C refrigerant pressure sensor low",
            "P0715" to "Transmission input speed sensor",
        )
        expected.forEach { (code, title) ->
            assertEquals("pinned entry $code", title, repo.generic.getValue(code).title)
        }
    }

    @Test
    fun explain_precedence_curatedThenGenericThenStructural() {
        assertEquals(DtcLayer.W203, repo.explain("P0171").layer)
        assertEquals(DtcLayer.GENERIC, repo.explain("P0031").layer)
        val structural = repo.explain("P1863")
        assertEquals(DtcLayer.STRUCTURAL, structural.layer)
        assertEquals("Manufacturer-specific powertrain code, transmission group", structural.title)
    }

    @Test
    fun explain_urgencyLabels_matchSpec() {
        assertEquals("Check soon", repo.explain("P0171").urgency!!.label)
        assertEquals("Urgent — drive gently", repo.explain("P0300").urgency!!.label)
        assertEquals("Info", repo.explain("P0455").urgency!!.label)
    }
}
