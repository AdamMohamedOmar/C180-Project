#pragma once

#include <cstdint>

#include "pid_schema.h"

// Telemetry frame flag bits — mirror-verbatim of docs/ble_protocol.md.
inline constexpr uint8_t kFlagDemoMode = 0x01;
inline constexpr uint8_t kFlagKlineConnected = 0x02;

// Latest-known value of every schema signal, fed by main.cpp from each
// PidScheduler reading (real build) or demo_feed (demo build), consumed by
// telemetry_frame's packer at ~2 Hz. Mask semantics (spec §3.2, normative):
// bit i = the MOST RECENT poll of Signal ordinal i succeeded. BATT_V_ADC's
// bit is never set on the real target in Phase 4 — the 100k/10k divider
// doesn't exist until Phase 2 and a floating GPIO34 must not masquerade as
// a battery reading.
struct LatestValues {
  float values[kSignalCount] = {0.0f};
  uint32_t avail_mask = 0;
  uint8_t flags = 0;
  uint16_t seq = 0;       // incremented by the notify path, not by apply()
  uint32_t uptime_ms = 0; // stamped by the notify path from millis()
};

// available=true: store value + set mask bit. available=false: zero the
// value + clear the bit. Out-of-range `signal` is ignored (defensive
// against a corrupted enum value — same posture as pid_schema_in_bounds).
void latest_values_apply(LatestValues* lv, Signal signal, float value, bool available);

void latest_values_set_flag(LatestValues* lv, uint8_t flag, bool on);
