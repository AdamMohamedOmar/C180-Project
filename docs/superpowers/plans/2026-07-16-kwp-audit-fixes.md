# KWP Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the three findings from the 2026-07-16 external audit of `kline_kwp` — dual-format DTC response parsing, half-duplex echo tolerance, and correct classification of request-build failures — with amendments where the audit's directives were verified wrong for this codebase.

**Architecture:** All changes live in the firmware's KWP layer (`firmware/src/kline_kwp.*`) plus one 6-line hardening in the ESP32 UART transport and one 2-line format change in the PC-side simulator. Nothing downstream changes: `read_dtcs` still emits `DtcList`, `send_request_and_get_response` keeps its signature, and the BLE/app protocol is untouched.

**Tech stack:** C++17 (PlatformIO, Arduino framework on target / native host for tests), Unity test framework, Python 3 + pytest for the simulator.

---

## Project rules that OVERRIDE default agent behavior (from CLAUDE.md)

1. **NEVER run `git commit`.** No commit steps exist in this plan — that is deliberate, not an omission. The user commits via the VS Code Source Control GUI. Each task ends with all tests green and a clean, reviewable working tree.
2. **Full replacement files.** When a task modifies a file it provides the complete new file content; write the whole file, don't patch fragments. (Exception: Task 4 swaps one small Python function with exact before/after shown — apply it as a precise edit.)
3. **Work in the main working tree `D:\C180 Project` on `master`** (clean at plan time). Do NOT touch `.claude\worktrees\phase45-app-intelligence` — that worktree is the Android Phase 4.5 stream; its firmware files are unmodified copies and will pick these fixes up via normal merge later.
4. **Confidence tags** (`[Confirmed]` / `[Likely]` / `[Guessing]` / `[Best estimate]`) must be preserved in comments exactly as written in the code blocks below.
5. Native test suites live one-per-folder under `firmware/test/test_<name>/` (PlatformIO requirement — do not add sibling `test_*.cpp` files with their own `main()` to an existing folder).

All `pio` commands run from `D:\C180 Project\firmware`.

---

## Context: audit validation verdicts (read before executing)

The audit was reviewed against master (`1a46d2f`). Its three findings were validated with these outcomes — the tasks below implement the *amended* fixes, not the audit's literal directives:

### Finding 1 — DTC count-byte assumption (audit: High) → VALID RISK, fix amended

- The risk is real but was **not discovered by the audit** — it is documented as `[Guessing]` in `kline_kwp.h:33-41` and in the Phase 4 design doc §2.3, with resolution deferred to Phase 3's car probe. We are pulling that fix forward because it is cheap and fully host-testable.
- The audit's dual-format proposal is sound: the two layouts are **parity-disjoint** in `data_len` (implicit `[SID][2N bytes]` → odd; explicit `[SID][count][2N bytes]` → even), so the KWP frame's own length field discriminates them. No probing or config needed.
- The audit **missed two cases** the fix must also cover: (a) a zero-DTC implicit response is a bare `[0x43]` (data_len 1), which the current `data_len < 2` guard rejects; (b) real J1979 K-line ECUs commonly pad short DTC responses with `0x00 0x00` slot pairs — those must be dropped, not surfaced as phantom "P0000" codes.
- Multi-frame DTC paging stays out of scope (acknowledged unknowable pre-probe in the design doc).

### Finding 2 — echo resets the timeout counter (audit: High) → INVALID diagnosis, real adjacent bug found

