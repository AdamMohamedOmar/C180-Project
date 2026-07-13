package com.kompressorlink.app.reference

import com.kompressorlink.app.telemetry.Signal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Healthy band for one signal in one context (spec §4.5). context is
// "always" or "warm_idle" — warm_idle bands only apply when the snapshot
// itself satisfies warm idle (DashboardViewModel decides that).
@Serializable
data class Band(
    val signal: String,
    val context: String,
    val lo: Float,
    val hi: Float,
    val unit: String,
    val confidence: String,
    val hint: String,
)

@Serializable
data class DtcEntry(
    val code: String,
    val title: String,
    val likely_causes: List<String>,
    val confidence: String,
    val note: String? = null,
)

object ReferenceParser {
    private val json = Json { ignoreUnknownKeys = true }
    fun parseBands(text: String): List<Band> = json.decodeFromString(text)
    fun parseDtcEntries(text: String): List<DtcEntry> = json.decodeFromString(text)
}

// loadAsset injected so JVM tests read straight from the filesystem while
// the app passes { context.assets.open(it).bufferedReader().readText() }.
class ReferenceRepository(private val loadAsset: (String) -> String) {
    val bands: List<Band> by lazy { ReferenceParser.parseBands(loadAsset("w203_bands.json")) }

    private val dtcEntries: Map<String, DtcEntry> by lazy {
        val entries = ReferenceParser.parseDtcEntries(loadAsset("w203_dtc.json"))
        val codes = entries.map { it.code }
        require(codes.size == codes.distinct().size) {
            "w203_dtc.json has duplicate DTC codes: ${codes.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}"
        }
        entries.associateBy { it.code }
    }

    fun bandsFor(signal: Signal): List<Band> = bands.filter { it.signal == signal.name }

    /** Null for codes with no W203-specific interpretation — the DTC screen
     *  then renders the raw code + an honest "no W203-specific entry". */
    fun dtcFor(code: String): DtcEntry? = dtcEntries[code]
}
