#include "capture_pipeline.h"

#include <cstdio>
#include <cstring>

namespace {

CaptureEvent make(CaptureEvent::Type type) {
  CaptureEvent e;
  memset(&e, 0, sizeof(e));
  e.type = type;
  return e;
}

}  // namespace

bool CapturePipeline::ride_start(const char* init_mode) {
  CaptureEvent e = make(CaptureEvent::kRideStart);
  snprintf(e.text2, sizeof(e.text2), "%s", init_mode);
  return q_.send(e);
}

bool CapturePipeline::header_line(const char* text) {
  CaptureEvent e = make(CaptureEvent::kHeaderLine);
  snprintf(e.text, sizeof(e.text), "%s", text);
  return q_.send(e);
}

bool CapturePipeline::ride_close() {
  return q_.send(make(CaptureEvent::kRideClose));
}

void CapturePipeline::reading(uint32_t t_ms, const PidScheduler::Reading& r) {
  CaptureEvent e = make(CaptureEvent::kReading);
  e.t_ms = t_ms;
  e.signal = r.signal;
  e.value = r.value;
  e.available = r.available;
  if (!q_.send(e)) ++dropped_;
}

void CapturePipeline::emit_drop_marker() {
  const uint32_t current = dropped_.load();
  if (current == last_reported_dropped_.load()) return;
  char line[32];
  snprintf(line, sizeof(line), "dropped=%lu", static_cast<unsigned long>(current));
  if (header_line(line)) last_reported_dropped_.store(current);
}
