#pragma once

#include <cstdint>

#include "pid_scheduler.h"
#include "ride_storage.h"

const char* signal_name(Signal signal);

class RideLogger {
 public:
  explicit RideLogger(RideStorage& storage) : storage_(storage) {}

  bool start_ride(const char* filename, const char* fw_version, const char* init_mode);
  void log_reading(uint32_t t_ms, const PidScheduler::Reading& reading);

  // Appends "#<text>" to the open ride file. Used for the startup DTC
  // snapshot and BLE time-sync markers (docs/ble_protocol.md control
  // frame). Comment-prefixed like the start_ride header block, so any
  // CSV consumer that skips #-lines keeps working mid-file.
  void write_header_line(const char* text);

  void flush();
  void close_ride();

 private:
  RideStorage& storage_;
};
