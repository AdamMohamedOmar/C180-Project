#pragma once

#include <cstdint>

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
  // NOT the ISO 14230 physical wake-pulse timing. See this plan's Scope
  // boundary note.
  bool start_communication();

  bool send_tester_present();

  // Requests Mode 01 data for `mode01_pid`. On success, decodes the response
  // via pid_wire_format's table and writes to *out_value, returns true. On
  // timeout (e.g. an unsupported PID — PLAN.md §2 expects several PIDs to
  // go unanswered on this ECU), returns false and increments
  // consecutive_timeouts() rather than blocking or erroring.
  bool read_pid(uint8_t mode01_pid, float* out_value);

  int consecutive_timeouts() const { return consecutive_timeouts_; }
  bool needs_reinit() const { return consecutive_timeouts_ >= 3; }

 private:
  bool send_request_and_get_response(const uint8_t* data, uint8_t data_len, ParsedFrame* out);

  KLineTransport& transport_;
  int consecutive_timeouts_ = 0;
};
