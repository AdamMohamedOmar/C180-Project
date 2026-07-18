#include <unity.h>

#include <deque>

#include "capture_pipeline.h"
#include "fake_ride_file_store.h"
#include "fake_storage.h"
#include "ride_logger.h"
#include "storage_drain.h"

// In-memory queue double. Single-threaded by design -- thread-safety in
// production comes from the FreeRTOS queue, not from this logic.
class FakeCaptureQueue : public CaptureQueue {
 public:
  bool send(const CaptureEvent& e) override {
    if (static_cast<int>(items.size()) >= capacity) return false;
    items.push_back(e);
    return true;
  }
  bool receive(CaptureEvent* out, uint32_t) override {
    if (items.empty()) return false;
    *out = items.front();
    items.pop_front();
    return true;
  }
  std::deque<CaptureEvent> items;
  int capacity = kCaptureQueueDepth;
};

void setUp() {}
void tearDown() {}

PidScheduler::Reading reading(Signal s, float v, bool avail) {
  PidScheduler::Reading r;
  r.signal = s;
  r.value = v;
  r.available = avail;
  return r;
}

void test_reading_event_preserves_availability_flag() {
  FakeCaptureQueue q;
  CapturePipeline p(q);
  p.reading(1234, reading(Signal::RPM, 800.0f, false));
  TEST_ASSERT_EQUAL_size_t(1, q.items.size());
  const CaptureEvent& e = q.items.front();
  TEST_ASSERT_EQUAL(CaptureEvent::kReading, e.type);
  TEST_ASSERT_EQUAL_UINT32(1234u, e.t_ms);
  TEST_ASSERT_FALSE(e.available);  // Untested contract survives the queue
}

void test_full_queue_drops_and_counts_readings() {
  FakeCaptureQueue q;
  q.capacity = 2;
  CapturePipeline p(q);
  p.reading(1, reading(Signal::RPM, 800.0f, true));
  p.reading(2, reading(Signal::RPM, 810.0f, true));
  p.reading(3, reading(Signal::RPM, 820.0f, true));  // dropped
  TEST_ASSERT_EQUAL_UINT32(1u, p.dropped());
  TEST_ASSERT_EQUAL_size_t(2, q.items.size());
}

void test_drop_marker_emitted_once_per_change() {
  FakeCaptureQueue q;
  q.capacity = 1;
  CapturePipeline p(q);
  p.reading(1, reading(Signal::RPM, 800.0f, true));  // fills queue
  p.reading(2, reading(Signal::RPM, 810.0f, true));  // dropped
  q.items.clear();
  p.emit_drop_marker();
  TEST_ASSERT_EQUAL_size_t(1, q.items.size());
  TEST_ASSERT_EQUAL(CaptureEvent::kHeaderLine, q.items.front().type);
  TEST_ASSERT_EQUAL_STRING("dropped=1", q.items.front().text);
  q.items.clear();
  p.emit_drop_marker();  // unchanged count -> silent
  TEST_ASSERT_EQUAL_size_t(0, q.items.size());
}

// Retention's actual eviction behavior is covered by test_ride_retention;
// this only checks that enforce_retention() runs without crashing/blocking
// ride creation, and that the allocated name/active-ride wiring is correct.
void test_drain_opens_ride_with_allocated_name() {
  FakeStorage storage;
  RideLogger logger(storage);
  FakeRideFileStore store;
  store.write_text("ride_next.txt", "7");
  StorageDrain drain(logger, store, "fw-test");

  FakeCaptureQueue q;
  CapturePipeline p(q);
  p.ride_start("logical-init");
  CaptureEvent e;
  while (q.receive(&e, 0)) drain.handle(e);

  TEST_ASSERT_TRUE(drain.ride_open());
  TEST_ASSERT_EQUAL_STRING("ride_00007.csv", drain.ride_name());
  TEST_ASSERT_EQUAL_STRING("ride_00007.csv", storage.opened_filename.c_str());
  TEST_ASSERT_EQUAL_STRING("ride_00007.csv", store.active.c_str());
}

void test_duplicate_ride_start_is_a_noop_while_already_open() {
  FakeStorage storage;
  RideLogger logger(storage);
  FakeRideFileStore store;
  store.write_text("ride_next.txt", "7");
  StorageDrain drain(logger, store, "fw-test");

  FakeCaptureQueue q;
  CapturePipeline p(q);
  p.ride_start("logical-init");
  CaptureEvent e;
  while (q.receive(&e, 0)) drain.handle(e);
  TEST_ASSERT_EQUAL_STRING("ride_00007.csv", drain.ride_name());

  // A second ride_start while the first is still open must be a true
  // no-op: no new seq allocated, no new file opened, old data untouched.
  p.ride_start("logical-init");
  while (q.receive(&e, 0)) drain.handle(e);

  TEST_ASSERT_EQUAL_STRING("ride_00007.csv", drain.ride_name());       // unchanged
  TEST_ASSERT_EQUAL_STRING("ride_00007.csv", storage.opened_filename.c_str());  // not reopened under a new name
  char next[16];
  store.read_text("ride_next.txt", next, sizeof(next));
  TEST_ASSERT_EQUAL_STRING("8", next);  // seq counter did NOT advance to 9 -- proves allocate_ride_seq wasn't called again
}

