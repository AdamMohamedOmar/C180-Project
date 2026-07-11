#include <unity.h>

#include "fake_storage.h"
#include "ride_logger.h"

// Unity fixture hooks -- the framework links against these even when unused.
void setUp(void) {}
void tearDown(void) {}

void test_start_ride_writes_header_and_column_row(void) {
  FakeStorage storage;
  RideLogger logger(storage);
  TEST_ASSERT_TRUE(logger.start_ride("ride_001.csv", "0.0.1-phase1", "fast"));
  TEST_ASSERT_EQUAL_STRING("ride_001.csv", storage.opened_filename.c_str());
  TEST_ASSERT_EQUAL(3, storage.lines.size());
  TEST_ASSERT_EQUAL_STRING("#fw_version=0.0.1-phase1", storage.lines[0].c_str());
  TEST_ASSERT_EQUAL_STRING("#init_mode=fast", storage.lines[1].c_str());
  TEST_ASSERT_EQUAL_STRING("t_ms,signal,value", storage.lines[2].c_str());
}

void test_log_reading_writes_a_csv_row_for_available_reading(void) {
  FakeStorage storage;
  RideLogger logger(storage);
  logger.start_ride("r.csv", "v", "fast");

  PidScheduler::Reading reading{Signal::RPM, 800.0f, true};
  logger.log_reading(1234, reading);

  TEST_ASSERT_EQUAL(4, storage.lines.size());
  TEST_ASSERT_EQUAL_STRING("1234,RPM,800.000", storage.lines[3].c_str());
}

void test_log_reading_skips_row_for_unavailable_reading(void) {
  FakeStorage storage;
  RideLogger logger(storage);
  logger.start_ride("r.csv", "v", "fast");

  PidScheduler::Reading reading{Signal::PEDAL_D, 0.0f, false};
  logger.log_reading(1234, reading);

  TEST_ASSERT_EQUAL(3, storage.lines.size());  // still just the header block
}

void test_signal_name_returns_correct_string(void) {
  TEST_ASSERT_EQUAL_STRING("ECT", signal_name(Signal::ECT));
  TEST_ASSERT_EQUAL_STRING("O2_B1S1_V", signal_name(Signal::O2_B1S1_V));
}

void test_start_ride_fails_and_writes_nothing_when_storage_cannot_open(void) {
  FakeStorage storage;
  storage.fail_open = true;
  RideLogger logger(storage);

  TEST_ASSERT_FALSE(logger.start_ride("ride_001.csv", "v", "fast"));
  TEST_ASSERT_TRUE(storage.lines.empty());
  TEST_ASSERT_EQUAL(0, storage.flush_count);
}

void test_flush_and_close_ride_forward_to_storage(void) {
  FakeStorage storage;
  RideLogger logger(storage);
  logger.start_ride("r.csv", "v", "fast");  // 1 flush() already, from start_ride

  logger.flush();
  TEST_ASSERT_EQUAL(2, storage.flush_count);

  TEST_ASSERT_FALSE(storage.closed);
  logger.close_ride();
  TEST_ASSERT_TRUE(storage.closed);
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_start_ride_writes_header_and_column_row);
  RUN_TEST(test_log_reading_writes_a_csv_row_for_available_reading);
  RUN_TEST(test_log_reading_skips_row_for_unavailable_reading);
  RUN_TEST(test_signal_name_returns_correct_string);
  RUN_TEST(test_start_ride_fails_and_writes_nothing_when_storage_cannot_open);
  RUN_TEST(test_flush_and_close_ride_forward_to_storage);
  return UNITY_END();
}
