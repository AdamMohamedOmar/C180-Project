#include <unity.h>

#include "fake_transport.h"
#include "kline_frame.h"
#include "kline_kwp.h"
#include "pid_scheduler.h"

// Unity fixture hooks -- the framework links against these even when unused.
void setUp(void) {}
void tearDown(void) {}

void test_first_tick_polls_the_first_due_signal(void) {
  FakeTransport transport;
  const uint8_t response_data[] = {0x41, 0x0C, 0x0C, 0x80};  // RPM=800.0
  uint8_t frame[16];
  size_t len = kline_build_frame(kTesterAddress, kTargetAddress, response_data, 4, frame, sizeof(frame));
  transport.queue_response(std::vector<uint8_t>(frame, frame + len));

  KlineKwp kwp(transport);
  PidScheduler scheduler(kwp);

  PidScheduler::Reading reading;
  TEST_ASSERT_TRUE(scheduler.tick(0, &reading));
  TEST_ASSERT_EQUAL(static_cast<int>(Signal::RPM), static_cast<int>(reading.signal));
  TEST_ASSERT_TRUE(reading.available);
  TEST_ASSERT_EQUAL_FLOAT(800.0f, reading.value);
}

void test_second_tick_at_same_time_polls_the_next_signal(void) {
  FakeTransport transport;
  const uint8_t rpm_data[] = {0x41, 0x0C, 0x0C, 0x80};
  const uint8_t speed_data[] = {0x41, 0x0D, 0x32};  // SPEED=50
  uint8_t rpm_frame[16], speed_frame[16];
  size_t rpm_len = kline_build_frame(kTesterAddress, kTargetAddress, rpm_data, 4, rpm_frame, sizeof(rpm_frame));
  size_t speed_len = kline_build_frame(kTesterAddress, kTargetAddress, speed_data, 3, speed_frame, sizeof(speed_frame));

  KlineKwp kwp(transport);
  PidScheduler scheduler(kwp);
  PidScheduler::Reading reading;

  transport.queue_response(std::vector<uint8_t>(rpm_frame, rpm_frame + rpm_len));
  scheduler.tick(0, &reading);  // consumes RPM's slot, sets its next_due_ms

  transport.queue_response(std::vector<uint8_t>(speed_frame, speed_frame + speed_len));
  TEST_ASSERT_TRUE(scheduler.tick(0, &reading));
  TEST_ASSERT_EQUAL(static_cast<int>(Signal::SPEED), static_cast<int>(reading.signal));
  TEST_ASSERT_EQUAL_FLOAT(50.0f, reading.value);
}

void test_more_overdue_signal_served_before_recently_due_lower_index_signal(void) {
  // Regression test for a starvation bug: a fixed lowest-index-wins scan
  // would let RPM (index 0, due every 500ms) always win over every other
  // signal, forever, once the schedule is running -- even signals that
  // have been waiting far longer. This proves the opposite: once RPM's
  // slot has been consumed and a later tick() finds RPM only moderately
  // overdue while everything else has been waiting since t=0 (and is
  // therefore MORE overdue), one of those other signals is served, not
  // RPM again, despite RPM having the lowest array index.
  FakeTransport transport;
  const uint8_t rpm_data[] = {0x41, 0x0C, 0x0C, 0x80};
  const uint8_t speed_data[] = {0x41, 0x0D, 0x32};  // SPEED=50
  uint8_t rpm_frame[16], speed_frame[16];
  size_t rpm_len = kline_build_frame(kTesterAddress, kTargetAddress, rpm_data, 4, rpm_frame, sizeof(rpm_frame));
  size_t speed_len = kline_build_frame(kTesterAddress, kTargetAddress, speed_data, 3, speed_frame, sizeof(speed_frame));

  KlineKwp kwp(transport);
  PidScheduler scheduler(kwp);
  PidScheduler::Reading reading;

  transport.queue_response(std::vector<uint8_t>(rpm_frame, rpm_frame + rpm_len));
  scheduler.tick(0, &reading);  // serves RPM (index 0, tied at t=0), next_due_ms_[0] = 500

  // At t=10500: RPM is overdue by 10500-500=10000ms. Every other signal
  // (indices 1-17) has been sitting untouched since next_due_ms_=0, so
  // they're overdue by 10500ms -- more than RPM. SPEED (index 1) is the
  // lowest-indexed among that tied group and should win the tie-break,
  // NOT RPM despite RPM having the globally lowest index.
  transport.queue_response(std::vector<uint8_t>(speed_frame, speed_frame + speed_len));
  TEST_ASSERT_TRUE(scheduler.tick(10500, &reading));
  TEST_ASSERT_EQUAL(static_cast<int>(Signal::SPEED), static_cast<int>(reading.signal));
}

void test_tier_interval_ms_matches_plan_budget(void) {
  TEST_ASSERT_EQUAL_UINT32(500, tier_interval_ms(Tier::F));
  TEST_ASSERT_EQUAL_UINT32(2000, tier_interval_ms(Tier::M));
  TEST_ASSERT_EQUAL_UINT32(10000, tier_interval_ms(Tier::S));
  TEST_ASSERT_EQUAL_UINT32(30000, tier_interval_ms(Tier::LEGACY));
  TEST_ASSERT_EQUAL_UINT32(0, tier_interval_ms(Tier::LOCAL));
}

void test_unanswered_pid_produces_unavailable_reading_not_a_crash(void) {
  FakeTransport transport;  // nothing queued for anyone yet
  KlineKwp kwp(transport);
  PidScheduler scheduler(kwp);
  PidScheduler::Reading reading;

  scheduler.tick(0, &reading);  // RPM: unanswered
  TEST_ASSERT_FALSE(reading.available);
  TEST_ASSERT_EQUAL(static_cast<int>(Signal::RPM), static_cast<int>(reading.signal));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_first_tick_polls_the_first_due_signal);
  RUN_TEST(test_second_tick_at_same_time_polls_the_next_signal);
  RUN_TEST(test_more_overdue_signal_served_before_recently_due_lower_index_signal);
  RUN_TEST(test_tier_interval_ms_matches_plan_budget);
  RUN_TEST(test_unanswered_pid_produces_unavailable_reading_not_a_crash);
  return UNITY_END();
}
