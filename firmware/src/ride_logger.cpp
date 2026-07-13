#include "ride_logger.h"

#include <cstdio>

const char* signal_name(Signal signal) {
  switch (signal) {
    case Signal::RPM: return "RPM";
    case Signal::SPEED: return "SPEED";
    case Signal::MAF_GS: return "MAF_GS";
    case Signal::THROTTLE: return "THROTTLE";
    case Signal::LOAD: return "LOAD";
    case Signal::STFT1: return "STFT1";
    case Signal::LTFT1: return "LTFT1";
    case Signal::MAP: return "MAP";
    case Signal::TIMING_ADV: return "TIMING_ADV";
    case Signal::O2_B1S1_V: return "O2_B1S1_V";
    case Signal::O2_B1S2_V: return "O2_B1S2_V";
    case Signal::ECT: return "ECT";
    case Signal::IAT: return "IAT";
    case Signal::FUEL_STATUS: return "FUEL_STATUS";
    case Signal::PEDAL_D: return "PEDAL_D";
    case Signal::PEDAL_E: return "PEDAL_E";
    case Signal::CMD_THROTTLE: return "CMD_THROTTLE";
    case Signal::CTRL_MODULE_V: return "CTRL_MODULE_V";
    case Signal::BATT_V_ADC: return "BATT_V_ADC";
    case Signal::COUNT: return "";
  }
  return "";
}

// 64 bytes covers every current line with margin: worst case is t_ms (10
// digits) + 2 commas + the longest signal name (CTRL_MODULE_V, 13 chars)
// + the widest value any current FormulaKind produces (TWO_BYTE_DIV4's max
// 16383.750, 9 chars) ~= 35 chars. fw_version/init_mode are short literals
// today (see version.h) but aren't schema-bounded like decoded values --
// snprintf truncates safely rather than overflowing, but revisit this
// bound if either ever becomes a longer, less-controlled string.
bool RideLogger::start_ride(const char* filename, const char* fw_version, const char* init_mode) {
  if (!storage_.open_for_write(filename)) {
    return false;
  }
  char header[64];
  snprintf(header, sizeof(header), "#fw_version=%s", fw_version);
  storage_.write_line(header);
  snprintf(header, sizeof(header), "#init_mode=%s", init_mode);
  storage_.write_line(header);
  storage_.write_line("t_ms,signal,value");
  storage_.flush();
  return true;
}

void RideLogger::log_reading(uint32_t t_ms, const PidScheduler::Reading& reading) {
  if (!reading.available) {
    return;
  }
  char line[64];
  snprintf(line, sizeof(line), "%lu,%s,%.3f", static_cast<unsigned long>(t_ms),
           signal_name(reading.signal), static_cast<double>(reading.value));
  storage_.write_line(line);
}

void RideLogger::write_header_line(const char* text) {
  // 96 bytes covers the longest current caller (8 DTC codes joined with
  // commas = 8*6-1+11 chars ≈ 58) with margin; snprintf truncates safely.
  char line[96];
  snprintf(line, sizeof(line), "#%s", text);
  storage_.write_line(line);
}

void RideLogger::flush() { storage_.flush(); }
void RideLogger::close_ride() { storage_.close(); }
