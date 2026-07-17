#include <unity.h>

#include "fake_transport.h"
#include "kline_frame.h"
#include "kline_kwp.h"

// Unity fixture hooks -- the framework links against these even when unused.
void setUp(void) {}
void tearDown(void) {}

namespace {
// Queues a checksum-valid ECU->tester frame carrying `data` on `transport`.
void queue_ecu_response(FakeTransport& transport, const uint8_t* data, uint8_t data_len) {
  uint8_t frame[32];
  size_t len = kline_build_frame(kTesterAddress, kTargetAddress, data, data_len, frame, sizeof(frame));
  transport.queue_response(std::vector<uint8_t>(frame, frame + len));
}
}  // namespace

void test_read_stored_dtcs_parses_single_p0171(void) {
  FakeTransport transport;
  // Legacy explicit-count layout: SID 0x43, count 1, then J2012 bytes
  // 0x01 0x71. This was kl_sim's format through Phase 4; kept supported as
  // a hedge in case the real ECU uses it (see kline_kwp.h contract).
  const uint8_t response[] = {0x43, 0x01, 0x01, 0x71};
  queue_ecu_response(transport, response, 4);

  KlineKwp kwp(transport);
  DtcList dtcs;
  TEST_ASSERT_TRUE(kwp.read_stored_dtcs(&dtcs));
  TEST_ASSERT_EQUAL_UINT8(1, dtcs.count);
  TEST_ASSERT_EQUAL_HEX16(0x0171, dtcs.codes[0]);
}

void test_read_stored_dtcs_parses_empty_list(void) {
  FakeTransport transport;
  const uint8_t response[] = {0x43, 0x00};
  queue_ecu_response(transport, response, 2);

  KlineKwp kwp(transport);
  DtcList dtcs;
  dtcs.count = 5;  // stale garbage that must be overwritten
  TEST_ASSERT_TRUE(kwp.read_stored_dtcs(&dtcs));
  TEST_ASSERT_EQUAL_UINT8(0, dtcs.count);
}

void test_read_pending_dtcs_uses_mode_07_sids(void) {
  FakeTransport transport;
  const uint8_t response[] = {0x47, 0x01, 0x01, 0x71};
  queue_ecu_response(transport, response, 4);

  KlineKwp kwp(transport);
  DtcList dtcs;
  TEST_ASSERT_TRUE(kwp.read_pending_dtcs(&dtcs));
  TEST_ASSERT_EQUAL_UINT8(1, dtcs.count);
  TEST_ASSERT_EQUAL_HEX16(0x0171, dtcs.codes[0]);
  // And the request actually sent Mode 07: frame is [FMT][TGT][SRC][0x07][CS].
  TEST_ASSERT_EQUAL_HEX8(0x07, transport.written_[3]);
}

void test_read_stored_dtcs_fails_on_wrong_sid(void) {
  FakeTransport transport;
  const uint8_t response[] = {0x7F, 0x03, 0x11};  // negative response
  queue_ecu_response(transport, response, 3);

  KlineKwp kwp(transport);
  DtcList dtcs;
  TEST_ASSERT_FALSE(kwp.read_stored_dtcs(&dtcs));
}

void test_read_stored_dtcs_fails_on_no_response(void) {
  FakeTransport transport;  // nothing queued
  KlineKwp kwp(transport);
  DtcList dtcs;
  TEST_ASSERT_FALSE(kwp.read_stored_dtcs(&dtcs));
  TEST_ASSERT_EQUAL_INT(1, kwp.consecutive_timeouts());
}

void test_read_stored_dtcs_clamps_count_to_pairs_actually_in_frame(void) {
  FakeTransport transport;
  // Claims 3 DTCs but carries only 1 pair -- a truncated/hostile frame.
  // Must clamp to what is actually present, never read unwritten bytes.
  const uint8_t response[] = {0x43, 0x03, 0x01, 0x71};
  queue_ecu_response(transport, response, 4);

  KlineKwp kwp(transport);
  DtcList dtcs;
  TEST_ASSERT_TRUE(kwp.read_stored_dtcs(&dtcs));
  TEST_ASSERT_EQUAL_UINT8(1, dtcs.count);
}

void test_read_stored_dtcs_caps_at_kmaxdtcs(void) {
  FakeTransport transport;
  // 10 pairs on the wire, count byte 10 -- keep only the first 8.
  uint8_t response[2 + 20];
  response[0] = 0x43;
  response[1] = 10;
  for (uint8_t i = 0; i < 10; ++i) {
    response[2 + 2 * i] = 0x01;
    response[3 + 2 * i] = i;  // P017x-ish family, distinct codes
  }
  queue_ecu_response(transport, response, sizeof(response));

  KlineKwp kwp(transport);
  DtcList dtcs;
  TEST_ASSERT_TRUE(kwp.read_stored_dtcs(&dtcs));
  TEST_ASSERT_EQUAL_UINT8(8, dtcs.count);
  TEST_ASSERT_EQUAL_HEX16(0x0107, dtcs.codes[7]);
}

