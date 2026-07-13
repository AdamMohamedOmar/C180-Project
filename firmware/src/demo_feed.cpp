#include "demo_feed.h"

namespace {

float warmup_ect(uint32_t t_ms) {
  // 20 °C -> 90 °C over ~300 s, then settled at 90 (thermostat 87 °C per
  // docs/w203_reference.md; settling slightly above it is normal).
  const float ect = 20.0f + static_cast<float>(t_ms) / 4286.0f;
  return ect > 90.0f ? 90.0f : ect;
}

// Narrowband upstream O2 switches rich/lean ~1 Hz in closed loop; the
// post-cat sensor sits steady on a healthy cat (docs/w203_reference.md's
// cat-snapshot criteria).
float o2_switching(uint32_t t_ms) { return ((t_ms / 500) % 2) ? 0.7f : 0.2f; }

}  // namespace

void demo_fill(uint32_t t_ms, LatestValues* lv) {
  const uint32_t phase_ms = t_ms % 60000;
  float rpm, speed, maf, throttle, load, map_kpa, timing, o2s1, fuel_status, stft;
  if (phase_ms < 30000) {
    // Warm idle: values chosen to sit INSIDE docs/w203_reference.md's
    // healthy bands so the final session's banded gauges read green.
    rpm = 750.0f + (((phase_ms / 1000) % 2) ? 30.0f : 0.0f);  // gentle idle wobble
    speed = 0.0f;
    maf = 4.0f;         // warm-idle band 3-5 g/s [Confirmed]
    throttle = 11.0f;
    load = 22.0f;
    map_kpa = 32.0f;    // idle manifold vacuum, ~32 kPa abs [Best estimate]
    timing = 12.0f;
    o2s1 = o2_switching(t_ms);
    fuel_status = 2.0f;  // closed loop
    stft = -1.5f;
  } else if (phase_ms < 40000) {
    // Rev sweep 750 -> 4500 -> 750 rpm (triangle over 10 s), brief boost.
    const float x = static_cast<float>(phase_ms - 30000) / 10000.0f;  // 0..1
    const float tri = x < 0.5f ? x * 2.0f : (1.0f - x) * 2.0f;        // 0..1..0
    rpm = 750.0f + tri * 3750.0f;
    speed = 0.0f;
    maf = 4.0f + tri * 56.0f;
    throttle = 11.0f + tri * 34.0f;
    load = 22.0f + tri * 48.0f;
    map_kpa = 32.0f + tri * 108.0f;  // peaks ~140 kPa abs ≈ 0.4 bar boost [Best estimate]
    timing = 12.0f + tri * 18.0f;
    o2s1 = 0.85f;        // enrichment
    fuel_status = 4.0f;  // open loop due to load
    stft = 0.0f;
  } else {
    // ~90 km/h cruise.
    rpm = 2600.0f;
    speed = 90.0f;
    maf = 18.0f;
    throttle = 18.0f;
    load = 45.0f;
    map_kpa = 95.0f;
    timing = 28.0f;
    o2s1 = o2_switching(t_ms);
    fuel_status = 2.0f;
    stft = 1.5f;
  }

  latest_values_apply(lv, Signal::RPM, rpm, true);
  latest_values_apply(lv, Signal::SPEED, speed, true);
  latest_values_apply(lv, Signal::MAF_GS, maf, true);
  latest_values_apply(lv, Signal::THROTTLE, throttle, true);
  latest_values_apply(lv, Signal::LOAD, load, true);
  latest_values_apply(lv, Signal::STFT1, stft, true);
  latest_values_apply(lv, Signal::LTFT1, 4.7f, true);  // inside +-10% band
  latest_values_apply(lv, Signal::MAP, map_kpa, true);
  latest_values_apply(lv, Signal::TIMING_ADV, timing, true);
  latest_values_apply(lv, Signal::O2_B1S1_V, o2s1, true);
  latest_values_apply(lv, Signal::O2_B1S2_V, 0.65f, true);  // steady post-cat
  latest_values_apply(lv, Signal::ECT, warmup_ect(t_ms), true);
  latest_values_apply(lv, Signal::IAT, 35.0f, true);
  latest_values_apply(lv, Signal::FUEL_STATUS, fuel_status, true);
  // Legacy PIDs: explicitly unavailable, exactly like the real 2004 ECU.
  latest_values_apply(lv, Signal::PEDAL_D, 0.0f, false);
  latest_values_apply(lv, Signal::PEDAL_E, 0.0f, false);
  latest_values_apply(lv, Signal::CMD_THROTTLE, 0.0f, false);
  latest_values_apply(lv, Signal::CTRL_MODULE_V, 0.0f, false);
  // The deliberate demo-only exception (see kDemoAvailMask comment).
  latest_values_apply(lv, Signal::BATT_V_ADC, 14.25f, true);

  latest_values_set_flag(lv, kFlagDemoMode, true);
  latest_values_set_flag(lv, kFlagKlineConnected, false);
}

void demo_dtcs(DtcList* stored, DtcList* pending) {
  *stored = DtcList{};
  *pending = DtcList{};
  stored->count = 1;
  stored->codes[0] = 0x0171;  // P0171 — matches kline_sim.py's fault-injected scenario
}
