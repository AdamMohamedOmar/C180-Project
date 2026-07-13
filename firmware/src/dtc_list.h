#pragma once

#include <cstdint>

// Fixed-capacity DTC holder — no heap on the ESP32. 8 is a deliberate cap:
// [Best estimate] a real ECU rarely stores more, and 8 codes fit in one
// KWP frame (63-byte data limit) and keep the BLE DTC frame under any
// sane MTU (docs/ble_protocol.md).
inline constexpr uint8_t kMaxDtcs = 8;

struct DtcList {
  uint8_t count = 0;               // 0..kMaxDtcs
  uint16_t codes[kMaxDtcs] = {0};  // SAE J2012 2-byte values, (byte1<<8)|byte2
};

// Renders a J2012 code as text, e.g. 0x0171 -> "P0171". `out` must hold 6
// bytes (5 chars + NUL). Digits render as uppercase hex — J2012's four
// "digits" are 4-bit fields, and codes like P0A0B legitimately exist in
// later standards. Firmware-side mirror of the app's FrameCodec.decodeDtc.
void dtc_code_to_string(uint16_t code, char out[6]);
