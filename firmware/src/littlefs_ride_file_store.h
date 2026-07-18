#pragma once

#include <Arduino.h>

#include "ride_file_store.h"

// LittleFS impl of the read/list/delete side. Shares the mount with
// LittleFsStorage (LittleFS.begin() is idempotent once mounted). Interface
// names are BARE; this impl prepends "/" for LittleFS paths.
//
// Called from TWO tasks (storage_task via StorageDrain, and loopTask via
// WifiSync's HTTP handlers -- see main.cpp) with no other synchronization
// between them, so every public method takes mux_ for its whole body. A
// FreeRTOS mutex (not a spinlock/portENTER_CRITICAL, which is used
// elsewhere in this codebase for tiny fixed-duration copies) is used
// deliberately: these methods do real flash I/O of unpredictable duration,
// and holding a spinlock across I/O would stall the OTHER core for that
// whole time -- exactly what the two-task split exists to avoid.
class LittleFsRideFileStore : public RideFileStore {
 public:
  LittleFsRideFileStore();

  int list_rides(RideFileInfo* out, int cap) override;
  int32_t read(const char* name, uint32_t offset, uint8_t* buf,
               uint32_t len) override;
  bool remove(const char* name) override;
  uint64_t free_bytes() override;
  bool read_text(const char* name, char* out, size_t cap) override;
  bool write_text(const char* name, const char* text) override;
  void set_active_ride(const char* name_or_null) override;

 private:
  bool ensure_mounted();
  char active_[40] = {0};
  SemaphoreHandle_t mux_;
};
