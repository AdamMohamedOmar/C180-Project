#pragma once

#include <cstdint>

#include "capture_event.h"
#include "ride_file_store.h"
#include "ride_logger.h"

// Same 5 s flush cadence main.cpp used inline (PLAN.md §5.1 ride_logger).
inline constexpr uint32_t kDrainFlushIntervalMs = 5000;

// Consumer side -- runs ONLY on the storage task (ride_open_, ride_name_,
// and last_flush_ms_ are plain non-atomic state; calling handle()/
// maybe_flush() from more than one task would race). The single owner of
// RideLogger after the split: allocates the ride name, enforces retention,
// opens/closes the file, writes the CRC sidecar at close.
class StorageDrain {
 public:
  StorageDrain(RideLogger& logger, RideFileStore& store,
               const char* fw_version)
      : logger_(logger), store_(store), fw_version_(fw_version) {}

  void handle(const CaptureEvent& e);
  void maybe_flush(uint32_t now_ms);

  bool ride_open() const { return ride_open_; }
  const char* ride_name() const { return ride_name_; }

 private:
  RideLogger& logger_;
  RideFileStore& store_;
  const char* fw_version_;
  bool ride_open_ = false;
  char ride_name_[40] = {0};
  uint32_t last_flush_ms_ = 0;
};
