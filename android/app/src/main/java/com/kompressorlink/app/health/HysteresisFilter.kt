package com.kompressorlink.app.health

import com.kompressorlink.app.dashboard.GaugeLevel

// UI/alerting smoothing (spec §4): escalation to a worse level requires the
// raw level to hold CONTINUOUSLY for ESCALATE_MS; de-escalation to a better
// level requires DEESCALATE_MS. A raw change to a different pending level
// restarts the timer (strict continuity). NEUTRAL/UNAVAILABLE pass through
// immediately and reset pending state — absence of data is never smoothed
// into a verdict. Raw session stats (SessionAggregator) bypass this class.
class HysteresisFilter(
    private val escalateMs: Long = HealthTuning.ESCALATE_MS,
    private val deescalateMs: Long = HealthTuning.DEESCALATE_MS,
) {
    private var displayed: GaugeLevel? = null
    private var pending: GaugeLevel? = null
    private var pendingSinceMs = 0L

    fun update(raw: GaugeLevel, nowMs: Long): GaugeLevel {
        val current = displayed
        val bypass = raw == GaugeLevel.NEUTRAL || raw == GaugeLevel.UNAVAILABLE ||
            current == null || current == GaugeLevel.NEUTRAL || current == GaugeLevel.UNAVAILABLE
        if (bypass) {
            displayed = raw
            pending = null
            return raw
        }
        if (raw == current) {
            pending = null
            return current
        }
        if (pending != raw) {
            pending = raw
            pendingSinceMs = nowMs
        }
        val windowMs = if (rank(raw) > rank(current)) escalateMs else deescalateMs
        return if (nowMs - pendingSinceMs >= windowMs) {
            displayed = raw
            pending = null
            raw
        } else current
    }

    private fun rank(level: GaugeLevel): Int = when (level) {
        GaugeLevel.RED -> 3
        GaugeLevel.AMBER -> 2
        else -> 1
    }
}
