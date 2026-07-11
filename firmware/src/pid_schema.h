#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

enum class Signal : uint8_t {
  RPM,
  SPEED,
  MAF_GS,
  THROTTLE,
  LOAD,
  STFT1,
  LTFT1,
  MAP,
  TIMING_ADV,
  O2_B1S1_V,
  O2_B1S2_V,
  ECT,
  IAT,
  FUEL_STATUS,
  PEDAL_D,
  PEDAL_E,
  CMD_THROTTLE,
  CTRL_MODULE_V,
  BATT_V_ADC,
  COUNT
};

// Polling tiers from PLAN.md §5.1's ~10 req/s K-line budget (mirrored in
// CLAUDE.md and pipeline/src/kl/schema.py — keep all three in sync):
//   F = ~2 Hz, M = ~0.5 Hz, S = ~0.1 Hz actively-scheduled OBD polls.
//   LOCAL = read off the ESP32's own hardware (ADC), no bus cost.
//   LEGACY = kept for CSV-column stability; not actively polled (2004
//            K-line ECU is not expected to support these PIDs).
enum class Tier : uint8_t { F, M, S, LOCAL, LEGACY };

struct SignalBounds {
  Signal signal;
  float min;
  float max;
  Tier tier;
};

inline constexpr size_t kSignalCount = static_cast<size_t>(Signal::COUNT);

// Canonical 19-signal schema. Also mirrored in CLAUDE.md's schema table and
// pipeline/src/kl/schema.py — if a bound changes, change it in all three.
// Array order MUST match the Signal enum's declaration order above; the
// static_assert below proves this positional alignment at compile time.
inline constexpr std::array<SignalBounds, kSignalCount> kSignalTable{{
    {Signal::RPM, 0.0f, 8000.0f, Tier::F},
    {Signal::SPEED, 0.0f, 255.0f, Tier::F},
    {Signal::MAF_GS, 0.0f, 400.0f, Tier::F},
    {Signal::THROTTLE, 0.0f, 100.0f, Tier::F},
    {Signal::LOAD, 0.0f, 100.0f, Tier::M},
    {Signal::STFT1, -100.0f, 99.2f, Tier::M},
    {Signal::LTFT1, -100.0f, 99.2f, Tier::M},
    {Signal::MAP, 0.0f, 250.0f, Tier::M},
    {Signal::TIMING_ADV, -64.0f, 63.5f, Tier::M},
    {Signal::O2_B1S1_V, 0.0f, 1.275f, Tier::M},
    {Signal::O2_B1S2_V, 0.0f, 1.275f, Tier::M},
    {Signal::ECT, -40.0f, 130.0f, Tier::S},
    {Signal::IAT, -40.0f, 80.0f, Tier::S},
    {Signal::FUEL_STATUS, 1.0f, 8.0f, Tier::S},
    {Signal::PEDAL_D, 0.0f, 100.0f, Tier::LEGACY},
    {Signal::PEDAL_E, 0.0f, 100.0f, Tier::LEGACY},
    {Signal::CMD_THROTTLE, 0.0f, 100.0f, Tier::LEGACY},
    {Signal::CTRL_MODULE_V, 0.0f, 20.0f, Tier::LEGACY},
    {Signal::BATT_V_ADC, 0.0f, 18.0f, Tier::LOCAL},
}};

namespace pid_schema_detail {
constexpr bool table_is_aligned_and_valid() {
  for (size_t i = 0; i < kSignalCount; ++i) {
    if (static_cast<size_t>(kSignalTable[i].signal) != i) return false;
    if (!(kSignalTable[i].min < kSignalTable[i].max)) return false;
  }
  return true;
}
}  // namespace pid_schema_detail

static_assert(pid_schema_detail::table_is_aligned_and_valid(),
              "kSignalTable must be ordered exactly like the Signal enum, "
              "and every entry's min must be < max");

// Returns false for an out-of-range `signal` (e.g. a value decoded from an
// untrusted OBD response byte) instead of reading out of bounds.
bool pid_schema_in_bounds(Signal signal, float value);
