package com.kompressorlink.app.dtc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CuratedDtcEntry(
    val code: String,
    val title: String,
    val meaning: String,
    val urgency: String,
    val can_i_drive: String,
    val check_first: List<String>,
    val note: String? = null,
    val confidence: String,
)

@Serializable
data class GenericDtcEntry(
    val code: String,
    val title: String,
    val meaning: String,
    val urgency: String,
)

// Spec §7.3: lookup precedence curated -> generic -> structural. No code
// ever renders bare.
class DtcRepository(private val loadAsset: (String) -> String) {
    private val json = Json { ignoreUnknownKeys = true }

    val curated: Map<String, CuratedDtcEntry> by lazy {
        val entries = json.decodeFromString<List<CuratedDtcEntry>>(loadAsset("w203_dtc.json"))
        requireUnique(entries.map { it.code }, "w203_dtc.json")
        entries.associateBy { it.code }
    }

    val generic: Map<String, GenericDtcEntry> by lazy {
        val entries = json.decodeFromString<List<GenericDtcEntry>>(loadAsset("generic_dtc.json"))
        requireUnique(entries.map { it.code }, "generic_dtc.json")
        entries.associateBy { it.code }
    }

    fun explain(code: String): DtcExplanation {
        curated[code]?.let {
            return DtcExplanation(
                code = it.code, title = it.title, meaning = it.meaning,
                urgency = DtcUrgency.valueOf(it.urgency), canIDrive = it.can_i_drive,
                checkFirst = it.check_first, note = it.note, confidence = it.confidence,
                layer = DtcLayer.W203,
            )
        }
        generic[code]?.let {
            return DtcExplanation(
                code = it.code, title = it.title, meaning = it.meaning,
                urgency = DtcUrgency.valueOf(it.urgency), canIDrive = null,
                checkFirst = emptyList(), note = null, confidence = "Confirmed",
                layer = DtcLayer.GENERIC,
            )
        }
        return DtcFamilyDecoder.decode(code)
    }

    private fun requireUnique(codes: List<String>, file: String) {
        require(codes.size == codes.distinct().size) {
            "$file has duplicate DTC codes: ${codes.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}"
        }
    }
}
