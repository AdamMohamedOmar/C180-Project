#include <unity.h>

#include "kline_frame.h"

// Unity fixture hooks -- the framework links against these even when unused.
void setUp(void) {}
void tearDown(void) {}

void test_checksum_matches_hand_computed_value(void) {
  const uint8_t data[] = {0x82, 0x33, 0xF1, 0x01, 0x0C};
  TEST_ASSERT_EQUAL_UINT8(0x82 + 0x33 + 0xF1 + 0x01 + 0x0C, kline_checksum(data, 5));
}

void test_build_frame_round_trips_through_parse_frame(void) {
  const uint8_t data[] = {0x41, 0x0C, 0x03, 0x20};
  uint8_t frame[16];
  size_t frame_len = kline_build_frame(kTargetAddress, kTesterAddress, data, 4, frame, sizeof(frame));

  ParsedFrame parsed;
  TEST_ASSERT_TRUE(kline_parse_frame(frame, frame_len, &parsed));
  TEST_ASSERT_EQUAL_UINT8(kTargetAddress, parsed.target);
  TEST_ASSERT_EQUAL_UINT8(kTesterAddress, parsed.source);
  TEST_ASSERT_EQUAL_UINT8(4, parsed.data_len);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(data, parsed.data, 4);
}

void test_parse_frame_rejects_bad_checksum(void) {
  const uint8_t data[] = {0x01};
  uint8_t frame[16];
  size_t frame_len = kline_build_frame(kTargetAddress, kTesterAddress, data, 1, frame, sizeof(frame));
  frame[frame_len - 1] ^= 0xFF;

  ParsedFrame parsed;
  TEST_ASSERT_FALSE(kline_parse_frame(frame, frame_len, &parsed));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_checksum_matches_hand_computed_value);
  RUN_TEST(test_build_frame_round_trips_through_parse_frame);
  RUN_TEST(test_parse_frame_rejects_bad_checksum);
  return UNITY_END();
}
