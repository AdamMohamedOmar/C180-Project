#include <unity.h>

#include <cstdint>
#include <string>
#include <vector>

#include "telemetry_frame.h"

void setUp(void) {}
void tearDown(void) {}

namespace {

std::vector<uint8_t> from_hex(const std::string& hex) {
  std::vector<uint8_t> out;
  for (size_t i = 0; i + 1 < hex.size(); i += 2) {
    out.push_back(static_cast<uint8_t>(std::stoul(hex.substr(i, 2), nullptr, 16)));
  }
  return out;
}

// Golden vector T1 — mirror-verbatim of docs/ble_protocol.md (one literal
// per doc line; adjacent-literal concatenation).
const std::string kT1Hex =
    "01023412a0860100ffff0700"
    "00803b44" "00000000" "00008040" "00004841" "0000c841" "000020c0"
    "0000a040" "0000c442" "00002041" "0000003f" "0000403f" "0000b442"
    "00000c42" "00000040" "00007041" "00007041" "00004041" "00006041"
    "00006441";

// Golden vector T2 — mirror-verbatim of docs/ble_protocol.md.
const std::string kT2Hex =
    "0101ffff0000000001080000"
    "00004844" "00000000" "00000000" "00000000" "00000000" "00000000"
    "00000000" "00000000" "00000000" "00000000" "00000000" "0000ae42"
    "00000000" "00000000" "00000000" "00000000" "00000000" "00000000"
    "00000000";

LatestValues t1_inputs(void) {
  LatestValues lv;
  const float vals[kSignalCount] = {750.0f, 0.0f,  4.0f,  12.5f, 25.0f, -2.5f, 5.0f,
                                    98.0f,  10.0f, 0.5f,  0.75f, 90.0f, 35.0f, 2.0f,
                                    15.0f,  15.0f, 12.0f, 14.0f, 14.25f};
  for (size_t i = 0; i < kSignalCount; ++i) {
    latest_values_apply(&lv, static_cast<Signal>(i), vals[i], true);
  }
  lv.flags = kFlagKlineConnected;
  lv.seq = 0x1234;
  lv.uptime_ms = 100000;
  return lv;
}

}  // namespace

void test_pack_telemetry_matches_golden_t1(void) {
  LatestValues lv = t1_inputs();
  uint8_t out[kTelemetryFrameLen];
  pack_telemetry(lv, out);
  const std::vector<uint8_t> expected = from_hex(kT1Hex);
  TEST_ASSERT_EQUAL_UINT(kTelemetryFrameLen, (unsigned)expected.size());
  TEST_ASSERT_EQUAL_HEX8_ARRAY(expected.data(), out, kTelemetryFrameLen);
}

void test_pack_telemetry_matches_golden_t2_sparse(void) {
  LatestValues lv;
  latest_values_apply(&lv, Signal::RPM, 800.0f, true);
  latest_values_apply(&lv, Signal::ECT, 87.0f, true);
  lv.flags = kFlagDemoMode;
  lv.seq = 0xFFFF;
  lv.uptime_ms = 0;
  uint8_t out[kTelemetryFrameLen];
  pack_telemetry(lv, out);
  const std::vector<uint8_t> expected = from_hex(kT2Hex);
  TEST_ASSERT_EQUAL_HEX8_ARRAY(expected.data(), out, kTelemetryFrameLen);
}

void test_pack_telemetry_zeroes_masked_out_values_even_if_stale(void) {
  // Contract: a masked-out signal is transmitted as 0.0f even if the cache
  // holds a stale value (belt + braces on top of latest_values_apply's own
  // zeroing — the packer must be safe regardless of who fed the struct).
  LatestValues lv;
  latest_values_apply(&lv, Signal::RPM, 800.0f, true);
  lv.values[11] = 90.0f;  // stale ECT value, mask bit NOT set
  uint8_t out[kTelemetryFrameLen];
  pack_telemetry(lv, out);
  // ECT slot = offset 12 + 11*4 = 56..59, must be 00 00 00 00.
  TEST_ASSERT_EQUAL_HEX8(0x00, out[56]);
  TEST_ASSERT_EQUAL_HEX8(0x00, out[57]);
  TEST_ASSERT_EQUAL_HEX8(0x00, out[58]);
  TEST_ASSERT_EQUAL_HEX8(0x00, out[59]);
}

