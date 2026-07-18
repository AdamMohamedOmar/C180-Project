package com.kompressorlink.app.dtc

// Layer 3 (spec §7.2): decodes the code's structure, never fabricates a
// meaning. "P1863 — manufacturer-specific powertrain code, transmission
// group."
object DtcFamilyDecoder {
    fun decode(code: String): DtcExplanation {
        val system = when (code.firstOrNull()) {
            'P' -> "powertrain"
            'C' -> "chassis"
            'B' -> "body"
            'U' -> "network"
            else -> null
        }
        val wellFormed = system != null && code.length == 5 &&
            code.drop(1).all { it.isDigit() || it in 'A'..'F' }
        if (!wellFormed) {
            return DtcExplanation(
                code = code, title = "Unrecognized code",
                meaning = "This doesn't look like a standard OBD-II code. It may be garbled — try reading it again.",
                urgency = null, canIDrive = null, checkFirst = emptyList(),
                note = null, confidence = null, layer = DtcLayer.STRUCTURAL,
            )
        }
        val generic = code[1] == '0'
        val family = if (code[0] == 'P') when (code[2]) {
            '0', '1' -> "fuel & air metering"
            '2' -> "fuel & air metering (injector circuit)"
            '3' -> "ignition system / misfire"
            '4' -> "auxiliary emission controls"
            '5' -> "vehicle speed & idle control"
            '6' -> "computer & output circuits"
            '7', '8', '9' -> "transmission"
            else -> "hybrid / propulsion"
        } else null
        val title = buildString {
            append(if (generic) "Generic " else "Manufacturer-specific ")
            append(system)
            append(" code")
            if (family != null) {
                append(", ")
                append(family)
                append(" group")
            }
        }
        return DtcExplanation(
            code = code, title = title,
            meaning = "No detailed entry for this code — have it read with an MB-capable scanner for the exact meaning.",
            urgency = null, canIDrive = null, checkFirst = emptyList(),
            note = null, confidence = null, layer = DtcLayer.STRUCTURAL,
        )
    }
}
