#include <unity.h>

#include "fake_transport.h"
#include "kline_frame.h"
#include "kline_kwp.h"

// Unity fixture hooks -- the framework links against these even when unused.
void setUp(void) {}
void tearDown(void) {}

namespace {
// Builds a checksum-valid frame and returns it as a standalone vector, so
// callers can concatenate multiple frames into one FakeTransport queue (to
// simulate a self-echo followed by a real ECU response arriving in the same
// read window).
std::vector<uint8_t> build_frame_vec(uint8_t target, uint8_t source, const uint8_t* data, uint8_t data_len) {
  uint8_t frame[32];
  size_t len = kline_build_frame(target, source, data, data_len, frame, sizeof(frame));
  return std::vector<uint8_t>(frame, frame + len);
}
}  // namespace

void test_start_communication_succeeds_on_positive_response(void) {
  FakeTransport transport;
  const uint8_t response_data[] = {0xC1, 0x8F, 0xE9};
  uint8_t frame[16];
  size_t len = kline_build_frame(kTesterAddress, kTargetAddress, response_data, 3, frame, sizeof(frame));
  transport.queue_response(std::vector<uint8_t>(frame, frame + len));

  KlineKwp kwp(transport);
  TEST_ASSERT_TRUE(kwp.start_communication());
}

void test_start_communication_fails_when_no_response_queued(void) {
  FakeTransport transport;  // nothing queued -> read() returns 0 bytes
  KlineKwp kwp(transport);
  TEST_ASSERT_FALSE(kwp.start_communication());
}

void test_start_communication_fails_on_wrong_content(void) {
  // Checksum-valid, correctly-addressed, but not the expected 0xC1 SID --
  // e.g. a KWP negative-response byte instead of a positive one.
  FakeTransport transport;
  const uint8_t response_data[] = {0x7F, 0x81, 0x11};  // generic negative response
  uint8_t frame[16];
  size_t len = kline_build_frame(kTesterAddress, kTargetAddress, response_data, 3, frame, sizeof(frame));
  transport.queue_response(std::vector<uint8_t>(frame, frame + len));

  KlineKwp kwp(transport);
  TEST_ASSERT_FALSE(kwp.start_communication());
}

void test_send_tester_present_succeeds_on_positive_response(void) {
  FakeTransport transport;
  const uint8_t response_data[] = {0x7E};
  uint8_t frame[16];
  size_t len = kline_build_frame(kTesterAddress, kTargetAddress, response_data, 1, frame, sizeof(frame));
  transport.queue_response(std::vector<uint8_t>(frame, frame + len));

  KlineKwp kwp(transport);
  TEST_ASSERT_TRUE(kwp.send_tester_present());
}

void test_send_tester_present_fails_on_wrong_content(void) {
  FakeTransport transport;
  const uint8_t response_data[] = {0x7F, 0x3E, 0x11};  // negative response, not 0x7E
  uint8_t frame[16];
  size_t len = kline_build_frame(kTesterAddress, kTargetAddress, response_data, 3, frame, sizeof(frame));
  transport.queue_response(std::vector<uint8_t>(frame, frame + len));

  KlineKwp kwp(transport);
  TEST_ASSERT_FALSE(kwp.send_tester_present());
}

void test_read_pid_fails_on_wrong_sid_echoed(void) {
  FakeTransport transport;
  const uint8_t response_data[] = {0x7F, 0x0C, 0x11};  // negative response, not 0x41
  uint8_t frame[16];
  size_t len = kline_build_frame(kTesterAddress, kTargetAddress, response_data, 3, frame, sizeof(frame));
  transport.queue_response(std::vector<uint8_t>(frame, frame + len));

  KlineKwp kwp(transport);
  float value = 0.0f;
  TEST_ASSERT_FALSE(kwp.read_pid(0x0C, &value));
}

