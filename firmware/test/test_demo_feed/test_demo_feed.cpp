#include <unity.h>

#include "demo_feed.h"
#include "pid_schema.h"

void setUp(void) {}
void tearDown(void) {}

void test_mask_is_exactly_the_demo_mask_at_all_times(void) {
  const uint32_t samples[] = {0, 500, 1000, 15000, 31000, 35000, 39999, 45000, 300000, 3600000};
  for (uint32_t t : samples) {
    LatestValues lv;
    demo_fill(t, &lv);
    TEST_ASSERT_EQUAL_HEX32(kDemoAvailMask, lv.avail_mask);
  }
}

void test_all_masked_values_stay_inside_schema_bounds(void) {
  // Sweep a full cycle + warmup at 250 ms steps — every served value must
  // satisfy pid_schema's physical bounds (the same guard the pipeline
  // applies; a demo that emits impossible values would exercise the app
  // against garbage).
  for (uint32_t t = 0; t <= 360000; t += 250) {
    LatestValues lv;
    demo_fill(t, &lv);
    for (size_t i = 0; i < kSignalCount; ++i) {
      if ((lv.avail_mask >> i) & 1u) {
        TEST_ASSERT_TRUE_MESSAGE(
            pid_schema_in_bounds(static_cast<Signal>(i), lv.values[i]),
            "demo value out of schema bounds");
      }
    }
  }
}

void test_flags_say_demo_not_kline(void) {
  LatestValues lv;
  demo_fill(1000, &lv);
  TEST_ASSERT_EQUAL_HEX8(kFlagDemoMode, lv.flags);
}

void test_is_deterministic(void) {
  LatestValues a, b;
  demo_fill(123456, &a);
  demo_fill(123456, &b);
  TEST_ASSERT_EQUAL_HEX32(a.avail_mask, b.avail_mask);
  for (size_t i = 0; i < kSignalCount; ++i) {
    TEST_ASSERT_EQUAL_FLOAT(a.values[i], b.values[i]);
  }
}

void test_idle_phase_looks_like_warm_idle_after_warmup(void) {
  // t=310000: past the 300 s warm-up, 10 s into a cycle => idle phase.
  LatestValues lv;
  demo_fill(310000, &lv);
  TEST_ASSERT_TRUE(lv.values[0] >= 600.0f && lv.values[0] <= 900.0f);  // RPM
  TEST_ASSERT_EQUAL_FLOAT(0.0f, lv.values[1]);                        // SPEED
  TEST_ASSERT_EQUAL_FLOAT(90.0f, lv.values[11]);                      // ECT settled
  // MAF inside the w203 warm-idle band 3-5 g/s so the dashboard's banded
  // gauge shows green during the final session's idle phase.
  TEST_ASSERT_TRUE(lv.values[2] >= 3.0f && lv.values[2] <= 5.0f);
}

void test_cruise_phase_has_speed(void) {
  // t=345000: 45 s into a cycle => cruise phase.
  LatestValues lv;
  demo_fill(345000, &lv);
  TEST_ASSERT_EQUAL_FLOAT(90.0f, lv.values[1]);
}

void test_ect_warms_up_from_cold(void) {
  LatestValues lv;
  demo_fill(0, &lv);
  TEST_ASSERT_FLOAT_WITHIN(1.0f, 20.0f, lv.values[11]);
  demo_fill(400000, &lv);
  TEST_ASSERT_EQUAL_FLOAT(90.0f, lv.values[11]);
}

void test_demo_dtcs_serve_stored_p0171_pending_empty(void) {
  DtcList stored, pending;
  demo_dtcs(&stored, &pending);
  TEST_ASSERT_EQUAL_UINT8(1, stored.count);
  TEST_ASSERT_EQUAL_HEX16(0x0171, stored.codes[0]);
  TEST_ASSERT_EQUAL_UINT8(0, pending.count);
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_mask_is_exactly_the_demo_mask_at_all_times);
  RUN_TEST(test_all_masked_values_stay_inside_schema_bounds);
  RUN_TEST(test_flags_say_demo_not_kline);
  RUN_TEST(test_is_deterministic);
  RUN_TEST(test_idle_phase_looks_like_warm_idle_after_warmup);
  RUN_TEST(test_cruise_phase_has_speed);
  RUN_TEST(test_ect_warms_up_from_cold);
  RUN_TEST(test_demo_dtcs_serve_stored_p0171_pending_empty);
  return UNITY_END();
}
