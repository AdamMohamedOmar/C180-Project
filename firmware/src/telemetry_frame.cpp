#include "telemetry_frame.h"

#include <cstring>

namespace {

void put_u16(uint8_t* p, uint16_t v) {
  p[0] = static_cast<uint8_t>(v & 0xFF);
  p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
}

void put_u32(uint8_t* p, uint32_t v) {
  p[0] = static_cast<uint8_t>(v & 0xFF);
  p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
  p[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
  p[3] = static_cast<uint8_t>((v >> 24) & 0xFF);
}

void put_f32(uint8_t* p, float v) {
  uint32_t bits;
  static_assert(sizeof(bits) == sizeof(v), "float32 assumed");
  std::memcpy(&bits, &v, sizeof(bits));
  put_u32(p, bits);
}

}  // namespace

void pack_telemetry(const LatestValues& lv, uint8_t out[kTelemetryFrameLen]) {
  out[0] = kBleProtocolVersion;
  out[1] = lv.flags;
  put_u16(&out[2], lv.seq);
  put_u32(&out[4], lv.uptime_ms);
  put_u32(&out[8], lv.avail_mask);
  for (size_t i = 0; i < kSignalCount; ++i) {
    // Contract: masked-out slots transmit exactly 0.0f regardless of cache
    // contents (docs/ble_protocol.md — receiver must ignore them anyway,
    // but never leak stale values onto the air).
    const bool available = (lv.avail_mask >> i) & 1u;
    put_f32(&out[12 + 4 * i], available ? lv.values[i] : 0.0f);
  }
}

size_t pack_dtc_report(const DtcList& stored, const DtcList& pending,
                       uint8_t* out, size_t cap) {
  // Defensive clamp: DtcList.count is a public, directly-settable field
  // with no invariant enforcement. The current sole producer (kline_kwp)
  // already clamps to kMaxDtcs before populating a DtcList, but this
  // packer must stay safe regardless of who fed the struct (same posture
  // as pack_telemetry's masked-value handling above).
  const uint8_t stored_count = stored.count > kMaxDtcs ? kMaxDtcs : stored.count;
  const uint8_t pending_count = pending.count > kMaxDtcs ? kMaxDtcs : pending.count;
  const size_t needed = 3 + 2 * static_cast<size_t>(stored_count) +
                        2 * static_cast<size_t>(pending_count);
  if (cap < needed) {
    return 0;
  }
  out[0] = kBleProtocolVersion;
  out[1] = stored_count;
  out[2] = pending_count;
  size_t o = 3;
  for (uint8_t i = 0; i < stored_count; ++i) {
    out[o++] = static_cast<uint8_t>(stored.codes[i] >> 8);  // J2012 byte1 first
    out[o++] = static_cast<uint8_t>(stored.codes[i] & 0xFF);
  }
  for (uint8_t i = 0; i < pending_count; ++i) {
    out[o++] = static_cast<uint8_t>(pending.codes[i] >> 8);
    out[o++] = static_cast<uint8_t>(pending.codes[i] & 0xFF);
  }
  return needed;
}

bool parse_control(const uint8_t* data, size_t len, ControlCommand* out) {
  if (len != kControlFrameLen || data[0] != kControlOpTimeSync) {
    return false;
  }
  out->opcode = data[0];
  uint64_t v = 0;
  for (int i = 0; i < 8; ++i) {
    v |= static_cast<uint64_t>(data[1 + i]) << (8 * i);
  }
  out->epoch_ms = v;
  return true;
}
