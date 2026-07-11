#pragma once

#include <cstdint>

#include "pid_schema.h"

enum class FormulaKind : uint8_t {
  TWO_BYTE_DIV4,          // RPM
  ONE_BYTE_RAW,           // SPEED, MAP
  TWO_BYTE_DIV100,        // MAF_GS
  ONE_BYTE_PCT255,        // THROTTLE, LOAD
  ONE_BYTE_TRIM,          // STFT1, LTFT1
  ONE_BYTE_HALF_MINUS64,  // TIMING_ADV
  TWO_BYTE_VOLT_DIV200,   // O2_B1S1_V, O2_B1S2_V
  ONE_BYTE_MINUS40,       // ECT, IAT
  ONE_BYTE_RAW_ENUM,      // FUEL_STATUS (simplified — not real SAE bit-flag
                          // decoding, see PLAN.md §0.4 confidence tags; also
                          // reads bank 1 only — M271 is inline-4, no bank 2)
  TWO_BYTE_DIV1000,       // CTRL_MODULE_V
};

struct WireFormat {
  Signal signal;
  uint8_t mode01_pid;  // e.g. 0x0C for RPM. SAE J1979 Mode 01 PID byte.
  FormulaKind formula;
  uint8_t byte_count;  // 1 or 2 — how many data bytes this formula reads.
};

// 18 entries: all Signal values except BATT_V_ADC (Local, never on K-line).
inline constexpr size_t kWireFormatCount = 18;
extern const WireFormat kWireFormatTable[kWireFormatCount];

// Returns nullptr if `signal` has no K-line wire format (i.e. BATT_V_ADC).
const WireFormat* find_wire_format(Signal signal);

// Decodes `byte_count` raw response bytes (big-endian, per SAE J1979) into a
// physical value using `formula`. Caller must pass exactly
// `WireFormat::byte_count` bytes.
float decode_formula(FormulaKind formula, const uint8_t* data);
