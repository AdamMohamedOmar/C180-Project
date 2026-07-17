#include "kline_kwp.h"

#include "kline_frame.h"
#include "pid_wire_format.h"

// The 32-byte TX buffer covers every current request with headroom: the
// largest is read_pid's 2-byte payload (3-byte header + 2 + checksum = 6).
// Revisit if a later phase adds a request needing a payload anywhere near
// kline_frame.h's protocol-max kKlineMaxDataLen (63).
bool KlineKwp::send_request_and_get_response(const uint8_t* data, uint8_t data_len, ParsedFrame* out) {
  uint8_t frame[32];
  size_t frame_len = kline_build_frame(kTargetAddress, kTesterAddress, data, data_len, frame, sizeof(frame));
  if (frame_len == 0) {
    // data_len exceeded kKlineMaxDataLen or didn't fit in `frame`. That is
    // a caller bug, NOT a live-ECU timeout: it must not feed needs_reinit()
    // and provoke pointless bus re-inits, so it gets its own counter (see
    // request_build_failures() in the header). No serial logging here --
    // this file stays Arduino-free so the native test env can compile it.
    ++request_build_failures_;
    return false;
  }
  transport_.write(frame, frame_len);

  // On real half-duplex K-line hardware every byte we transmit is also
  // received (single-wire bus), so the read window typically returns our
  // own request echo followed by the ECU's response, back to back. Read
  // the whole window into one buffer and scan it frame by frame (each
  // frame's length comes from its own format byte), skipping any
  // well-formed frame that is not addressed ECU->tester -- a self-echo has
  // target/source exactly reversed. Phase 1's direct-UART bench link
  // produces no echo and resolves on the first frame, unchanged. A frame
  // that fails to parse (bad checksum, truncated tail) aborts the scan --
  // no byte-level resync attempt; the exchange fails and the caller
  // retries.
  uint8_t rx[64];  // request echo (frame <= 32) + response (frame <= 32)
  const size_t rx_len = transport_.read(rx, sizeof(rx), kResponseTimeoutMs);
  size_t pos = 0;
  while (rx_len - pos >= 4) {
    const size_t flen = 3 + (rx[pos] & kKlineMaxDataLen) + 1;
    if (pos + flen > rx_len || !kline_parse_frame(&rx[pos], flen, out)) {
      break;
    }
    if (out->target == kTesterAddress && out->source == kTargetAddress) {
      // Resets on any well-formed, correctly-addressed reply -- including
      // one a caller (e.g. read_pid) goes on to reject for wrong SID/PID
      // content. This intentionally tracks "is the K-line link alive" (are
      // we getting parseable frames back at all), not "did this specific
      // request get the answer we wanted" -- an ECU declining one PID
      // doesn't mean the link is dead and doesn't warrant a full re-init.
      consecutive_timeouts_ = 0;
      return true;
    }
    pos += flen;  // echo or foreign traffic -- skip it and keep scanning
  }
  ++consecutive_timeouts_;
  return false;
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
  if (response.data_len < 1 || response.data[0] != positive_sid) {
    return false;
  }
  // Layout by data-length parity (see the header contract): odd data_len is
  // [SID][pairs...] (count implicit in frame length), even data_len is
  // [SID][count][pairs...]. Both defensive clamps survive in either layout:
  // never read pairs beyond what the frame physically carries, never store
  // more than kMaxDtcs. 0x0000 pairs are padding, not codes -- skipped.
  const bool has_count_byte = (response.data_len % 2) == 0;
  const uint8_t first = has_count_byte ? 2 : 1;
  uint8_t pairs = static_cast<uint8_t>((response.data_len - first) / 2);
  if (has_count_byte && response.data[1] < pairs) {
    pairs = response.data[1];
  }
  out->count = 0;
  for (uint8_t i = 0; i < kMaxDtcs; ++i) {
    out->codes[i] = 0;
  }
  for (uint8_t i = 0; i < pairs && out->count < kMaxDtcs; ++i) {
    const uint16_t code = static_cast<uint16_t>((response.data[first + 2 * i] << 8) |
                                                response.data[first + 2 * i + 1]);
    if (code != 0) {
      out->codes[out->count++] = code;
    }
  }
  return true;
}

bool KlineKwp::read_stored_dtcs(DtcList* out) { return read_dtcs(0x03, 0x43, out); }
bool KlineKwp::read_pending_dtcs(DtcList* out) { return read_dtcs(0x07, 0x47, out); }
