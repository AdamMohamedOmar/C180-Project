package com.kompressorlink.app.health

// Spec §5.4's DTC-family table — the ranges are DISJOINT by design (fixed
// during the spec self-review). Generic P0 codes route by number; every
// other shape (P1xxx manufacturer, C/B/U, malformed) is OTHER.
object DtcSubsystemMap {
    fun subsystemFor(code: String): Subsystem {
        if (code.length != 5 || !code.startsWith("P0")) return Subsystem.OTHER
        val n = code.substring(2).toIntOrNull() ?: return Subsystem.OTHER
        return when (n) {
            in 130..135, in 170..175, in 200..209, in 440..457 -> Subsystem.FUELING
            68, in 100..114, in 120..124, in 235..238, in 505..507 -> Subsystem.AIR_INTAKE
            in 115..119, in 125..128, in 480..485 -> Subsystem.COOLING
            in 10..17, in 300..312, in 325..328, in 335..349, in 350..362 -> Subsystem.IGNITION
            in 136..141, in 410..419, in 420..439 -> Subsystem.EXHAUST_CAT
            in 560..563 -> Subsystem.BATTERY
            else -> Subsystem.OTHER
        }
    }
}
