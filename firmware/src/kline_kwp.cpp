#include "kline_kwp.h"

#include "kline_frame.h"
#include "pid_wire_format.h"

// 32 bytes covers every current request/response: the largest is now a
// Mode 03 response at its defensive cap (3-byte header + SID + count +
// 8*2 DTC bytes + checksum = 22). Revisit if a later phase adds a request
// needing a payload anywhere near kline_frame.h's protocol-max
// kKlineMaxDataLen (63).
bool KlineKwp::send_request_and_get_response(const uint8_t* data, uint8_t data_len, ParsedFrame* out) {
  uint8_t frame[32];
  size_t frame_len = kline_build_frame(kTargetAddress, kTesterAddress, data, data_len, frame, sizeof(frame));
  if (frame_len == 0) {
    // data_len exceeded kKlineMaxDataLen or didn't fit in `frame` -- a
    // caller bug, not a live-ECU timeout. Fail the same way a timeout
    // would (rather than write nothing and silently wait out the full
    // response window for a request that was never actually sent), so
    // this is at least visible as a repeated failure instead of a mystery
    // stall.
    ++consecutive_timeouts_;
    return false;
  }
  transport_.write(frame, frame_len);

  // Deliberately no pre-write RX drain here: FakeTransport-based tests
  // pre-queue a response before write() is called (a drain would eat it),
  // and a stale frame left over from a prior exchange gets caught by
  // read_frame's own address check (discarded, not returned) or by the
  // content checks in read_pid/read_dtcs (wrong SID/PID rejected) within
  // this one poll cycle -- no separate flush is needed for correctness.
  if (!read_frame(out, kResponseTimeoutMs)) {
    ++consecutive_timeouts_;
    return false;
  }
  // Resets on any well-formed, correctly-addressed reply -- including one
  // a caller (e.g. read_pid) goes on to reject for wrong SID/PID content.
  // This intentionally tracks "is the K-line link alive" (are we getting
  // parseable frames back at all), not "did this specific request get the
  // answer we wanted" -- an ECU declining one PID doesn't mean the link
  // is dead and doesn't warrant a full re-init.
  consecutive_timeouts_ = 0;
  return true;
}

bool KlineKwp::read_frame(ParsedFrame* out, uint32_t deadline_budget_ms) {
  const uint32_t start = transport_.now_ms();
  uint8_t buf[3 + kKlineMaxDataLen + 1];  // FMT,TGT,SRC,DATA...,CS

  for (;;) {
    const uint32_t elapsed_before_fmt = transport_.now_ms() - start;
    if (elapsed_before_fmt >= deadline_budget_ms) {
      return false;
    }
    // Real half-duplex K-line hardware echoes our own TX back on RX before
    // the ECU's reply arrives; not reachable on Phase 1's direct-UART bench
    // link (no echo), but this loop tolerates it either way. A self-echo is
    // addressed target=kTargetAddress/source=kTesterAddress -- exactly
    // reversed from a genuine ECU reply -- and gets silently discarded
    // below (`continue`) rather than counted as a failed exchange.
    if (transport_.read(&buf[0], 1, deadline_budget_ms - elapsed_before_fmt) == 0) {
      return false;  // KLineTransport::read blocks until timeout; 0 bytes IS the deadline.
    }

    const uint8_t data_len = buf[0] & kKlineMaxDataLen;
    if (data_len == 0) {
      // KWP's escape-length form (additional length byte follows) -- never
      // emitted by kline_build_frame and not implemented here.
      return false;
    }

    const size_t body_len = 2 + static_cast<size_t>(data_len) + 1;  // TGT,SRC,DATA...,CS
    bool body_ok = true;
    for (size_t i = 0; i < body_len; ++i) {
      const uint32_t elapsed = transport_.now_ms() - start;
      if (elapsed >= deadline_budget_ms) {
        body_ok = false;
        break;
      }
      const uint32_t remaining = deadline_budget_ms - elapsed;
      const uint32_t byte_timeout = kInterByteTimeoutMs < remaining ? kInterByteTimeoutMs : remaining;
      if (transport_.read(&buf[1 + i], 1, byte_timeout) == 0) {
        body_ok = false;  // inter-byte gap exceeded -- frame died mid-transmission
        break;
      }
    }
    if (!body_ok) {
      return false;
    }

    const size_t frame_len = 3 + static_cast<size_t>(data_len) + 1;
    if (!kline_parse_frame(buf, frame_len, out)) {
      return false;  // corrupt/checksum-mismatched frame -- don't keep reading past it
    }

    if (out->target != kTesterAddress || out->source != kTargetAddress) {
      continue;  // self-echo or foreign traffic -- discard and keep listening
    }
    return true;
  }
}

