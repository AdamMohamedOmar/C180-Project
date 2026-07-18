package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.GaugeLevel
import com.kompressorlink.app.data.db.WarningEntity
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.Signal

data class SubsystemCard(
    val subsystem: Subsystem,
    val status: HealthStatus,
    val reason: String,
    val driftArrow: Char?,      // '↗' / '↘' while a drift verdict is active
    val baselineGate: String?,  // "Collecting baseline · N of 8 drives" while gated
    val footnote: String?,      // IGNITION scope-honesty footnote
)

data class OverallVerdict(val status: HealthStatus, val headline: String, val subline: String)

// Spec §5.4: subsystem status = worst of (a) live post-hysteresis levels
// (AMBER⇒WATCH, RED⇒ATTENTION, no-data⇒OK — absence of data is never a
// fault), (b) open warnings targeting it, (c) DTCs (stored⇒ATTENTION,
// pending⇒WATCH). Overall = worst subsystem, never an average.
object HealthRollup {

    private fun signalTitle(signal: Signal): String = when (signal) {
        Signal.RPM -> "RPM"; Signal.MAF_GS -> "MAF"; Signal.STFT1 -> "STFT"
        Signal.LTFT1 -> "LTFT"; Signal.ECT -> "Coolant"; Signal.MAP -> "MAP"
        Signal.O2_B1S1_V -> "O2 pre-cat"; Signal.O2_B1S2_V -> "O2 post-cat"
        Signal.BATT_V_ADC -> "Battery"; Signal.TIMING_ADV -> "Timing"
        Signal.THROTTLE -> "Throttle"; Signal.FUEL_STATUS -> "Fuel status"
        else -> signal.name
    }

    private fun rank(status: HealthStatus): Int = when (status) {
        HealthStatus.ATTENTION -> 2
        HealthStatus.WATCH -> 1
        HealthStatus.OK -> 0
    }

    fun cards(
        liveLevels: Map<Signal, GaugeLevel>,
        openWarnings: List<WarningEntity>,
        dtcReport: DtcReport?,
        drift: Map<MetricId, Drift.Result>,
        baseline: Map<MetricId, Baseline.Result>,
    ): List<SubsystemCard> = Subsystem.entries.mapNotNull { subsystem ->
        // Ordered contributions: highest-priority reason wins at equal status.
        val contributions = mutableListOf<Pair<HealthStatus, String>>()

        dtcReport?.stored.orEmpty()
            .filter { DtcSubsystemMap.subsystemFor(it) == subsystem }
            .forEach { contributions += HealthStatus.ATTENTION to "Stored code $it — see DTCs tab" }

        liveLevels.forEach { (signal, level) ->
            if (Subsystem.SIGNAL_SUBSYSTEMS[signal] == subsystem) {
                when (level) {
                    GaugeLevel.RED ->
                        contributions += HealthStatus.ATTENTION to "${signalTitle(signal)} out of range right now"
                    GaugeLevel.AMBER ->
                        contributions += HealthStatus.WATCH to "${signalTitle(signal)} outside its band"
                    else -> Unit
                }
            }
        }

        openWarnings.filter { it.subsystem == subsystem.name }.forEach { w ->
            val status = if (w.level == "ATTENTION") HealthStatus.ATTENTION else HealthStatus.WATCH
            contributions += status to w.title
        }

        var arrow: Char? = null
        MetricId.entries.filter { it.subsystem == subsystem }.forEach { metric ->
            val d = drift[metric]
            if (d is Drift.Result.Drifting) {
                if (arrow == null) arrow = if (d.rising) '↗' else '↘'
                contributions += HealthStatus.WATCH to
                    "${metric.displayName} drifting ${if (d.rising) "up" else "down"} — ~${d.weeksToEdge} weeks to the limit"
            }
        }

        dtcReport?.pending.orEmpty()
            .filter { DtcSubsystemMap.subsystemFor(it) == subsystem }
            .forEach { contributions += HealthStatus.WATCH to "Pending code $it — not confirmed yet" }

        val gated = MetricId.entries
            .filter { it.subsystem == subsystem }
            .mapNotNull { baseline[it] as? Baseline.Result.Gated }
            .maxByOrNull { it.eligibleCount }
        val baselineGate = gated?.let { "Collecting baseline · ${it.eligibleCount} of ${it.needed} drives" }

        val worst = contributions.maxByOrNull { rank(it.first) }
        val status = worst?.first ?: HealthStatus.OK
        val reason = contributions.filter { it.first == status }.firstOrNull()?.second ?: "Looks normal"

        if (subsystem == Subsystem.OTHER && contributions.isEmpty()) {
            null  // OTHER renders only when a DTC lands there (spec §5.4)
        } else {
            SubsystemCard(
                subsystem = subsystem,
                status = status,
                reason = reason,
                driftArrow = arrow,
                baselineGate = baselineGate,
                footnote = if (subsystem == Subsystem.IGNITION) Subsystem.IGNITION_FOOTNOTE else null,
            )
        }
    }

    fun overall(cards: List<SubsystemCard>): OverallVerdict {
        val worst = cards.maxByOrNull { rank(it.status) }?.status ?: HealthStatus.OK
        val offenders = cards.filter { it.status == worst }.map { it.subsystem.displayName }
        return when (worst) {
            HealthStatus.OK -> OverallVerdict(worst, "All systems look normal",
                "No warnings from your car's data")
            HealthStatus.WATCH -> OverallVerdict(worst, "Worth watching",
                plural(offenders, "worth watching"))
            HealthStatus.ATTENTION -> OverallVerdict(worst, "Needs a look",
                plural(offenders, if (offenders.size == 1) "needs attention" else "need attention"))
        }
    }

    private fun plural(names: List<String>, phrase: String): String {
        val n = names.size
        val noun = if (n == 1) "system" else "systems"
        return "$n $noun $phrase — ${names.joinToString(", ")}"
    }
}
