#include "pid_wire_format.h"

const WireFormat kWireFormatTable[kWireFormatCount] = {
    {Signal::RPM, 0x0C, FormulaKind::TWO_BYTE_DIV4, 2},
    {Signal::SPEED, 0x0D, FormulaKind::ONE_BYTE_RAW, 1},
    {Signal::MAF_GS, 0x10, FormulaKind::TWO_BYTE_DIV100, 2},
    {Signal::THROTTLE, 0x11, FormulaKind::ONE_BYTE_PCT255, 1},
    {Signal::LOAD, 0x04, FormulaKind::ONE_BYTE_PCT255, 1},
    {Signal::STFT1, 0x06, FormulaKind::ONE_BYTE_TRIM, 1},
    {Signal::LTFT1, 0x07, FormulaKind::ONE_BYTE_TRIM, 1},
    {Signal::MAP, 0x0B, FormulaKind::ONE_BYTE_RAW, 1},
    {Signal::TIMING_ADV, 0x0E, FormulaKind::ONE_BYTE_HALF_MINUS64, 1},
    {Signal::O2_B1S1_V, 0x14, FormulaKind::TWO_BYTE_VOLT_DIV200, 2},
    {Signal::O2_B1S2_V, 0x15, FormulaKind::TWO_BYTE_VOLT_DIV200, 2},
    {Signal::ECT, 0x05, FormulaKind::ONE_BYTE_MINUS40, 1},
    {Signal::IAT, 0x0F, FormulaKind::ONE_BYTE_MINUS40, 1},
    {Signal::FUEL_STATUS, 0x03, FormulaKind::ONE_BYTE_RAW_ENUM, 1},
    {Signal::PEDAL_D, 0x49, FormulaKind::ONE_BYTE_PCT255, 1},
    {Signal::PEDAL_E, 0x4A, FormulaKind::ONE_BYTE_PCT255, 1},
    {Signal::CMD_THROTTLE, 0x4C, FormulaKind::ONE_BYTE_PCT255, 1},
    {Signal::CTRL_MODULE_V, 0x42, FormulaKind::TWO_BYTE_DIV1000, 2},
};

const WireFormat* find_wire_format(Signal signal) {
  for (size_t i = 0; i < kWireFormatCount; ++i) {
    if (kWireFormatTable[i].signal == signal) {
      return &kWireFormatTable[i];
    }
  }
  return nullptr;
}

float decode_formula(FormulaKind formula, const uint8_t* data) {
  switch (formula) {
    case FormulaKind::TWO_BYTE_DIV4:
      return ((data[0] * 256.0f) + data[1]) / 4.0f;
    case FormulaKind::ONE_BYTE_RAW:
      return static_cast<float>(data[0]);
    case FormulaKind::TWO_BYTE_DIV100:
      return ((data[0] * 256.0f) + data[1]) / 100.0f;
    case FormulaKind::ONE_BYTE_PCT255:
      return data[0] * 100.0f / 255.0f;
    case FormulaKind::ONE_BYTE_TRIM:
      return (data[0] - 128.0f) * 100.0f / 128.0f;
    case FormulaKind::ONE_BYTE_HALF_MINUS64:
      return data[0] / 2.0f - 64.0f;
    case FormulaKind::TWO_BYTE_VOLT_DIV200:
      return data[0] / 200.0f;  // data[1] is an unused trim byte here.
    case FormulaKind::ONE_BYTE_MINUS40:
      return data[0] - 40.0f;
    case FormulaKind::ONE_BYTE_RAW_ENUM:
      return static_cast<float>(data[0]);
    case FormulaKind::TWO_BYTE_DIV1000:
      return ((data[0] * 256.0f) + data[1]) / 1000.0f;
  }
  return 0.0f;  // Unreachable if all enum values are handled above.
}
