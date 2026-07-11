#include <unity.h>

#include "pid_schema.h"

// Unity fixture hooks -- the framework links against these even when unused.
void setUp(void) {}
void tearDown(void) {}

void test_rpm_within_bounds(void) {
  TEST_ASSERT_TRUE(pid_schema_in_bounds(Signal::RPM, 850.0f));
}

void test_rpm_at_min_boundary_is_in_bounds(void) {
  TEST_ASSERT_TRUE(pid_schema_in_bounds(Signal::RPM, 0.0f));
}

void test_rpm_at_max_boundary_is_in_bounds(void) {
  TEST_ASSERT_TRUE(pid_schema_in_bounds(Signal::RPM, 8000.0f));
}

void test_rpm_rejects_above_max(void) {
  TEST_ASSERT_FALSE(pid_schema_in_bounds(Signal::RPM, 9000.0f));
}

void test_ect_rejects_below_min(void) {
  TEST_ASSERT_FALSE(pid_schema_in_bounds(Signal::ECT, -50.0f));
}

void test_signal_table_size_matches_enum_count(void) {
  TEST_ASSERT_EQUAL(19, static_cast<int>(Signal::COUNT));
}

void test_out_of_range_signal_index_is_rejected(void) {
  TEST_ASSERT_FALSE(pid_schema_in_bounds(static_cast<Signal>(255), 0.0f));
}

int main(int argc, char **argv) {
  UNITY_BEGIN();
  RUN_TEST(test_rpm_within_bounds);
  RUN_TEST(test_rpm_at_min_boundary_is_in_bounds);
  RUN_TEST(test_rpm_at_max_boundary_is_in_bounds);
  RUN_TEST(test_rpm_rejects_above_max);
  RUN_TEST(test_ect_rejects_below_min);
  RUN_TEST(test_signal_table_size_matches_enum_count);
  RUN_TEST(test_out_of_range_signal_index_is_rejected);
  return UNITY_END();
}
