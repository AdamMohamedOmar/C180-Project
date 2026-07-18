#pragma once

#include <Arduino.h>

#include "capture_queue.h"

// FreeRTOS-backed CaptureQueue. xQueueSend with 0 timeout never blocks the
// capture task; xQueueSend/xQueueReceive are task-safe, so the NimBLE
// callback may also send (time-sync markers) without extra locking.
class FreeRtosCaptureQueue : public CaptureQueue {
 public:
  FreeRtosCaptureQueue() {
    handle_ = xQueueCreate(kCaptureQueueDepth, sizeof(CaptureEvent));
  }

  bool send(const CaptureEvent& e) override {
    return handle_ != nullptr && xQueueSend(handle_, &e, 0) == pdTRUE;
  }

  bool receive(CaptureEvent* out, uint32_t timeout_ms) override {
    return handle_ != nullptr &&
           xQueueReceive(handle_, out, pdMS_TO_TICKS(timeout_ms)) == pdTRUE;
  }

 private:
  QueueHandle_t handle_ = nullptr;
};