bool KlineKwp::start_communication() {
  const uint8_t data[] = {0x81};  // SID_START_COMMUNICATION
  ParsedFrame response;
  if (!send_request_and_get_response(data, 1, &response)) {
    return false;
  }
  return response.data_len >= 1 && response.data[0] == 0xC1;
}

bool KlineKwp::send_tester_present() {
  const uint8_t data[] = {0x3E};  // SID_TESTER_PRESENT
  ParsedFrame response;
  if (!send_request_and_get_response(data, 1, &response)) {
    return false;
  }
  return response.data_len >= 1 && response.data[0] == 0x7E;
}

bool KlineKwp::read_pid(uint8_t mode01_pid, float* out_value) {
  const uint8_t data[] = {0x01, mode01_pid};  // SID_SHOW_CURRENT_DATA
  ParsedFrame response;
  if (!send_request_and_get_response(data, 2, &response)) {
    return false;
  }
  if (response.data_len < 2 || response.data[0] != 0x41 || response.data[1] != mode01_pid) {
    return false;
  }

  // Find the formula for this PID (linear scan of 18 entries — fine at this scale).
  for (size_t i = 0; i < kWireFormatCount; ++i) {
    if (kWireFormatTable[i].mode01_pid == mode01_pid) {
      // A checksum-valid but truncated response (e.g. just SID+PID, no
      // payload) would otherwise read past response.data_len into
      // never-written stack bytes -- reject it as a failed read rather
      // than decode garbage and report success.
      if (response.data_len < 2 + kWireFormatTable[i].byte_count) {
        return false;
      }
      *out_value = decode_formula(kWireFormatTable[i].formula, &response.data[2]);
      return true;
    }
  }
  return false;  // PID not in our schema — shouldn't happen if callers use pid_schema's table.
}

bool KlineKwp::read_dtcs(uint8_t request_sid, uint8_t positive_sid, DtcList* out) {
  const uint8_t data[] = {request_sid};
  ParsedFrame response;
  if (!send_request_and_get_response(data, 1, &response)) {
    return false;
  }
  if (response.data_len < 2 || response.data[0] != positive_sid) {
    return false;
  }
  // Clamp twice: to the pairs physically present in the frame (a frame
  // claiming more DTCs than it carries must never read unwritten bytes),
  // then to kMaxDtcs (defensive cap, see dtc_list.h).
  const uint8_t pairs_in_frame = static_cast<uint8_t>((response.data_len - 2) / 2);
  uint8_t n = response.data[1];
  if (n > pairs_in_frame) n = pairs_in_frame;
  if (n > kMaxDtcs) n = kMaxDtcs;
  out->count = n;
  for (uint8_t i = 0; i < kMaxDtcs; ++i) {
    out->codes[i] = 0;
  }
  for (uint8_t i = 0; i < n; ++i) {
    out->codes[i] = static_cast<uint16_t>((response.data[2 + 2 * i] << 8) | response.data[3 + 2 * i]);
  }
  return true;
}

bool KlineKwp::read_stored_dtcs(DtcList* out) { return read_dtcs(0x03, 0x43, out); }
bool KlineKwp::read_pending_dtcs(DtcList* out) { return read_dtcs(0x07, 0x47, out); }
