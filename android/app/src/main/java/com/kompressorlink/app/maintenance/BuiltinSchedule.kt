package com.kompressorlink.app.maintenance

import com.kompressorlink.app.data.db.MaintenanceItemEntity

// Spec §6.2's seeded schedule. Service-conservative defaults for a 22-year-
// old M271 — reference-guide-grade, NOT WIS gospel (the Maintenance screen
// carries the same confirm-against-WIS caveat as docs/w203_reference.md).
// All intervals editable in-app; confidence tags per CLAUDE.md rule 4.
object BuiltinSchedule {
    private fun item(
        name: String, category: String, km: Int?, months: Int?,
        note: String, confidence: String,
    ) = MaintenanceItemEntity(
        name = name, category = category, intervalKm = km, intervalMonths = months,
        note = note, confidence = confidence, builtin = true, enabled = true,
        lastNotifiedAtEpochMs = null,
    )

    val ITEMS: List<MaintenanceItemEntity> = listOf(
        item("Engine oil + filter", "ENGINE", 10_000, 12,
            "Chain-driven engine — fresh oil protects the timing chain and cam adjusters",
            "Best estimate"),
        item("Breather-hose inspection", "INSPECTION", 20_000, 24,
            "THE M271 failure: cracked partial-load breather hoses cause lean codes (P0171)",
            "Best estimate"),
        item("Cam-magnet oil-ingress check", "INSPECTION", 10_000, 12,
            "Known M271 issue — oil wicks into the connector/harness; check at each oil change",
            "Likely"),
        item("Spark plugs", "ENGINE", 60_000, 48,
            "Correct plug: FR6MPP332 or NGK ILFR6A [Confirmed type]",
            "Best estimate"),
        item("Air filter", "ENGINE", 40_000, 48,
            "Supercharged intake — a clean filter matters more than on NA engines",
            "Best estimate"),
        item("Cabin filter", "ENGINE", 20_000, 24,
            "Comfort item; cheap",
            "Best estimate"),
        item("Fuel filter", "ENGINE", 60_000, null,
            "Often forgotten on this era of MB",
            "Best estimate"),
        item("Coolant renewal", "FLUIDS", 100_000, 60,
            "Use MB 325.0-approved coolant",
            "Best estimate"),
        item("Brake fluid", "BRAKES", null, 24,
            "Standard MB two-year interval — hygroscopic fluid",
            "Likely"),
        item("Transmission service (722.6)", "TRANSMISSION", 60_000, 60,
            "'Lifetime fill' is a myth — fluid + filter + pan gasket",
            "Best estimate"),
        item("Poly-V belt + tensioner inspection", "INSPECTION", 60_000, 48,
            "Listen for tensioner rattle; check for cracking",
            "Best estimate"),
    )
}
