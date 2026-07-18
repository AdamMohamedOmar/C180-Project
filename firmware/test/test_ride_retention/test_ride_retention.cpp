#include <unity.h>

#include "fake_ride_file_store.h"
#include "ride_retention.h"

void setUp() {}
void tearDown() {}

void test_noop_when_enough_free_space() {
  FakeRideFileStore store;
  store.capacity_bytes = 1000;
  store.add_ride("ride_00001.csv", 100);
  TEST_ASSERT_EQUAL_INT(0, enforce_retention(store, 500));
  TEST_ASSERT_EQUAL_size_t(1, store.files.size());
}

void test_deletes_oldest_first_until_floor_met() {
  FakeRideFileStore store;
  store.capacity_bytes = 1000;
  store.add_ride("ride_00001.csv", 300);
  store.add_ride("ride_00002.csv", 300);
  store.add_ride("ride_00003.csv", 300);
  // free = 100; need 500 -> delete 00001 (free 400), then 00002 (free 700).
  TEST_ASSERT_EQUAL_INT(2, enforce_retention(store, 500));
  TEST_ASSERT_TRUE(store.files.count("ride_00003.csv") == 1);
  TEST_ASSERT_TRUE(store.files.count("ride_00001.csv") == 0);
  TEST_ASSERT_TRUE(store.files.count("ride_00002.csv") == 0);
}

void test_deletes_crc_sidecar_with_its_ride() {
  FakeRideFileStore store;
  store.capacity_bytes = 400;
  store.add_ride("ride_00001.csv", 300);
  store.write_text("ride_00001.csv.crc", "CBF43926");
  enforce_retention(store, 200);
  TEST_ASSERT_TRUE(store.files.count("ride_00001.csv.crc") == 0);
}

void test_never_deletes_active_ride() {
  FakeRideFileStore store;
  store.capacity_bytes = 400;
  store.add_ride("ride_00001.csv", 350);
  store.set_active_ride("ride_00001.csv");  // excluded from listing
  TEST_ASSERT_EQUAL_INT(0, enforce_retention(store, 200));
  TEST_ASSERT_EQUAL_size_t(1, store.files.size());
}

void test_stops_when_nothing_deletable_remains() {
  FakeRideFileStore store;
  store.capacity_bytes = 100;
  store.files["not_a_ride.bin"] = std::string(90, 'x');  // never listed
  TEST_ASSERT_EQUAL_INT(0, enforce_retention(store, 50));  // must not loop forever
}

void test_never_auto_deletes_unparseable_ride_shaped_names() {
  FakeRideFileStore store;
  store.capacity_bytes = 400;
  store.add_ride("ride_00001.csv", 300);   // valid, deletable
  store.add_ride("ride_bad.csv", 50);      // ride-shaped but parse_ride_seq() == 0
  // free = 400-350 = 50; floor 200 -> must delete ride_00001.csv only.
  TEST_ASSERT_EQUAL_INT(1, enforce_retention(store, 200));
  TEST_ASSERT_TRUE(store.files.count("ride_00001.csv") == 0);
  TEST_ASSERT_TRUE(store.files.count("ride_bad.csv") == 1);  // never touched
}

void test_deletes_more_than_two_when_floor_demands_it() {
  FakeRideFileStore store;
  store.capacity_bytes = 1000;
  store.add_ride("ride_00001.csv", 150);
  store.add_ride("ride_00002.csv", 150);
  store.add_ride("ride_00003.csv", 150);
  store.add_ride("ride_00004.csv", 150);
  store.add_ride("ride_00005.csv", 150);
  // free = 1000-750 = 250; floor 700 -> must delete 3 oldest (1,2,3) to
  // reach free=250+450=700, leaving 4 and 5 (300 bytes) untouched.
  TEST_ASSERT_EQUAL_INT(3, enforce_retention(store, 700));
  TEST_ASSERT_TRUE(store.files.count("ride_00001.csv") == 0);
  TEST_ASSERT_TRUE(store.files.count("ride_00002.csv") == 0);
  TEST_ASSERT_TRUE(store.files.count("ride_00003.csv") == 0);
  TEST_ASSERT_TRUE(store.files.count("ride_00004.csv") == 1);
  TEST_ASSERT_TRUE(store.files.count("ride_00005.csv") == 1);
}

void test_bails_out_entirely_when_ride_removal_fails() {
  FakeRideFileStore store;
  store.capacity_bytes = 1000;
  store.add_ride("ride_00001.csv", 300);
  store.add_ride("ride_00002.csv", 300);
  store.fail_remove.insert("ride_00001.csv");  // oldest ride's removal will fail
  // free = 400; floor 500 -> tries to delete ride_00001.csv (oldest), fails,
  // bails out entirely rather than skipping to ride_00002.csv.
  TEST_ASSERT_EQUAL_INT(0, enforce_retention(store, 500));
  TEST_ASSERT_TRUE(store.files.count("ride_00001.csv") == 1);  // still present (remove failed)
  TEST_ASSERT_TRUE(store.files.count("ride_00002.csv") == 1);  // untouched (bailed before reaching it)
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_noop_when_enough_free_space);
  RUN_TEST(test_deletes_oldest_first_until_floor_met);
  RUN_TEST(test_deletes_crc_sidecar_with_its_ride);
  RUN_TEST(test_never_deletes_active_ride);
  RUN_TEST(test_stops_when_nothing_deletable_remains);
  RUN_TEST(test_never_auto_deletes_unparseable_ride_shaped_names);
  RUN_TEST(test_deletes_more_than_two_when_floor_demands_it);
  RUN_TEST(test_bails_out_entirely_when_ride_removal_fails);
  return UNITY_END();
}
