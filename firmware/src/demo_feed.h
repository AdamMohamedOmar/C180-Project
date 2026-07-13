#pragma once

#include <cstdint>

#include "dtc_list.h"
#include "latest_values.h"

// Availability mask demo mode serves: all F/M/S signals (enum ordinals
// 0-13) plus BATT_V_ADC (18); Legacy bits (14-17) stay clear, mirroring
// what a real healthy session shows. BATT_V_ADC is the one deliberate
// exception to real-target behavior: the real build can't serve it until
// Phase 2's voltage divider exists, but demo serves ~14.25 V so the
// battery gauge is exercised end-to-end at least once (spec §3.4).
inline constexpr uint32_t kDemoAvailMask = 0x0004'3FFF;

// Fills lv->values / avail_mask / flags for time t_ms with a deterministic
// plausible M271 cycle: 60 s period (0-30 s warm idle, 30-40 s rev sweep,
// 40-60 s 90 km/h cruise) + ECT warm-up 20→90 °C over the first ~300 s.
// Pure function of t_ms (natively testable). Does NOT touch lv->seq or
// lv->uptime_ms — that's the notify path's bookkeeping.
void demo_fill(uint32_t t_ms, LatestValues* lv);

// Demo DTC report: stored = {P0171}, pending = empty — proves the DTC
// screen over real BLE in the final session without a car.
void demo_dtcs(DtcList* stored, DtcList* pending);
