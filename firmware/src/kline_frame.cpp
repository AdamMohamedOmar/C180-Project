#include "kline_frame.h"

uint8_t kline_checksum(const uint8_t* data, size_t len) {
  uint32_t sum = 0;
  for (size_t i = 0; i < len; ++i) {
    sum += data[i];
  }
  return static_cast<uint8_t>(sum & 0xFF);
}

size_t kline_build_frame(uint8_t target, uint8_t source, const uint8_t* data,
                          size_t data_len, uint8_t* out, size_t out_capacity) {
  if (data_len > kKlineMaxDataLen) {
    return 0;
  }
  const size_t frame_len = 3 + data_len + 1;
  if (out_capacity < frame_len) {
    return 0;
  }
  out[0] = static_cast<uint8_t>(0x80 | data_len);
  out[1] = target;
  out[2] = source;
  for (size_t i = 0; i < data_len; ++i) {
    out[3 + i] = data[i];
  }
  out[3 + data_len] = kline_checksum(out, 3 + data_len);
  return frame_len;
}

bool kline_parse_frame(const uint8_t* frame, size_t frame_len, ParsedFrame* out) {
  if (frame_len < 4) {
    return false;
  }
  const uint8_t fmt = frame[0];
  const size_t length = fmt & kKlineMaxDataLen;
  if (frame_len != 3 + length + 1) {
    return false;
  }
  if (kline_checksum(frame, 3 + length) != frame[3 + length]) {
    return false;
  }
  out->target = frame[1];
  out->source = frame[2];
  out->data_len = static_cast<uint8_t>(length);
  for (size_t i = 0; i < length; ++i) {
    out->data[i] = frame[3 + i];
  }
  return true;
}