void test_read_pid_fails_on_wrong_pid_echoed(void) {
  // Correct SID (0x41) but a different PID than requested -- must not be
  // decoded as if it answered the request that was actually sent.
  FakeTransport transport;
  const uint8_t response_data[] = {0x41, 0x0D, 0x32};  // echoes SPEED (0x0D), not RPM
  uint8_t frame[16];
  size_t len = kline_build_frame(kTesterAddress, kTargetAddress, response_data, 3, frame, sizeof(frame));
  transport.queue_response(std::vector<uint8_t>(frame, frame + len));

  KlineKwp kwp(transport);
  float value = 0.0f;
  TEST_ASSERT_FALSE(kwp.read_pid(0x0C, &value));  // asked for RPM (0x0C)
}

void test_send_request_rejects_response_with_swapped_addressing(void) {
  // target/source reversed from a genuine ECU reply -- e.g. a half-duplex
  // TX self-echo on real K-line hardware. Nothing else is queued behind it,
  // so read_frame discards the echo and then hits the deadline (0 bytes on
  // the next read) rather than ever seeing a genuine reply.
  FakeTransport transport;
  const uint8_t response_data[] = {0xC1, 0x8F, 0xE9};
  uint8_t frame[16];
  // Addresses swapped: built as if the TESTER's own request echoed back,
  // not a real ECU response (target=kTargetAddress, source=kTesterAddress).
  size_t len = kline_build_frame(kTargetAddress, kTesterAddress, response_data, 3, frame, sizeof(frame));
  transport.queue_response(std::vector<uint8_t>(frame, frame + len));

  KlineKwp kwp(transport);
  TEST_ASSERT_FALSE(kwp.start_communication());
  TEST_ASSERT_EQUAL_INT(1, kwp.consecutive_timeouts());
}

void test_read_pid_decodes_rpm_from_response(void) {
  FakeTransport transport;
  const uint8_t response_data[] = {0x41, 0x0C, 0x0C, 0x80};  // RPM=800.0
  uint8_t frame[16];
  size_t len = kline_build_frame(kTesterAddress, kTargetAddress, response_data, 4, frame, sizeof(frame));
  transport.queue_response(std::vector<uint8_t>(frame, frame + len));

  KlineKwp kwp(transport);
  float value = 0.0f;
  TEST_ASSERT_TRUE(kwp.read_pid(0x0C, &value));
  TEST_ASSERT_EQUAL_FLOAT(800.0f, value);
}

void test_read_pid_returns_false_and_counts_timeout_when_unanswered(void) {
  FakeTransport transport;  // nothing queued
  KlineKwp kwp(transport);
  float value = 0.0f;
  TEST_ASSERT_FALSE(kwp.read_pid(0x49, &value));  // PEDAL_D, expected unsupported
  TEST_ASSERT_EQUAL_INT(1, kwp.consecutive_timeouts());
}

void test_read_pid_rejects_truncated_response_instead_of_reading_garbage(void) {
  // A checksum-valid response carrying only SID+PID echo, no payload bytes
  // -- must not decode response.data[2]/[3], which kline_parse_frame never
  // wrote for a 2-byte-payload frame.
  FakeTransport transport;
  const uint8_t response_data[] = {0x41, 0x0C};  // RPM's formula needs 2 more bytes
  uint8_t frame[16];
  size_t len = kline_build_frame(kTesterAddress, kTargetAddress, response_data, 2, frame, sizeof(frame));
  transport.queue_response(std::vector<uint8_t>(frame, frame + len));

  KlineKwp kwp(transport);
  float value = 0.0f;
  TEST_ASSERT_FALSE(kwp.read_pid(0x0C, &value));
}

void test_needs_reinit_becomes_true_after_3_consecutive_timeouts(void) {
  FakeTransport transport;
  KlineKwp kwp(transport);
  float value = 0.0f;
  kwp.read_pid(0x49, &value);
  kwp.read_pid(0x49, &value);
  TEST_ASSERT_FALSE(kwp.needs_reinit());
  kwp.read_pid(0x49, &value);
  TEST_ASSERT_TRUE(kwp.needs_reinit());
}

