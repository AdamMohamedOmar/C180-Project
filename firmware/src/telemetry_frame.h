#pragma once

#include <cstddef>
#include <cstdint>

#include "dtc_list.h"
#include "latest_values.h"

// BLE frame packer/parser — mirror-verbatim of docs/ble_protocol.md v1.
// Framework-agnostic (no Arduino headers): golden-vector-tested natively,
// consumed by ble_svc on the ESP32. The Kotlin mirror is FrameCodec.kt.

inline constexpr uint8_t kBleProtocolVersion = 0x01;
inline constexpr size_t kTelemetryFrameLen = 88;  // 12 header + 19*4 floats
inline constexpr size_t kControlFrameLen = 9;     // opcode + uint64 epoch_ms
inline constexpr size_t kDtcFrameMaxLen = 3 + 2 * kMaxDtcs + 2 * kMaxDtcs;  // 35
inline constexpr uint8_t kControlOpTimeSync = 0x01;
// 0x02 = CLEAR_DTC: reserved in the protocol doc, deliberately UNIMPLEMENTED
// (deferred out of Phase 4 — the only ECU write in the whole project).

void pack_telemetry(const LatestValues& lv, uint8_t out[kTelemetryFrameLen]);

// Returns bytes written (3 + 2*stored.count + 2*pending.count), or 0 if
// `cap` is too small.
size_t pack_dtc_report(const DtcList& stored, const DtcList& pending,
                       uint8_t* out, size_t cap);

struct ControlCommand {
  uint8_t opcode;
  uint64_t epoch_ms;
};

// True only for a well-formed 9-byte frame with a KNOWN opcode. Unknown
// opcodes (including reserved 0x02) return false — caller logs and ignores.
bool parse_control(const uint8_t* data, size_t len, ControlCommand* out);
