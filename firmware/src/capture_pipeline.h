#pragma once

#include <atomic>
#include <cstdint>

#include "capture_queue.h"
#include "pid_scheduler.h"

// Producer-side helper (capture task + BLE time-sync callback): shapes
// readings/headers/lifecycle into CaptureEvents. Readings are droppable
// under backpressure (counted); lifecycle/header sends report failure so
// the caller can log. NOT itself thread-safe — one logical producer at a
// time; the underlying FreeRTOS queue makes concurrent send() calls from
// the NimBLE callback safe in production.
class CapturePipeline {
 public:
  explicit CapturePipeline(CaptureQueue& q) : q_(q) {}

  bool ride_start(const char* init_mode);
  bool header_line(const char* text);
  bool ride_close();
  void reading(uint32_t t_ms, const PidScheduler::Reading& r);

  uint32_t dropped() const { return dropped_; }

  // Emits "dropped=<total>" as a header line when the count changed since
  // the last emit — cheap engineering honesty about backpressure loss.
  void emit_drop_marker();

 private:
  CaptureQueue& q_;
  std::atomic<uint32_t> dropped_{0};
  std::atomic<uint32_t> last_reported_dropped_{0};
};
