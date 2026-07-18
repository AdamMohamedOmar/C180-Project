package com.kompressorlink.app.health

// Every tunable threshold in the health/maintenance stack lives HERE and
// nowhere else. All values [Best estimate] defaults per spec §5.6 — tune in
// one place; tests reference these constants, never magic numbers.
object HealthTuning {
    // Session recording (spec §3)
    const val SESSION_CLOSE_GAP_MS = 30_000L
    const val SESSION_MIN_DURATION_MS = 60_000L
    const val SESSION_MIN_SNAPSHOTS = 30
    const val DISTANCE_MAX_GAP_MS = 5_000L

    // Engine-state context (spec §3/§4)
    const val ENGINE_RUNNING_MIN_RPM = 400f

    // UI hysteresis (spec §4)
    const val ESCALATE_MS = 3_000L
    const val DEESCALATE_MS = 10_000L

    // Metric extraction (spec §5.1)
    const val CONTEXT_MIN_SAMPLES = 30

    // Personal baseline (spec §5.2)
    const val BASELINE_WINDOW = 20
    const val BASELINE_MAD_K = 3f
    const val BASELINE_MIN_SESSIONS = 8
    const val BASELINE_MIN_SPAN_DAYS = 14
    const val BASELINE_DEGENERATE_MAD_FRACTION = 0.01f   // MAD < 1% of band width…
    const val BASELINE_DEGENERATE_WIDEN_FRACTION = 0.05f // …widen to ±5% of band width

    // Drift (spec §5.3)
    const val DRIFT_WINDOW = 20
    const val DRIFT_MIN_POINTS = 8
    const val DRIFT_MIN_SPAN_DAYS = 14
    const val DRIFT_MIN_WEEKS = 1f
    const val DRIFT_MAX_WEEKS = 26f

    // Live warnings (spec §5.5)
    const val AMBER_WARN_DWELL_MS = 60_000L

    // Maintenance (spec §6.3/§6.4)
    const val DUE_SOON_KM = 500
    const val DUE_SOON_DAYS = 30L
    const val RENOTIFY_DAYS = 7L

    // Dynamic M271 contexts/features (2026-07-17 enhancement plan).
    // All [Best estimate] unless noted — tune here only.
    const val HIGH_LOAD_THROTTLE_MIN = 70f     // %
    const val HIGH_LOAD_RPM_MIN = 3000f        // rpm
    const val HIGH_LOAD_DWELL_SNAPSHOTS = 4    // ~2 s at the 2 Hz snapshot cadence
    const val HIGH_LOAD_MIN_SAMPLES = 15       // ~7.5 s cumulative high-load dwell per
                                               // session — far below the generic 30
                                               // floor because sustained WOT is rare
    const val WARMUP_START_MAX_ECT = 40f       // °C — first ECT below this = cold start
    const val WARMUP_COMPLETE_ECT = 80f        // °C — approach to the 87 °C stat [Confirmed
                                               // thermostat spec; 80 keeps partial-opening
                                               // noise out of the slope]
    const val WARMUP_MIN_SPAN_MS = 120_000L    // slope over < 2 min is idle-vs-drive noise
    const val O2_SWING_LOW_V = 0.3f            // narrowband rich/lean rails [Confirmed
    const val O2_SWING_HIGH_V = 0.6f           //  generic narrowband behavior; SIM4LKE
                                               //  support itself is [Likely] — the
                                               //  availability mask absorbs absence]
    const val O2_ONSET_MAX_S = 300f            // past 5 min = no meaningful onset
}