void test_read_stored_dtcs_parses_implicit_format_without_count_byte(void) {
  FakeTransport transport;
  // Standard J1979-over-K-line layout: no count byte, the count is implicit
  // in the frame's own length. [Likely] the real SIM4LKE's format -- the
  // Phase 3 car probe confirms.
  const uint8_t response[] = {0x43, 0x01, 0x71};
  queue_ecu_response(transport, response, 3);

  KlineKwp kwp(transport);
  DtcList dtcs;
  TEST_ASSERT_TRUE(kwp.read_stored_dtcs(&dtcs));
  TEST_ASSERT_EQUAL_UINT8(1, dtcs.count);
  TEST_ASSERT_EQUAL_HEX16(0x0171, dtcs.codes[0]);
}

void test_read_stored_dtcs_parses_implicit_empty_response(void) {
  FakeTransport transport;
  // Zero stored DTCs in the implicit layout is a bare positive SID.
  const uint8_t response[] = {0x43};
  queue_ecu_response(transport, response, 1);

  KlineKwp kwp(transport);
  DtcList dtcs;
  dtcs.count = 5;  // stale garbage that must be overwritten
  TEST_ASSERT_TRUE(kwp.read_stored_dtcs(&dtcs));
  TEST_ASSERT_EQUAL_UINT8(0, dtcs.count);
}

void test_read_stored_dtcs_drops_zero_padding_pairs(void) {
  FakeTransport transport;
  // J1979 ECUs commonly pad the DTC response to 3 slots with 0x0000 pairs;
  // P0000 is not a real code and must not surface as one.
  const uint8_t response[] = {0x43, 0x01, 0x71, 0x00, 0x00, 0x00, 0x00};
  queue_ecu_response(transport, response, 7);

  KlineKwp kwp(transport);
  DtcList dtcs;
  TEST_ASSERT_TRUE(kwp.read_stored_dtcs(&dtcs));
  TEST_ASSERT_EQUAL_UINT8(1, dtcs.count);
  TEST_ASSERT_EQUAL_HEX16(0x0171, dtcs.codes[0]);
  TEST_ASSERT_EQUAL_HEX16(0x0000, dtcs.codes[1]);
}

void test_read_stored_dtcs_implicit_format_caps_at_kmaxdtcs(void) {
  FakeTransport transport;
  // 10 pairs on the wire, no count byte -- keep only the first 8.
  uint8_t response[1 + 20];
  response[0] = 0x43;
  for (uint8_t i = 0; i < 10; ++i) {
    response[1 + 2 * i] = 0x01;
    response[2 + 2 * i] = static_cast<uint8_t>(i + 1);  // 0x0101..0x010A, all nonzero
  }
  queue_ecu_response(transport, response, sizeof(response));

  KlineKwp kwp(transport);
  DtcList dtcs;
  TEST_ASSERT_TRUE(kwp.read_stored_dtcs(&dtcs));
  TEST_ASSERT_EQUAL_UINT8(8, dtcs.count);
  TEST_ASSERT_EQUAL_HEX16(0x0108, dtcs.codes[7]);
}

void test_read_stored_dtcs_rejects_empty_data_frame(void) {
  FakeTransport transport;
  // A checksum-valid frame with zero data bytes carries no SID at all.
  queue_ecu_response(transport, nullptr, 0);

  KlineKwp kwp(transport);
  DtcList dtcs;
  TEST_ASSERT_FALSE(kwp.read_stored_dtcs(&dtcs));
}

void test_dtc_code_to_string_renders_p0171(void) {
  char out[6];
  dtc_code_to_string(0x0171, out);
  TEST_ASSERT_EQUAL_STRING("P0171", out);
}

void test_dtc_code_to_string_renders_c_letter_code(void) {
  // C0300: letter C=01, digits 0,3,0,0 -> byte1=0x43, byte2=0x00.
  char out[6];
  dtc_code_to_string(0x4300, out);
  TEST_ASSERT_EQUAL_STRING("C0300", out);
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_read_stored_dtcs_parses_single_p0171);
  RUN_TEST(test_read_stored_dtcs_parses_empty_list);
  RUN_TEST(test_read_pending_dtcs_uses_mode_07_sids);
  RUN_TEST(test_read_stored_dtcs_fails_on_wrong_sid);
  RUN_TEST(test_read_stored_dtcs_fails_on_no_response);
  RUN_TEST(test_read_stored_dtcs_clamps_count_to_pairs_actually_in_frame);
  RUN_TEST(test_read_stored_dtcs_caps_at_kmaxdtcs);
  RUN_TEST(test_read_stored_dtcs_parses_implicit_format_without_count_byte);
  RUN_TEST(test_read_stored_dtcs_parses_implicit_empty_response);
  RUN_TEST(test_read_stored_dtcs_drops_zero_padding_pairs);
  RUN_TEST(test_read_stored_dtcs_implicit_format_caps_at_kmaxdtcs);
  RUN_TEST(test_read_stored_dtcs_rejects_empty_data_frame);
  RUN_TEST(test_dtc_code_to_string_renders_p0171);
  RUN_TEST(test_dtc_code_to_string_renders_c_letter_code);
  return UNITY_END();
}
