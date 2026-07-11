#include "pid_scheduler.h"

uint32_t tier_interval_ms(Tier tier) {
  switch (tier) {
    case Tier::F:
      return 500;
    case Tier::M:
      return 2000;
    case Tier::S:
      return 10000;
    case Tier::LEGACY:
      return 30000;
    case Tier::LOCAL:
      return 0;
  }
  return 10000;
}

PidScheduler::PidScheduler(KlineKwp& kwp) : kwp_(kwp) {
  for (size_t i = 0; i < kWireFormatCount; ++i) {
    next_due_ms_[i] = 0;
  }
}

bool PidScheduler::tick(uint32_t now_ms, Reading* out_reading) {
  // Earliest-deadline-first: among all signals whose next_due_ms_ has
  // arrived, serve the one that's been overdue longest (ties go to the
  // lowest index, for deterministic behavior at boot when everything is
  // tied at 0). A fixed lowest-index-first scan would let Tier F
  // (indices 0-3, due every 500ms) permanently starve Tier S/LEGACY
  // signals further down the table -- including ECT, this car's most
  // safety-relevant signal -- once ideal demand (~11.9 req/s across all
  // tiers) exceeds the ~10 req/s K-line budget the schedule is meant to
  // fit inside.
  //
  // Uses signed-difference comparison rather than `next_due_ms_[i] <=
  // now_ms` so this stays correct across millis() wraparound instead of a
  // burst of early re-polls at the wrap boundary (~49.7 days of
  // continuous uptime). The signed-diff trick's own disambiguation window
  // is half that, ~24.8 days -- a signal overdue by more than that would
  // itself look "not due" -- which relies on power_mgr (a later module)
  // actually sleeping/rebooting the device between rides, well inside
  // that window; cheap to get this right regardless.
  size_t due_index = kWireFormatCount;
  int32_t most_overdue_by = -1;
  for (size_t i = 0; i < kWireFormatCount; ++i) {
    const int32_t overdue_by = static_cast<int32_t>(now_ms - next_due_ms_[i]);
    if (overdue_by >= 0 && overdue_by > most_overdue_by) {
      most_overdue_by = overdue_by;
      due_index = i;
    }
  }
  if (due_index == kWireFormatCount) {
    return false;
  }

  const WireFormat& wf = kWireFormatTable[due_index];
  const Tier tier = kSignalTable[static_cast<size_t>(wf.signal)].tier;
  next_due_ms_[due_index] = now_ms + tier_interval_ms(tier);

  out_reading->signal = wf.signal;
  out_reading->available = kwp_.read_pid(wf.mode01_pid, &out_reading->value);
  if (!out_reading->available) {
    out_reading->value = 0.0f;
  }
  return true;
}