void test_successful_read_resets_consecutive_timeouts(void) {
  FakeTransport transport;
  const uint8_t response_data[] = {0x41, 0x0C, 0x0C, 0x80};
  uint8_t frame[16];
  size_t len = kline_build_frame(kTesterAddress, kTargetAddress, response_data, 4, frame, sizeof(frame));

  KlineKwp kwp(transport);
  float value = 0.0f;
  kwp.read_pid(0x49, &value);  // 1 timeout, nothing queued
  transport.queue_response(std::vector<uint8_t>(frame, frame + len));
  TEST_ASSERT_TRUE(kwp.read_pid(0x0C, &value));
  TEST_ASSERT_EQUAL_INT(0, kwp.consecutive_timeouts());
}

// --- Echo tolerance / framing (K-line half-duplex bugfix coverage) ---

void test_read_pid_succeeds_after_self_echo_discarded(void) {
  // Real half-duplex K-line hardware echoes our own TX (the request) back
  // on RX before the ECU's genuine reply arrives. read_frame must discard
  // the echo -- addressed target=kTargetAddress/source=kTesterAddress,
  // exactly reversed from a real reply -- without counting it as a failure,
  // then keep listening for the real response in the same read window.
  FakeTransport transport;
  const uint8_t echo_data[] = {0x01, 0x0C};  // our own request, as the bus would echo it
  const uint8_t response_data[] = {0x41, 0x0C, 0x0C, 0x80};  // RPM=800.0

  std::vector<uint8_t> echo = build_frame_vec(kTargetAddress, kTesterAddress, echo_data, 2);
  std::vector<uint8_t> response = build_frame_vec(kTesterAddress, kTargetAddress, response_data, 4);
  std::vector<uint8_t> combined = echo;
  combined.insert(combined.end(), response.begin(), response.end());
  transport.queue_response(combined);

  KlineKwp kwp(transport);
  float value = 0.0f;
  TEST_ASSERT_TRUE(kwp.read_pid(0x0C, &value));
  TEST_ASSERT_EQUAL_FLOAT(800.0f, value);
  TEST_ASSERT_EQUAL_INT(0, kwp.consecutive_timeouts());
}

void test_read_pid_fails_and_counts_timeout_on_echo_only(void) {
  // The echo arrives but the ECU never actually answers -- must still
  // fail (and count as a timeout) once the deadline is hit, not treat the
  // echo itself as satisfying the request.
  FakeTransport transport;
  const uint8_t echo_data[] = {0x01, 0x0C};
  transport.queue_response(build_frame_vec(kTargetAddress, kTesterAddress, echo_data, 2));

  KlineKwp kwp(transport);
  float value = 0.0f;
  TEST_ASSERT_FALSE(kwp.read_pid(0x0C, &value));
  TEST_ASSERT_EQUAL_INT(1, kwp.consecutive_timeouts());
}

void test_read_pid_succeeds_after_two_echoes_discarded(void) {
  // Guards against an off-by-one "discard at most once" implementation --
  // the loop must keep listening past more than one mis-addressed frame.
  FakeTransport transport;
  const uint8_t echo_data[] = {0x01, 0x0C};
  const uint8_t response_data[] = {0x41, 0x0C, 0x0C, 0x80};

  std::vector<uint8_t> combined = build_frame_vec(kTargetAddress, kTesterAddress, echo_data, 2);
  std::vector<uint8_t> echo2 = build_frame_vec(kTargetAddress, kTesterAddress, echo_data, 2);
  combined.insert(combined.end(), echo2.begin(), echo2.end());
  std::vector<uint8_t> response = build_frame_vec(kTesterAddress, kTargetAddress, response_data, 4);
  combined.insert(combined.end(), response.begin(), response.end());
  transport.queue_response(combined);

  KlineKwp kwp(transport);
  float value = 0.0f;
  TEST_ASSERT_TRUE(kwp.read_pid(0x0C, &value));
  TEST_ASSERT_EQUAL_FLOAT(800.0f, value);
}

