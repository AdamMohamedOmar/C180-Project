#pragma once

#include <cstdint>

#include "capture_event.h"

inline constexpr int kCaptureQueueDepth = 128;

// Task-safe FIFO boundary between the capture task (readings, most
// events) and the storage task (the only consumer). The BLE time-sync
// callback (NimBLE host task) also produces header-line events into this
// same queue -- see CapturePipeline's thread-safety notes. The FreeRTOS
// impl lives in freertos_capture_queue.h (ESP32-only, a later task); tests
// use an in-memory fake. send() must never block -- the capture task's
// K-line timing is the whole point of this boundary.
class CaptureQueue {
 public:
  virtual ~CaptureQueue() = default;
  virtual bool send(const CaptureEvent& e) = 0;  // false = queue full
  virtual bool receive(CaptureEvent* out, uint32_t timeout_ms) = 0;
};
