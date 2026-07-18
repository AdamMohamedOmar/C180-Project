#include <unity.h>

#include <cstring>

#include "crc32.h"

void setUp() {}
void tearDown() {}

// Standard CRC-32/ISO-HDLC check value ("123456789" -> 0xCBF43926).
void test_golden_check_value() {
  Crc32 crc;
  crc.update(reinterpret_cast<const uint8_t*>("123456789"), 9);
  TEST_ASSERT_EQUAL_HEX32(0xCBF43926u, crc.value());
}

void test_empty_input_is_zero() {
  Crc32 crc;
  TEST_ASSERT_EQUAL_HEX32(0x00000000u, crc.value());
}

void test_incremental_equals_one_shot() {
  const char* s = "kompressorlink ride data";
  Crc32 one;
  one.update(reinterpret_cast<const uint8_t*>(s), strlen(s));
  Crc32 inc;
  inc.update(reinterpret_cast<const uint8_t*>(s), 10);
  inc.update(reinterpret_cast<const uint8_t*>(s) + 10, strlen(s) - 10);
  TEST_ASSERT_EQUAL_HEX32(one.value(), inc.value());
}

void test_value_is_idempotent() {
  Crc32 crc;
  crc.update(reinterpret_cast<const uint8_t*>("abc"), 3);
  const uint32_t first = crc.value();
  TEST_ASSERT_EQUAL_HEX32(first, crc.value());
  crc.update(reinterpret_cast<const uint8_t*>("def"), 3);
  TEST_ASSERT_NOT_EQUAL(first, crc.value());
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_golden_check_value);
  RUN_TEST(test_empty_input_is_zero);
  RUN_TEST(test_incremental_equals_one_shot);
  RUN_TEST(test_value_is_idempotent);
  return UNITY_END();
}
