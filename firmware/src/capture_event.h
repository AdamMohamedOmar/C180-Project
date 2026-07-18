#pragma once

#include <cstdint>

#include "pid_schema.h"

// Fixed-size event for the capture -> storage FreeRTOS queue (queues copy
// by value; no pointers may cross tasks). 140 B (sizeof, incl. alignment
// padding); depth 128 => ~17.9 KB heap.
// [Best estimate — comfortable within the ESP32's free heap next to NimBLE.]
struct CaptureEvent {
  enum Type : uint8_t {
    kReading = 0,     // t_ms + signal/value/available
    kHeaderLine = 1,  // text = "#"-prefixed-by-logger header content
    kRideStart = 2,   // text2 = init_mode (filename is allocated storage-side)
    kRideClose = 3,
  };

  Type type;
  uint32_t t_ms;
  Signal signal;
  float value;
  bool available;   // Untested contract: unavailable PIDs stay visible
  char text[96];    // kHeaderLine payload
  char text2[24];   // kRideStart init_mode
};
