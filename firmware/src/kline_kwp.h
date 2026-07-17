#pragma once

#include <cstdint>

#include "dtc_list.h"
#include "kline_frame.h"
#include "kline_transport.h"

// [Best estimate] read timeout for a single request/response over the
// Phase 1 direct-UART bench link. Real P2max negotiation (from the
// StartCommunication response) supersedes this once Phase 3 has real ECU
// data — see docs/superpowers/specs/2026-07-09-phase1-firmware-core-design.md §5.
inline constexpr uint32_t kResponseTimeoutMs = 100;

class KlineKwp {
 public:
  explicit KlineKwp(KLineTransport& transport) : transport_(transport) {}

  // Logical StartCommunication exchange (frame + checksum + key bytes) —
  // NOT the ISO 14230 physical wake-pulse timing. See the Phase 1 plan's
  // Scope boundary note.
  bool start_communication();

  bool send_tester_present();

  // Requests Mode 01 data for `mode01_pid`. On success, decodes the response
  // via pid_wire_format's table and writes to *out_value, returns true. On
  // timeout (e.g. an unsupported PID — PLAN.md §2 expects several PIDs to
  // go unanswered on this ECU), returns false and increments
  // consecutive_timeouts() rather than blocking or erroring.
  bool read_pid(uint8_t mode01_pid, float* out_value);

  // Mode 03 (stored) / Mode 07 (pending) DTC reads. Accepts BOTH known
  // response layouts, discriminated by the frame's own data-length parity
  // [Best estimate — assumes well-formed frames; the two layouts are
  // parity-disjoint by construction]:
  //   implicit  [SID][2 bytes per DTC]         (odd data_len) — standard
  //     J1979-over-K-line; [Likely] what the real SIM4LKE sends. Phase 3's
  //     car probe confirms which layout the car actually uses.
  //   explicit  [SID][count][2 bytes per DTC]  (even data_len) — the layout
  //     kl_sim's build_dtc_response used through Phase 4 [Confirmed against
  //     that code]; kept as a hedge should the real ECU use it.
  // 0x0000 pairs are dropped in both layouts (J1979 ECUs pad short DTC
  // responses with empty slots; P0000 is not a real code). Truncates
  // defensively: never reads pairs beyond what the frame physically
  // carries, caps at kMaxDtcs. Multi-frame DTC paging is out of scope
  // until the Phase 3 probe shows whether this ECU pages at all.
  bool read_stored_dtcs(DtcList* out);
  bool read_pending_dtcs(DtcList* out);

  int consecutive_timeouts() const { return consecutive_timeouts_; }
  bool needs_reinit() const { return consecutive_timeouts_ >= 3; }

  // Requests that failed to BUILD (payload too big for the TX buffer) — a
  // caller bug, deliberately kept out of consecutive_timeouts() so a
  // software defect can never masquerade as a dead link, suppress the
  // kline_connected flag, and trigger pointless bus re-inits. No current
  // public API can produce this (largest request is read_pid's 2 bytes);
  // it exists for future callers with bigger payloads (e.g. Phase 7
  // module scans).
  int request_build_failures() const { return request_build_failures_; }

 private:
  bool send_request_and_get_response(const uint8_t* data, uint8_t data_len, ParsedFrame* out);
  bool read_dtcs(uint8_t request_sid, uint8_t positive_sid, DtcList* out);

  KLineTransport& transport_;
  int consecutive_timeouts_ = 0;
  int request_build_failures_ = 0;
};
