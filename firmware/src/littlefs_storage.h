#pragma once

#include <LittleFS.h>

#include "ride_storage.h"

// Phase 1 storage backend: ESP32 internal flash via LittleFS. No extra
// wiring needed (Phase 0's build used ~20% of flash, plenty of headroom for
// bench-test ride files). Replaced by an SdStorage in Phase 2 behind the
// same RideStorage interface once the SD card is wired.
class LittleFsStorage : public RideStorage {
 public:
  bool open_for_write(const char* filename) override;
  void write_line(const char* line) override;
  void flush() override;
  void close() override;

 private:
  File file_;
};
