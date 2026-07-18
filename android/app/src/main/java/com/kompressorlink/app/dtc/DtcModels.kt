package com.kompressorlink.app.dtc

// Spec §7.1's urgency taxonomy with normative UI labels.
enum class DtcUrgency(val label: String) {
    INFO("Info"),
    CHECK_SOON("Check soon"),
    URGENT("Urgent — drive gently"),
    STOP("Stop driving"),
}

// Which layer answered — rendered as a chip so friendliness never blurs
// provenance (spec §7.3).
enum class DtcLayer(val chip: String) {
    W203("W203-specific"),
    GENERIC("Generic OBD-II"),
    STRUCTURAL("Code family only"),
}

data class DtcExplanation(
    val code: String,
    val title: String,
    val meaning: String,
    val urgency: DtcUrgency?,     // null only on the structural layer ("Unknown urgency")
    val canIDrive: String?,
    val checkFirst: List<String>,
    val note: String?,
    val confidence: String?,
    val layer: DtcLayer,
)
