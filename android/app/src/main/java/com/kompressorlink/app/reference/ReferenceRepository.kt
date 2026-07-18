package com.kompressorlink.app.reference

import com.kompressorlink.app.telemetry.Signal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Healthy band for one signal in one context (spec §4). Contexts: "always",
// "warm_idle", "engine_running", "engine_off" — DashboardLogic.contextHolds
// decides applicability from the snapshot itself.
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

object ReferenceParser {
    private val json = Json { ignoreUnknownKeys = true }
    fun parseBands(text: String): List<Band> = json.decodeFromString(text)
}

// loadAsset injected so JVM tests read straight from the filesystem while
// the app passes { context.assets.open(it).bufferedReader().readText() }.
// DTC interpretation moved to dtc/DtcRepository in Phase 4.5 (spec §7).
class ReferenceRepository(private val loadAsset: (String) -> String) {
    val bands: List<Band> by lazy { ReferenceParser.parseBands(loadAsset("w203_bands.json")) }

    fun bandsFor(signal: Signal): List<Band> = bands.filter { it.signal == signal.name }
}
