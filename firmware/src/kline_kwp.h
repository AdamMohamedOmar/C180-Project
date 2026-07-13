#pragma once

#include <cstdint>

#include "dtc_list.h"
#include "kline_frame.h"
#include "kline_transport.h"

// [Best estimate] overall budget for one request/response exchange, from the
// end of our write() to a fully-received, correctly-addressed response frame.
// Covers a possible self-echo (see read_frame), ECU think time, and frame
// transmission at 10.4 kbps (a max-length frame is ~26 bytes, ~25 ms on the
// wire). Real P2/P2* negotiation (from the StartCommunication response)
// supersedes this once Phase 3 has real ECU data — see
// docs/superpowers/specs/2026-07-09-phase1-firmware-core-design.md §5.
inline constexpr uint32_t kResponseTimeoutMs = 100;

// [Best estimate] max gap between consecutive bytes WITHIN one frame. ISO
// 14230-2's default P1 max is 20 ms; +5 ms margin for USB-TTL adapter
// chunking on the Phase 1 bench link. A gap longer than this means the frame
// died mid-transmission. Real P1 is settled at Phase 3 against the car.
inline constexpr uint32_t kInterByteTimeoutMs = 25;

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

  // Mode 03 (stored) / Mode 07 (pending) DTC reads. Response format
  // expected: [SID_positive][count][2 bytes per DTC]. The explicit count
  // byte mirrors kl_sim/protocol.py's build_dtc_response and is [Guessing]
  // for the real SIM4LKE — real K-line J1979 Mode 03 typically has no
  // count byte (count implicit in frame length, possibly CAN-style framing
  // borrowed by the sim). Phase 3's car probe settles it; the fix lands
  // HERE and nothing downstream changes (everything speaks DtcList).
  // Truncates defensively: count is clamped to the pairs actually present
  // in the frame, then to kMaxDtcs.
  bool read_stored_dtcs(DtcList* out);
  bool read_pending_dtcs(DtcList* out);

  int consecutive_timeouts() const { return consecutive_timeouts_; }
  bool needs_reinit() const { return consecutive_timeouts_ >= 3; }

 private:
  bool send_request_and_get_response(const uint8_t* data, uint8_t data_len, ParsedFrame* out);
  bool read_dtcs(uint8_t request_sid, uint8_t positive_sid, DtcList* out);

  // Reads one complete, correctly-addressed frame within `deadline_budget_ms`
  // of `transport_.now_ms()`, transparently discarding any self-echo (or
  // foreign bus traffic) along the way. See kline_kwp.cpp for the algorithm.
  bool read_frame(ParsedFrame* out, uint32_t deadline_budget_ms);

  KLineTransport& transport_;
  int consecutive_timeouts_ = 0;
};