void test_read_pid_fails_on_frame_truncated_mid_body(void) {
  // Header promises a full frame but the byte stream ends partway through
  // the body (simulates an inter-byte gap the ECU never closes) -- the
  // frame is incomplete and must be rejected, not parsed from a short
  // buffer.
  FakeTransport transport;
  const uint8_t response_data[] = {0x41, 0x0C, 0x0C, 0x80};
  std::vector<uint8_t> full = build_frame_vec(kTesterAddress, kTargetAddress, response_data, 4);
  std::vector<uint8_t> truncated(full.begin(), full.end() - 2);  // drop the last 2 bytes
  transport.queue_response(truncated);

  KlineKwp kwp(transport);
  float value = 0.0f;
  TEST_ASSERT_FALSE(kwp.read_pid(0x0C, &value));
  TEST_ASSERT_EQUAL_INT(1, kwp.consecutive_timeouts());
}

void test_read_pid_fails_on_corrupted_checksum(void) {
  FakeTransport transport;
  const uint8_t response_data[] = {0x41, 0x0C, 0x0C, 0x80};
  std::vector<uint8_t> frame = build_frame_vec(kTesterAddress, kTargetAddress, response_data, 4);
  frame.back() ^= 0xFF;  // corrupt the checksum byte
  transport.queue_response(frame);

  KlineKwp kwp(transport);
  float value = 0.0f;
  TEST_ASSERT_FALSE(kwp.read_pid(0x0C, &value));
  TEST_ASSERT_EQUAL_INT(1, kwp.consecutive_timeouts());
}

void test_read_pid_fails_on_escape_length_format_byte(void) {
  // A format byte with the low 6 bits all zero signals KWP's "length in a
  // separate byte" escape form, which this firmware doesn't implement --
  // must fail cleanly rather than misread a following byte as data.
  FakeTransport transport;
  transport.queue_response(std::vector<uint8_t>{0x80});

  KlineKwp kwp(transport);
  float value = 0.0f;
  TEST_ASSERT_FALSE(kwp.read_pid(0x0C, &value));
  TEST_ASSERT_EQUAL_INT(1, kwp.consecutive_timeouts());
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_start_communication_succeeds_on_positive_response);
  RUN_TEST(test_start_communication_fails_when_no_response_queued);
  RUN_TEST(test_start_communication_fails_on_wrong_content);
  RUN_TEST(test_send_tester_present_succeeds_on_positive_response);
  RUN_TEST(test_send_tester_present_fails_on_wrong_content);
  RUN_TEST(test_read_pid_fails_on_wrong_sid_echoed);
  RUN_TEST(test_read_pid_fails_on_wrong_pid_echoed);
  RUN_TEST(test_send_request_rejects_response_with_swapped_addressing);
  RUN_TEST(test_read_pid_decodes_rpm_from_response);
  RUN_TEST(test_read_pid_returns_false_and_counts_timeout_when_unanswered);
  RUN_TEST(test_read_pid_rejects_truncated_response_instead_of_reading_garbage);
  RUN_TEST(test_needs_reinit_becomes_true_after_3_consecutive_timeouts);
  RUN_TEST(test_successful_read_resets_consecutive_timeouts);
  RUN_TEST(test_read_pid_succeeds_after_self_echo_discarded);
  RUN_TEST(test_read_pid_fails_and_counts_timeout_on_echo_only);
  RUN_TEST(test_read_pid_succeeds_after_two_echoes_discarded);
  RUN_TEST(test_read_pid_fails_on_frame_truncated_mid_body);
  RUN_TEST(test_read_pid_fails_on_corrupted_checksum);
  RUN_TEST(test_read_pid_fails_on_escape_length_format_byte);
  return UNITY_END();
}
