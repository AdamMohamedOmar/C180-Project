#include <unity.h>

#include "fake_ride_file_store.h"
#include "ride_crc.h"

void setUp() {}
void tearDown() {}

void test_sidecar_name() {
  char out[48];
  crc_sidecar_name("ride_00001.csv", out, sizeof(out));
  TEST_ASSERT_EQUAL_STRING("ride_00001.csv.crc", out);
}

void test_compute_matches_known_crc() {
  FakeRideFileStore store;
  store.files["ride_00001.csv"] = "123456789";
  RideFileInfo info;
  snprintf(info.name, sizeof(info.name), "ride_00001.csv");
  info.size = 9;
  uint32_t crc = 0;
  TEST_ASSERT_TRUE(crc_for_ride(store, info, &crc));
  TEST_ASSERT_EQUAL_HEX32(0xCBF43926u, crc);
}

void test_backfills_sidecar_on_first_compute() {
  FakeRideFileStore store;
  store.files["ride_00001.csv"] = "123456789";
  RideFileInfo info;
  snprintf(info.name, sizeof(info.name), "ride_00001.csv");
  info.size = 9;
  uint32_t crc = 0;
  crc_for_ride(store, info, &crc);
  char text[16];
  TEST_ASSERT_TRUE(store.read_text("ride_00001.csv.crc", text, sizeof(text)));
  TEST_ASSERT_EQUAL_STRING("CBF43926", text);
}

void test_prefers_existing_sidecar_over_recompute() {
  FakeRideFileStore store;
  store.files["ride_00001.csv"] = "123456789";
  store.write_text("ride_00001.csv.crc", "DEADBEEF");  // deliberately wrong
  RideFileInfo info;
  snprintf(info.name, sizeof(info.name), "ride_00001.csv");
  info.size = 9;
  uint32_t crc = 0;
  TEST_ASSERT_TRUE(crc_for_ride(store, info, &crc));
  TEST_ASSERT_EQUAL_HEX32(0xDEADBEEFu, crc);
}

void test_crc_for_ride_fails_when_ride_unreadable() {
  FakeRideFileStore store;  // "ride_00099.csv" was never added
  RideFileInfo info;
  snprintf(info.name, sizeof(info.name), "ride_00099.csv");
  info.size = 0;
  uint32_t crc = 0xAAAAAAAAu;  // sentinel to prove *out is left untouched
  TEST_ASSERT_FALSE(crc_for_ride(store, info, &crc));
  TEST_ASSERT_EQUAL_HEX32(0xAAAAAAAAu, crc);
}

void test_write_sidecar_helper() {
  FakeRideFileStore store;
  store.files["ride_00002.csv"] = "123456789";
  TEST_ASSERT_TRUE(write_crc_sidecar(store, "ride_00002.csv"));
  char text[16];
  TEST_ASSERT_TRUE(store.read_text("ride_00002.csv.crc", text, sizeof(text)));
  TEST_ASSERT_EQUAL_STRING("CBF43926", text);
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_sidecar_name);
  RUN_TEST(test_compute_matches_known_crc);
  RUN_TEST(test_backfills_sidecar_on_first_compute);
  RUN_TEST(test_prefers_existing_sidecar_over_recompute);
  RUN_TEST(test_write_sidecar_helper);
  RUN_TEST(test_crc_for_ride_fails_when_ride_unreadable);
  return UNITY_END();
}
