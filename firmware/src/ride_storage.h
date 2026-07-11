#pragma once

// Abstracts ride-file storage so ride_logger doesn't care whether it's
// writing to the ESP32's internal flash (Phase 1, no SD card wired yet) or
// a real SD card (Phase 2, once hardware/wiring.md's SPI pins are wired).
class RideStorage {
 public:
  virtual ~RideStorage() = default;

  virtual bool open_for_write(const char* filename) = 0;
  virtual void write_line(const char* line) = 0;
  virtual void flush() = 0;
  virtual void close() = 0;
};