void test_pack_telemetry_fully_masked_produces_all_zero_value_region(void) {
  LatestValues lv;  // default: avail_mask = 0, everything masked out
  uint8_t out[kTelemetryFrameLen];
  pack_telemetry(lv, out);
  TEST_ASSERT_EQUAL_HEX8(kBleProtocolVersion, out[0]);
  TEST_ASSERT_EQUAL_HEX8(0x00, out[1]);  // flags
  for (size_t i = 12; i < kTelemetryFrameLen; ++i) {
    TEST_ASSERT_EQUAL_HEX8_MESSAGE(0x00, out[i], "expected all-zero value region for fully-masked frame");
  }
}

void test_pack_dtc_report_matches_golden_d1(void) {
  DtcList stored;
  stored.count = 1;
  stored.codes[0] = 0x0171;  // P0171
  DtcList pending;
  uint8_t out[kDtcFrameMaxLen];
  const size_t n = pack_dtc_report(stored, pending, out, sizeof(out));
  const std::vector<uint8_t> expected = from_hex("0101000171");
  TEST_ASSERT_EQUAL_UINT(5, (unsigned)n);
  TEST_ASSERT_EQUAL_HEX8_ARRAY(expected.data(), out, 5);
}

void test_pack_dtc_report_matches_golden_d2_empty(void) {
  DtcList stored, pending;
  uint8_t out[kDtcFrameMaxLen];
  const size_t n = pack_dtc_report(stored, pending, out, sizeof(out));
  const std::vector<uint8_t> expected = from_hex("010000");
  TEST_ASSERT_EQUAL_UINT(3, (unsigned)n);
  TEST_ASSERT_EQUAL_HEX8_ARRAY(expected.data(), out, 3);
}

void test_pack_dtc_report_returns_zero_when_cap_too_small(void) {
  DtcList stored;
  stored.count = 2;
  stored.codes[0] = 0x0171;
  stored.codes[1] = 0x0300;
  DtcList pending;
  uint8_t out[4];  // needs 7
  TEST_ASSERT_EQUAL_UINT(0, (unsigned)pack_dtc_report(stored, pending, out, sizeof(out)));
}

void test_parse_control_decodes_golden_c1_time_sync(void) {
  const std::vector<uint8_t> frame = from_hex("01efcdab8967452301");
  ControlCommand cmd;
  TEST_ASSERT_TRUE(parse_control(frame.data(), frame.size(), &cmd));
  TEST_ASSERT_EQUAL_HEX8(kControlOpTimeSync, cmd.opcode);
  TEST_ASSERT_EQUAL_HEX64(0x0123456789ABCDEFull, cmd.epoch_ms);
}

void test_parse_control_rejects_wrong_length(void) {
  const std::vector<uint8_t> frame = from_hex("01efcdab89674523");  // 8 bytes
  ControlCommand cmd;
  TEST_ASSERT_FALSE(parse_control(frame.data(), frame.size(), &cmd));
}

void test_parse_control_rejects_reserved_clear_dtc_opcode(void) {
  // 0x02 = CLEAR_DTC is reserved and deliberately UNIMPLEMENTED in Phase 4
  // (the only ECU write in the project, deferred by design — spec, scope).
  const std::vector<uint8_t> frame = from_hex("02efcdab8967452301");
  ControlCommand cmd;
  TEST_ASSERT_FALSE(parse_control(frame.data(), frame.size(), &cmd));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_pack_telemetry_matches_golden_t1);
  RUN_TEST(test_pack_telemetry_matches_golden_t2_sparse);
  RUN_TEST(test_pack_telemetry_zeroes_masked_out_values_even_if_stale);
  RUN_TEST(test_pack_telemetry_fully_masked_produces_all_zero_value_region);
  RUN_TEST(test_pack_dtc_report_matches_golden_d1);
  RUN_TEST(test_pack_dtc_report_matches_golden_d2_empty);
  RUN_TEST(test_pack_dtc_report_returns_zero_when_cap_too_small);
  RUN_TEST(test_parse_control_decodes_golden_c1_time_sync);
  RUN_TEST(test_parse_control_rejects_wrong_length);
  RUN_TEST(test_parse_control_rejects_reserved_clear_dtc_opcode);
  return UNITY_END();
}
