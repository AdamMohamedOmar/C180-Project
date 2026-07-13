#include <unity.h>

#include "latest_values.h"

void setUp(void) {}
void tearDown(void) {}

void test_apply_available_sets_value_and_mask_bit(void) {
  LatestValues lv;
  latest_values_apply(&lv, Signal::RPM, 750.0f, true);
  TEST_ASSERT_EQUAL_FLOAT(750.0f, lv.values[0]);
  TEST_ASSERT_EQUAL_HEX32(0x00000001, lv.avail_mask);
}

void test_apply_unavailable_clears_value_and_mask_bit(void) {
  LatestValues lv;
  latest_values_apply(&lv, Signal::ECT, 90.0f, true);
  TEST_ASSERT_EQUAL_HEX32(1u << 11, lv.avail_mask);  // ECT is enum ordinal 11
  latest_values_apply(&lv, Signal::ECT, 0.0f, false);
  TEST_ASSERT_EQUAL_HEX32(0x00000000, lv.avail_mask);
  TEST_ASSERT_EQUAL_FLOAT(0.0f, lv.values[11]);
}

void test_apply_tracks_most_recent_poll_not_ever_succeeded(void) {
  // Spec 3.2: bit = MOST RECENT poll succeeded. A signal that answered
  // once then stopped answering must drop out of the mask.
  LatestValues lv;
  latest_values_apply(&lv, Signal::MAP, 98.0f, true);
  latest_values_apply(&lv, Signal::MAP, 0.0f, false);
  TEST_ASSERT_EQUAL_HEX32(0, lv.avail_mask);
  latest_values_apply(&lv, Signal::MAP, 99.0f, true);
  TEST_ASSERT_EQUAL_HEX32(1u << 7, lv.avail_mask);
  TEST_ASSERT_EQUAL_FLOAT(99.0f, lv.values[7]);
}

void test_apply_ignores_out_of_range_signal(void) {
  // Defensive: Signal::COUNT (or worse, a corrupted enum value) must not
  // index off the end of values[].
  LatestValues lv;
  latest_values_apply(&lv, Signal::COUNT, 1.0f, true);
  TEST_ASSERT_EQUAL_HEX32(0x00000000, lv.avail_mask);
}

void test_set_flag_sets_and_clears(void) {
  LatestValues lv;
  latest_values_set_flag(&lv, kFlagKlineConnected, true);
  TEST_ASSERT_EQUAL_HEX8(0x02, lv.flags);
  latest_values_set_flag(&lv, kFlagDemoMode, true);
  TEST_ASSERT_EQUAL_HEX8(0x03, lv.flags);
  latest_values_set_flag(&lv, kFlagKlineConnected, false);
  TEST_ASSERT_EQUAL_HEX8(0x01, lv.flags);
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_apply_available_sets_value_and_mask_bit);
  RUN_TEST(test_apply_unavailable_clears_value_and_mask_bit);
  RUN_TEST(test_apply_tracks_most_recent_poll_not_ever_succeeded);
  RUN_TEST(test_apply_ignores_out_of_range_signal);
  RUN_TEST(test_set_flag_sets_and_clears);
  return UNITY_END();
}
