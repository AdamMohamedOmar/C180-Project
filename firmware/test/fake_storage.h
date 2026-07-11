#pragma once

#include <string>
#include <vector>

#include "ride_storage.h"

class FakeStorage : public RideStorage {
 public:
  bool open_for_write(const char* filename) override {
    if (fail_open) {
      return false;
    }
    opened_filename = filename;
    return true;
  }
  void write_line(const char* line) override { lines.push_back(line); }
  void flush() override { ++flush_count; }
  void close() override { closed = true; }

  // Set before calling RideLogger::start_ride() to simulate a storage
  // failure (e.g. LittleFS mount failure) -- tests open_for_write()'s
  // false-return path.
  bool fail_open = false;

  std::string opened_filename;
  std::vector<std::string> lines;
  int flush_count = 0;
  bool closed = false;
};