void test_drain_routes_headers_and_readings_in_order() {
  FakeStorage storage;
  RideLogger logger(storage);
  FakeRideFileStore store;
  StorageDrain drain(logger, store, "fw-test");

  FakeCaptureQueue q;
  CapturePipeline p(q);
  p.ride_start("logical-init");
  p.header_line("dtc_stored=");
  p.reading(500, reading(Signal::RPM, 800.0f, true));
  CaptureEvent e;
  while (q.receive(&e, 0)) drain.handle(e);

  // start_ride writes its own header block first; our two lines follow in
  // queue order. Just assert relative order of the two payloads we sent.
  int header_idx = -1, reading_idx = -1;
  for (size_t i = 0; i < storage.lines.size(); ++i) {
    if (storage.lines[i].find("dtc_stored=") != std::string::npos)
      header_idx = static_cast<int>(i);
    if (storage.lines[i].find("500") == 0)
      reading_idx = static_cast<int>(i);
  }
  TEST_ASSERT_TRUE(header_idx >= 0);
  TEST_ASSERT_TRUE(reading_idx > header_idx);
}

void test_drain_ignores_events_before_ride_start() {
  FakeStorage storage;
  RideLogger logger(storage);
  FakeRideFileStore store;
  StorageDrain drain(logger, store, "fw-test");
  CaptureEvent e;
  memset(&e, 0, sizeof(e));
  e.type = CaptureEvent::kReading;
  e.signal = Signal::RPM;
  e.available = true;
  drain.handle(e);
  TEST_ASSERT_EQUAL_size_t(0, storage.lines.size());
  TEST_ASSERT_FALSE(drain.ride_open());
}

void test_drain_flushes_on_cadence_only_while_open() {
  FakeStorage storage;
  RideLogger logger(storage);
  FakeRideFileStore store;
  StorageDrain drain(logger, store, "fw-test");

  drain.maybe_flush(10000);  // closed -> no flush
  TEST_ASSERT_EQUAL_INT(0, storage.flush_count);

  FakeCaptureQueue q;
  CapturePipeline p(q);
  p.ride_start("logical-init");
  CaptureEvent e;
  while (q.receive(&e, 0)) drain.handle(e);

  drain.maybe_flush(20000);
  const int after_first = storage.flush_count;
  TEST_ASSERT_TRUE(after_first >= 1);
  drain.maybe_flush(20001);  // within cadence -> no extra flush
  TEST_ASSERT_EQUAL_INT(after_first, storage.flush_count);
  drain.maybe_flush(25001);  // past cadence -> flush
  TEST_ASSERT_EQUAL_INT(after_first + 1, storage.flush_count);
}

void test_drain_close_writes_crc_sidecar_and_clears_active() {
  FakeStorage storage;
  RideLogger logger(storage);
  FakeRideFileStore store;
  // Give the fake store ride content so the sidecar has bytes to CRC. The
  // drain writes via RideLogger/FakeStorage (write side), so mirror the
  // closed file into the read-side fake by hand.
  StorageDrain drain(logger, store, "fw-test");
  FakeCaptureQueue q;
  CapturePipeline p(q);
  p.ride_start("logical-init");
  CaptureEvent e;
  while (q.receive(&e, 0)) drain.handle(e);
  store.files[drain.ride_name()] = "123456789";

  p.ride_close();
  while (q.receive(&e, 0)) drain.handle(e);

  TEST_ASSERT_FALSE(drain.ride_open());
  TEST_ASSERT_TRUE(storage.closed);
  TEST_ASSERT_EQUAL_STRING("", store.active.c_str());
  char text[16];
  TEST_ASSERT_TRUE(store.read_text("ride_00001.csv.crc", text, sizeof(text)));
  TEST_ASSERT_EQUAL_STRING("CBF43926", text);
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_reading_event_preserves_availability_flag);
  RUN_TEST(test_full_queue_drops_and_counts_readings);
  RUN_TEST(test_drop_marker_emitted_once_per_change);
  RUN_TEST(test_drain_opens_ride_with_allocated_name);
  RUN_TEST(test_duplicate_ride_start_is_a_noop_while_already_open);
  RUN_TEST(test_drain_routes_headers_and_readings_in_order);
  RUN_TEST(test_drain_ignores_events_before_ride_start);
  RUN_TEST(test_drain_flushes_on_cadence_only_while_open);
  RUN_TEST(test_drain_close_writes_crc_sidecar_and_clears_active);
  return UNITY_END();
}
