#include <unity.h>

#include <cstring>

#include "fake_ride_file_store.h"
#include "wifi_sync_core.h"

void setUp() {}
void tearDown() {}

void test_manifest_empty_store() {
  FakeRideFileStore store;
  char out[256];
  const size_t n = build_manifest_json(store, out, sizeof(out));
  TEST_ASSERT_TRUE(n > 0);
  TEST_ASSERT_EQUAL_STRING("{\"rides\":[]}", out);
}

void test_manifest_lists_rides_with_size_and_crc() {
  FakeRideFileStore store;
  store.files["ride_00001.csv"] = "123456789";
  store.write_text("ride_00002.csv.crc", "AABBCCDD");
  store.files["ride_00002.csv"] = "zz";
  char out[512];
  build_manifest_json(store, out, sizeof(out));
  // std::map ordering -> 00001 before 00002. 00001 computed+backfilled,
  // 00002 taken from its (deliberately fake) sidecar.
  TEST_ASSERT_EQUAL_STRING(
      "{\"rides\":["
      "{\"name\":\"ride_00001.csv\",\"size\":9,\"crc32\":\"CBF43926\"},"
      "{\"name\":\"ride_00002.csv\",\"size\":2,\"crc32\":\"AABBCCDD\"}"
      "]}",
      out);
}

void test_manifest_omits_ride_whose_crc_cannot_be_computed() {
  FakeRideFileStore store;
  store.files["ride_00001.csv"] = "aaaaaaaaa";  // content irrelevant -- read() will be blocked
  store.files["ride_00002.csv"] = "123456789";  // known CRC: 0xCBF43926
  store.fail_read.insert("ride_00001.csv");  // simulates an unreadable ride still present in the listing
  char out[256];
  build_manifest_json(store, out, sizeof(out));
  // ride_00001.csv is skipped entirely (no fabricated crc32 reported for
  // it); ride_00002.csv appears ALONE with NO leading comma, proving
  // wrote_any (not loop index) drives comma placement -- this is the one
  // property that was previously correct only "by inspection."
  TEST_ASSERT_EQUAL_STRING(
      "{\"rides\":[{\"name\":\"ride_00002.csv\",\"size\":9,\"crc32\":\"CBF43926\"}]}",
      out);
}

void test_manifest_excludes_active_ride() {
  FakeRideFileStore store;
  store.files["ride_00001.csv"] = "123456789";
  store.set_active_ride("ride_00001.csv");
  char out[256];
  build_manifest_json(store, out, sizeof(out));
  TEST_ASSERT_EQUAL_STRING("{\"rides\":[]}", out);
}

void test_manifest_overflow_returns_zero() {
  FakeRideFileStore store;
  store.files["ride_00001.csv"] = "123456789";
  char out[16];
  TEST_ASSERT_EQUAL_size_t(0, build_manifest_json(store, out, sizeof(out)));
}

void test_chunk_happy_path() {
  FakeRideFileStore store;
  store.files["ride_00001.csv"] = "0123456789";
  uint8_t buf[kSyncMaxChunk];
  const int32_t n = read_ride_chunk(store, "ride_00001.csv", 2, 4, buf, sizeof(buf));
  TEST_ASSERT_EQUAL_INT32(4, n);
  TEST_ASSERT_EQUAL_MEMORY("2345", buf, 4);
}

void test_chunk_clamps_to_eof_and_max() {
  FakeRideFileStore store;
  store.files["ride_00001.csv"] = "0123456789";
  uint8_t buf[kSyncMaxChunk];
  TEST_ASSERT_EQUAL_INT32(3, read_ride_chunk(store, "ride_00001.csv", 7, 100, buf, sizeof(buf)));
  TEST_ASSERT_EQUAL_INT32(0, read_ride_chunk(store, "ride_00001.csv", 10, 4, buf, sizeof(buf)));
  // length > kSyncMaxChunk is clamped, not an error:
  TEST_ASSERT_EQUAL_INT32(10, read_ride_chunk(store, "ride_00001.csv", 0, kSyncMaxChunk + 500, buf, sizeof(buf)));
}

void test_chunk_clamped_by_output_buffer_cap() {
  FakeRideFileStore store;
  store.files["ride_00001.csv"] = "0123456789";  // 10 bytes available
  uint8_t buf[5];  // tighter than both length(8) and remaining(10)
  const int32_t n = read_ride_chunk(store, "ride_00001.csv", 0, 8, buf, sizeof(buf));
  TEST_ASSERT_EQUAL_INT32(5, n);
  TEST_ASSERT_EQUAL_MEMORY("01234", buf, 5);
}

void test_chunk_zero_length_returns_zero() {
  FakeRideFileStore store;
  store.files["ride_00001.csv"] = "0123456789";
  uint8_t buf[kSyncMaxChunk];
  TEST_ASSERT_EQUAL_INT32(0, read_ride_chunk(store, "ride_00001.csv", 0, 0, buf, sizeof(buf)));
}

void test_chunk_unknown_file_is_error() {
  FakeRideFileStore store;
  uint8_t buf[8];
  TEST_ASSERT_EQUAL_INT32(-1, read_ride_chunk(store, "nope.csv", 0, 4, buf, sizeof(buf)));
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_manifest_empty_store);
  RUN_TEST(test_manifest_lists_rides_with_size_and_crc);
  RUN_TEST(test_manifest_omits_ride_whose_crc_cannot_be_computed);
  RUN_TEST(test_manifest_excludes_active_ride);
  RUN_TEST(test_manifest_overflow_returns_zero);
  RUN_TEST(test_chunk_happy_path);
  RUN_TEST(test_chunk_clamps_to_eof_and_max);
  RUN_TEST(test_chunk_clamped_by_output_buffer_cap);
  RUN_TEST(test_chunk_zero_length_returns_zero);
  RUN_TEST(test_chunk_unknown_file_is_error);
  return UNITY_END();
}
