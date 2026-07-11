#pragma once

#include <cstddef>
#include <cstdint>

// [Likely] placeholder ECU address — confirm against real SIM4LKE in Phase 3.
inline constexpr uint8_t kTargetAddress = 0x33;
// [Confirmed] standard ISO 14230 tester address.
inline constexpr uint8_t kTesterAddress = 0xF1;

// KWP2000's format byte packs length into 6 bits (fmt & 0x3F) -- this is
// the single source of that limit. ParsedFrame::data, kline_build_frame's
// data_len guard, and kline_parse_frame's length mask all derive from it,
// so a future change to the frame format only needs to touch this one value.
inline constexpr size_t kKlineMaxDataLen = 0x3F;

struct ParsedFrame {
  uint8_t target;
  uint8_t source;
  uint8_t data[kKlineMaxDataLen];
  uint8_t data_len;
};

uint8_t kline_checksum(const uint8_t* data, size_t len);

// Writes a frame ([FMT][TGT][SRC][DATA...][CS]) into `out` (capacity
// `out_capacity`). Returns the frame length, or 0 if `out_capacity` is too
// small or `data_len` > kKlineMaxDataLen.
size_t kline_build_frame(uint8_t target, uint8_t source, const uint8_t* data,
                          size_t data_len, uint8_t* out, size_t out_capacity);

// Parses `frame` into `*out`. Returns false on a length mismatch or bad
// checksum (mirrors protocol.py's parse_frame returning None).
bool kline_parse_frame(const uint8_t* frame, size_t frame_len, ParsedFrame* out);