- The audit's claimed code shape ("`consecutive_timeouts_` reset before the echo check") **does not exist** — not on master, not in the worktree, not even in the plan document the audit cites. The address check at `kline_kwp.cpp:32` increments and returns *before* the reset at `:46`, and `test_send_request_rejects_response_with_swapped_addressing` already covers it. The feared stuck state (echoes keep the link looking healthy) cannot occur.
- **However**, validation found a real echo bug with the opposite mechanism: `Esp32UartTransport::read` is `readBytes(buf, 32)` — it blocks the full 100 ms window and returns everything received. On real half-duplex K-line hardware (Phase 2/3 transceiver: every TX byte appears on RX), the window returns **echo + response concatenated**, and `kline_parse_frame`'s exact-length check rejects that buffer. Every exchange fails even with a healthy ECU → `StartCommunication` never succeeds → the device cannot talk to the real car at all.
- Fix: scan the receive window frame-by-frame (frame length comes from each frame's own format byte), skipping well-formed frames not addressed ECU→tester. This is a single-read buffer scan, **not** the audit's re-read loop — `readBytes` semantics mean one read per exchange is the reality, and a `now_ms()`-deadline loop would hang the `FakeTransport`-based tests (its clock never self-advances).

### Finding 3 — build failure counted as timeout (audit: Medium) → PARTIALLY VALID, directive rejected

- Factually correct: the `frame_len == 0` branch increments `consecutive_timeouts_`. It is a *documented deliberate trade-off* (comment at `kline_kwp.cpp:15-20`), and the branch is **unreachable through the current public API** (largest request payload is `read_pid`'s 2 bytes vs. the ~27-byte capacity ceiling). Severity is future-proofing: it becomes reachable if Phase 7 module scans send bigger payloads, and then a caller bug would masquerade as a dead link, flip `needs_reinit()`, suppress the `kline_connected` flag (`main.cpp:183`), and trigger pointless bus re-inits (`main.cpp:229`).
- The audit's directive to add `Serial.println` is **rejected**: `kline_kwp.cpp` is compiled by the native test env (`platformio.ini` `test_build_src = yes`; only Arduino-dependent TUs are filtered out). Adding an Arduino dependency would break the entire host test build.
- Amended fix: a dedicated `request_build_failures_` counter + accessor. Classification is fixed, visibility is preserved (inspectable in diagnostics), no Arduino dependency. Honest testing note: the branch itself cannot be triggered through the public API, so the new test pins the *separation* of the two counters (link timeouts never move the build-failure counter); the branch body is verified by review and compilation.

---

## File map

| File | Task | Change |
|---|---|---|
| `firmware/test/test_kwp_dtc/test_kwp_dtc.cpp` | 1 | +5 tests (dual-format, padding, empty-frame guard) |
| `firmware/src/kline_kwp.h` | 1, 2 | DTC contract comment (T1); `request_build_failures()` (T2) |
| `firmware/src/kline_kwp.cpp` | 1, 2 | dual-format `read_dtcs` (T1); frame-scan `send_request_and_get_response` + counter split (T2) |
| `firmware/test/test_kline_kwp/test_kline_kwp.cpp` | 2 | +5 tests (echo skip, echo-offline reinit, partial frame, trailing bytes, counter separation) |
| `firmware/src/esp32_uart_transport.cpp` | 3 | drain stale RX bytes before each request write |
| `firmware/tools/kl_sim/protocol.py` | 4 | `build_dtc_response` emits standard implicit layout |
| `firmware/tools/kl_sim/test_protocol.py` | 4 | update the one byte-layout assert |

No other files change. `dtc_list.h/.cpp`, `telemetry_frame`, `ble_svc`, and the Android app are untouched — the BLE DTC report frame's explicit N/M counts (design §2.3) are the *app* protocol and are unrelated to the K-line wire layout.

---

## Task 1: Dual-format DTC parsing (audit finding 1)

**Files:**
- Modify (full replacement): `D:\C180 Project\firmware\test\test_kwp_dtc\test_kwp_dtc.cpp`
- Modify (full replacement): `D:\C180 Project\firmware\src\kline_kwp.h`
- Modify (full replacement): `D:\C180 Project\firmware\src\kline_kwp.cpp`

- [ ] **Step 1: Write the failing tests — full replacement `test_kwp_dtc.cpp`** (existing 9 tests verbatim, one comment updated on the first test, plus 5 new tests):

```cpp
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
```

- [ ] **Step 2: Run the suite to verify the new tests fail for the right reasons**

Run: `pio test -e native -f test_kwp_dtc`
Expected: 4 FAIL / 10 PASS —
- `..._parses_implicit_format_without_count_byte` FAILS (current code treats `0x01` as a count byte, parses 0 pairs → count 0, expected 1)
- `..._parses_implicit_empty_response` FAILS (the current `data_len < 2` guard returns false)
- `..._drops_zero_padding_pairs` FAILS (current code reads `codes[0]` as `0x7100`)
- `..._implicit_format_caps_at_kmaxdtcs` FAILS (current code reads count byte `0x01` → count 1, expected 8)
- `..._rejects_empty_data_frame` PASSES already — it pins the guard so the Step 4 rewrite can't regress it. All other tests PASS.

- [ ] **Step 3: Full replacement `kline_kwp.h`** (only the Mode 03/07 comment block changes; everything else byte-identical to the current file):

```cpp
#pragma once

#include <cstdint>

#include "dtc_list.h"
#include "kline_frame.h"
#include "kline_transport.h"

// [Best estimate] read timeout for a single request/response over the
// Phase 1 direct-UART bench link. Real P2max negotiation (from the
// StartCommunication response) supersedes this once Phase 3 has real ECU
// data — see docs/superpowers/specs/2026-07-09-phase1-firmware-core-design.md §5.
inline constexpr uint32_t kResponseTimeoutMs = 100;

class KlineKwp {
 public:
  explicit KlineKwp(KLineTransport& transport) : transport_(transport) {}

  // Logical StartCommunication exchange (frame + checksum + key bytes) —
  // NOT the ISO 14230 physical wake-pulse timing. See the Phase 1 plan's
  // Scope boundary note.
  bool start_communication();

  bool send_tester_present();

  // Requests Mode 01 data for `mode01_pid`. On success, decodes the response
  // via pid_wire_format's table and writes to *out_value, returns true. On
  // timeout (e.g. an unsupported PID — PLAN.md §2 expects several PIDs to
  // go unanswered on this ECU), returns false and increments
  // consecutive_timeouts() rather than blocking or erroring.
  bool read_pid(uint8_t mode01_pid, float* out_value);

  // Mode 03 (stored) / Mode 07 (pending) DTC reads. Accepts BOTH known
  // response layouts, discriminated by the frame's own data-length parity
  // [Best estimate — assumes well-formed frames; the two layouts are
  // parity-disjoint by construction]:
  //   implicit  [SID][2 bytes per DTC]         (odd data_len) — standard
  //     J1979-over-K-line; [Likely] what the real SIM4LKE sends. Phase 3's
  //     car probe confirms which layout the car actually uses.
  //   explicit  [SID][count][2 bytes per DTC]  (even data_len) — the layout
  //     kl_sim's build_dtc_response used through Phase 4 [Confirmed against
  //     that code]; kept as a hedge should the real ECU use it.
  // 0x0000 pairs are dropped in both layouts (J1979 ECUs pad short DTC
  // responses with empty slots; P0000 is not a real code). Truncates
  // defensively: never reads pairs beyond what the frame physically
  // carries, caps at kMaxDtcs. Multi-frame DTC paging is out of scope
  // until the Phase 3 probe shows whether this ECU pages at all.
  bool read_stored_dtcs(DtcList* out);
  bool read_pending_dtcs(DtcList* out);

  int consecutive_timeouts() const { return consecutive_timeouts_; }
  bool needs_reinit() const { return consecutive_timeouts_ >= 3; }

 private:
  bool send_request_and_get_response(const uint8_t* data, uint8_t data_len, ParsedFrame* out);
  bool read_dtcs(uint8_t request_sid, uint8_t positive_sid, DtcList* out);

  KLineTransport& transport_;
  int consecutive_timeouts_ = 0;
};
```

- [ ] **Step 4: Full replacement `kline_kwp.cpp`** (only `read_dtcs` changes; `send_request_and_get_response` and everything else stay byte-identical to the current file — Task 2 rewrites them later):

```cpp
#include "kline_kwp.h"

#include "kline_frame.h"
#include "pid_wire_format.h"

// 32 bytes covers every current request/response: the largest is now a
// Mode 03 response at its defensive cap (3-byte header + SID + count +
// 8*2 DTC bytes + checksum = 22). Revisit if a later phase adds a request
// needing a payload anywhere near kline_frame.h's protocol-max
// kKlineMaxDataLen (63).
bool KlineKwp::send_request_and_get_response(const uint8_t* data, uint8_t data_len, ParsedFrame* out) {
  uint8_t frame[32];
  size_t frame_len = kline_build_frame(kTargetAddress, kTesterAddress, data, data_len, frame, sizeof(frame));
  if (frame_len == 0) {
    // data_len exceeded kKlineMaxDataLen or didn't fit in `frame` -- a
    // caller bug, not a live-ECU timeout. Fail the same way a timeout
    // would (rather than write nothing and silently wait out the full
    // response window for a request that was never actually sent), so
    // this is at least visible as a repeated failure instead of a mystery
    // stall.
    ++consecutive_timeouts_;
    return false;
  }
  transport_.write(frame, frame_len);

  uint8_t response[32];
  size_t response_len = transport_.read(response, sizeof(response), kResponseTimeoutMs);
  if (response_len == 0 || !kline_parse_frame(response, response_len, out)) {
    ++consecutive_timeouts_;
    return false;
  }
  if (out->target != kTesterAddress || out->source != kTargetAddress) {
    // Real half-duplex K-line hardware can echo the request back on RX;
    // a self-echo has target/source exactly reversed from a genuine ECU
    // response. Not reachable on Phase 1's direct-UART bench link (no
    // echo), but cheap to guard now ahead of Phase 2/3 real hardware.
    ++consecutive_timeouts_;
    return false;
  }
  // Resets on any well-formed, correctly-addressed reply -- including one
  // a caller (e.g. read_pid) goes on to reject for wrong SID/PID content.
  // This intentionally tracks "is the K-line link alive" (are we getting
  // parseable frames back at all), not "did this specific request get the
  // answer we wanted" -- an ECU declining one PID doesn't mean the link
  // is dead and doesn't warrant a full re-init.
  consecutive_timeouts_ = 0;
  return true;
}

bool KlineKwp::start_communication() {
  const uint8_t data[] = {0x81};  // SID_START_COMMUNICATION
  ParsedFrame response;
  if (!send_request_and_get_response(data, 1, &response)) {
    return false;
  }
  return response.data_len >= 1 && response.data[0] == 0xC1;
}

bool KlineKwp::send_tester_present() {
  const uint8_t data[] = {0x3E};  // SID_TESTER_PRESENT
  ParsedFrame response;
  if (!send_request_and_get_response(data, 1, &response)) {
    return false;
  }
  return response.data_len >= 1 && response.data[0] == 0x7E;
}

bool KlineKwp::read_pid(uint8_t mode01_pid, float* out_value) {
  const uint8_t data[] = {0x01, mode01_pid};  // SID_SHOW_CURRENT_DATA
  ParsedFrame response;
  if (!send_request_and_get_response(data, 2, &response)) {
    return false;
  }
  if (response.data_len < 2 || response.data[0] != 0x41 || response.data[1] != mode01_pid) {
    return false;
  }

  // Find the formula for this PID (linear scan of 18 entries — fine at this scale).
  for (size_t i = 0; i < kWireFormatCount; ++i) {
    if (kWireFormatTable[i].mode01_pid == mode01_pid) {
      // A checksum-valid but truncated response (e.g. just SID+PID, no
      // payload) would otherwise read past response.data_len into
      // never-written stack bytes -- reject it as a failed read rather
      // than decode garbage and report success.
      if (response.data_len < 2 + kWireFormatTable[i].byte_count) {
        return false;
      }
      *out_value = decode_formula(kWireFormatTable[i].formula, &response.data[2]);
      return true;
    }
  }
  return false;  // PID not in our schema — shouldn't happen if callers use pid_schema's table.
}

bool KlineKwp::read_dtcs(uint8_t request_sid, uint8_t positive_sid, DtcList* out) {
  const uint8_t data[] = {request_sid};
  ParsedFrame response;
  if (!send_request_and_get_response(data, 1, &response)) {
    return false;
  }
  if (response.data_len < 1 || response.data[0] != positive_sid) {
    return false;
  }
  // Layout by data-length parity (see the header contract): odd data_len is
  // [SID][pairs...] (count implicit in frame length), even data_len is
  // [SID][count][pairs...]. Both defensive clamps survive in either layout:
  // never read pairs beyond what the frame physically carries, never store
  // more than kMaxDtcs. 0x0000 pairs are padding, not codes -- skipped.
  const bool has_count_byte = (response.data_len % 2) == 0;
  const uint8_t first = has_count_byte ? 2 : 1;
  uint8_t pairs = static_cast<uint8_t>((response.data_len - first) / 2);
  if (has_count_byte && response.data[1] < pairs) {
    pairs = response.data[1];
  }
  out->count = 0;
  for (uint8_t i = 0; i < kMaxDtcs; ++i) {
    out->codes[i] = 0;
  }
  for (uint8_t i = 0; i < pairs && out->count < kMaxDtcs; ++i) {
    const uint16_t code = static_cast<uint16_t>((response.data[first + 2 * i] << 8) |
                                                response.data[first + 2 * i + 1]);
    if (code != 0) {
      out->codes[out->count++] = code;
    }
  }
  return true;
}

bool KlineKwp::read_stored_dtcs(DtcList* out) { return read_dtcs(0x03, 0x43, out); }
bool KlineKwp::read_pending_dtcs(DtcList* out) { return read_dtcs(0x07, 0x47, out); }
```

- [ ] **Step 5: Run the suite** — `pio test -e native -f test_kwp_dtc` → expected: 14/14 PASS.
- [ ] **Step 6: Regression — run ALL native suites** (this task touched a shared source file): `pio test -e native` → expected: every suite PASS.
- [ ] **Step 7: Leave for review** (no commit).

---

## Task 2: Echo-tolerant response scan + failure-class separation (audit findings 2 and 3)

Both changes land in `send_request_and_get_response`, so they share one task and one test batch.

**Files:**
- Modify (full replacement): `D:\C180 Project\firmware\test\test_kline_kwp\test_kline_kwp.cpp`
- Modify (full replacement): `D:\C180 Project\firmware\src\kline_kwp.h`
- Modify (full replacement): `D:\C180 Project\firmware\src\kline_kwp.cpp`

- [ ] **Step 1: Write the failing tests — full replacement `test_kline_kwp.cpp`** (existing 13 tests verbatim, plus 5 new at the end before `main`):

```cpp
#include <unity.h>

#include "fake_transport.h"
#include "kline_frame.h"
#include "kline_kwp.h"

// Unity fixture hooks -- the framework links against these even when unused.
void setUp(void) {}
void tearDown(void) {}

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
  // TX self-echo on real K-line hardware. Not reachable on Phase 1's bench
  // link, but the guard should still reject it if it ever occurs.
  FakeTransport transport;
  const uint8_t response_data[] = {0xC1, 0x8F, 0xE9};
  uint8_t frame[16];
  // Addresses swapped: built as if the TESTER's own request echoed back,
  // not a real ECU response (target=kTargetAddress, source=kTesterAddress).
  size_t len = kline_build_frame(kTargetAddress, kTesterAddress, response_data, 3, frame, sizeof(frame));
  transport.queue_response(std::vector<uint8_t>(frame, frame + len));

  KlineKwp kwp(transport);
  TEST_ASSERT_FALSE(kwp.start_communication());
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

void test_request_echo_followed_by_real_response_succeeds(void) {
  // Real half-duplex K-line: the RX window contains our own transmitted
  // request first (self-echo), then the genuine ECU response. The exchange
  // must skip the echo and succeed -- otherwise the device can never talk
  // to the real car (Phase 2/3 hardware always echoes).
  FakeTransport transport;
  uint8_t echo[16];
  const uint8_t request_data[] = {0x81};  // what start_communication sends
  size_t echo_len = kline_build_frame(kTargetAddress, kTesterAddress, request_data, 1, echo, sizeof(echo));
  uint8_t response[16];
  const uint8_t response_data[] = {0xC1, 0x8F, 0xE9};
  size_t response_len =
      kline_build_frame(kTesterAddress, kTargetAddress, response_data, 3, response, sizeof(response));
  std::vector<uint8_t> window(echo, echo + echo_len);
  window.insert(window.end(), response, response + response_len);
  transport.queue_response(window);

  KlineKwp kwp(transport);
  TEST_ASSERT_TRUE(kwp.start_communication());
  TEST_ASSERT_EQUAL_INT(0, kwp.consecutive_timeouts());
}

void test_echo_with_no_ecu_response_still_counts_toward_reinit(void) {
  // ECU offline but the hardware still echoes every request: hearing our
  // own voice is NOT a healthy link. Each exchange must count as a timeout
  // so needs_reinit() fires after 3 -- the failure mode the 2026-07-16
  // audit feared (link stuck looking alive on echoes) must stay impossible.
  FakeTransport transport;
  KlineKwp kwp(transport);
  uint8_t echo[16];
  const uint8_t request_data[] = {0x81};
  size_t echo_len = kline_build_frame(kTargetAddress, kTesterAddress, request_data, 1, echo, sizeof(echo));
  for (int i = 0; i < 3; ++i) {
    transport.queue_response(std::vector<uint8_t>(echo, echo + echo_len));
    TEST_ASSERT_FALSE(kwp.start_communication());
  }
  TEST_ASSERT_TRUE(kwp.needs_reinit());
}

void test_partial_frame_in_window_counts_as_timeout(void) {
  // Only the first 3 bytes of a response arrived before the window closed.
  FakeTransport transport;
  uint8_t frame[16];
  const uint8_t response_data[] = {0xC1, 0x8F, 0xE9};
  kline_build_frame(kTesterAddress, kTargetAddress, response_data, 3, frame, sizeof(frame));
  transport.queue_response(std::vector<uint8_t>(frame, frame + 3));

  KlineKwp kwp(transport);
  TEST_ASSERT_FALSE(kwp.start_communication());
  TEST_ASSERT_EQUAL_INT(1, kwp.consecutive_timeouts());
}

void test_bytes_trailing_a_valid_response_are_ignored(void) {
  // A complete valid response followed by the first bytes of something
  // else (late noise, next frame's start) must not spoil the exchange.
  FakeTransport transport;
  uint8_t frame[16];
  const uint8_t response_data[] = {0xC1, 0x8F, 0xE9};
  size_t len = kline_build_frame(kTesterAddress, kTargetAddress, response_data, 3, frame, sizeof(frame));
  std::vector<uint8_t> window(frame, frame + len);
  window.push_back(0x83);  // stray first bytes of a later frame
  window.push_back(0xF1);
  transport.queue_response(window);

  KlineKwp kwp(transport);
  TEST_ASSERT_TRUE(kwp.start_communication());
  TEST_ASSERT_EQUAL_INT(0, kwp.consecutive_timeouts());
}

void test_link_timeouts_are_not_build_failures(void) {
  // The two failure classes stay separate: a silent ECU moves only
  // consecutive_timeouts(); request_build_failures() moves only on an
  // oversized request payload, which no current public API can produce
  // (largest request is read_pid's 2 bytes -- the branch is future-proofing
  // for bigger Phase 7 payloads and is pinned here from the observable side).
  FakeTransport transport;
  KlineKwp kwp(transport);
  float value = 0.0f;
  TEST_ASSERT_FALSE(kwp.read_pid(0x0C, &value));
  TEST_ASSERT_EQUAL_INT(1, kwp.consecutive_timeouts());
  TEST_ASSERT_EQUAL_INT(0, kwp.request_build_failures());
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
  RUN_TEST(test_request_echo_followed_by_real_response_succeeds);
  RUN_TEST(test_echo_with_no_ecu_response_still_counts_toward_reinit);
  RUN_TEST(test_partial_frame_in_window_counts_as_timeout);
  RUN_TEST(test_bytes_trailing_a_valid_response_are_ignored);
  RUN_TEST(test_link_timeouts_are_not_build_failures);
  return UNITY_END();
}
```

- [ ] **Step 2: Run the suite to verify it fails**

Run: `pio test -e native -f test_kline_kwp`
Expected: FAIL to compile — `request_build_failures` is not a member of `KlineKwp`. (Once it compiles after Step 3+4, the interesting pre-fix failures would have been `..._echo_followed_by_real_response_succeeds` and `..._bytes_trailing_a_valid_response_are_ignored` — the current exact-length parse rejects any window that isn't exactly one frame. `..._echo_with_no_ecu_response_still_counts_toward_reinit` and `..._partial_frame_in_window_counts_as_timeout` pass both before and after; they are regression pins.)

- [ ] **Step 3: Full replacement `kline_kwp.h`** (Task 1's version plus the `request_build_failures` accessor and member):

```cpp
#pragma once

#include <cstdint>

#include "dtc_list.h"
#include "kline_frame.h"
#include "kline_transport.h"

// [Best estimate] read timeout for a single request/response over the
// Phase 1 direct-UART bench link. Real P2max negotiation (from the
// StartCommunication response) supersedes this once Phase 3 has real ECU
// data — see docs/superpowers/specs/2026-07-09-phase1-firmware-core-design.md §5.
inline constexpr uint32_t kResponseTimeoutMs = 100;

class KlineKwp {
 public:
  explicit KlineKwp(KLineTransport& transport) : transport_(transport) {}

  // Logical StartCommunication exchange (frame + checksum + key bytes) —
  // NOT the ISO 14230 physical wake-pulse timing. See the Phase 1 plan's
  // Scope boundary note.
  bool start_communication();

  bool send_tester_present();

  // Requests Mode 01 data for `mode01_pid`. On success, decodes the response
  // via pid_wire_format's table and writes to *out_value, returns true. On
  // timeout (e.g. an unsupported PID — PLAN.md §2 expects several PIDs to
  // go unanswered on this ECU), returns false and increments
  // consecutive_timeouts() rather than blocking or erroring.
  bool read_pid(uint8_t mode01_pid, float* out_value);

  // Mode 03 (stored) / Mode 07 (pending) DTC reads. Accepts BOTH known
  // response layouts, discriminated by the frame's own data-length parity
  // [Best estimate — assumes well-formed frames; the two layouts are
  // parity-disjoint by construction]:
  //   implicit  [SID][2 bytes per DTC]         (odd data_len) — standard
  //     J1979-over-K-line; [Likely] what the real SIM4LKE sends. Phase 3's
  //     car probe confirms which layout the car actually uses.
  //   explicit  [SID][count][2 bytes per DTC]  (even data_len) — the layout
  //     kl_sim's build_dtc_response used through Phase 4 [Confirmed against
  //     that code]; kept as a hedge should the real ECU use it.
  // 0x0000 pairs are dropped in both layouts (J1979 ECUs pad short DTC
  // responses with empty slots; P0000 is not a real code). Truncates
  // defensively: never reads pairs beyond what the frame physically
  // carries, caps at kMaxDtcs. Multi-frame DTC paging is out of scope
  // until the Phase 3 probe shows whether this ECU pages at all.
  bool read_stored_dtcs(DtcList* out);
  bool read_pending_dtcs(DtcList* out);

  int consecutive_timeouts() const { return consecutive_timeouts_; }
  bool needs_reinit() const { return consecutive_timeouts_ >= 3; }

  // Requests that failed to BUILD (payload too big for the TX buffer) — a
  // caller bug, deliberately kept out of consecutive_timeouts() so a
  // software defect can never masquerade as a dead link, suppress the
  // kline_connected flag, and trigger pointless bus re-inits. No current
  // public API can produce this (largest request is read_pid's 2 bytes);
  // it exists for future callers with bigger payloads (e.g. Phase 7
  // module scans).
  int request_build_failures() const { return request_build_failures_; }

 private:
  bool send_request_and_get_response(const uint8_t* data, uint8_t data_len, ParsedFrame* out);
  bool read_dtcs(uint8_t request_sid, uint8_t positive_sid, DtcList* out);

  KLineTransport& transport_;
  int consecutive_timeouts_ = 0;
  int request_build_failures_ = 0;
};
```

- [ ] **Step 4: Full replacement `kline_kwp.cpp`** (Task 1's `read_dtcs` unchanged; `send_request_and_get_response` rewritten — frame scan + counter split; all other functions byte-identical):

```cpp
#include "kline_kwp.h"

#include "kline_frame.h"
#include "pid_wire_format.h"

// The 32-byte TX buffer covers every current request with headroom: the
// largest is read_pid's 2-byte payload (3-byte header + 2 + checksum = 6).
// Revisit if a later phase adds a request needing a payload anywhere near
// kline_frame.h's protocol-max kKlineMaxDataLen (63).
bool KlineKwp::send_request_and_get_response(const uint8_t* data, uint8_t data_len, ParsedFrame* out) {
  uint8_t frame[32];
  size_t frame_len = kline_build_frame(kTargetAddress, kTesterAddress, data, data_len, frame, sizeof(frame));
  if (frame_len == 0) {
    // data_len exceeded kKlineMaxDataLen or didn't fit in `frame`. That is
    // a caller bug, NOT a live-ECU timeout: it must not feed needs_reinit()
    // and provoke pointless bus re-inits, so it gets its own counter (see
    // request_build_failures() in the header). No serial logging here --
    // this file stays Arduino-free so the native test env can compile it.
    ++request_build_failures_;
    return false;
  }
  transport_.write(frame, frame_len);

  // On real half-duplex K-line hardware every byte we transmit is also
  // received (single-wire bus), so the read window typically returns our
  // own request echo followed by the ECU's response, back to back. Read
  // the whole window into one buffer and scan it frame by frame (each
  // frame's length comes from its own format byte), skipping any
  // well-formed frame that is not addressed ECU->tester -- a self-echo has
  // target/source exactly reversed. Phase 1's direct-UART bench link
  // produces no echo and resolves on the first frame, unchanged. A frame
  // that fails to parse (bad checksum, truncated tail) aborts the scan --
  // no byte-level resync attempt; the exchange fails and the caller
  // retries.
  uint8_t rx[64];  // request echo (frame <= 32) + response (frame <= 32)
  const size_t rx_len = transport_.read(rx, sizeof(rx), kResponseTimeoutMs);
  size_t pos = 0;
  while (rx_len - pos >= 4) {
    const size_t flen = 3 + (rx[pos] & kKlineMaxDataLen) + 1;
    if (pos + flen > rx_len || !kline_parse_frame(&rx[pos], flen, out)) {
      break;
    }
    if (out->target == kTesterAddress && out->source == kTargetAddress) {
      // Resets on any well-formed, correctly-addressed reply -- including
      // one a caller (e.g. read_pid) goes on to reject for wrong SID/PID
      // content. This intentionally tracks "is the K-line link alive" (are
      // we getting parseable frames back at all), not "did this specific
      // request get the answer we wanted" -- an ECU declining one PID
      // doesn't mean the link is dead and doesn't warrant a full re-init.
      consecutive_timeouts_ = 0;
      return true;
    }
    pos += flen;  // echo or foreign traffic -- skip it and keep scanning
  }
  ++consecutive_timeouts_;
  return false;
}

bool KlineKwp::start_communication() {
  const uint8_t data[] = {0x81};  // SID_START_COMMUNICATION
  ParsedFrame response;
  if (!send_request_and_get_response(data, 1, &response)) {
    return false;
  }
  return response.data_len >= 1 && response.data[0] == 0xC1;
}

bool KlineKwp::send_tester_present() {
  const uint8_t data[] = {0x3E};  // SID_TESTER_PRESENT
  ParsedFrame response;
  if (!send_request_and_get_response(data, 1, &response)) {
    return false;
  }
  return response.data_len >= 1 && response.data[0] == 0x7E;
}

bool KlineKwp::read_pid(uint8_t mode01_pid, float* out_value) {
  const uint8_t data[] = {0x01, mode01_pid};  // SID_SHOW_CURRENT_DATA
  ParsedFrame response;
  if (!send_request_and_get_response(data, 2, &response)) {
    return false;
  }
  if (response.data_len < 2 || response.data[0] != 0x41 || response.data[1] != mode01_pid) {
    return false;
  }

  // Find the formula for this PID (linear scan of 18 entries — fine at this scale).
  for (size_t i = 0; i < kWireFormatCount; ++i) {
    if (kWireFormatTable[i].mode01_pid == mode01_pid) {
      // A checksum-valid but truncated response (e.g. just SID+PID, no
      // payload) would otherwise read past response.data_len into
      // never-written stack bytes -- reject it as a failed read rather
      // than decode garbage and report success.
      if (response.data_len < 2 + kWireFormatTable[i].byte_count) {
        return false;
      }
      *out_value = decode_formula(kWireFormatTable[i].formula, &response.data[2]);
      return true;
    }
  }
  return false;  // PID not in our schema — shouldn't happen if callers use pid_schema's table.
}

bool KlineKwp::read_dtcs(uint8_t request_sid, uint8_t positive_sid, DtcList* out) {
  const uint8_t data[] = {request_sid};
  ParsedFrame response;
  if (!send_request_and_get_response(data, 1, &response)) {
    return false;
  }
  if (response.data_len < 1 || response.data[0] != positive_sid) {
    return false;
  }
  // Layout by data-length parity (see the header contract): odd data_len is
  // [SID][pairs...] (count implicit in frame length), even data_len is
  // [SID][count][pairs...]. Both defensive clamps survive in either layout:
  // never read pairs beyond what the frame physically carries, never store
  // more than kMaxDtcs. 0x0000 pairs are padding, not codes -- skipped.
  const bool has_count_byte = (response.data_len % 2) == 0;
  const uint8_t first = has_count_byte ? 2 : 1;
  uint8_t pairs = static_cast<uint8_t>((response.data_len - first) / 2);
  if (has_count_byte && response.data[1] < pairs) {
    pairs = response.data[1];
  }
  out->count = 0;
  for (uint8_t i = 0; i < kMaxDtcs; ++i) {
    out->codes[i] = 0;
  }
  for (uint8_t i = 0; i < pairs && out->count < kMaxDtcs; ++i) {
    const uint16_t code = static_cast<uint16_t>((response.data[first + 2 * i] << 8) |
                                                response.data[first + 2 * i + 1]);
    if (code != 0) {
      out->codes[out->count++] = code;
    }
  }
  return true;
}

bool KlineKwp::read_stored_dtcs(DtcList* out) { return read_dtcs(0x03, 0x43, out); }
bool KlineKwp::read_pending_dtcs(DtcList* out) { return read_dtcs(0x07, 0x47, out); }
```

- [ ] **Step 5: Run the suite** — `pio test -e native -f test_kline_kwp` → expected: 18/18 PASS.
- [ ] **Step 6: Regression — run ALL native suites** (`send_request_and_get_response` underlies the scheduler and DTC suites too): `pio test -e native` → expected: every suite PASS, including `test_kwp_dtc` at 14/14.
- [ ] **Step 7: Leave for review** (no commit).

---

## Task 3: Drain stale RX bytes before each request (hardening adjacent to finding 2)

With Task 2's scan, a *stale but correctly-addressed* frame left in the UART RX buffer (a late response to an exchange we already abandoned) could be accepted as the reply to the next request when SID+PID happen to match (e.g. back-to-back RPM polls at the F tier's 2 Hz). Draining RX before each transmit closes that hole at the hardware boundary, keeping `kline_kwp` transport-agnostic.

**Files:**
- Modify (full replacement): `D:\C180 Project\firmware\src\esp32_uart_transport.cpp`

- [ ] **Step 1: Full replacement `esp32_uart_transport.cpp`:**

```cpp
#include "esp32_uart_transport.h"

#include <Arduino.h>

Esp32UartTransport::Esp32UartTransport(HardwareSerial& serial) : serial_(serial) {}

void Esp32UartTransport::write(const uint8_t* data, size_t len) {
  // Strict request/response protocol: any byte still sitting in RX when a
  // new request starts is stale by definition -- a late or partial response
  // to an exchange kline_kwp already gave up on. Drain it so it can't be
  // mistaken for the reply to THIS request. kline_kwp's frame scan skips
  // echoes and misaddressed frames, but a stale well-addressed frame would
  // look genuine.
  while (serial_.available() > 0) {
    serial_.read();
  }
  serial_.write(data, len);
}

size_t Esp32UartTransport::read(uint8_t* buf, size_t len, uint32_t timeout_ms) {
  serial_.setTimeout(timeout_ms);
  return serial_.readBytes(buf, len);
}

uint32_t Esp32UartTransport::now_ms() {
  return millis();
}
```

- [ ] **Step 2: Verify the ESP32 target still compiles** (this TU is excluded from the native env, so this is the only automated check available — there is no host test for it, by design):

Run: `pio run -e esp32dev`
Expected: SUCCESS (compile + link; no upload).

- [ ] **Step 3: Leave for review** (no commit).

---

## Task 4: Simulator emits the standard implicit DTC layout

Independently droppable — firmware accepts both layouts after Task 1 either way. Rationale for doing it: the sim exists to imitate the real ECU, and the real ECU [Likely] uses the implicit layout, so the bench should exercise the path the car will take. TDD on the pytest side.

**Files:**
- Modify: `D:\C180 Project\firmware\tools\kl_sim\protocol.py` (one function)
- Modify: `D:\C180 Project\firmware\tools\kl_sim\test_protocol.py` (one test)

- [ ] **Step 1: Update the layout test first.** In `test_protocol.py`, replace exactly this test:

```python
def test_build_dtc_response_stored_dtcs():
    _, _, data = parse_frame(build_dtc_response(sid_positive=0x43, dtcs=["P0171"]))
    assert data == bytes([0x43, 0x01, 0x01, 0x71])
```

with:

```python
def test_build_dtc_response_stored_dtcs():
    # Standard J1979-over-K-line layout: no count byte, count implicit in
    # the frame's own length field (matches firmware read_dtcs's [Likely]
    # branch for the real ECU).
    _, _, data = parse_frame(build_dtc_response(sid_positive=0x43, dtcs=["P0171"]))
    assert data == bytes([0x43, 0x01, 0x71])


def test_build_dtc_response_empty_list_is_bare_sid():
    _, _, data = parse_frame(build_dtc_response(sid_positive=0x47, dtcs=[]))
    assert data == bytes([0x47])
```

- [ ] **Step 2: Run to verify it fails.** From `D:\C180 Project\firmware\tools\kl_sim`: `python -m pytest -q`
Expected: `test_build_dtc_response_stored_dtcs` FAILS (data still carries the count byte `0x01`); `..._empty_list_is_bare_sid` FAILS (data is `[0x47, 0x00]`). All other tests PASS.

- [ ] **Step 3: Update the builder.** In `protocol.py`, replace exactly:

```python
def build_dtc_response(sid_positive: int, dtcs: list[str]) -> bytes:
    payload = bytes([sid_positive, len(dtcs)])
    for code in dtcs:
        payload += encode_dtc(code)
    return build_frame(TESTER_ADDRESS, TARGET_ADDRESS, payload)
```

with:

```python
def build_dtc_response(sid_positive: int, dtcs: list[str]) -> bytes:
    # Standard J1979-over-K-line layout: no count byte -- the DTC count is
    # implicit in the frame's own length field. The firmware's read_dtcs
    # accepts both this and the legacy explicit-count layout, so the sim
    # emits what the real ECU [Likely] sends.
    payload = bytes([sid_positive])
    for code in dtcs:
        payload += encode_dtc(code)
    return build_frame(TESTER_ADDRESS, TARGET_ADDRESS, payload)
```

`kline_sim.py` needs no change — it calls `build_dtc_response` for both the Mode 03 (`scenario.dtcs`) and Mode 07 (empty list) paths and is layout-agnostic. The scenario tests assert DTC *lists*, not frame bytes.

- [ ] **Step 4: Run to verify it passes.** From `D:\C180 Project\firmware\tools\kl_sim`: `python -m pytest -q`
Expected: all tests PASS.

- [ ] **Step 5: Leave for review** (no commit).

---

## Task 5: Final verification

- [ ] **Step 1: Full native test run.** From `D:\C180 Project\firmware`: `pio test -e native`
Expected: every suite PASS — `test_kline_kwp` 18/18, `test_kwp_dtc` 14/14, all other suites unchanged.
- [ ] **Step 2: ESP32 target compile gate.** `pio run -e esp32dev` → SUCCESS. (Optional extra: `pio run -e esp32dev_demo` → SUCCESS.)
- [ ] **Step 3: Confirm the working tree contains exactly the seven files from the File map** (`git status` from `D:\C180 Project`) and nothing else. Do NOT commit — summarize the diff for the user and stop.

---

## Out of scope (deliberate)

- **Multi-frame DTC paging** — unknowable before the Phase 3 car probe; single-window reads only.
- **P2min/P2max timing negotiation** — `kResponseTimeoutMs = 100` stays `[Best estimate]` until Phase 3.
- **Historical planning docs** (`docs/superpowers/plans/2026-07-10-phase4-android-ble.md`, `.../specs/2026-07-10-phase4-android-ble-design.md`) are records of what was planned; they are not rewritten. Current truth lives in the code comments updated by Tasks 1–2.
- **Per-frame read pacing optimization** (reading the format byte first to return early instead of blocking the full 100 ms window) — a latency tweak, not a correctness fix; revisit if the Phase 2 bench shows the polling budget needs it.
