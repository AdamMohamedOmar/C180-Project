#include <unity.h>

#include "fake_ride_file_store.h"
#include "ride_naming.h"

void setUp() {}
void tearDown() {}

void test_format_and_parse_round_trip() {
  char name[40];
  format_ride_name(name, sizeof(name), 42);
  TEST_ASSERT_EQUAL_STRING("ride_00042.csv", name);
  TEST_ASSERT_EQUAL_UINT32(42u, parse_ride_seq(name));
}

void test_parse_rejects_non_ride_names() {
  TEST_ASSERT_EQUAL_UINT32(0u, parse_ride_seq("ride_next.txt"));
  TEST_ASSERT_EQUAL_UINT32(0u, parse_ride_seq("ride_00042.csv.crc"));
  TEST_ASSERT_EQUAL_UINT32(0u, parse_ride_seq("other.csv"));
  TEST_ASSERT_EQUAL_UINT32(0u, parse_ride_seq("ride_x.csv"));
}

void test_first_allocation_on_empty_store_is_one() {
  FakeRideFileStore store;
  TEST_ASSERT_EQUAL_UINT32(1u, allocate_ride_seq(store));
}

void test_allocation_advances_past_existing_files() {
  FakeRideFileStore store;
  store.add_ride("ride_00007.csv", 10);
  TEST_ASSERT_EQUAL_UINT32(8u, allocate_ride_seq(store));
}

void test_counter_wins_over_listing_after_deletions() {
  // All files deleted by retention, but the persisted counter remembers —
  // numbers are NEVER reused (the app keys sync state by name).
  FakeRideFileStore store;
  store.write_text("ride_next.txt", "12");
  TEST_ASSERT_EQUAL_UINT32(12u, allocate_ride_seq(store));
}

void test_allocation_persists_next_before_returning() {
  FakeRideFileStore store;
  const uint32_t a = allocate_ride_seq(store);
  const uint32_t b = allocate_ride_seq(store);  // no file was ever created
  TEST_ASSERT_EQUAL_UINT32(a + 1, b);  // crash between allocate and open skips a number
}

void test_max_seen_wins_when_persisted_counter_is_stale() {
  FakeRideFileStore store;
  store.add_ride("ride_00007.csv", 10);
  store.write_text("ride_next.txt", "3");  // stale/behind -- must not be trusted blindly
  TEST_ASSERT_EQUAL_UINT32(8u, allocate_ride_seq(store));  // max_seen+1 wins, not the stale "3"
}

void test_max_seen_scans_all_files_not_just_one() {
  FakeRideFileStore store;
  store.add_ride("ride_00003.csv", 10);
  store.add_ride("ride_00019.csv", 10);
  store.add_ride("ride_00011.csv", 10);
  TEST_ASSERT_EQUAL_UINT32(20u, allocate_ride_seq(store));  // must find 19 as the true max, not 3 or 11
}

void test_parse_seq_beyond_five_digits() {
  TEST_ASSERT_EQUAL_UINT32(123456u, parse_ride_seq("ride_123456.csv"));
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_format_and_parse_round_trip);
  RUN_TEST(test_parse_rejects_non_ride_names);
  RUN_TEST(test_first_allocation_on_empty_store_is_one);
  RUN_TEST(test_allocation_advances_past_existing_files);
  RUN_TEST(test_counter_wins_over_listing_after_deletions);
  RUN_TEST(test_allocation_persists_next_before_returning);
  RUN_TEST(test_max_seen_wins_when_persisted_counter_is_stale);
  RUN_TEST(test_max_seen_scans_all_files_not_just_one);
  RUN_TEST(test_parse_seq_beyond_five_digits);
  return UNITY_END();
}
