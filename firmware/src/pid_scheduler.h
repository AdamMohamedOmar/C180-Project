#pragma once

#include <cstdint>

#include "kline_kwp.h"
#include "pid_schema.h"
#include "pid_wire_format.h"

// Polling intervals per tier, from PLAN.md §5.1's ~10 req/s K-line budget.
// LEGACY is polled slowly (not "not at all") specifically so the firmware's
// graceful-timeout handling for an unsupported PID gets exercised on real
// hardware, not just assumed — PLAN.md §2's Untested contract. LOCAL
// (BATT_V_ADC) isn't on K-line at all; excluded from this scheduler.
uint32_t tier_interval_ms(Tier tier);

class PidScheduler {
 public:
  struct Reading {
    Signal signal;
    float value;
    bool available;
  };

  explicit PidScheduler(KlineKwp& kwp);

  // Polls the single most-overdue signal at `now_ms`, if any. Returns true
  // and fills *out_reading if a poll happened (whether or not the ECU
  // answered — see `Reading::available`); false if nothing is due yet.
  bool tick(uint32_t now_ms, Reading* out_reading);

 private:
  KlineKwp& kwp_;
  uint32_t next_due_ms_[kWireFormatCount];
};
