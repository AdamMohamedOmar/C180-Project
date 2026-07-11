# Phase 4: Android App + Firmware BLE Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The ESP32 firmware serves live telemetry + DTCs over BLE GATT, and a Kotlin/Compose Android app auto-connects to it (CompanionDeviceManager), renders a live dashboard with W203 healthy bands and a read-only DTC screen — with every piece developed and tested software-first, the ESP32 plugged in exactly once at the very end (over its own USB cable, in demo mode — no bench wiring, no car).

**Architecture:** See `docs/superpowers/specs/2026-07-10-phase4-android-ble-design.md` for full rationale. Summary: one BLE contract doc (`docs/ble_protocol.md`, Task 1) is hand-mirrored by a framework-agnostic C++ packer (`telemetry_frame`, native-tested) and a pure-Kotlin parser (`FrameCodec`, JVM-tested), with identical golden hex vectors enforced on both sides so drift goes red. Firmware splits Phase 1-style: native-testable logic + a thin ESP32-only NimBLE wrapper (`ble_svc`). The app's UI only ever sees a `TelemetrySource` interface with fake (day-to-day) and BLE (final session) implementations.

**Tech Stack:** C++17 (framework-agnostic core + Arduino/NimBLE glue), NimBLE-Arduino, PlatformIO (native + esp32dev + esp32dev_demo envs, Unity tests), Kotlin 2.x + Jetpack Compose (Material 3), kotlinx-serialization, DataStore, CompanionDeviceManager, JUnit4 + kotlinx-coroutines-test.

---

## Execution notes (read first — hard-won facts, do not rediscover)

- **NEVER run `git commit`.** The user commits via the VS Code Source Control GUI. Every task ends "leave for review" with a clean, reviewable working tree.
- **Full replacement files** when changing an existing file — modules are sized to regenerate whole.
- **Firmware commands** run from `D:\C180 Project\firmware\`:
  - `pio test -e native` (all native suites), `pio test -e native -f test_<name>` (one suite)
  - `pio run -e esp32dev` / `pio run -e esp32dev_demo` (compile checks)
  - Host compiler is w64devkit GCC at `C:\Users\DELL\tools\w64devkit\bin`, already on the user PATH. A bash session started before that PATH change may need `export PATH="/c/Users/DELL/tools/w64devkit/bin:$PATH"`.
- **Android commands** run from `D:\C180 Project\android\` and only exist after Task 0 (user installs Android Studio) + Task 9 (agent probes the environment and records the exact headless invocation — **read Task 9's recorded env block before running any gradle command in later tasks**). Until then, Android tasks 10–20 are blocked; firmware tasks 1–8 are not.
- Every Unity test `.cpp` MUST define `void setUp(void) {}` and `void tearDown(void) {}` and its own `main()`, and live in its own `firmware/test/test_<name>/` folder (PlatformIO links each folder as one binary).
- Piping to `tee` masks exit codes — use `set -o pipefail` and read the actual log before claiming success.
- Session-limit economics: tasks are fully specified so an interrupted subagent is cheap to resume — verify what exists on disk, continue from the first missing step.
- Confidence tags (`[Confirmed]`/`[Likely]`/`[Best estimate]`/`[Guessing]`) in code comments are load-bearing project policy (PLAN.md §0.4) — keep every one that appears in the code below.

## File map

**Created:** `docs/ble_protocol.md`; firmware: `src/dtc_list.h`, `src/latest_values.h/.cpp`, `src/telemetry_frame.h/.cpp`, `src/demo_feed.h/.cpp`, `src/ble_svc.h/.cpp`, tests `test/test_latest_values/`, `test/test_telemetry_frame/`, `test/test_demo_feed/`, `test/test_kwp_dtc/`; android: `app/src/main/assets/w203_bands.json`, `w203_dtc.json`, Kotlin sources under `app/src/main/java/com/kompressorlink/app/` (`telemetry/`, `telemetry/ble/`, `connection/`, `dashboard/`, `dtc/`, `reference/`, `KompressorLinkApp.kt`), unit tests under `app/src/test/java/com/kompressorlink/app/`.

**Modified (full replacement):** `firmware/src/kline_kwp.h/.cpp` (adds Mode 03/07), `firmware/src/ride_logger.h/.cpp` (adds `write_header_line`), `firmware/src/main.cpp`, `firmware/platformio.ini`, `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/com/kompressorlink/app/MainActivity.kt`.

**Untouched:** `pid_schema.*`, `pid_wire_format.*`, `kline_frame.*`, `pid_scheduler.*`, transports/storage, `kline_sim.py` + `kl_sim/` (the sim already answers Mode 03/07 — nothing to add), `pipeline/`.

---

## Task 0: Install Android Studio + first Gradle sync (USER step — can run in parallel with Tasks 1–8)

**Files:** none (environment only).

- [ ] **Step 1 (user): Install Android Studio** (latest stable) from https://developer.android.com/studio with default options (bundled SDK + JBR). ~1.5 GB download.
- [ ] **Step 2 (user): Open `D:\C180 Project\android\` as a project.** Let Studio generate the Gradle wrapper (`gradlew.bat`) and sync. If Studio prompts to upgrade AGP/Gradle/Kotlin: **accept, and write down what it changed.**
- [ ] **Step 3 (user): Run the app once** on the S23 FE over USB (enable Developer options + USB debugging on the phone). Expected: "KompressorLink — Phase 0 scaffold…" text renders.
- [ ] **Step 4 (user): Report back** to the executing agent: (a) sync succeeded? (b) any version upgrades Studio applied, (c) app ran on phone? Task 9 consumes this.

---

## Task 1: `docs/ble_protocol.md` — the normative BLE contract

Single source of truth both codebases mirror. The golden hex vectors below are **copied verbatim** into the C++ tests (Task 4) and Kotlin tests (Task 12) — if you retype rather than copy, you have created the exact drift bug this document exists to prevent.

**Files:**
- Create: `D:\C180 Project\docs\ble_protocol.md`

- [ ] **Step 1: Write the document** with exactly this content:

````markdown
# KompressorLink BLE Protocol v1

Normative contract between `firmware/src/telemetry_frame.h/.cpp` (packer)
and `android/.../telemetry/ble/FrameCodec.kt` (parser). Both mirror this
file verbatim — change here first, then both mirrors, then the golden-vector
tests on both sides (they embed the vectors below as constants).

All multi-byte fields are **little-endian**. Floats are IEEE 754 float32.

## GATT layout

Device name: `KompressorLink`. Advertises the service UUID below whenever
powered (no sleep gating until `power_mgr` exists). Standard Device
Information Service (0x180A) also served: Firmware Revision String (0x2A26)
= `FW_VERSION`, Manufacturer Name (0x2A29) = `KompressorLink`.

| UUID | Role | Properties |
|---|---|---|
| `c1800001-4b4c-4d27-b946-c180c0deba5e` | KompressorLink service | — |
| `c1800002-4b4c-4d27-b946-c180c0deba5e` | Telemetry | notify |
| `c1800003-4b4c-4d27-b946-c180c0deba5e` | DTC report | read + notify |
| `c1800004-4b4c-4d27-b946-c180c0deba5e` | Control | write (with response) |

No pairing/bonding in v1 (accepted risk: read-only telemetry, personal tool).

## Telemetry frame — 88 bytes, notified every 500 ms

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `protocol_version` = 0x01 |
| 1 | 1 | `flags`: bit0 demo_mode, bit1 kline_connected, bits 2–7 reserved 0 |
| 2 | 2 | `seq` — uint16, +1 per notification, wraps |
| 4 | 4 | `uptime_ms` — uint32, firmware millis(), wraps ~49.7 days |
| 8 | 4 | `avail_mask` — bit i = Signal enum ordinal i available; bits 19–31 = 0 |
| 12 | 76 | 19 × float32, Signal enum declaration order (see below) |

Signal order (mirror-verbatim of `firmware/src/pid_schema.h`'s enum — index
= mask bit = value slot): RPM(0), SPEED(1), MAF_GS(2), THROTTLE(3), LOAD(4),
STFT1(5), LTFT1(6), MAP(7), TIMING_ADV(8), O2_B1S1_V(9), O2_B1S2_V(10),
ECT(11), IAT(12), FUEL_STATUS(13), PEDAL_D(14), PEDAL_E(15),
CMD_THROTTLE(16), CTRL_MODULE_V(17), BATT_V_ADC(18).

A signal with mask bit clear is transmitted as 0.0f and MUST be ignored by
the receiver. Receivers MUST reject frames whose length ≠ 88 or whose
version byte ≠ 0x01 (surface a protocol-mismatch state, never mis-parse).
Requires ATT MTU ≥ 91; the client requests 517 after connecting and treats
a negotiated MTU < 91 as a failed connection (disconnect + retry).

## DTC report frame — read anytime, notified on change

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `protocol_version` = 0x01 |
| 1 | 1 | `stored_count` N (≤ 8) |
| 2 | 1 | `pending_count` M (≤ 8) |
| 3 | 2·N | stored DTCs, 2 bytes each |
| 3+2·N | 2·M | pending DTCs, 2 bytes each |

DTC 2-byte encoding is SAE J2012 [Confirmed], exactly what
`firmware/tools/kl_sim/protocol.py`'s `encode_dtc` produces: byte1 =
letter(2 bits: P=00 C=01 B=10 U=11) | first-digit(2 bits) | second-digit
(4 bits); byte2 = third-digit(4) | fourth-digit(4). The two DTC bytes keep
their J2012 order (byte1 first) — they are NOT little-endian swapped.

## Control frame — 9 bytes, written by the client

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `opcode`: 0x01 = TIME_SYNC. 0x02 = CLEAR_DTC reserved, UNIMPLEMENTED (deferred by design) |
| 1 | 8 | `epoch_ms` — uint64 LE, Unix epoch milliseconds |

Firmware ignores unknown opcodes (logs to Serial). On TIME_SYNC it sets the
system clock and, if a ride is active, writes `#time_sync=<t_ms>:<epoch_ms>`
into the ride CSV.

## Golden test vectors (embed VERBATIM in both codebases' tests)

Each telemetry vector is given as one 24-hex-char header line + nineteen
8-hex-char value lines (one per signal, in enum order). The canonical
vector is the **concatenation of the lines in order** — 176 hex chars = 88
bytes. Tests embed the vector by concatenating these exact line strings
(C++ adjacent string literals / Kotlin `+`), one per line, so each line is
independently checkable against this table and there is no single
unwrappable 176-char string to mistype.

**T1 — telemetry, fully populated.** version=1, flags=0x02
(kline_connected), seq=0x1234, uptime_ms=100000, mask=0x0007FFFF (all 19),
values: RPM=750, SPEED=0, MAF_GS=4, THROTTLE=12.5, LOAD=25, STFT1=-2.5,
LTFT1=5, MAP=98, TIMING_ADV=10, O2_B1S1_V=0.5, O2_B1S2_V=0.75, ECT=90,
IAT=35, FUEL_STATUS=2, PEDAL_D=15, PEDAL_E=15, CMD_THROTTLE=12,
CTRL_MODULE_V=14, BATT_V_ADC=14.25 (all exactly representable in float32 —
deliberate, so equality asserts are exact):

```
01023412a0860100ffff0700   header: ver=01 flags=02 seq=3412 uptime=a0860100 mask=ffff0700
00803b44   RPM          = 750.0
00000000   SPEED        = 0.0
00008040   MAF_GS       = 4.0
00004841   THROTTLE     = 12.5
0000c841   LOAD         = 25.0
000020c0   STFT1        = -2.5
0000a040   LTFT1        = 5.0
0000c442   MAP          = 98.0
00002041   TIMING_ADV   = 10.0
0000003f   O2_B1S1_V    = 0.5
0000403f   O2_B1S2_V    = 0.75
0000b442   ECT          = 90.0
00000c42   IAT          = 35.0
00000040   FUEL_STATUS  = 2.0
00007041   PEDAL_D      = 15.0
00007041   PEDAL_E      = 15.0
00004041   CMD_THROTTLE = 12.0
00006041   CTRL_MODULE_V= 14.0
00006441   BATT_V_ADC   = 14.25
```

**T2 — telemetry, sparse.** version=1, flags=0x01 (demo_mode), seq=0xFFFF,
uptime_ms=0, mask=0x00000801 (only RPM bit 0 + ECT bit 11), RPM=800,
ECT=87, every other value slot zero:

```
0101ffff0000000001080000   header: ver=01 flags=01 seq=ffff uptime=00000000 mask=01080000
00004844   RPM          = 800.0
00000000   SPEED        (masked out)
00000000   MAF_GS       (masked out)
00000000   THROTTLE     (masked out)
00000000   LOAD         (masked out)
00000000   STFT1        (masked out)
00000000   LTFT1        (masked out)
00000000   MAP          (masked out)
00000000   TIMING_ADV   (masked out)
00000000   O2_B1S1_V    (masked out)
00000000   O2_B1S2_V    (masked out)
0000ae42   ECT          = 87.0
00000000   IAT          (masked out)
00000000   FUEL_STATUS  (masked out)
00000000   PEDAL_D      (masked out)
00000000   PEDAL_E      (masked out)
00000000   CMD_THROTTLE (masked out)
00000000   CTRL_MODULE_V(masked out)
00000000   BATT_V_ADC   (masked out)
```

**D1 — DTC report: stored=[P0171], pending=[]:** `0101000171`
**D2 — DTC report: both empty:** `010000`
**C1 — control TIME_SYNC, epoch_ms=0x0123456789ABCDEF:** `01efcdab8967452301`

## Version policy

Breaking layout change ⇒ bump `protocol_version`, update this doc + both
mirrors + vectors. The app rejects unknown versions with a visible
protocol-mismatch state.

## Confidence

Frame layouts, UUIDs, encodings above: design-fixed here, [Confirmed] as
the contract by the golden tests. The DTC **count-byte framing on the
K-line side** (how the ECU reports DTCs to firmware) is [Guessing] — see
spec §2.3; that uncertainty is isolated in `kline_kwp` and does NOT affect
this BLE frame, which is our own format end-to-end.
````

- [ ] **Step 2: Verify vector arithmetic** (arithmetic only, no code): each telemetry vector = 24 header chars + 19×8 value chars = 176 hex chars = 88 bytes; D1 is 5 bytes = 10 chars; D2 3 bytes = 6 chars; C1 9 bytes = 18 chars. Spot-check two float encodings independently: 750.0f = 0x443B8000 → LE bytes `00 80 3b 44` (T1's RPM line) and 87.0f = 0x42AE0000 → LE bytes `00 00 ae 42` (T2's ECT line).
- [ ] **Step 3: Leave for review** (no commit — user commits via VS Code GUI).

---

## Task 2: `dtc_list.h` + `kline_kwp` Mode 03/07 DTC reads

**Files:**
- Create: `D:\C180 Project\firmware\src\dtc_list.h`
- Modify (full replacement): `D:\C180 Project\firmware\src\kline_kwp.h`
- Modify (full replacement): `D:\C180 Project\firmware\src\kline_kwp.cpp`
- Create: `D:\C180 Project\firmware\test\test_kwp_dtc\test_kwp_dtc.cpp`

- [ ] **Step 1: Write `dtc_list.h`** (its own tiny header so `telemetry_frame` can use it without dragging in the KWP stack):

```cpp
#pragma once

#include <cstdint>

// Fixed-capacity DTC holder — no heap on the ESP32. 8 is a deliberate cap:
// [Best estimate] a real ECU rarely stores more, and 8 codes fit in one
// KWP frame (63-byte data limit) and keep the BLE DTC frame under any
// sane MTU (docs/ble_protocol.md).
inline constexpr uint8_t kMaxDtcs = 8;

struct DtcList {
  uint8_t count = 0;               // 0..kMaxDtcs
  uint16_t codes[kMaxDtcs] = {0};  // SAE J2012 2-byte values, (byte1<<8)|byte2
};
```

- [ ] **Step 2: Write the failing tests** — `firmware/test/test_kwp_dtc/test_kwp_dtc.cpp`:

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
  // Mirrors kl_sim/protocol.py build_dtc_response(0x43, ["P0171"]):
  // SID 0x43, count 1, then J2012 bytes 0x01 0x71.
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

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_read_stored_dtcs_parses_single_p0171);
  RUN_TEST(test_read_stored_dtcs_parses_empty_list);
  RUN_TEST(test_read_pending_dtcs_uses_mode_07_sids);
  RUN_TEST(test_read_stored_dtcs_fails_on_wrong_sid);
  RUN_TEST(test_read_stored_dtcs_fails_on_no_response);
  RUN_TEST(test_read_stored_dtcs_clamps_count_to_pairs_actually_in_frame);
  RUN_TEST(test_read_stored_dtcs_caps_at_kmaxdtcs);
  return UNITY_END();
}
```

- [ ] **Step 3: Run the suite to verify it fails**

Run (from `firmware/`): `pio test -e native -f test_kwp_dtc`
Expected: FAIL to compile — `DtcList`/`read_stored_dtcs` not declared.

- [ ] **Step 4: Full replacement `kline_kwp.h`** (adds the include, two public methods, one private helper; everything else byte-identical to Phase 1's version):

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

  // Mode 03 (stored) / Mode 07 (pending) DTC reads. Response format
  // expected: [SID_positive][count][2 bytes per DTC]. The explicit count
  // byte mirrors kl_sim/protocol.py's build_dtc_response and is [Guessing]
  // for the real SIM4LKE — real K-line J1979 Mode 03 typically has no
  // count byte (count implicit in frame length, possibly CAN-style framing
  // borrowed by the sim). Phase 3's car probe settles it; the fix lands
  // HERE and nothing downstream changes (everything speaks DtcList).
  // Truncates defensively: count is clamped to the pairs actually present
  // in the frame, then to kMaxDtcs.
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

- [ ] **Step 5: Full replacement `kline_kwp.cpp`** — Phase 1's file with `read_dtcs` + the two wrappers appended (everything above `read_stored_dtcs` is byte-identical to the current repo file; regenerate whole per project policy):

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
  if (response.data_len < 2 || response.data[0] != positive_sid) {
    return false;
  }
  // Clamp twice: to the pairs physically present in the frame (a frame
  // claiming more DTCs than it carries must never read unwritten bytes),
  // then to kMaxDtcs (defensive cap, see dtc_list.h).
  const uint8_t pairs_in_frame = static_cast<uint8_t>((response.data_len - 2) / 2);
  uint8_t n = response.data[1];
  if (n > pairs_in_frame) n = pairs_in_frame;
  if (n > kMaxDtcs) n = kMaxDtcs;
  out->count = n;
  for (uint8_t i = 0; i < kMaxDtcs; ++i) {
    out->codes[i] = 0;
  }
  for (uint8_t i = 0; i < n; ++i) {
    out->codes[i] = static_cast<uint16_t>((response.data[2 + 2 * i] << 8) | response.data[3 + 2 * i]);
  }
  return true;
}

bool KlineKwp::read_stored_dtcs(DtcList* out) { return read_dtcs(0x03, 0x43, out); }
bool KlineKwp::read_pending_dtcs(DtcList* out) { return read_dtcs(0x07, 0x47, out); }
```

- [ ] **Step 6: Run the new suite** — `pio test -e native -f test_kwp_dtc` → expected: 7/7 PASS.
- [ ] **Step 7: Run ALL native suites** (regression — this task touched a Phase 1 file): `pio test -e native` → expected: all suites PASS (42 pre-existing tests + 7 new).
- [ ] **Step 8: Leave for review** (no commit).

## Task 3: `latest_values` — the telemetry cache

**Files:**
- Create: `D:\C180 Project\firmware\src\latest_values.h`
- Create: `D:\C180 Project\firmware\src\latest_values.cpp`
- Create: `D:\C180 Project\firmware\test\test_latest_values\test_latest_values.cpp`

- [ ] **Step 1: Write the failing tests** — `firmware/test/test_latest_values/test_latest_values.cpp`:

```cpp
#include <unity.h>

#include "latest_values.h"

void setUp(void) {}
void tearDown(void) {}

void test_apply_available_sets_value_and_mask_bit(void) {
  LatestValues lv;
  latest_values_apply(&lv, Signal::RPM, 750.0f, true);
  TEST_ASSERT_EQUAL_FLOAT(750.0f, lv.values[0]);
  TEST_ASSERT_EQUAL_HEX32(0x00000001, lv.avail_mask);
}

void test_apply_unavailable_clears_value_and_mask_bit(void) {
  LatestValues lv;
  latest_values_apply(&lv, Signal::ECT, 90.0f, true);
  TEST_ASSERT_EQUAL_HEX32(1u << 11, lv.avail_mask);  // ECT is enum ordinal 11
  latest_values_apply(&lv, Signal::ECT, 0.0f, false);
  TEST_ASSERT_EQUAL_HEX32(0x00000000, lv.avail_mask);
  TEST_ASSERT_EQUAL_FLOAT(0.0f, lv.values[11]);
}

void test_apply_tracks_most_recent_poll_not_ever_succeeded(void) {
  // Spec 3.2: bit = MOST RECENT poll succeeded. A signal that answered
  // once then stopped answering must drop out of the mask.
  LatestValues lv;
  latest_values_apply(&lv, Signal::MAP, 98.0f, true);
  latest_values_apply(&lv, Signal::MAP, 0.0f, false);
  latest_values_apply(&lv, Signal::MAP, 99.0f, true);
  TEST_ASSERT_EQUAL_HEX32(1u << 7, lv.avail_mask);
  TEST_ASSERT_EQUAL_FLOAT(99.0f, lv.values[7]);
}

void test_apply_ignores_out_of_range_signal(void) {
  // Defensive: Signal::COUNT (or worse, a corrupted enum value) must not
  // index off the end of values[].
  LatestValues lv;
  latest_values_apply(&lv, Signal::COUNT, 1.0f, true);
  TEST_ASSERT_EQUAL_HEX32(0x00000000, lv.avail_mask);
}

void test_set_flag_sets_and_clears(void) {
  LatestValues lv;
  latest_values_set_flag(&lv, kFlagKlineConnected, true);
  TEST_ASSERT_EQUAL_HEX8(0x02, lv.flags);
  latest_values_set_flag(&lv, kFlagDemoMode, true);
  TEST_ASSERT_EQUAL_HEX8(0x03, lv.flags);
  latest_values_set_flag(&lv, kFlagKlineConnected, false);
  TEST_ASSERT_EQUAL_HEX8(0x01, lv.flags);
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_apply_available_sets_value_and_mask_bit);
  RUN_TEST(test_apply_unavailable_clears_value_and_mask_bit);
  RUN_TEST(test_apply_tracks_most_recent_poll_not_ever_succeeded);
  RUN_TEST(test_apply_ignores_out_of_range_signal);
  RUN_TEST(test_set_flag_sets_and_clears);
  return UNITY_END();
}
```

- [ ] **Step 2: Run to verify failure** — `pio test -e native -f test_latest_values` → expected: compile FAIL (`latest_values.h` missing).

- [ ] **Step 3: Write `latest_values.h`:**

```cpp
#pragma once

#include <cstdint>

#include "pid_schema.h"

// Telemetry frame flag bits — mirror-verbatim of docs/ble_protocol.md.
inline constexpr uint8_t kFlagDemoMode = 0x01;
inline constexpr uint8_t kFlagKlineConnected = 0x02;

// Latest-known value of every schema signal, fed by main.cpp from each
// PidScheduler reading (real build) or demo_feed (demo build), consumed by
// telemetry_frame's packer at ~2 Hz. Mask semantics (spec §3.2, normative):
// bit i = the MOST RECENT poll of Signal ordinal i succeeded. BATT_V_ADC's
// bit is never set on the real target in Phase 4 — the 100k/10k divider
// doesn't exist until Phase 2 and a floating GPIO34 must not masquerade as
// a battery reading.
struct LatestValues {
  float values[kSignalCount] = {0.0f};
  uint32_t avail_mask = 0;
  uint8_t flags = 0;
  uint16_t seq = 0;       // incremented by the notify path, not by apply()
  uint32_t uptime_ms = 0; // stamped by the notify path from millis()
};

// available=true: store value + set mask bit. available=false: zero the
// value + clear the bit. Out-of-range `signal` is ignored (defensive
// against a corrupted enum value — same posture as pid_schema_in_bounds).
void latest_values_apply(LatestValues* lv, Signal signal, float value, bool available);

void latest_values_set_flag(LatestValues* lv, uint8_t flag, bool on);
```

- [ ] **Step 4: Write `latest_values.cpp`:**

```cpp
#include "latest_values.h"

void latest_values_apply(LatestValues* lv, Signal signal, float value, bool available) {
  const size_t i = static_cast<size_t>(signal);
  if (i >= kSignalCount) {
    return;
  }
  if (available) {
    lv->values[i] = value;
    lv->avail_mask |= (1u << i);
  } else {
    lv->values[i] = 0.0f;
    lv->avail_mask &= ~(1u << i);
  }
}

void latest_values_set_flag(LatestValues* lv, uint8_t flag, bool on) {
  if (on) {
    lv->flags |= flag;
  } else {
    lv->flags &= static_cast<uint8_t>(~flag);
  }
}
```

- [ ] **Step 5: Run the suite** — `pio test -e native -f test_latest_values` → expected: 5/5 PASS.
- [ ] **Step 6: Leave for review** (no commit).

---

## Task 4: `telemetry_frame` — BLE frame packer/parser (golden vectors)

**Files:**
- Create: `D:\C180 Project\firmware\src\telemetry_frame.h`
- Create: `D:\C180 Project\firmware\src\telemetry_frame.cpp`
- Create: `D:\C180 Project\firmware\test\test_telemetry_frame\test_telemetry_frame.cpp`

- [ ] **Step 1: Write the failing tests.** The hex constants below are **copied from `docs/ble_protocol.md`** (Task 1) — mirror-verbatim, one string literal per doc line.

`firmware/test/test_telemetry_frame/test_telemetry_frame.cpp`:

```cpp
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
  RUN_TEST(test_pack_dtc_report_matches_golden_d1);
  RUN_TEST(test_pack_dtc_report_matches_golden_d2_empty);
  RUN_TEST(test_pack_dtc_report_returns_zero_when_cap_too_small);
  RUN_TEST(test_parse_control_decodes_golden_c1_time_sync);
  RUN_TEST(test_parse_control_rejects_wrong_length);
  RUN_TEST(test_parse_control_rejects_reserved_clear_dtc_opcode);
  return UNITY_END();
}
```

- [ ] **Step 2: Run to verify failure** — `pio test -e native -f test_telemetry_frame` → expected: compile FAIL (`telemetry_frame.h` missing).

- [ ] **Step 3: Write `telemetry_frame.h`:**

```cpp
#pragma once

#include <cstddef>
#include <cstdint>

#include "dtc_list.h"
#include "latest_values.h"

// BLE frame packer/parser — mirror-verbatim of docs/ble_protocol.md v1.
// Framework-agnostic (no Arduino headers): golden-vector-tested natively,
// consumed by ble_svc on the ESP32. The Kotlin mirror is FrameCodec.kt.

inline constexpr uint8_t kBleProtocolVersion = 0x01;
inline constexpr size_t kTelemetryFrameLen = 88;  // 12 header + 19*4 floats
inline constexpr size_t kControlFrameLen = 9;     // opcode + uint64 epoch_ms
inline constexpr size_t kDtcFrameMaxLen = 3 + 2 * kMaxDtcs + 2 * kMaxDtcs;  // 35
inline constexpr uint8_t kControlOpTimeSync = 0x01;
// 0x02 = CLEAR_DTC: reserved in the protocol doc, deliberately UNIMPLEMENTED
// (deferred out of Phase 4 — the only ECU write in the whole project).

void pack_telemetry(const LatestValues& lv, uint8_t out[kTelemetryFrameLen]);

// Returns bytes written (3 + 2*stored.count + 2*pending.count), or 0 if
// `cap` is too small.
size_t pack_dtc_report(const DtcList& stored, const DtcList& pending,
                       uint8_t* out, size_t cap);

struct ControlCommand {
  uint8_t opcode;
  uint64_t epoch_ms;
};

// True only for a well-formed 9-byte frame with a KNOWN opcode. Unknown
// opcodes (including reserved 0x02) return false — caller logs and ignores.
bool parse_control(const uint8_t* data, size_t len, ControlCommand* out);
```

- [ ] **Step 4: Write `telemetry_frame.cpp`:**

```cpp
#include "telemetry_frame.h"

#include <cstring>

namespace {

void put_u16(uint8_t* p, uint16_t v) {
  p[0] = static_cast<uint8_t>(v & 0xFF);
  p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
}

void put_u32(uint8_t* p, uint32_t v) {
  p[0] = static_cast<uint8_t>(v & 0xFF);
  p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
  p[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
  p[3] = static_cast<uint8_t>((v >> 24) & 0xFF);
}

void put_f32(uint8_t* p, float v) {
  uint32_t bits;
  static_assert(sizeof(bits) == sizeof(v), "float32 assumed");
  std::memcpy(&bits, &v, sizeof(bits));
  put_u32(p, bits);
}

}  // namespace

void pack_telemetry(const LatestValues& lv, uint8_t out[kTelemetryFrameLen]) {
  out[0] = kBleProtocolVersion;
  out[1] = lv.flags;
  put_u16(&out[2], lv.seq);
  put_u32(&out[4], lv.uptime_ms);
  put_u32(&out[8], lv.avail_mask);
  for (size_t i = 0; i < kSignalCount; ++i) {
    // Contract: masked-out slots transmit exactly 0.0f regardless of cache
    // contents (docs/ble_protocol.md — receiver must ignore them anyway,
    // but never leak stale values onto the air).
    const bool available = (lv.avail_mask >> i) & 1u;
    put_f32(&out[12 + 4 * i], available ? lv.values[i] : 0.0f);
  }
}

size_t pack_dtc_report(const DtcList& stored, const DtcList& pending,
                       uint8_t* out, size_t cap) {
  const size_t needed = 3 + 2 * static_cast<size_t>(stored.count) +
                        2 * static_cast<size_t>(pending.count);
  if (cap < needed) {
    return 0;
  }
  out[0] = kBleProtocolVersion;
  out[1] = stored.count;
  out[2] = pending.count;
  size_t o = 3;
  for (uint8_t i = 0; i < stored.count; ++i) {
    out[o++] = static_cast<uint8_t>(stored.codes[i] >> 8);  // J2012 byte1 first
    out[o++] = static_cast<uint8_t>(stored.codes[i] & 0xFF);
  }
  for (uint8_t i = 0; i < pending.count; ++i) {
    out[o++] = static_cast<uint8_t>(pending.codes[i] >> 8);
    out[o++] = static_cast<uint8_t>(pending.codes[i] & 0xFF);
  }
  return needed;
}

bool parse_control(const uint8_t* data, size_t len, ControlCommand* out) {
  if (len != kControlFrameLen || data[0] != kControlOpTimeSync) {
    return false;
  }
  out->opcode = data[0];
  uint64_t v = 0;
  for (int i = 0; i < 8; ++i) {
    v |= static_cast<uint64_t>(data[1 + i]) << (8 * i);
  }
  out->epoch_ms = v;
  return true;
}
```

- [ ] **Step 5: Run the suite** — `pio test -e native -f test_telemetry_frame` → expected: 9/9 PASS. If a golden assert fails, diff byte-by-byte against `docs/ble_protocol.md` — the doc is normative; fix the code (or, if the doc itself is arithmetically wrong, fix doc + both mirrors together and say so in the review notes). If `TEST_ASSERT_EQUAL_HEX64` turns out to be unavailable in this Unity build (64-bit support is auto-detected and [Likely] present on native gcc), assert the two 32-bit halves instead: `TEST_ASSERT_EQUAL_HEX32(0x01234567u, (uint32_t)(cmd.epoch_ms >> 32))` and `TEST_ASSERT_EQUAL_HEX32(0x89ABCDEFu, (uint32_t)cmd.epoch_ms)`.
- [ ] **Step 6: Leave for review** (no commit).

---

## Task 5: `demo_feed` — synthetic telemetry for the final session

**Files:**
- Create: `D:\C180 Project\firmware\src\demo_feed.h`
- Create: `D:\C180 Project\firmware\src\demo_feed.cpp`
- Create: `D:\C180 Project\firmware\test\test_demo_feed\test_demo_feed.cpp`

- [ ] **Step 1: Write the failing tests** — `firmware/test/test_demo_feed/test_demo_feed.cpp`:

```cpp
#include <unity.h>

#include "demo_feed.h"
#include "pid_schema.h"

void setUp(void) {}
void tearDown(void) {}

void test_mask_is_exactly_the_demo_mask_at_all_times(void) {
  const uint32_t samples[] = {0, 500, 1000, 15000, 31000, 35000, 39999, 45000, 300000, 3600000};
  for (uint32_t t : samples) {
    LatestValues lv;
    demo_fill(t, &lv);
    TEST_ASSERT_EQUAL_HEX32(kDemoAvailMask, lv.avail_mask);
  }
}

void test_all_masked_values_stay_inside_schema_bounds(void) {
  // Sweep a full cycle + warmup at 250 ms steps — every served value must
  // satisfy pid_schema's physical bounds (the same guard the pipeline
  // applies; a demo that emits impossible values would exercise the app
  // against garbage).
  for (uint32_t t = 0; t <= 360000; t += 250) {
    LatestValues lv;
    demo_fill(t, &lv);
    for (size_t i = 0; i < kSignalCount; ++i) {
      if ((lv.avail_mask >> i) & 1u) {
        TEST_ASSERT_TRUE_MESSAGE(
            pid_schema_in_bounds(static_cast<Signal>(i), lv.values[i]),
            "demo value out of schema bounds");
      }
    }
  }
}

void test_flags_say_demo_not_kline(void) {
  LatestValues lv;
  demo_fill(1000, &lv);
  TEST_ASSERT_EQUAL_HEX8(kFlagDemoMode, lv.flags);
}

void test_is_deterministic(void) {
  LatestValues a, b;
  demo_fill(123456, &a);
  demo_fill(123456, &b);
  TEST_ASSERT_EQUAL_HEX32(a.avail_mask, b.avail_mask);
  for (size_t i = 0; i < kSignalCount; ++i) {
    TEST_ASSERT_EQUAL_FLOAT(a.values[i], b.values[i]);
  }
}

void test_idle_phase_looks_like_warm_idle_after_warmup(void) {
  // t=310000: past the 300 s warm-up, 10 s into a cycle => idle phase.
  LatestValues lv;
  demo_fill(310000, &lv);
  TEST_ASSERT_TRUE(lv.values[0] >= 600.0f && lv.values[0] <= 900.0f);  // RPM
  TEST_ASSERT_EQUAL_FLOAT(0.0f, lv.values[1]);                        // SPEED
  TEST_ASSERT_EQUAL_FLOAT(90.0f, lv.values[11]);                      // ECT settled
  // MAF inside the w203 warm-idle band 3-5 g/s so the dashboard's banded
  // gauge shows green during the final session's idle phase.
  TEST_ASSERT_TRUE(lv.values[2] >= 3.0f && lv.values[2] <= 5.0f);
}

void test_cruise_phase_has_speed(void) {
  // t=345000: 45 s into a cycle => cruise phase.
  LatestValues lv;
  demo_fill(345000, &lv);
  TEST_ASSERT_EQUAL_FLOAT(90.0f, lv.values[1]);
}

void test_ect_warms_up_from_cold(void) {
  LatestValues lv;
  demo_fill(0, &lv);
  TEST_ASSERT_FLOAT_WITHIN(1.0f, 20.0f, lv.values[11]);
  demo_fill(400000, &lv);
  TEST_ASSERT_EQUAL_FLOAT(90.0f, lv.values[11]);
}

void test_demo_dtcs_serve_stored_p0171_pending_empty(void) {
  DtcList stored, pending;
  demo_dtcs(&stored, &pending);
  TEST_ASSERT_EQUAL_UINT8(1, stored.count);
  TEST_ASSERT_EQUAL_HEX16(0x0171, stored.codes[0]);
  TEST_ASSERT_EQUAL_UINT8(0, pending.count);
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_mask_is_exactly_the_demo_mask_at_all_times);
  RUN_TEST(test_all_masked_values_stay_inside_schema_bounds);
  RUN_TEST(test_flags_say_demo_not_kline);
  RUN_TEST(test_is_deterministic);
  RUN_TEST(test_idle_phase_looks_like_warm_idle_after_warmup);
  RUN_TEST(test_cruise_phase_has_speed);
  RUN_TEST(test_ect_warms_up_from_cold);
  RUN_TEST(test_demo_dtcs_serve_stored_p0171_pending_empty);
  return UNITY_END();
}
```

- [ ] **Step 2: Run to verify failure** — `pio test -e native -f test_demo_feed` → expected: compile FAIL.

- [ ] **Step 3: Write `demo_feed.h`:**

```cpp
#pragma once

#include <cstdint>

#include "dtc_list.h"
#include "latest_values.h"

// Availability mask demo mode serves: all F/M/S signals (enum ordinals
// 0-13) plus BATT_V_ADC (18); Legacy bits (14-17) stay clear, mirroring
// what a real healthy session shows. BATT_V_ADC is the one deliberate
// exception to real-target behavior: the real build can't serve it until
// Phase 2's voltage divider exists, but demo serves ~14.25 V so the
// battery gauge is exercised end-to-end at least once (spec §3.4).
inline constexpr uint32_t kDemoAvailMask = 0x0004'3FFF;

// Fills lv->values / avail_mask / flags for time t_ms with a deterministic
// plausible M271 cycle: 60 s period (0-30 s warm idle, 30-40 s rev sweep,
// 40-60 s 90 km/h cruise) + ECT warm-up 20→90 °C over the first ~300 s.
// Pure function of t_ms (natively testable). Does NOT touch lv->seq or
// lv->uptime_ms — that's the notify path's bookkeeping.
void demo_fill(uint32_t t_ms, LatestValues* lv);

// Demo DTC report: stored = {P0171}, pending = empty — proves the DTC
// screen over real BLE in the final session without a car.
void demo_dtcs(DtcList* stored, DtcList* pending);
```

- [ ] **Step 4: Write `demo_feed.cpp`:**

```cpp
#include "demo_feed.h"

namespace {

float warmup_ect(uint32_t t_ms) {
  // 20 °C -> 90 °C over ~300 s, then settled at 90 (thermostat 87 °C per
  // docs/w203_reference.md; settling slightly above it is normal).
  const float ect = 20.0f + static_cast<float>(t_ms) / 4286.0f;
  return ect > 90.0f ? 90.0f : ect;
}

// Narrowband upstream O2 switches rich/lean ~1 Hz in closed loop; the
// post-cat sensor sits steady on a healthy cat (docs/w203_reference.md's
// cat-snapshot criteria).
float o2_switching(uint32_t t_ms) { return ((t_ms / 500) % 2) ? 0.7f : 0.2f; }

}  // namespace

void demo_fill(uint32_t t_ms, LatestValues* lv) {
  const uint32_t phase_ms = t_ms % 60000;
  float rpm, speed, maf, throttle, load, map_kpa, timing, o2s1, fuel_status, stft;
  if (phase_ms < 30000) {
    // Warm idle: values chosen to sit INSIDE docs/w203_reference.md's
    // healthy bands so the final session's banded gauges read green.
    rpm = 750.0f + (((phase_ms / 1000) % 2) ? 30.0f : 0.0f);  // gentle idle wobble
    speed = 0.0f;
    maf = 4.0f;         // warm-idle band 3-5 g/s [Confirmed]
    throttle = 11.0f;
    load = 22.0f;
    map_kpa = 32.0f;    // idle manifold vacuum, ~32 kPa abs [Best estimate]
    timing = 12.0f;
    o2s1 = o2_switching(t_ms);
    fuel_status = 2.0f;  // closed loop
    stft = -1.5f;
  } else if (phase_ms < 40000) {
    // Rev sweep 750 -> 4500 -> 750 rpm (triangle over 10 s), brief boost.
    const float x = static_cast<float>(phase_ms - 30000) / 10000.0f;  // 0..1
    const float tri = x < 0.5f ? x * 2.0f : (1.0f - x) * 2.0f;        // 0..1..0
    rpm = 750.0f + tri * 3750.0f;
    speed = 0.0f;
    maf = 4.0f + tri * 56.0f;
    throttle = 11.0f + tri * 34.0f;
    load = 22.0f + tri * 48.0f;
    map_kpa = 32.0f + tri * 108.0f;  // peaks ~140 kPa abs ≈ 0.4 bar boost [Best estimate]
    timing = 12.0f + tri * 18.0f;
    o2s1 = 0.85f;        // enrichment
    fuel_status = 4.0f;  // open loop due to load
    stft = 0.0f;
  } else {
    // ~90 km/h cruise.
    rpm = 2600.0f;
    speed = 90.0f;
    maf = 18.0f;
    throttle = 18.0f;
    load = 45.0f;
    map_kpa = 95.0f;
    timing = 28.0f;
    o2s1 = o2_switching(t_ms);
    fuel_status = 2.0f;
    stft = 1.5f;
  }

  latest_values_apply(lv, Signal::RPM, rpm, true);
  latest_values_apply(lv, Signal::SPEED, speed, true);
  latest_values_apply(lv, Signal::MAF_GS, maf, true);
  latest_values_apply(lv, Signal::THROTTLE, throttle, true);
  latest_values_apply(lv, Signal::LOAD, load, true);
  latest_values_apply(lv, Signal::STFT1, stft, true);
  latest_values_apply(lv, Signal::LTFT1, 4.7f, true);  // inside +-10% band
  latest_values_apply(lv, Signal::MAP, map_kpa, true);
  latest_values_apply(lv, Signal::TIMING_ADV, timing, true);
  latest_values_apply(lv, Signal::O2_B1S1_V, o2s1, true);
  latest_values_apply(lv, Signal::O2_B1S2_V, 0.65f, true);  // steady post-cat
  latest_values_apply(lv, Signal::ECT, warmup_ect(t_ms), true);
  latest_values_apply(lv, Signal::IAT, 35.0f, true);
  latest_values_apply(lv, Signal::FUEL_STATUS, fuel_status, true);
  // Legacy PIDs: explicitly unavailable, exactly like the real 2004 ECU.
  latest_values_apply(lv, Signal::PEDAL_D, 0.0f, false);
  latest_values_apply(lv, Signal::PEDAL_E, 0.0f, false);
  latest_values_apply(lv, Signal::CMD_THROTTLE, 0.0f, false);
  latest_values_apply(lv, Signal::CTRL_MODULE_V, 0.0f, false);
  // The deliberate demo-only exception (see kDemoAvailMask comment).
  latest_values_apply(lv, Signal::BATT_V_ADC, 14.25f, true);

  latest_values_set_flag(lv, kFlagDemoMode, true);
  latest_values_set_flag(lv, kFlagKlineConnected, false);
}

void demo_dtcs(DtcList* stored, DtcList* pending) {
  *stored = DtcList{};
  *pending = DtcList{};
  stored->count = 1;
  stored->codes[0] = 0x0171;  // P0171 — matches kline_sim.py's fault-injected scenario
}
```

- [ ] **Step 5: Run the suite** — `pio test -e native -f test_demo_feed` → expected: 8/8 PASS.
- [ ] **Step 6: Leave for review** (no commit).

## Task 6: `dtc_code_to_string` + `ride_logger.write_header_line`

Two small extensions main.cpp needs: render a J2012 uint16 as "P0171" for
the ride CSV header, and append arbitrary `#`-prefixed lines to an open ride.

**Files:**
- Modify (full replacement): `D:\C180 Project\firmware\src\dtc_list.h`
- Create: `D:\C180 Project\firmware\src\dtc_list.cpp`
- Modify (full replacement): `D:\C180 Project\firmware\src\ride_logger.h`
- Modify (full replacement): `D:\C180 Project\firmware\src\ride_logger.cpp`
- Modify: `D:\C180 Project\firmware\test\test_kwp_dtc\test_kwp_dtc.cpp` (add 2 tests)
- Modify: `D:\C180 Project\firmware\test\test_ride_logger\test_ride_logger.cpp` (add 2 tests)

- [ ] **Step 1: Add the failing tests.** Append to `test_kwp_dtc.cpp` (before `main`, and add the two `RUN_TEST` lines):

```cpp
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
```

Append to `test_ride_logger.cpp` (before `main`, plus `RUN_TEST` lines — this suite already uses `FakeStorage` from `test/fake_storage.h`):

```cpp
void test_write_header_line_prefixes_hash(void) {
  FakeStorage storage;
  RideLogger logger(storage);
  TEST_ASSERT_TRUE(logger.start_ride("/r.csv", "fw", "init"));
  logger.write_header_line("dtc_stored=P0171");
  TEST_ASSERT_EQUAL_STRING("#dtc_stored=P0171", storage.lines.back().c_str());
}

void test_write_header_line_truncates_instead_of_overflowing(void) {
  FakeStorage storage;
  RideLogger logger(storage);
  TEST_ASSERT_TRUE(logger.start_ride("/r.csv", "fw", "init"));
  std::string long_text(200, 'x');
  logger.write_header_line(long_text.c_str());  // must not crash; snprintf truncates
  TEST_ASSERT_EQUAL_CHAR('#', storage.lines.back()[0]);
  TEST_ASSERT_TRUE(storage.lines.back().size() < 200);
}
```

- [ ] **Step 2: Run to verify failure** — `pio test -e native -f test_kwp_dtc` and `-f test_ride_logger` → expected: compile FAIL (new functions missing).

- [ ] **Step 3: Full replacement `dtc_list.h`:**

```cpp
#pragma once

#include <cstdint>

// Fixed-capacity DTC holder — no heap on the ESP32. 8 is a deliberate cap:
// [Best estimate] a real ECU rarely stores more, and 8 codes fit in one
// KWP frame (63-byte data limit) and keep the BLE DTC frame under any
// sane MTU (docs/ble_protocol.md).
inline constexpr uint8_t kMaxDtcs = 8;

struct DtcList {
  uint8_t count = 0;               // 0..kMaxDtcs
  uint16_t codes[kMaxDtcs] = {0};  // SAE J2012 2-byte values, (byte1<<8)|byte2
};

// Renders a J2012 code as text, e.g. 0x0171 -> "P0171". `out` must hold 6
// bytes (5 chars + NUL). Digits render as uppercase hex — J2012's four
// "digits" are 4-bit fields, and codes like P0A0B legitimately exist in
// later standards. Firmware-side mirror of the app's FrameCodec.decodeDtc.
void dtc_code_to_string(uint16_t code, char out[6]);
```

- [ ] **Step 4: Write `dtc_list.cpp`:**

```cpp
#include "dtc_list.h"

void dtc_code_to_string(uint16_t code, char out[6]) {
  static const char kLetters[4] = {'P', 'C', 'B', 'U'};
  static const char kHex[] = "0123456789ABCDEF";
  out[0] = kLetters[(code >> 14) & 0x3];
  out[1] = kHex[(code >> 12) & 0x3];
  out[2] = kHex[(code >> 8) & 0xF];
  out[3] = kHex[(code >> 4) & 0xF];
  out[4] = kHex[code & 0xF];
  out[5] = '\0';
}
```

- [ ] **Step 5: Full replacement `ride_logger.h`** (adds one method; rest identical):

```cpp
#pragma once

#include <cstdint>

#include "pid_scheduler.h"
#include "ride_storage.h"

const char* signal_name(Signal signal);

class RideLogger {
 public:
  explicit RideLogger(RideStorage& storage) : storage_(storage) {}

  bool start_ride(const char* filename, const char* fw_version, const char* init_mode);
  void log_reading(uint32_t t_ms, const PidScheduler::Reading& reading);

  // Appends "#<text>" to the open ride file. Used for the startup DTC
  // snapshot and BLE time-sync markers (docs/ble_protocol.md control
  // frame). Comment-prefixed like the start_ride header block, so any
  // CSV consumer that skips #-lines keeps working mid-file.
  void write_header_line(const char* text);

  void flush();
  void close_ride();

 private:
  RideStorage& storage_;
};
```

- [ ] **Step 6: Full replacement `ride_logger.cpp`** — current file plus this method appended after `log_reading` (everything else byte-identical to the repo file):

```cpp
void RideLogger::write_header_line(const char* text) {
  // 96 bytes covers the longest current caller (8 DTC codes joined with
  // commas = 8*6-1+11 chars ≈ 58) with margin; snprintf truncates safely.
  char line[96];
  snprintf(line, sizeof(line), "#%s", text);
  storage_.write_line(line);
}
```

- [ ] **Step 7: Run both suites** — `pio test -e native -f test_kwp_dtc` (9/9) and `-f test_ride_logger` (all pass, including Phase 1's existing tests).
- [ ] **Step 8: Leave for review** (no commit).

---

## Task 7: `ble_svc` (NimBLE wrapper) + `platformio.ini`

**Honest verification note:** BLE cannot be exercised natively or on the
bench without the final session. This task's gate is *compilation* of both
ESP32 envs + zero regression in native tests. The NimBLE API names below are
[Likely] for NimBLE-Arduino 2.x — if the pinned version's signatures differ,
fix the wrapper to match the installed version's headers (that is expected,
not a plan bug; the wrapper exists precisely to quarantine this).

**Files:**
- Create: `D:\C180 Project\firmware\src\ble_svc.h`
- Create: `D:\C180 Project\firmware\src\ble_svc.cpp`
- Modify (full replacement): `D:\C180 Project\firmware\platformio.ini`

- [ ] **Step 1: Full replacement `platformio.ini`:**

```ini
[env:esp32dev]
platform = espressif32
board = esp32dev
framework = arduino
monitor_speed = 115200
; framework-arduinoespressif32's own build script injects -std=gnu++11 after
; build_flags are applied, silently overriding a plain build_flags=-std=gnu++17
; (confirmed by a real `pio run -e esp32dev` build on 2026-07-09: without the
; unflag, pid_schema.h's constexpr/inline-variable code fails to compile).
build_unflags = -std=gnu++11
build_flags = -std=gnu++17
board_build.filesystem = littlefs
board_build.partitions = default.csv
; [Likely] NimBLE-Arduino 2.x is the current major; if PlatformIO resolves
; a different latest-2.x that renames an API used in ble_svc.cpp, adapt
; ble_svc.cpp to the installed headers (see Task 7's verification note).
lib_deps = h2zero/NimBLE-Arduino@^2.1.0

; Demo target for the final hardware session: bare ESP32 on USB power, no
; UART/K-line at all — demo_feed replaces the scheduler/KWP entirely
; (spec §3.6). Everything else inherited from esp32dev.
[env:esp32dev_demo]
extends = env:esp32dev
build_flags = ${env:esp32dev.build_flags} -DKL_DEMO_MODE

[env:native]
platform = native
test_framework = unity
; PlatformIO does NOT compile src/ into test binaries by default -- without
; this, every test suite fails to link with undefined references to the
; production code under test (found the first time `pio test -e native`
; actually ran, 2026-07-10).
test_build_src = yes
build_flags = -std=gnu++17 -I src
; Exclude Arduino/ESP32-only sources -- esp32_uart_transport.cpp includes
; <Arduino.h>/<HardwareSerial.h>, littlefs_storage.cpp includes <LittleFS.h>,
; and ble_svc.cpp includes <NimBLEDevice.h>, none of which exist for the
; native (host) platform. build_src_filter controls which .cpp files get
; compiled at all, not just which headers get pulled in.
build_src_filter = +<*> -<main.cpp> -<esp32_uart_transport.cpp> -<littlefs_storage.cpp> -<ble_svc.cpp>
```

- [ ] **Step 2: Write `ble_svc.h`:**

```cpp
#pragma once

#include <cstdint>

#include "dtc_list.h"
#include "latest_values.h"
#include "telemetry_frame.h"

// GATT identity — mirror-verbatim of docs/ble_protocol.md. Fixed forever.
inline constexpr const char* kBleDeviceName = "KompressorLink";
inline constexpr const char* kKlServiceUuid = "c1800001-4b4c-4d27-b946-c180c0deba5e";
inline constexpr const char* kTelemetryCharUuid = "c1800002-4b4c-4d27-b946-c180c0deba5e";
inline constexpr const char* kDtcCharUuid = "c1800003-4b4c-4d27-b946-c180c0deba5e";
inline constexpr const char* kControlCharUuid = "c1800004-4b4c-4d27-b946-c180c0deba5e";

// Thin ESP32-only NimBLE wrapper (spec §3.5): advertise, own the three
// characteristics + Device Information Service, notify from caller-supplied
// data, dispatch control writes. ZERO protocol logic — all bytes come from
// telemetry_frame. Compile-verified only (ble_svc.cpp is excluded from the
// native env); real-BLE behavior is proven in the final hardware session.
class BleSvc {
 public:
  using TimeSyncCallback = void (*)(uint64_t epoch_ms);

  // Init NimBLE, create service/characteristics/DIS, start advertising.
  void begin(const char* fw_version, TimeSyncCallback on_time_sync);

  // Packs lv (caller has already stamped seq/uptime_ms) and notifies if a
  // client is subscribed. Call at ~2 Hz.
  void notify_telemetry(const LatestValues& lv);

  // Packs the DTC report; updates the readable value; notifies only when
  // the packed bytes actually changed since the last call.
  void update_dtc(const DtcList& stored, const DtcList& pending);
};
```

- [ ] **Step 3: Write `ble_svc.cpp`:**

```cpp
#include "ble_svc.h"

#include <Arduino.h>
#include <NimBLEDevice.h>

#include <cstring>

// File-scope singletons: NimBLE objects are created once in begin() and
// live for the device's whole uptime (no teardown path — the ESP32 just
// powers off). API names below are [Likely] for NimBLE-Arduino 2.x — adapt
// to the pinned version's headers if a signature differs (Task 7 note).
namespace {
NimBLECharacteristic* g_telemetry_chr = nullptr;
NimBLECharacteristic* g_dtc_chr = nullptr;
BleSvc::TimeSyncCallback g_time_cb = nullptr;
uint8_t g_last_dtc[kDtcFrameMaxLen] = {0};
size_t g_last_dtc_len = 0;

class ControlCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* chr, NimBLEConnInfo& conn_info) override {
    NimBLEAttValue v = chr->getValue();
    ControlCommand cmd;
    if (parse_control(v.data(), v.length(), &cmd)) {
      if (g_time_cb != nullptr) {
        g_time_cb(cmd.epoch_ms);
      }
    } else {
      // Unknown opcode (incl. reserved 0x02 CLEAR_DTC) or malformed frame:
      // ignore, per docs/ble_protocol.md.
      Serial.println("ble_svc: ignored unknown/malformed control write");
    }
  }
};

ControlCallbacks g_control_callbacks;
}  // namespace

void BleSvc::begin(const char* fw_version, TimeSyncCallback on_time_sync) {
  g_time_cb = on_time_sync;

  NimBLEDevice::init(kBleDeviceName);
  NimBLEServer* server = NimBLEDevice::createServer();

  NimBLEService* svc = server->createService(kKlServiceUuid);
  g_telemetry_chr = svc->createCharacteristic(kTelemetryCharUuid, NIMBLE_PROPERTY::NOTIFY);
  g_dtc_chr = svc->createCharacteristic(kDtcCharUuid,
                                        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  NimBLECharacteristic* control =
      svc->createCharacteristic(kControlCharUuid, NIMBLE_PROPERTY::WRITE);
  control->setCallbacks(&g_control_callbacks);
  svc->start();

  // Standard Device Information Service: fw revision + manufacturer.
  NimBLEService* dis = server->createService("180A");
  dis->createCharacteristic("2A26", NIMBLE_PROPERTY::READ)->setValue(fw_version);
  dis->createCharacteristic("2A29", NIMBLE_PROPERTY::READ)->setValue(kBleDeviceName);
  dis->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(kKlServiceUuid);
  adv->start();
  Serial.println("ble_svc: advertising as KompressorLink");
}

void BleSvc::notify_telemetry(const LatestValues& lv) {
  if (g_telemetry_chr == nullptr) {
    return;
  }
  uint8_t frame[kTelemetryFrameLen];
  pack_telemetry(lv, frame);
  g_telemetry_chr->setValue(frame, kTelemetryFrameLen);
  if (g_telemetry_chr->getSubscribedCount() > 0) {
    g_telemetry_chr->notify();
  }
}

void BleSvc::update_dtc(const DtcList& stored, const DtcList& pending) {
  if (g_dtc_chr == nullptr) {
    return;
  }
  uint8_t frame[kDtcFrameMaxLen];
  const size_t n = pack_dtc_report(stored, pending, frame, sizeof(frame));
  if (n == 0) {
    return;  // can't happen while DtcList's kMaxDtcs invariant holds
  }
  const bool changed = (n != g_last_dtc_len) || (memcmp(frame, g_last_dtc, n) != 0);
  if (!changed) {
    return;
  }
  memcpy(g_last_dtc, frame, n);
  g_last_dtc_len = n;
  g_dtc_chr->setValue(frame, n);
  if (g_dtc_chr->getSubscribedCount() > 0) {
    g_dtc_chr->notify();
  }
}
```

- [ ] **Step 4: Verify native regression** — `pio test -e native` → all suites still pass (ble_svc.cpp is excluded; nothing else changed).
- [ ] **Step 5: Verify ESP32 compile** — `pio run -e esp32dev`. Expected: SUCCESS (first run downloads NimBLE-Arduino). This step does NOT verify BLE behavior — compilation only. If NimBLE API signatures don't match, fix `ble_svc.cpp` against the installed headers under `.pio/libdeps/esp32dev/NimBLE-Arduino/src/` and note the changes for review.
- [ ] **Step 6: Leave for review** (no commit).

---

## Task 8: `main.cpp` — wire it all together (real + demo builds)

**Files:**
- Modify (full replacement): `D:\C180 Project\firmware\src\main.cpp`

- [ ] **Step 1: Full replacement `main.cpp`:**

```cpp
#include <Arduino.h>

#include <sys/time.h>

#include "ble_svc.h"
#include "latest_values.h"
#include "version.h"

#ifdef KL_DEMO_MODE
#include "demo_feed.h"
#else
#include "esp32_uart_transport.h"
#include "kline_kwp.h"
#include "littlefs_storage.h"
#include "pid_scheduler.h"
#include "ride_logger.h"
#endif

namespace {

BleSvc ble;
LatestValues latest;
uint32_t last_notify_ms = 0;
constexpr uint32_t kNotifyIntervalMs = 500;  // ~2 Hz, per docs/ble_protocol.md

#ifndef KL_DEMO_MODE
Esp32UartTransport transport(Serial2);
KlineKwp kwp(transport);
PidScheduler scheduler(kwp);
LittleFsStorage storage;
RideLogger logger(storage);

uint32_t last_tester_present_ms = 0;
uint32_t last_flush_ms = 0;
uint32_t last_setup_retry_ms = 0;
uint32_t last_reinit_attempt_ms = 0;
uint32_t last_dtc_read_ms = 0;
constexpr uint32_t kTesterPresentIntervalMs = 2000;
constexpr uint32_t kFlushIntervalMs = 5000;
// How often to retry the whole StartCommunication+start_ride sequence
// while idle, and how often to retry a stuck link once active. Bench-test
// scoped: a human is expected to be watching Serial and can restart
// kline_sim.py or power-cycle if this drags on -- these intervals just
// mean the firmware itself keeps trying rather than going silent forever
// after one failed attempt at boot.
constexpr uint32_t kSetupRetryIntervalMs = 5000;
constexpr uint32_t kReinitRetryIntervalMs = 3000;
// [Best estimate] DTCs change rarely; one extra K-line request pair per
// minute is negligible inside the ~10 req/s budget (spec §3.6).
constexpr uint32_t kDtcReadIntervalMs = 60000;
bool ride_active = false;
DtcList stored_dtcs;
DtcList pending_dtcs;

// Reads stored+pending DTCs, pushes them to the BLE cache, and writes the
// startup-snapshot-lite header lines into the ride CSV (spec §3.6).
void read_and_publish_dtcs(bool write_csv_header) {
  const bool got_stored = kwp.read_stored_dtcs(&stored_dtcs);
  const bool got_pending = kwp.read_pending_dtcs(&pending_dtcs);
  if (!got_stored && !got_pending) {
    return;  // link hiccup — keep the previous cache, try again next interval
  }
  ble.update_dtc(stored_dtcs, pending_dtcs);
  if (!write_csv_header) {
    return;
  }
  char line[96];
  size_t o = snprintf(line, sizeof(line), "dtc_stored=");
  for (uint8_t i = 0; i < stored_dtcs.count && o + 7 < sizeof(line); ++i) {
    char code[6];
    dtc_code_to_string(stored_dtcs.codes[i], code);
    o += snprintf(line + o, sizeof(line) - o, "%s%s", i ? "," : "", code);
  }
  logger.write_header_line(line);
  o = snprintf(line, sizeof(line), "dtc_pending=");
  for (uint8_t i = 0; i < pending_dtcs.count && o + 7 < sizeof(line); ++i) {
    char code[6];
    dtc_code_to_string(pending_dtcs.codes[i], code);
    o += snprintf(line + o, sizeof(line) - o, "%s%s", i ? "," : "", code);
  }
  logger.write_header_line(line);
}

// Logical StartCommunication only (see kline_kwp.h) -- the physical
// fast-init/5-baud wake-pulse distinction is Phase 2/3 scope, once the
// L9637D exists. Shared by setup() and loop()'s idle-retry path so a
// failed boot-time attempt isn't a dead end.
bool try_start_ride() {
  if (!kwp.start_communication()) {
    return false;
  }
  Serial.println("StartCommunication OK");
  if (!logger.start_ride("/ride_phase4.csv", FW_VERSION, "logical-init")) {
    Serial.println("Failed to open ride file on LittleFS");
    return false;
  }
  Serial.println("Ride file opened, polling...");
  read_and_publish_dtcs(/*write_csv_header=*/true);  // startup-snapshot-lite
  return true;
}
#endif  // !KL_DEMO_MODE

void on_time_sync(uint64_t epoch_ms) {
  timeval tv;
  tv.tv_sec = static_cast<time_t>(epoch_ms / 1000);
  tv.tv_usec = static_cast<suseconds_t>((epoch_ms % 1000) * 1000);
  settimeofday(&tv, nullptr);
  Serial.print("time sync applied, epoch_s=");
  Serial.println(static_cast<unsigned long>(epoch_ms / 1000));
#ifndef KL_DEMO_MODE
  if (ride_active) {
    char marker[48];
    snprintf(marker, sizeof(marker), "time_sync=%lu:%llu",
             static_cast<unsigned long>(millis()),
             static_cast<unsigned long long>(epoch_ms));
    logger.write_header_line(marker);
  }
#endif
}

void notify_tick(uint32_t now) {
  if (now - last_notify_ms < kNotifyIntervalMs) {
    return;
  }
  last_notify_ms = now;
  ++latest.seq;
  latest.uptime_ms = now;
  ble.notify_telemetry(latest);
}

}  // namespace

#ifdef KL_DEMO_MODE

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.print("KompressorLink DEMO firmware boot, fw=");
  Serial.println(FW_VERSION);
  ble.begin(FW_VERSION, on_time_sync);
  DtcList stored, pending;
  demo_dtcs(&stored, &pending);
  ble.update_dtc(stored, pending);
}

void loop() {
  const uint32_t now = millis();
  demo_fill(now, &latest);
  notify_tick(now);
  delay(10);
}

#else  // real target: K-line polling + BLE together

void setup() {
  Serial.begin(115200);
  Serial2.begin(10400, SERIAL_8N1, /*RX=*/16, /*TX=*/17);
  delay(200);
  Serial.print("KompressorLink firmware boot, fw=");
  Serial.println(FW_VERSION);

  ble.begin(FW_VERSION, on_time_sync);

  ride_active = try_start_ride();
  if (!ride_active) {
    Serial.println("StartCommunication failed -- will keep retrying (check wiring / confirm kline_sim.py is running)");
  }
}

void loop() {
  const uint32_t now = millis();

  // kline_connected reflects live link state on every pass (spec §3.6).
  latest_values_set_flag(&latest, kFlagKlineConnected,
                         ride_active && !kwp.needs_reinit());
  notify_tick(now);

  if (!ride_active) {
    if (now - last_setup_retry_ms >= kSetupRetryIntervalMs) {
      last_setup_retry_ms = now;
      ride_active = try_start_ride();
    }
    delay(100);  // shorter than Phase 1's 1000 ms so BLE notify cadence holds while idle
    return;
  }

  PidScheduler::Reading reading;
  if (scheduler.tick(now, &reading)) {
    logger.log_reading(now, reading);
    latest_values_apply(&latest, reading.signal, reading.value, reading.available);
    Serial.print(now);
    Serial.print(",");
    Serial.print(signal_name(reading.signal));
    Serial.print(",");
    if (reading.available) {
      Serial.println(reading.value, 3);
    } else {
      Serial.println("(unavailable)");
    }
  } else {
    // Nothing due this iteration -- yield instead of busy-spinning at
    // full CPU until the next signal comes due.
    delay(1);
  }

  if (now - last_tester_present_ms >= kTesterPresentIntervalMs) {
    kwp.send_tester_present();
    last_tester_present_ms = now;
  }

  if (now - last_flush_ms >= kFlushIntervalMs) {
    logger.flush();
    last_flush_ms = now;
  }

  if (now - last_dtc_read_ms >= kDtcReadIntervalMs) {
    last_dtc_read_ms = now;
    read_and_publish_dtcs(/*write_csv_header=*/false);
  }

  if (kwp.needs_reinit() && now - last_reinit_attempt_ms >= kReinitRetryIntervalMs) {
    last_reinit_attempt_ms = now;
    Serial.print("3 consecutive timeouts -- re-running StartCommunication: ");
    Serial.println(kwp.start_communication() ? "OK" : "failed");
  }
}

#endif  // KL_DEMO_MODE
```

- [ ] **Step 2: Build the real target** — `pio run -e esp32dev` → expected: SUCCESS. Note RAM/Flash % in the review notes (spec risk: NimBLE footprint; Phase 1 baseline was RAM 6.6 % / Flash 24 %).
- [ ] **Step 3: Build the demo target** — `pio run -e esp32dev_demo` → expected: SUCCESS.
- [ ] **Step 4: Full native regression** — `pio test -e native` → expected: every suite passes (Phase 1's 42 + Tasks 2–6's new tests).
- [ ] **Step 5: Leave for review** (no commit). Firmware side is now complete pending the final hardware session (Task 21).

## Task 9: Probe the Android environment + record the headless invocation (AGENT — blocked on Task 0)

**Files:**
- Create: `D:\C180 Project\android\AGENT_ENV.md`

- [ ] **Step 1: Confirm Task 0 happened.** Check `Test-Path "D:\C180 Project\android\gradlew.bat"` and `Test-Path "$env:LOCALAPPDATA\Android\Sdk"`. If either is false, STOP this task and every later Android task; tell the user Task 0 (Android Studio install + first sync) is still pending. Firmware tasks are unaffected.
- [ ] **Step 2: Locate the JDK.** Probe in order: `$env:JAVA_HOME` (if ≥ 17), `C:\Program Files\Android\Android Studio\jbr` (Studio's bundled JBR — [Likely] location for a default install). Verify with `& "<jbr>\bin\java.exe" -version` → expect 17+.
- [ ] **Step 3: Verify a headless build works.** From `D:\C180 Project\android\` in PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"  # or the path found in Step 2
.\gradlew.bat :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. If Gradle can't find the SDK, check `android\local.properties` contains `sdk.dir=C:\\Users\\DELL\\AppData\\Local\\Android\\Sdk` (Studio writes this during Task 0; create it if missing).

- [ ] **Step 4: Verify headless unit tests run:** `.\gradlew.bat :app:testDebugUnitTest` → `BUILD SUCCESSFUL` (there are no unit tests yet — an empty test run passing is the success criterion).
- [ ] **Step 5: Write `android/AGENT_ENV.md`** recording: the JAVA_HOME that worked, the sdk.dir, the exact two commands above, any version bumps Task 0's sync applied (from the user's report), and the date. **Every later Android task's verification steps say "use the invocation recorded in AGENT_ENV.md" — this file is what they mean.**
- [ ] **Step 6: Leave for review** (no commit).

---

## Task 10: Gradle wiring + manifest (dependencies, permissions, service declarations)

**Files:**
- Modify (full replacement): `D:\C180 Project\android\gradle\libs.versions.toml`
- Modify (full replacement): `D:\C180 Project\android\app\build.gradle.kts`
- Modify (full replacement): `D:\C180 Project\android\app\src\main\AndroidManifest.xml`
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\KompressorLinkApp.kt`

**Version note:** all new version pins below are [Best estimate] as of plan
authoring. If Task 0's sync bumped AGP/Kotlin, keep the bumped values (do
not revert them) and align new entries if the sync complains; record any
deviation in `AGENT_ENV.md`.

- [ ] **Step 1: Full replacement `gradle/libs.versions.toml`:**

```toml
[versions]
agp = "8.7.2"
kotlin = "2.0.20"
coreKtx = "1.13.1"
lifecycleRuntimeKtx = "2.8.6"
activityCompose = "1.9.2"
composeBom = "2024.09.00"
navigationCompose = "2.8.2"
datastore = "1.1.1"
kotlinxSerializationJson = "1.7.3"
kotlinxCoroutines = "1.9.0"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-service = { group = "androidx.lifecycle", name = "lifecycle-service", version.ref = "lifecycleRuntimeKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: Full replacement `app/build.gradle.kts`:**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kompressorlink.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kompressorlink.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0-phase4"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 3: Full replacement `AndroidManifest.xml`.** Includes the two service declarations for classes that arrive in Task 20 — the manifest merger does not verify class existence at build time, and this keeps the manifest a single reviewed unit. (Consequence until Task 20 lands: don't let CDM events fire — they can't, nothing is associated yet.) The typed foreground-service permission is load-bearing: targetSdk 34+ throws `SecurityException` at `startForeground()` without it.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
    <uses-feature android:name="android.software.companion_device_setup" />

    <!-- Legacy BT permissions for API 26-30 (minSdk 26). -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <!-- API 31+ runtime permission for GATT connect. BLUETOOTH_SCAN is NOT
         requested: CompanionDeviceManager does the scanning during
         association, and presence observation is covered by the observe
         permission below. [Likely — revisit if association UI complains] -->
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE" />

    <application
        android:name=".KompressorLinkApp"
        android:label="KompressorLink"
        android:allowBackup="false"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".connection.ConnectionService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />

        <service
            android:name=".connection.KlCompanionService"
            android:exported="true"
            android:permission="android.permission.BIND_COMPANION_DEVICE_SERVICE">
            <intent-filter>
                <action android:name="android.companion.CompanionDeviceService" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

- [ ] **Step 4: Write `KompressorLinkApp.kt`** — the manual-DI root. `AppContainer` starts minimal and gains fields in Tasks 13/15/19/20 (each of those tasks shows its full replacement):

```kotlin
package com.kompressorlink.app

import android.app.Application

class KompressorLinkApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

// Manual DI for a 3-screen personal tool — no Hilt (spec §4). Grows as
// tasks land; ViewModels reach it via (application as KompressorLinkApp).
class AppContainer(private val app: Application)
```

- [ ] **Step 5: Build** — from `android/` with AGENT_ENV.md's invocation: `.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`. The first run downloads new dependencies; if a version pin fails to resolve, pick the nearest available version, note it in AGENT_ENV.md, and continue.
- [ ] **Step 6: Leave for review** (no commit).

---

## Task 11: Telemetry domain types (`Signal` mirror, snapshot, source interface)

**Files:**
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\telemetry\Signal.kt`
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\telemetry\TelemetrySnapshot.kt`
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\telemetry\DtcReport.kt`
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\telemetry\ConnectionState.kt`
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\telemetry\TelemetrySource.kt`
- Test: `D:\C180 Project\android\app\src\test\java\com\kompressorlink\app\telemetry\SignalSchemaTest.kt`
- Test: `D:\C180 Project\android\app\src\test\java\com\kompressorlink\app\telemetry\TelemetrySnapshotTest.kt`

- [ ] **Step 1: Write the failing tests.**

`SignalSchemaTest.kt`:

```kotlin
package com.kompressorlink.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalSchemaTest {
    @Test
    fun `has exactly 19 signals`() {
        assertEquals(SIGNAL_COUNT, Signal.entries.size)
        assertEquals(19, SIGNAL_COUNT)
    }

    @Test
    fun `ordinals mirror the firmware enum`() {
        // Spot checks against firmware/src/pid_schema.h (mirror-verbatim
        // discipline; a reorder on either side must fail here).
        assertEquals(0, Signal.RPM.ordinal)
        assertEquals(7, Signal.MAP.ordinal)
        assertEquals(11, Signal.ECT.ordinal)
        assertEquals(13, Signal.FUEL_STATUS.ordinal)
        assertEquals(18, Signal.BATT_V_ADC.ordinal)
    }
}
```

`TelemetrySnapshotTest.kt`:

```kotlin
package com.kompressorlink.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetrySnapshotTest {
    private fun snapshot(mask: Int, flags: Int = 0): TelemetrySnapshot {
        val values = FloatArray(SIGNAL_COUNT)
        values[Signal.RPM.ordinal] = 750f
        values[Signal.ECT.ordinal] = 90f
        return TelemetrySnapshot(values, mask, flags, seq = 1, uptimeMs = 1000L)
    }

    @Test
    fun `value returns reading only when mask bit set`() {
        val s = snapshot(mask = 1 shl Signal.RPM.ordinal)
        assertEquals(750f, s.value(Signal.RPM))
        // ECT has a (stale) value in the array but its bit is clear — the
        // Untested contract says that must surface as "no reading".
        assertNull(s.value(Signal.ECT))
        assertFalse(s.isAvailable(Signal.ECT))
    }

    @Test
    fun `flag helpers decode the protocol flag bits`() {
        assertTrue(snapshot(0, flags = 0x01).isDemo)
        assertFalse(snapshot(0, flags = 0x01).klineConnected)
        assertTrue(snapshot(0, flags = 0x02).klineConnected)
    }
}
```

- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :app:testDebugUnitTest` → compile FAIL (types missing).

- [ ] **Step 3: Write the five source files.**

`Signal.kt`:

```kotlin
package com.kompressorlink.app.telemetry

// Mirror-verbatim of firmware/src/pid_schema.h's Signal enum — ordinal ==
// avail_mask bit == value slot in the telemetry frame (docs/ble_protocol.md).
// A change on either side must update both + the protocol doc + the golden
// vectors, or SignalSchemaTest / the golden tests go red.
enum class Signal {
    RPM, SPEED, MAF_GS, THROTTLE, LOAD, STFT1, LTFT1, MAP, TIMING_ADV,
    O2_B1S1_V, O2_B1S2_V, ECT, IAT, FUEL_STATUS,
    PEDAL_D, PEDAL_E, CMD_THROTTLE, CTRL_MODULE_V, BATT_V_ADC,
}

const val SIGNAL_COUNT: Int = 19
```

`TelemetrySnapshot.kt`:

```kotlin
package com.kompressorlink.app.telemetry

// One decoded telemetry frame. `value()` is the Untested contract's
// enforcement point in the app: a masked-out signal has NO reading, ever —
// UI layers render "not provided", never 0.
class TelemetrySnapshot(
    val values: FloatArray,
    val availMask: Int,
    val flags: Int,
    val seq: Int,
    val uptimeMs: Long,
) {
    init {
        require(values.size == SIGNAL_COUNT) { "expected $SIGNAL_COUNT values, got ${values.size}" }
    }

    fun isAvailable(signal: Signal): Boolean = (availMask shr signal.ordinal) and 1 == 1

    fun value(signal: Signal): Float? =
        if (isAvailable(signal)) values[signal.ordinal] else null

    val isDemo: Boolean get() = flags and FLAG_DEMO_MODE != 0
    val klineConnected: Boolean get() = flags and FLAG_KLINE_CONNECTED != 0

    companion object {
        // Mirror-verbatim of docs/ble_protocol.md flag bits.
        const val FLAG_DEMO_MODE = 0x01
        const val FLAG_KLINE_CONNECTED = 0x02
    }
}
```

`DtcReport.kt`:

```kotlin
package com.kompressorlink.app.telemetry

data class DtcReport(
    val stored: List<String>,   // e.g. ["P0171"], decoded from SAE J2012 bytes
    val pending: List<String>,
)
```

`ConnectionState.kt`:

```kotlin
package com.kompressorlink.app.telemetry

// What the top-bar chip renders (spec §4.5) and what drives the foreground
// service notification text.
sealed interface ConnectionState {
    /** Real-BLE source selected but no CDM association exists yet. */
    data object NotAssociated : ConnectionState
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    /** Streaming. demo/klineConnected come from the frame's flag bits. */
    data class Ready(val demo: Boolean, val klineConnected: Boolean) : ConnectionState
    /** Frames arrive but with an unknown protocol_version — firmware/app mismatch. */
    data object ProtocolMismatch : ConnectionState
    /** A fake source is active (day-to-day development mode). */
    data class Simulated(val scenarioName: String) : ConnectionState
}
```

`TelemetrySource.kt`:

```kotlin
package com.kompressorlink.app.telemetry

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// The load-bearing seam (spec §4.1): ViewModels only ever see this.
// Implementations: FakeTelemetrySource (Task 13), BleTelemetrySource
// (Task 19), SourceSwitcher (Task 15, delegates to whichever is active).
interface TelemetrySource {
    val connectionState: StateFlow<ConnectionState>
    val telemetry: Flow<TelemetrySnapshot>
    val dtcReport: StateFlow<DtcReport?>

    /** No-op on fake sources; writes the control frame on BLE. */
    suspend fun sendTimeSync()
}
```

- [ ] **Step 4: Run the tests** — `.\gradlew.bat :app:testDebugUnitTest` → expected: BUILD SUCCESSFUL, 4 tests pass.
- [ ] **Step 5: Leave for review** (no commit).

---

## Task 12: `FrameCodec` — the Kotlin mirror of the BLE contract (golden vectors)

**Files:**
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\telemetry\ble\FrameCodec.kt`
- Test: `D:\C180 Project\android\app\src\test\java\com\kompressorlink\app\telemetry\ble\FrameCodecTest.kt`

- [ ] **Step 1: Write the failing tests.** Hex constants **copied from `docs/ble_protocol.md`**, one string per doc line:

```kotlin
package com.kompressorlink.app.telemetry.ble

import com.kompressorlink.app.telemetry.Signal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCodecTest {

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex.substring(2 * it, 2 * it + 2)).toInt(16)).toByte() }

    // Golden vector T1 — mirror-verbatim of docs/ble_protocol.md (one
    // string per doc line).
    private val t1Hex =
        "01023412a0860100ffff0700" +
            "00803b44" + "00000000" + "00008040" + "00004841" + "0000c841" + "000020c0" +
            "0000a040" + "0000c442" + "00002041" + "0000003f" + "0000403f" + "0000b442" +
            "00000c42" + "00000040" + "00007041" + "00007041" + "00004041" + "00006041" +
            "00006441"

    // Golden vector T2 — mirror-verbatim of docs/ble_protocol.md.
    private val t2Hex =
        "0101ffff0000000001080000" +
            "00004844" + "00000000" + "00000000" + "00000000" + "00000000" + "00000000" +
            "00000000" + "00000000" + "00000000" + "00000000" + "00000000" + "0000ae42" +
            "00000000" + "00000000" + "00000000" + "00000000" + "00000000" + "00000000" +
            "00000000"

    @Test
    fun `parses golden T1 fully populated frame`() {
        val s = FrameCodec.parseTelemetry(hexToBytes(t1Hex))!!
        assertEquals(0x1234, s.seq)
        assertEquals(100000L, s.uptimeMs)
        assertEquals(0x0007FFFF, s.availMask)
        assertTrue(s.klineConnected)
        assertFalse(s.isDemo)
        val expected = floatArrayOf(
            750f, 0f, 4f, 12.5f, 25f, -2.5f, 5f, 98f, 10f, 0.5f, 0.75f,
            90f, 35f, 2f, 15f, 15f, 12f, 14f, 14.25f,
        )
        for (sig in Signal.entries) {
            assertEquals("value of ${sig.name}", expected[sig.ordinal], s.value(sig))
        }
    }

    @Test
    fun `parses golden T2 sparse frame with mask semantics`() {
        val s = FrameCodec.parseTelemetry(hexToBytes(t2Hex))!!
        assertEquals(0xFFFF, s.seq)
        assertTrue(s.isDemo)
        assertEquals(800f, s.value(Signal.RPM))
        assertEquals(87f, s.value(Signal.ECT))
        assertNull(s.value(Signal.SPEED))       // masked out => no reading
        assertNull(s.value(Signal.BATT_V_ADC))  // Untested contract on the wire
    }

    @Test
    fun `rejects wrong protocol version`() {
        val bytes = hexToBytes(t1Hex)
        bytes[0] = 0x02
        assertNull(FrameCodec.parseTelemetry(bytes))
    }

    @Test
    fun `rejects wrong length`() {
        assertNull(FrameCodec.parseTelemetry(hexToBytes(t1Hex).copyOf(87)))
    }

    @Test
    fun `parses golden D1 dtc report`() {
        val r = FrameCodec.parseDtcReport(hexToBytes("0101000171"))!!
        assertEquals(listOf("P0171"), r.stored)
        assertTrue(r.pending.isEmpty())
    }

    @Test
    fun `parses golden D2 empty dtc report`() {
        val r = FrameCodec.parseDtcReport(hexToBytes("010000"))!!
        assertTrue(r.stored.isEmpty())
        assertTrue(r.pending.isEmpty())
    }

    @Test
    fun `rejects dtc report whose counts disagree with its length`() {
        // Claims 2 stored but carries bytes for 1.
        assertNull(FrameCodec.parseDtcReport(hexToBytes("0102000171")))
    }

    @Test
    fun `builds golden C1 time sync frame`() {
        assertArrayEquals(
            hexToBytes("01efcdab8967452301"),
            FrameCodec.buildTimeSync(0x0123456789ABCDEFL),
        )
    }

    @Test
    fun `decodes non-P letter dtc`() {
        // C0300: byte1=0x43 (letter C=01, digits 0,3), byte2=0x00 — mirrors
        // firmware test test_dtc_code_to_string_renders_c_letter_code.
        assertEquals("C0300", FrameCodec.decodeDtc(0x43, 0x00))
    }
}
```

- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :app:testDebugUnitTest` → compile FAIL (`FrameCodec` missing).

- [ ] **Step 3: Write `FrameCodec.kt`:**

```kotlin
package com.kompressorlink.app.telemetry.ble

import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.SIGNAL_COUNT
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Kotlin mirror of docs/ble_protocol.md v1 (the C++ mirror is
// firmware/src/telemetry_frame.h/.cpp). Golden-vector-tested; pure JVM, no
// Android imports — deliberately unit-testable without a device.
object FrameCodec {
    const val PROTOCOL_VERSION = 0x01
    const val TELEMETRY_FRAME_LEN = 88
    private const val OP_TIME_SYNC: Byte = 0x01

    /** Returns null for wrong length or unknown version — caller surfaces
     *  ConnectionState.ProtocolMismatch rather than mis-parsing. */
    fun parseTelemetry(bytes: ByteArray): TelemetrySnapshot? {
        if (bytes.size != TELEMETRY_FRAME_LEN) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.get().toInt() and 0xFF
        if (version != PROTOCOL_VERSION) return null
        val flags = buf.get().toInt() and 0xFF
        val seq = buf.short.toInt() and 0xFFFF
        val uptimeMs = buf.int.toLong() and 0xFFFFFFFFL
        val mask = buf.int
        val values = FloatArray(SIGNAL_COUNT) { buf.float }
        return TelemetrySnapshot(values, mask, flags, seq, uptimeMs)
    }

    fun parseDtcReport(bytes: ByteArray): DtcReport? {
        if (bytes.size < 3) return null
        if (bytes[0].toInt() and 0xFF != PROTOCOL_VERSION) return null
        val stored = bytes[1].toInt() and 0xFF
        val pending = bytes[2].toInt() and 0xFF
        if (bytes.size != 3 + 2 * (stored + pending)) return null
        fun codeAt(offset: Int) =
            decodeDtc(bytes[offset].toInt() and 0xFF, bytes[offset + 1].toInt() and 0xFF)
        return DtcReport(
            stored = (0 until stored).map { codeAt(3 + 2 * it) },
            pending = (0 until pending).map { codeAt(3 + 2 * stored + 2 * it) },
        )
    }

    fun buildTimeSync(epochMs: Long): ByteArray =
        ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .put(OP_TIME_SYNC)
            .putLong(epochMs)
            .array()

    /** SAE J2012 2-byte decode — the inverse of kl_sim/protocol.py's
     *  encode_dtc and the twin of firmware's dtc_code_to_string. The three
     *  4-bit "digits" render as uppercase hex (P0A0B-style codes exist). */
    fun decodeDtc(byte1: Int, byte2: Int): String {
        val letter = "PCBU"[(byte1 ushr 6) and 0x3]
        val d1 = (byte1 ushr 4) and 0x3
        val d2 = (byte1 and 0xF).toString(16).uppercase()
        val d3 = ((byte2 ushr 4) and 0xF).toString(16).uppercase()
        val d4 = (byte2 and 0xF).toString(16).uppercase()
        return "$letter$d1$d2$d3$d4"
    }
}
```

- [ ] **Step 4: Run the tests** — `.\gradlew.bat :app:testDebugUnitTest` → expected: all pass (Task 11's 4 + these 10). If a golden test fails, diff against `docs/ble_protocol.md` byte-by-byte — the doc is normative (same rule as firmware Task 4 Step 5).
- [ ] **Step 5: Leave for review** (no commit).

## Task 13: `FakeTelemetrySource` — the day-to-day development source

**Files:**
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\telemetry\FakeTelemetrySource.kt`
- Test: `D:\C180 Project\android\app\src\test\java\com\kompressorlink\app\telemetry\FakeTelemetrySourceTest.kt`

- [ ] **Step 1: Write the failing tests:**

```kotlin
package com.kompressorlink.app.telemetry

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTelemetrySourceTest {

    @Test
    fun `healthy scenario serves the demo mask and in-band values`() {
        val s = FakeTelemetrySource.snapshotAt(FakeScenario.HEALTHY, tMs = 1000L, seq = 2)
        assertEquals(FakeTelemetrySource.HEALTHY_MASK, s.availMask)
        assertTrue(s.value(Signal.MAF_GS)!! in 3f..5f)      // w203 warm-idle band
        assertTrue(s.value(Signal.LTFT1)!! in -10f..10f)
        assertEquals(14.25f, s.value(Signal.BATT_V_ADC))
        assertNull(s.value(Signal.PEDAL_D))                  // Legacy: never provided
    }

    @Test
    fun `fault scenario pushes LTFT out of band and reports P0171`() {
        val s = FakeTelemetrySource.snapshotAt(FakeScenario.FAULT, tMs = 0L, seq = 0)
        assertEquals(18f, s.value(Signal.LTFT1))
        val source = FakeTelemetrySource(FakeScenario.FAULT)
        assertEquals(DtcReport(listOf("P0171"), emptyList()), source.dtcReport.value)
    }

    @Test
    fun `sparse scenario masks out MAP and both O2 sensors with zeroed slots`() {
        val s = FakeTelemetrySource.snapshotAt(FakeScenario.SPARSE, tMs = 0L, seq = 0)
        assertFalse(s.isAvailable(Signal.MAP))
        assertFalse(s.isAvailable(Signal.O2_B1S1_V))
        assertFalse(s.isAvailable(Signal.O2_B1S2_V))
        assertNull(s.value(Signal.MAP))
        assertEquals(0f, s.values[Signal.MAP.ordinal])  // contract parity with firmware packer
        assertTrue(s.isAvailable(Signal.RPM))           // everything else still there
    }

    @Test
    fun `is deterministic in t`() {
        val a = FakeTelemetrySource.snapshotAt(FakeScenario.HEALTHY, 12345L, 7)
        val b = FakeTelemetrySource.snapshotAt(FakeScenario.HEALTHY, 12345L, 7)
        assertTrue(a.values.contentEquals(b.values))
        assertEquals(a.availMask, b.availMask)
    }

    @Test
    fun `flow ticks emit consecutive seq`() = runTest {
        val source = FakeTelemetrySource(FakeScenario.HEALTHY)
        val two = source.telemetry.take(2).toList()  // virtual time — no real 500 ms waits
        assertEquals(two[0].seq + 1, two[1].seq)
    }

    @Test
    fun `healthy and sparse report no dtcs`() {
        assertEquals(DtcReport(emptyList(), emptyList()),
                     FakeTelemetrySource(FakeScenario.HEALTHY).dtcReport.value)
        assertEquals(DtcReport(emptyList(), emptyList()),
                     FakeTelemetrySource(FakeScenario.SPARSE).dtcReport.value)
    }
}
```

- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat :app:testDebugUnitTest` → compile FAIL.

- [ ] **Step 3: Write `FakeTelemetrySource.kt`:**

```kotlin
package com.kompressorlink.app.telemetry

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

// The three simulated scenarios (spec §4.2). SPARSE rehearses a stingy
// SIM4LKE hiding MAP + both O2 sensors — exactly what Phase 3's probe
// might reveal (PLAN.md §10 risk 1).
enum class FakeScenario(val displayName: String) {
    HEALTHY("healthy"),
    FAULT("fault: P0171 + LTFT +18%"),
    SPARSE("sparse ECU"),
}

class FakeTelemetrySource(
    private val scenario: FakeScenario,
    private val tickMs: Long = 500L,  // matches the real device's 2 Hz notify cadence
) : TelemetrySource {

    override val connectionState: StateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.Simulated(scenario.displayName))

    override val dtcReport: StateFlow<DtcReport?> = MutableStateFlow(
        when (scenario) {
            FakeScenario.FAULT -> DtcReport(stored = listOf("P0171"), pending = emptyList())
            else -> DtcReport(stored = emptyList(), pending = emptyList())
        }
    )

    override val telemetry: Flow<TelemetrySnapshot> = flow {
        var seq = 0
        while (true) {
            emit(snapshotAt(scenario, tMs = seq * tickMs, seq = seq))
            seq = (seq + 1) and 0xFFFF
            delay(tickMs)
        }
    }

    override suspend fun sendTimeSync() {
        // No device clock to sync — deliberate no-op on fakes.
    }

    companion object {
        // Mirrors firmware demo_feed's kDemoAvailMask reasoning: all F/M/S
        // signals (bits 0-13) + BATT_V_ADC (18); Legacy bits stay clear.
        const val HEALTHY_MASK = 0x0004_3FFF
        val SPARSE_MASK = HEALTHY_MASK and
            (1 shl Signal.MAP.ordinal).inv() and
            (1 shl Signal.O2_B1S1_V.ordinal).inv() and
            (1 shl Signal.O2_B1S2_V.ordinal).inv()

        /** Deterministic warm-idle waveform — pure function of (scenario, t). */
        fun snapshotAt(scenario: FakeScenario, tMs: Long, seq: Int): TelemetrySnapshot {
            val v = FloatArray(SIGNAL_COUNT)
            v[Signal.RPM.ordinal] = if ((tMs / 1000) % 2 == 0L) 750f else 780f
            v[Signal.SPEED.ordinal] = 0f
            v[Signal.MAF_GS.ordinal] = 4f
            v[Signal.THROTTLE.ordinal] = 11f
            v[Signal.LOAD.ordinal] = 22f
            v[Signal.STFT1.ordinal] = if (scenario == FakeScenario.FAULT) 8f else -1.5f
            // FAULT: the classic M271 breather-leak signature — LTFT +18%
            // at warm idle (docs/w203_reference.md) — drives the dashboard's
            // out-of-band tinting + hint end-to-end.
            v[Signal.LTFT1.ordinal] = if (scenario == FakeScenario.FAULT) 18f else 4.7f
            v[Signal.MAP.ordinal] = 32f
            v[Signal.TIMING_ADV.ordinal] = 12f
            v[Signal.O2_B1S1_V.ordinal] = if ((tMs / 500) % 2 == 0L) 0.2f else 0.7f
            v[Signal.O2_B1S2_V.ordinal] = 0.65f
            v[Signal.ECT.ordinal] = 90f
            v[Signal.IAT.ordinal] = 35f
            v[Signal.FUEL_STATUS.ordinal] = 2f
            v[Signal.BATT_V_ADC.ordinal] = 14.25f

            val mask = if (scenario == FakeScenario.SPARSE) SPARSE_MASK else HEALTHY_MASK
            // Contract parity with the firmware packer: masked-out slots
            // carry exactly 0.0f.
            for (s in Signal.entries) {
                if ((mask shr s.ordinal) and 1 == 0) v[s.ordinal] = 0f
            }
            // flags = 0: this is an in-app simulation, not the device's demo
            // build — the connection chip gets its truth from
            // ConnectionState.Simulated, not from frame flags.
            return TelemetrySnapshot(v, mask, flags = 0, seq = seq, uptimeMs = tMs)
        }
    }
}
```

- [ ] **Step 4: Run the tests** — `.\gradlew.bat :app:testDebugUnitTest` → all pass.
- [ ] **Step 5: Leave for review** (no commit).

---

## Task 14: Reference JSONs (`w203_bands.json`, `w203_dtc.json`) + `ReferenceRepository`

Closes handover gap 3. Derived from `docs/w203_reference.md`; every entry
carries its confidence tag. Deliberate omissions (do NOT "fix" these):
**MAP has no band** (the reference boost figure is a WOT-peak value and
Phase 4 has no WOT-context gating — a always-on band would false-alarm at
idle); **O2 sensors have no numeric band** (the reference gives switching
behavior, not voltage limits); **cooling-system DTCs are omitted** (the
reference lists no concrete codes for that family — unknown codes render
raw, which is the honest fallback).

**Files:**
- Create: `D:\C180 Project\android\app\src\main\assets\w203_bands.json`
- Create: `D:\C180 Project\android\app\src\main\assets\w203_dtc.json`
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\reference\ReferenceRepository.kt`
- Test: `D:\C180 Project\android\app\src\test\java\com\kompressorlink\app\reference\ReferenceRepositoryTest.kt`

- [ ] **Step 1: Write `w203_bands.json`:**

```json
[
  {
    "signal": "MAF_GS", "context": "warm_idle", "lo": 3.0, "hi": 5.0,
    "unit": "g/s", "confidence": "Confirmed",
    "hint": "Warm-idle MAF outside 3–5 g/s: check intake/breather leaks first (the classic M271 fault)"
  },
  {
    "signal": "STFT1", "context": "always", "lo": -10.0, "hi": 10.0,
    "unit": "%", "confidence": "Confirmed",
    "hint": "Short-term trim beyond ±10%: active mixture correction — watch LTFT"
  },
  {
    "signal": "LTFT1", "context": "always", "lo": -10.0, "hi": 10.0,
    "unit": "%", "confidence": "Confirmed",
    "hint": "LTFT high at warm idle: classic M271 partial-load breather-hose leak — see guide §2 tree"
  },
  {
    "signal": "ECT", "context": "warm_idle", "lo": 87.0, "hi": 95.0,
    "unit": "°C", "confidence": "Best estimate",
    "hint": "Warm idle should settle 87–95 °C (thermostat opens at 87 °C)"
  },
  {
    "signal": "BATT_V_ADC", "context": "always", "lo": 13.8, "hi": 14.5,
    "unit": "V", "confidence": "Confirmed",
    "hint": "Engine-running voltage outside 13.8–14.5 V: charging-system check"
  }
]
```

- [ ] **Step 2: Write `w203_dtc.json`** (P0010–P0014 and P0300–P0304 families expanded to individual codes, per spec §4.7):

```json
[
  { "code": "P0170", "title": "Fuel trim malfunction (bank 1)",
    "likely_causes": ["Partial-load breather hoses (THE M271 fault)", "MAF sensor", "Fuel pressure"],
    "confidence": "Confirmed" },
  { "code": "P0171", "title": "System too lean (bank 1)",
    "likely_causes": ["Partial-load breather hoses (THE M271 fault)", "MAF sensor", "Fuel pressure"],
    "confidence": "Confirmed" },
  { "code": "P0016", "title": "Crank/cam correlation",
    "likely_causes": ["Timing chain stretch", "Cam magnet"],
    "confidence": "Confirmed" },
  { "code": "P0017", "title": "Crank/cam correlation (exhaust)",
    "likely_causes": ["Timing chain stretch", "Cam magnet"],
    "confidence": "Confirmed" },
  { "code": "P0010", "title": "Camshaft adjuster circuit (bank 1)",
    "likely_causes": ["Cam adjuster magnet — oil ingress"],
    "confidence": "Confirmed" },
  { "code": "P0011", "title": "Camshaft position — over-advanced (bank 1)",
    "likely_causes": ["Cam adjuster magnet — oil ingress"],
    "confidence": "Confirmed" },
  { "code": "P0012", "title": "Camshaft position — over-retarded (bank 1)",
    "likely_causes": ["Cam adjuster magnet — oil ingress"],
    "confidence": "Confirmed" },
  { "code": "P0013", "title": "Camshaft adjuster circuit (bank 1, exhaust)",
    "likely_causes": ["Cam adjuster magnet — oil ingress"],
    "confidence": "Confirmed" },
  { "code": "P0014", "title": "Camshaft position — over-advanced (bank 1, exhaust)",
    "likely_causes": ["Cam adjuster magnet — oil ingress"],
    "confidence": "Confirmed" },
  { "code": "P0300", "title": "Random/multiple misfire",
    "likely_causes": ["Ignition coils", "Spark plugs"],
    "confidence": "Confirmed",
    "note": "Correct plug: FR6MPP332 or NGK ILFR6A" },
  { "code": "P0301", "title": "Misfire cylinder 1",
    "likely_causes": ["Ignition coil", "Spark plug"],
    "confidence": "Confirmed",
    "note": "Correct plug: FR6MPP332 or NGK ILFR6A" },
  { "code": "P0302", "title": "Misfire cylinder 2",
    "likely_causes": ["Ignition coil", "Spark plug"],
    "confidence": "Confirmed",
    "note": "Correct plug: FR6MPP332 or NGK ILFR6A" },
  { "code": "P0303", "title": "Misfire cylinder 3",
    "likely_causes": ["Ignition coil", "Spark plug"],
    "confidence": "Confirmed",
    "note": "Correct plug: FR6MPP332 or NGK ILFR6A" },
  { "code": "P0304", "title": "Misfire cylinder 4",
    "likely_causes": ["Ignition coil", "Spark plug"],
    "confidence": "Confirmed",
    "note": "Correct plug: FR6MPP332 or NGK ILFR6A" },
  { "code": "P0420", "title": "Catalyst efficiency below threshold",
    "likely_causes": ["Verify with the in-app cat-health snapshot test before condemning the catalyst (guided test — Phase 6)"],
    "confidence": "Confirmed" }
]
```

- [ ] **Step 3: Write the failing tests:**

```kotlin
package com.kompressorlink.app.reference

import com.kompressorlink.app.telemetry.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReferenceRepositoryTest {

    // Gradle runs JVM unit tests with the module dir as CWD [Likely]; the
    // fallback covers a root-project CWD. If both miss, the failure is
    // loud and the fix is mechanical (adjust the relative path).
    private fun asset(name: String): String =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .first { it.exists() }
            .readText()

    private fun repo() = ReferenceRepository { asset(it) }

    @Test
    fun `bands parse and reference only real schema signals`() {
        val bands = repo().bands
        assertTrue(bands.isNotEmpty())
        val names = Signal.entries.map { it.name }.toSet()
        for (band in bands) {
            assertTrue("unknown signal ${band.signal}", band.signal in names)
            assertTrue(band.lo < band.hi)
            assertTrue(band.confidence.isNotBlank())
            assertTrue(band.hint.isNotBlank())
        }
    }

    @Test
    fun `every dashboard-banded signal from the reference doc is covered`() {
        // docs/w203_reference.md rows that map onto dashboard signals.
        val banded = repo().bands.map { it.signal }.toSet()
        assertEquals(setOf("MAF_GS", "STFT1", "LTFT1", "ECT", "BATT_V_ADC"), banded)
    }

    @Test
    fun `p0171 interpretation leads with breather hoses`() {
        val entry = repo().dtcFor("P0171")
        assertNotNull(entry)
        assertTrue(entry!!.likely_causes.first().contains("breather", ignoreCase = true))
    }

    @Test
    fun `unknown code has no entry`() {
        assertNull(repo().dtcFor("P9999"))
    }

    @Test
    fun `bandsFor filters by signal`() {
        val r = repo()
        assertEquals(1, r.bandsFor(Signal.MAF_GS).size)
        assertTrue(r.bandsFor(Signal.RPM).isEmpty())  // deliberately unbanded
    }
}
```

- [ ] **Step 4: Run to verify failure** — compile FAIL (`ReferenceRepository` missing).

- [ ] **Step 5: Write `ReferenceRepository.kt`:**

```kotlin
package com.kompressorlink.app.reference

import com.kompressorlink.app.telemetry.Signal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Healthy band for one signal in one context (spec §4.5). context is
// "always" or "warm_idle" — warm_idle bands only apply when the snapshot
// itself satisfies warm idle (DashboardViewModel decides that).
@Serializable
data class Band(
    val signal: String,
    val context: String,
    val lo: Float,
    val hi: Float,
    val unit: String,
    val confidence: String,
    val hint: String,
)

@Serializable
data class DtcEntry(
    val code: String,
    val title: String,
    val likely_causes: List<String>,
    val confidence: String,
    val note: String? = null,
)

object ReferenceParser {
    private val json = Json { ignoreUnknownKeys = true }
    fun parseBands(text: String): List<Band> = json.decodeFromString(text)
    fun parseDtcEntries(text: String): List<DtcEntry> = json.decodeFromString(text)
}

// loadAsset injected so JVM tests read straight from the filesystem while
// the app passes { context.assets.open(it).bufferedReader().readText() }.
class ReferenceRepository(private val loadAsset: (String) -> String) {
    val bands: List<Band> by lazy { ReferenceParser.parseBands(loadAsset("w203_bands.json")) }

    private val dtcEntries: Map<String, DtcEntry> by lazy {
        ReferenceParser.parseDtcEntries(loadAsset("w203_dtc.json")).associateBy { it.code }
    }

    fun bandsFor(signal: Signal): List<Band> = bands.filter { it.signal == signal.name }

    /** Null for codes with no W203-specific interpretation — the DTC screen
     *  then renders the raw code + an honest "no W203-specific entry". */
    fun dtcFor(code: String): DtcEntry? = dtcEntries[code]
}
```

- [ ] **Step 6: Run the tests** — all pass.
- [ ] **Step 7: Leave for review** (no commit).

---

## Task 15: `SourceSwitcher` + DataStore persistence + `AppContainer` wiring

**Files:**
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\telemetry\SourceSwitcher.kt`
- Modify (full replacement): `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\KompressorLinkApp.kt`
- Test: `D:\C180 Project\android\app\src\test\java\com\kompressorlink\app\telemetry\SourceSwitcherTest.kt`

- [ ] **Step 1: Write the failing tests:**

```kotlin
package com.kompressorlink.app.telemetry

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSwitcherTest {

    private class FakeStore : SourceChoiceStore {
        val flow = MutableStateFlow(SourceChoice.SIMULATED_HEALTHY)
        override val choice: Flow<SourceChoice> = flow
        override suspend fun set(choice: SourceChoice) { flow.value = choice }
    }

    private class StubSource(name: String) : TelemetrySource {
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Simulated(name))
        override val telemetry: Flow<TelemetrySnapshot> = emptyFlow()
        override val dtcReport = MutableStateFlow<DtcReport?>(null)
        var timeSyncCount = 0
        override suspend fun sendTimeSync() { timeSyncCount++ }
    }

    @Test
    fun `starts on the store's choice and switches when it changes`() = runTest {
        val store = FakeStore()
        val healthy = StubSource("healthy")
        val fault = StubSource("fault")
        val switcher = SourceSwitcher(backgroundScope, store) { choice ->
            if (choice == SourceChoice.SIMULATED_FAULT) fault else healthy
        }
        runCurrent()
        assertEquals(ConnectionState.Simulated("healthy"), switcher.connectionState.value)

        store.set(SourceChoice.SIMULATED_FAULT)
        runCurrent()
        assertEquals(ConnectionState.Simulated("fault"), switcher.connectionState.value)
    }

    @Test
    fun `routes sendTimeSync to the currently active source`() = runTest {
        val store = FakeStore()
        val healthy = StubSource("healthy")
        val fault = StubSource("fault")
        val switcher = SourceSwitcher(backgroundScope, store) { choice ->
            if (choice == SourceChoice.SIMULATED_FAULT) fault else healthy
        }
        runCurrent()
        switcher.sendTimeSync()
        store.set(SourceChoice.SIMULATED_FAULT)
        runCurrent()
        switcher.sendTimeSync()
        assertEquals(1, healthy.timeSyncCount)
        assertEquals(1, fault.timeSyncCount)
    }
}
```

- [ ] **Step 2: Run to verify failure** — compile FAIL.

- [ ] **Step 3: Write `SourceSwitcher.kt`:**

```kotlin
package com.kompressorlink.app.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// Which telemetry source drives the app. Persisted choice; default is
// SIMULATED_HEALTHY on a fresh install — there is no ESP32 until the
// final hardware session (spec §4.1).
enum class SourceChoice(val displayName: String) {
    SIMULATED_HEALTHY("Simulated: healthy"),
    SIMULATED_FAULT("Simulated: fault (P0171)"),
    SIMULATED_SPARSE("Simulated: sparse ECU"),
    REAL_BLE("KompressorLink device (BLE)"),
}

interface SourceChoiceStore {
    val choice: Flow<SourceChoice>
    suspend fun set(choice: SourceChoice)
}

// Itself a TelemetrySource: delegates every flow to whichever source the
// persisted choice selects. ViewModels depend on THIS and never notice a
// switch (spec §4.1).
@OptIn(ExperimentalCoroutinesApi::class)
class SourceSwitcher(
    scope: CoroutineScope,
    private val store: SourceChoiceStore,
    private val sourceFor: (SourceChoice) -> TelemetrySource,
) : TelemetrySource {

    private val active: StateFlow<TelemetrySource> = store.choice
        .map { sourceFor(it) }
        .stateIn(scope, SharingStarted.Eagerly, sourceFor(SourceChoice.SIMULATED_HEALTHY))

    override val connectionState: StateFlow<ConnectionState> = active
        .flatMapLatest { it.connectionState }
        .stateIn(scope, SharingStarted.Eagerly, active.value.connectionState.value)

    override val telemetry: Flow<TelemetrySnapshot> = active.flatMapLatest { it.telemetry }

    override val dtcReport: StateFlow<DtcReport?> = active
        .flatMapLatest { it.dtcReport }
        .stateIn(scope, SharingStarted.Eagerly, active.value.dtcReport.value)

    override suspend fun sendTimeSync() = active.value.sendTimeSync()
}
```

- [ ] **Step 4: Full replacement `KompressorLinkApp.kt`** (container now wires the store, three fakes, and an honest stand-in for REAL_BLE that Task 20 replaces with `BleTelemetrySource` — until an association exists, "Real BLE" truthfully reports NotAssociated):

```kotlin
package com.kompressorlink.app

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.FakeScenario
import com.kompressorlink.app.telemetry.FakeTelemetrySource
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.telemetry.SourceChoiceStore
import com.kompressorlink.app.telemetry.SourceSwitcher
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import com.kompressorlink.app.reference.ReferenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

private val Context.klDataStore: DataStore<Preferences> by preferencesDataStore(name = "kompressorlink")

class KompressorLinkApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class DataStoreSourceChoiceStore(
    private val dataStore: DataStore<Preferences>,
) : SourceChoiceStore {
    private val key = stringPreferencesKey("source_choice")

    override val choice: Flow<SourceChoice> = dataStore.data.map { prefs ->
        prefs[key]?.let { stored ->
            runCatching { SourceChoice.valueOf(stored) }.getOrNull()
        } ?: SourceChoice.SIMULATED_HEALTHY
    }

    override suspend fun set(choice: SourceChoice) {
        dataStore.edit { it[key] = choice.name }
    }
}

// REAL_BLE stand-in until Task 20 lands BleTelemetrySource: truthfully
// reports that no device association exists. Replaced, not extended.
private object NotAssociatedSource : TelemetrySource {
    override val connectionState: StateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.NotAssociated)
    override val telemetry: Flow<TelemetrySnapshot> = emptyFlow()
    override val dtcReport: StateFlow<DtcReport?> = MutableStateFlow(null)
    override suspend fun sendTimeSync() {}
}

// Manual DI for a 3-screen personal tool — no Hilt (spec §4).
class AppContainer(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val choiceStore: SourceChoiceStore = DataStoreSourceChoiceStore(app.klDataStore)

    val referenceRepository = ReferenceRepository { name ->
        app.assets.open(name).bufferedReader().readText()
    }

    private val fakes = mapOf(
        SourceChoice.SIMULATED_HEALTHY to FakeTelemetrySource(FakeScenario.HEALTHY),
        SourceChoice.SIMULATED_FAULT to FakeTelemetrySource(FakeScenario.FAULT),
        SourceChoice.SIMULATED_SPARSE to FakeTelemetrySource(FakeScenario.SPARSE),
    )

    val telemetrySource: SourceSwitcher = SourceSwitcher(scope, choiceStore) { choice ->
        fakes[choice] ?: NotAssociatedSource
    }
}
```

- [ ] **Step 5: Run all unit tests + build** — `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` → all pass, BUILD SUCCESSFUL.
- [ ] **Step 6: Leave for review** (no commit).

## Task 16: Dashboard — logic, ViewModel, UI, MainActivity shell

**Band→color rule (plan-fixed, the spec left the exact thresholds open):**
inside band = OK (green-ish); outside band by up to half the band's width =
AMBER; further = RED. `warm_idle` bands apply only when the snapshot itself
satisfies warm idle (RPM 600–900, SPEED = 0, ECT > 80 — PLAN.md §7's
definition); a signal with no applicable band renders NEUTRAL. Mask bit
clear renders UNAVAILABLE ("not provided by ECU") — never a value, never 0.

**Files:**
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\dashboard\DashboardLogic.kt`
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\dashboard\DashboardViewModel.kt`
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\dashboard\DashboardScreen.kt`
- Modify (full replacement): `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\MainActivity.kt`
- Test: `D:\C180 Project\android\app\src\test\java\com\kompressorlink\app\dashboard\DashboardLogicTest.kt`

- [ ] **Step 1: Write the failing tests** (all against the pure logic layer — composables are verified on the phone, honestly):

```kotlin
package com.kompressorlink.app.dashboard

import com.kompressorlink.app.reference.Band
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.FakeScenario
import com.kompressorlink.app.telemetry.FakeTelemetrySource
import com.kompressorlink.app.telemetry.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashboardLogicTest {

    private fun asset(name: String): String =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .first { it.exists() }
            .readText()

    private val refs = ReferenceRepository { asset(it) }
    private val band = Band("LTFT1", "always", -10f, 10f, "%", "Confirmed", "hint")

    @Test
    fun `level inside band is OK`() {
        assertEquals(GaugeLevel.OK, DashboardLogic.levelFor(5f, band))
        assertEquals(GaugeLevel.OK, DashboardLogic.levelFor(-10f, band))
    }

    @Test
    fun `level within half-width beyond band is AMBER, further is RED`() {
        // band -10..10, width 20, half-width 10 => amber to 20, red past it.
        assertEquals(GaugeLevel.AMBER, DashboardLogic.levelFor(18f, band))
        assertEquals(GaugeLevel.AMBER, DashboardLogic.levelFor(-15f, band))
        assertEquals(GaugeLevel.RED, DashboardLogic.levelFor(25f, band))
        assertEquals(GaugeLevel.RED, DashboardLogic.levelFor(-21f, band))
    }

    @Test
    fun `warm idle detected from the snapshot itself`() {
        val idle = FakeTelemetrySource.snapshotAt(FakeScenario.HEALTHY, 0L, 0)
        assertTrue(DashboardLogic.isWarmIdle(idle))
    }

    @Test
    fun `warm idle is false when a gating signal is unavailable`() {
        // SPARSE keeps RPM/SPEED/ECT, so build one manually with ECT masked.
        val base = FakeTelemetrySource.snapshotAt(FakeScenario.HEALTHY, 0L, 0)
        val noEct = com.kompressorlink.app.telemetry.TelemetrySnapshot(
            base.values, base.availMask and (1 shl Signal.ECT.ordinal).inv(),
            base.flags, base.seq, base.uptimeMs,
        )
        assertFalse(DashboardLogic.isWarmIdle(noEct))
    }

    @Test
    fun `fault scenario turns the LTFT gauge amber with the breather hint`() {
        val snapshot = FakeTelemetrySource.snapshotAt(FakeScenario.FAULT, 0L, 0)
        val gauge = DashboardLogic.gaugeFor(Signal.LTFT1, snapshot, refs, history = emptyList())
        assertEquals(GaugeLevel.AMBER, gauge.level)
        assertNotNull(gauge.hint)
        assertTrue(gauge.hint!!.contains("breather", ignoreCase = true))
    }

    @Test
    fun `sparse scenario renders MAP as unavailable, not zero`() {
        val snapshot = FakeTelemetrySource.snapshotAt(FakeScenario.SPARSE, 0L, 0)
        val gauge = DashboardLogic.gaugeFor(Signal.MAP, snapshot, refs, history = emptyList())
        assertEquals(GaugeLevel.UNAVAILABLE, gauge.level)
        assertEquals("—", gauge.valueText)
    }

    @Test
    fun `warm-idle band does not apply outside warm idle`() {
        // Healthy values but RPM forced to cruise: MAF's warm_idle band must
        // not judge a cruise MAF value.
        val base = FakeTelemetrySource.snapshotAt(FakeScenario.HEALTHY, 0L, 0)
        base.values[Signal.RPM.ordinal] = 2600f
        base.values[Signal.MAF_GS.ordinal] = 18f  // way over the 3-5 idle band
        val gauge = DashboardLogic.gaugeFor(Signal.MAF_GS, base, refs, history = emptyList())
        assertEquals(GaugeLevel.NEUTRAL, gauge.level)
        assertNull(gauge.hint)
    }

    @Test
    fun `dashboard signal list matches the spec section 4-5`() {
        assertEquals(
            listOf(Signal.RPM, Signal.MAF_GS, Signal.STFT1, Signal.LTFT1, Signal.ECT,
                   Signal.MAP, Signal.O2_B1S1_V, Signal.O2_B1S2_V, Signal.BATT_V_ADC,
                   Signal.TIMING_ADV),
            DashboardLogic.DASHBOARD_SIGNALS,
        )
    }
}
```

- [ ] **Step 2: Run to verify failure** — compile FAIL.

- [ ] **Step 3: Write `DashboardLogic.kt`:**

```kotlin
package com.kompressorlink.app.dashboard

import com.kompressorlink.app.reference.Band
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.Signal
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import java.util.Locale

enum class GaugeLevel { NEUTRAL, OK, AMBER, RED, UNAVAILABLE }

data class GaugeUiState(
    val signal: Signal,
    val title: String,
    val valueText: String,   // "—" when unavailable
    val unit: String,
    val level: GaugeLevel,
    val bandText: String?,   // e.g. "3–5 g/s", null when no band applies
    val hint: String?,       // reference hint, only when AMBER/RED
    val history: List<Float>,
)

// Pure functions — everything the DashboardLogicTest covers lives here,
// framework-free.
object DashboardLogic {

    // Spec §4.5's gauge list, in display order.
    val DASHBOARD_SIGNALS = listOf(
        Signal.RPM, Signal.MAF_GS, Signal.STFT1, Signal.LTFT1, Signal.ECT,
        Signal.MAP, Signal.O2_B1S1_V, Signal.O2_B1S2_V, Signal.BATT_V_ADC,
        Signal.TIMING_ADV,
    )

    private val TITLES = mapOf(
        Signal.RPM to "RPM", Signal.MAF_GS to "MAF", Signal.STFT1 to "STFT",
        Signal.LTFT1 to "LTFT", Signal.ECT to "Coolant", Signal.MAP to "MAP",
        Signal.O2_B1S1_V to "O2 pre-cat", Signal.O2_B1S2_V to "O2 post-cat",
        Signal.BATT_V_ADC to "Battery", Signal.TIMING_ADV to "Timing",
    )

    private val UNITS = mapOf(
        Signal.RPM to "rpm", Signal.MAF_GS to "g/s", Signal.STFT1 to "%",
        Signal.LTFT1 to "%", Signal.ECT to "°C", Signal.MAP to "kPa",
        Signal.O2_B1S1_V to "V", Signal.O2_B1S2_V to "V",
        Signal.BATT_V_ADC to "V", Signal.TIMING_ADV to "°",
    )

    /** PLAN.md §7's warm-idle definition, computed from the snapshot itself.
     *  Any gating signal being unavailable means "not warm idle" — never
     *  guess a context from missing data (Untested contract). */
    fun isWarmIdle(s: TelemetrySnapshot): Boolean {
        val rpm = s.value(Signal.RPM) ?: return false
        val speed = s.value(Signal.SPEED) ?: return false
        val ect = s.value(Signal.ECT) ?: return false
        return rpm in 600f..900f && speed == 0f && ect > 80f
    }

    /** Plan-fixed thresholds: in-band OK; ≤ half-width beyond AMBER; else RED. */
    fun levelFor(value: Float, band: Band): GaugeLevel {
        val halfWidth = (band.hi - band.lo) / 2f
        return when {
            value >= band.lo && value <= band.hi -> GaugeLevel.OK
            value < band.lo - halfWidth || value > band.hi + halfWidth -> GaugeLevel.RED
            else -> GaugeLevel.AMBER
        }
    }

    fun applicableBand(signal: Signal, snapshot: TelemetrySnapshot, refs: ReferenceRepository): Band? =
        refs.bandsFor(signal).firstOrNull { band ->
            band.context == "always" || (band.context == "warm_idle" && isWarmIdle(snapshot))
        }

    fun gaugeFor(
        signal: Signal,
        snapshot: TelemetrySnapshot,
        refs: ReferenceRepository,
        history: List<Float>,
    ): GaugeUiState {
        val title = TITLES.getValue(signal)
        val unit = UNITS.getValue(signal)
        val value = snapshot.value(signal)
            ?: return GaugeUiState(signal, title, "—", unit, GaugeLevel.UNAVAILABLE,
                                   bandText = null, hint = null, history = emptyList())
        val band = applicableBand(signal, snapshot, refs)
        val level = band?.let { levelFor(value, it) } ?: GaugeLevel.NEUTRAL
        val decimals = if (signal == Signal.O2_B1S1_V || signal == Signal.O2_B1S2_V) 2 else 1
        return GaugeUiState(
            signal = signal,
            title = title,
            valueText = String.format(Locale.US, "%.${decimals}f", value),
            unit = unit,
            level = level,
            bandText = band?.let {
                String.format(Locale.US, "%.1f–%.1f %s (%s)", it.lo, it.hi, it.unit, it.confidence)
            },
            hint = if (level == GaugeLevel.AMBER || level == GaugeLevel.RED) band?.hint else null,
            history = history,
        )
    }
}
```

- [ ] **Step 4: Run the logic tests** — `.\gradlew.bat :app:testDebugUnitTest` → all pass.

- [ ] **Step 5: Write `DashboardViewModel.kt`:**

```kotlin
package com.kompressorlink.app.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.TelemetrySource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val gauges: List<GaugeUiState>,
    val connectionLabel: String,
)

class DashboardViewModel(
    source: TelemetrySource,
    private val refs: ReferenceRepository,
) : ViewModel() {

    // Sparkline ring buffers: 120 samples at 2 Hz = last 60 s.
    private val histories =
        DashboardLogic.DASHBOARD_SIGNALS.associateWith { ArrayDeque<Float>(HISTORY_CAP) }

    val uiState: StateFlow<DashboardUiState> =
        combine(source.telemetry, source.connectionState) { snapshot, conn ->
            for (signal in DashboardLogic.DASHBOARD_SIGNALS) {
                val history = histories.getValue(signal)
                snapshot.value(signal)?.let {
                    if (history.size == HISTORY_CAP) history.removeFirst()
                    history.addLast(it)
                }
            }
            DashboardUiState(
                gauges = DashboardLogic.DASHBOARD_SIGNALS.map { signal ->
                    DashboardLogic.gaugeFor(signal, snapshot, refs,
                                            histories.getValue(signal).toList())
                },
                connectionLabel = label(conn, snapshot.isDemo, snapshot.klineConnected),
            )
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            DashboardUiState(emptyList(), "Waiting for data…"),
        )

    private fun label(conn: ConnectionState, demo: Boolean, kline: Boolean): String = when (conn) {
        is ConnectionState.Simulated -> "Simulated: ${conn.scenarioName}"
        is ConnectionState.Ready -> when {
            demo -> "Demo device"
            kline -> "Live · K-line up"
            else -> "Live · K-line down"
        }
        ConnectionState.Connecting -> "Connecting…"
        ConnectionState.Disconnected -> "Disconnected"
        ConnectionState.NotAssociated -> "No device associated"
        ConnectionState.ProtocolMismatch -> "Protocol mismatch — update app/firmware"
    }

    private companion object {
        const val HISTORY_CAP = 120
    }
}
```

- [ ] **Step 6: Write `DashboardScreen.kt`** (tiles in a 2-column grid; STFT+LTFT rendered side by side inside one "Fuel trims" card per spec §4.5):

```kotlin
package com.kompressorlink.app.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val state by viewModel.uiState.collectAsState()
    val trims = state.gauges.filter { it.title == "STFT" || it.title == "LTFT" }
    val others = state.gauges.filterNot { it.title == "STFT" || it.title == "LTFT" }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (trims.isNotEmpty()) {
            item(span = { GridItemSpan(2) }) { TrimsTile(trims) }
        }
        items(others) { gauge -> GaugeTile(gauge) }
    }
}

private fun levelColor(level: GaugeLevel): Color? = when (level) {
    GaugeLevel.AMBER -> Color(0xFFFFE0B2)
    GaugeLevel.RED -> Color(0xFFFFCDD2)
    else -> null
}

@Composable
private fun GaugeTile(gauge: GaugeUiState) {
    val tint = levelColor(gauge.level)
    Card(
        colors = tint?.let { CardDefaults.cardColors(containerColor = it) }
            ?: CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(gauge.title, style = MaterialTheme.typography.labelMedium)
            if (gauge.level == GaugeLevel.UNAVAILABLE) {
                // The Untested contract on screen: no value, an honest label.
                Text("—", style = MaterialTheme.typography.headlineMedium)
                Text("Not provided by ECU", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${gauge.valueText} ${gauge.unit}",
                     style = MaterialTheme.typography.headlineMedium)
                gauge.bandText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                gauge.hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error)
                }
                Sparkline(gauge.history)
            }
        }
    }
}

@Composable
private fun TrimsTile(trims: List<GaugeUiState>) {
    // One card, two columns — spec §4.5's "one trims tile".
    val worst = trims.maxByOrNull { it.level.ordinal }
    Card(
        colors = worst?.let { levelColor(it.level) }
            ?.let { CardDefaults.cardColors(containerColor = it) }
            ?: CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Fuel trims", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                trims.forEach { g ->
                    Column {
                        Text(g.title, style = MaterialTheme.typography.labelSmall)
                        Text(if (g.level == GaugeLevel.UNAVAILABLE) "—" else "${g.valueText} ${g.unit}",
                             style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            trims.firstNotNullOfOrNull { it.hint }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Sparkline(history: List<Float>) {
    if (history.size < 2) return
    val min = history.min()
    val max = history.max()
    val span = (max - min).takeIf { it > 0f } ?: 1f
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(28.dp).padding(top = 4.dp)) {
        val stepX = size.width / (history.size - 1)
        val path = Path()
        history.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height * (1f - (v - min) / span)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 3f))
    }
}
```

- [ ] **Step 7: Full replacement `MainActivity.kt`** (nav shell, connection chip, source menu, runtime permissions):

```kotlin
package com.kompressorlink.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kompressorlink.app.dashboard.DashboardScreen
import com.kompressorlink.app.dashboard.DashboardViewModel
import com.kompressorlink.app.dtc.DtcScreen
import com.kompressorlink.app.dtc.DtcViewModel
import com.kompressorlink.app.telemetry.SourceChoice
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as KompressorLinkApp).container
        setContent {
            MaterialTheme {
                AppUi(container)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppUi(container: AppContainer) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val choice by container.choiceStore.choice
        .collectAsState(initial = SourceChoice.SIMULATED_HEALTHY)
    var menuOpen by remember { mutableStateOf(false) }

    // Runtime permissions, requested once at startup. BLUETOOTH_CONNECT is
    // an API 31+ runtime permission; POST_NOTIFICATIONS is API 33+. Both
    // no-ops below those levels. The S23 FE (API 34) needs both.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results surface implicitly: BLE connect fails visibly if denied */ }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KompressorLink") },
                actions = {
                    // Connection chip + source menu (spec §4.5 / §4.1: the
                    // overflow toggle IS the fake-mode switch — no settings
                    // screen). Detailed connection state renders inside the
                    // dashboard (DashboardUiState.connectionLabel).
                    TextButton(onClick = { menuOpen = true }) {
                        Text(if (choice == SourceChoice.REAL_BLE) "BLE" else "SIM")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        SourceChoice.entries.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(if (c == choice) "✓ ${c.displayName}" else c.displayName) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { container.choiceStore.set(c) }
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            val backStack by navController.currentBackStackEntryAsState()
            val route = backStack?.destination?.route
            NavigationBar {
                NavigationBarItem(
                    selected = route == "dashboard",
                    onClick = { navController.navigate("dashboard") { launchSingleTop = true } },
                    icon = {}, label = { Text("Dashboard") },
                )
                NavigationBarItem(
                    selected = route == "dtc",
                    onClick = { navController.navigate("dtc") { launchSingleTop = true } },
                    icon = {}, label = { Text("DTCs") },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding),
        ) {
            composable("dashboard") {
                val vm: DashboardViewModel = viewModel(initializer = {
                    DashboardViewModel(container.telemetrySource, container.referenceRepository)
                })
                DashboardScreen(vm)
            }
            composable("dtc") {
                val vm: DtcViewModel = viewModel(initializer = {
                    DtcViewModel(container.telemetrySource, container.referenceRepository)
                })
                DtcScreen(vm)
            }
        }
    }
}
```

**Note:** `DtcViewModel`/`DtcScreen` are Task 17 — this file won't compile until Task 17 lands. Tasks 16 and 17 are one review unit for the build step; run Step 8 only after Task 17 Step 4.

- [ ] **Step 8 (after Task 17): Build + install milestone (user).** `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` then, with the S23 FE connected, `.\gradlew.bat :app:installDebug`. User verifies: dashboard renders live fake data; switching to "Simulated: fault" turns the trims card amber with the breather hint and the DTC tab shows P0171; "Simulated: sparse ECU" renders MAP and both O2 tiles as "Not provided by ECU"; "KompressorLink device (BLE)" shows "No device associated". Report back with observations (screenshots welcome).
- [ ] **Step 9: Leave for review** (no commit).

---

## Task 17: DTC screen (read-only)

**Files:**
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\dtc\DtcViewModel.kt`
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\dtc\DtcScreen.kt`
- Test: `D:\C180 Project\android\app\src\test\java\com\kompressorlink\app\dtc\DtcViewModelTest.kt`

- [ ] **Step 1: Write the failing tests:**

```kotlin
package com.kompressorlink.app.dtc

import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.DtcReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DtcViewModelTest {

    private fun asset(name: String): String =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
            .first { it.exists() }
            .readText()

    private val refs = ReferenceRepository { asset(it) }

    @Test
    fun `known code is enriched with the W203 interpretation`() {
        val state = DtcViewModel.build(DtcReport(listOf("P0171"), emptyList()), refs)
        val ui = state.stored.single()
        assertEquals("P0171", ui.code)
        assertEquals("System too lean (bank 1)", ui.title)
        assertTrue(ui.causes.first().contains("breather", ignoreCase = true))
    }

    @Test
    fun `unknown code renders raw with no interpretation`() {
        val state = DtcViewModel.build(DtcReport(listOf("P1234"), emptyList()), refs)
        val ui = state.stored.single()
        assertEquals("P1234", ui.code)
        assertNull(ui.title)
        assertTrue(ui.causes.isEmpty())
    }

    @Test
    fun `null report means no data yet`() {
        val state = DtcViewModel.build(null, refs)
        assertFalse(state.hasReport)
        assertTrue(state.stored.isEmpty())
    }

    @Test
    fun `empty report is a real (clean) report`() {
        val state = DtcViewModel.build(DtcReport(emptyList(), emptyList()), refs)
        assertTrue(state.hasReport)
        assertTrue(state.stored.isEmpty())
        assertTrue(state.pending.isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify failure** — compile FAIL.

- [ ] **Step 3: Write `DtcViewModel.kt`:**

```kotlin
package com.kompressorlink.app.dtc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.TelemetrySource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DtcUi(
    val code: String,
    val title: String?,          // null = no W203-specific interpretation
    val causes: List<String>,    // ordered likely-cause chain
    val note: String?,
    val confidence: String?,
)

data class DtcScreenState(
    val stored: List<DtcUi>,
    val pending: List<DtcUi>,
    val hasReport: Boolean,      // false until the first DTC frame arrives
)

class DtcViewModel(
    source: TelemetrySource,
    private val refs: ReferenceRepository,
) : ViewModel() {

    val uiState: StateFlow<DtcScreenState> = source.dtcReport
        .map { build(it, refs) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(null, refs))

    companion object {
        fun build(report: DtcReport?, refs: ReferenceRepository): DtcScreenState {
            if (report == null) return DtcScreenState(emptyList(), emptyList(), hasReport = false)
            fun enrich(code: String): DtcUi {
                val entry = refs.dtcFor(code)
                return DtcUi(
                    code = code,
                    title = entry?.title,
                    causes = entry?.likely_causes ?: emptyList(),
                    note = entry?.note,
                    confidence = entry?.confidence,
                )
            }
            return DtcScreenState(
                stored = report.stored.map(::enrich),
                pending = report.pending.map(::enrich),
                hasReport = true,
            )
        }
    }
}
```

- [ ] **Step 4: Write `DtcScreen.kt`:**

```kotlin
package com.kompressorlink.app.dtc

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

@Composable
fun DtcScreen(viewModel: DtcViewModel) {
    val state by viewModel.uiState.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item { Text("Stored codes", style = MaterialTheme.typography.titleMedium) }
        if (!state.hasReport) {
            item { Text("No DTC data received yet.", Modifier.padding(vertical = 8.dp)) }
        } else if (state.stored.isEmpty()) {
            item { Text("No stored codes.", Modifier.padding(vertical = 8.dp)) }
        } else {
            items(state.stored) { DtcCard(it) }
        }

        item {
            Text("Pending codes", style = MaterialTheme.typography.titleMedium,
                 modifier = Modifier.padding(top = 16.dp))
        }
        if (!state.hasReport) {
            item { Text("—", Modifier.padding(vertical = 8.dp)) }
        } else if (state.pending.isEmpty()) {
            item { Text("No pending codes.", Modifier.padding(vertical = 8.dp)) }
        } else {
            items(state.pending) { DtcCard(it) }
        }

        item {
            // Read-only by design: Mode 04 clear-DTC is the project's only
            // ECU write and is deferred out of Phase 4 (erases evidence,
            // resets readiness monitors — spec, scope decision 3).
            Text(
                "Clearing codes is not available in this version.",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

@Composable
private fun DtcCard(dtc: DtcUi) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (dtc.title != null) "${dtc.code} — ${dtc.title}" else dtc.code,
                style = MaterialTheme.typography.titleSmall,
            )
            if (dtc.causes.isEmpty()) {
                Text("No W203-specific interpretation for this code.",
                     style = MaterialTheme.typography.bodySmall)
            } else {
                dtc.causes.forEachIndexed { i, cause ->
                    Text("${i + 1}. $cause", style = MaterialTheme.typography.bodyMedium)
                }
            }
            dtc.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            }
            dtc.confidence?.let {
                Text("Confidence: $it", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
```

- [ ] **Step 5: Run everything** — `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` → all tests pass, build succeeds. Then do Task 16 Step 8 (the on-phone milestone).
- [ ] **Step 6: Leave for review** (no commit).

## Task 18: `GattLink` seam + `GattClient` (raw BluetoothGatt wrapper)

**Design note (locks in a spec-level decision):** the GATT connect flow here
is strictly linear — connect → MTU → discover → enable telemetry notify →
enable DTC notify → time sync — and the only Ready-state write is the rare
time sync. The state machine in `BleTelemetrySource` (Task 19) therefore IS
the operation serializer: it never issues a GATT call until the previous
one's event arrives. No separate op-queue class — one less moving part, same
guarantee Android needs (concurrent GATT ops get silently dropped
[Confirmed — documented Android behavior]).

**Honest verification note:** `GattClient` touches `BluetoothGatt` and can
only be *compiled* + reviewed until the final session; every decision it
makes is pushed into events consumed by `BleTelemetrySource`, which IS
unit-tested (Task 19) against a fake link.

**Files:**
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\telemetry\ble\GattLink.kt`
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\telemetry\ble\GattClient.kt`

- [ ] **Step 1: Write `GattLink.kt`** (the seam + UUID constants):

```kotlin
package com.kompressorlink.app.telemetry.ble

import kotlinx.coroutines.flow.SharedFlow

// GATT identity — mirror-verbatim of docs/ble_protocol.md (the firmware
// twin is firmware/src/ble_svc.h).
object KlUuids {
    const val SERVICE = "c1800001-4b4c-4d27-b946-c180c0deba5e"
    const val TELEMETRY = "c1800002-4b4c-4d27-b946-c180c0deba5e"
    const val DTC = "c1800003-4b4c-4d27-b946-c180c0deba5e"
    const val CONTROL = "c1800004-4b4c-4d27-b946-c180c0deba5e"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
}

sealed interface GattEvent {
    data object Connected : GattEvent
    data class Disconnected(val status: Int) : GattEvent
    data object ServicesDiscovered : GattEvent
    data class MtuChanged(val mtu: Int) : GattEvent
    data class NotifyEnabled(val charUuid: String) : GattEvent
    data class CharacteristicChanged(val charUuid: String, val value: ByteArray) : GattEvent
    data object WriteCompleted : GattEvent
    /** A GATT call could not even be issued (missing service/char, etc.). */
    data class OperationFailed(val what: String) : GattEvent
}

// Everything BleTelemetrySource needs from the platform, as events —
// GattClient implements it for real; tests drive a fake (spec §4.1's
// testability seam, one level deeper).
interface GattLink {
    val events: SharedFlow<GattEvent>
    fun connect()
    fun disconnect()
    fun requestMtu(mtu: Int)
    fun discoverServices()
    fun enableNotifications(charUuid: String)
    fun writeControl(value: ByteArray)
}
```

- [ ] **Step 2: Write `GattClient.kt`:**

```kotlin
package com.kompressorlink.app.telemetry.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

// The ~250-line raw wrapper the design chose over a BLE library (spec,
// decision 6). Every known sharp edge is explicit and tagged:
//  - TRANSPORT_LE on connect: without it some stacks (Samsung included)
//    may try a BR/EDR connection and fail with status 133. [Likely —
//    community-established; unverifiable before the final session]
//  - Status-133 retries live in BleTelemetrySource's backoff (every
//    Disconnected event, whatever its status, schedules a retry).
//  - Operation serialization is the state machine's job (Task 18 note).
// BLUETOOTH_CONNECT is requested by MainActivity before any of this runs;
// SecurityException here means the user denied it — surfaced as
// OperationFailed, not a crash.
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    private val macAddress: String,
) : GattLink {

    private val _events = MutableSharedFlow<GattEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<GattEvent> = _events

    private var gatt: BluetoothGatt? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _events.tryEmit(GattEvent.Connected)
            } else {
                g.close()
                gatt = null
                _events.tryEmit(GattEvent.Disconnected(status))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            _events.tryEmit(GattEvent.MtuChanged(mtu))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _events.tryEmit(GattEvent.ServicesDiscovered)
            } else {
                _events.tryEmit(GattEvent.OperationFailed("discoverServices status=$status"))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            _events.tryEmit(GattEvent.NotifyEnabled(d.characteristic.uuid.toString()))
        }

        // API 33+ overload (value passed directly).
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            _events.tryEmit(GattEvent.CharacteristicChanged(ch.uuid.toString(), value))
        }

        // Pre-33 overload — reads ch.value. Both overrides kept because
        // minSdk 26 still compiles this path.
        @Deprecated("pre-API33 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                _events.tryEmit(
                    GattEvent.CharacteristicChanged(ch.uuid.toString(), ch.value ?: ByteArray(0))
                )
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) {
            _events.tryEmit(GattEvent.WriteCompleted)
        }
    }

    override fun connect() {
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = manager.adapter.getRemoteDevice(macAddress)
            gatt = device.connectGatt(context, /*autoConnect=*/false, callback,
                                      BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            _events.tryEmit(GattEvent.OperationFailed("connect: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            _events.tryEmit(GattEvent.OperationFailed("bad address: ${e.message}"))
        }
    }

    override fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    override fun requestMtu(mtu: Int) {
        if (gatt?.requestMtu(mtu) != true) {
            _events.tryEmit(GattEvent.OperationFailed("requestMtu"))
        }
    }

    override fun discoverServices() {
        if (gatt?.discoverServices() != true) {
            _events.tryEmit(GattEvent.OperationFailed("discoverServices"))
        }
    }

    override fun enableNotifications(charUuid: String) {
        val g = gatt ?: return _events.let { it.tryEmit(GattEvent.OperationFailed("no gatt")); Unit }
        val ch = g.getService(UUID.fromString(KlUuids.SERVICE))
            ?.getCharacteristic(UUID.fromString(charUuid))
            ?: return _events.let {
                it.tryEmit(GattEvent.OperationFailed("char $charUuid missing")); Unit
            }
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(UUID.fromString(KlUuids.CCCD))
            ?: return _events.let {
                it.tryEmit(GattEvent.OperationFailed("CCCD missing on $charUuid")); Unit
            }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }
        if (!ok) _events.tryEmit(GattEvent.OperationFailed("writeDescriptor $charUuid"))
    }

    override fun writeControl(value: ByteArray) {
        val g = gatt ?: return
        val ch = g.getService(UUID.fromString(KlUuids.SERVICE))
            ?.getCharacteristic(UUID.fromString(KlUuids.CONTROL))
            ?: return _events.let {
                it.tryEmit(GattEvent.OperationFailed("control char missing")); Unit
            }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.value = value
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(ch)
            }
        }
        if (!ok) _events.tryEmit(GattEvent.OperationFailed("writeControl"))
    }
}
```

- [ ] **Step 3: Compile** — `.\gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL (unit tests unaffected; this task adds none — the logic layer is Task 19's).
- [ ] **Step 4: Leave for review** (no commit).

---

## Task 19: `BleTelemetrySource` — the tested state machine over `GattLink`

**Files:**
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\telemetry\ble\BleTelemetrySource.kt`
- Test: `D:\C180 Project\android\app\src\test\java\com\kompressorlink\app\telemetry\ble\BleTelemetrySourceTest.kt`

- [ ] **Step 1: Write the failing tests:**

```kotlin
package com.kompressorlink.app.telemetry.ble

import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleTelemetrySourceTest {

    private class FakeLink : GattLink {
        val calls = mutableListOf<String>()
        private val _events = MutableSharedFlow<GattEvent>(extraBufferCapacity = 32)
        override val events: SharedFlow<GattEvent> = _events
        fun emit(e: GattEvent) { check(_events.tryEmit(e)) }
        override fun connect() { calls += "connect" }
        override fun disconnect() { calls += "disconnect" }
        override fun requestMtu(mtu: Int) { calls += "mtu:$mtu" }
        override fun discoverServices() { calls += "discover" }
        override fun enableNotifications(charUuid: String) { calls += "notify:$charUuid" }
        override fun writeControl(value: ByteArray) { calls += "write:${value.size}" }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex.substring(2 * it, 2 * it + 2)).toInt(16)).toByte() }

    // Golden vector T2 (sparse, demo flag) — mirror-verbatim of
    // docs/ble_protocol.md.
    private val t2Hex =
        "0101ffff0000000001080000" +
            "00004844" + "00000000" + "00000000" + "00000000" + "00000000" + "00000000" +
            "00000000" + "00000000" + "00000000" + "00000000" + "00000000" + "0000ae42" +
            "00000000" + "00000000" + "00000000" + "00000000" + "00000000" + "00000000" +
            "00000000"

    private fun happyPathToReady(link: FakeLink) {
        link.emit(GattEvent.Connected)
        link.emit(GattEvent.MtuChanged(517))
        link.emit(GattEvent.ServicesDiscovered)
        link.emit(GattEvent.NotifyEnabled(KlUuids.TELEMETRY))
        link.emit(GattEvent.NotifyEnabled(KlUuids.DTC))
    }

    @Test
    fun `walks the linear connect flow to Ready and sends time sync`() = runTest {
        val link = FakeLink()
        val source = BleTelemetrySource(backgroundScope, link, now = { 42L })
        source.start()
        runCurrent()
        assertEquals(listOf("connect"), link.calls)
        assertEquals(ConnectionState.Connecting, source.connectionState.value)

        link.emit(GattEvent.Connected); runCurrent()
        assertEquals("mtu:517", link.calls.last())
        link.emit(GattEvent.MtuChanged(517)); runCurrent()
        assertEquals("discover", link.calls.last())
        link.emit(GattEvent.ServicesDiscovered); runCurrent()
        assertEquals("notify:${KlUuids.TELEMETRY}", link.calls.last())
        link.emit(GattEvent.NotifyEnabled(KlUuids.TELEMETRY)); runCurrent()
        assertEquals("notify:${KlUuids.DTC}", link.calls.last())
        link.emit(GattEvent.NotifyEnabled(KlUuids.DTC)); runCurrent()

        assertTrue(source.connectionState.value is ConnectionState.Ready)
        assertEquals("write:9", link.calls.last())  // time sync control frame
    }

    @Test
    fun `telemetry frames update state from the demo flag and emit snapshots`() = runTest {
        val link = FakeLink()
        val source = BleTelemetrySource(backgroundScope, link, now = { 42L })
        source.start(); runCurrent()
        happyPathToReady(link); runCurrent()

        var received: TelemetrySnapshot? = null
        val job = launch { source.telemetry.collect { received = it } }
        runCurrent()
        link.emit(GattEvent.CharacteristicChanged(KlUuids.TELEMETRY, hexToBytes(t2Hex)))
        runCurrent()
        assertEquals(0xFFFF, received!!.seq)
        assertEquals(ConnectionState.Ready(demo = true, klineConnected = false),
                     source.connectionState.value)
        job.cancel()
    }

    @Test
    fun `unknown protocol version surfaces ProtocolMismatch`() = runTest {
        val link = FakeLink()
        val source = BleTelemetrySource(backgroundScope, link, now = { 42L })
        source.start(); runCurrent()
        happyPathToReady(link); runCurrent()
        val bad = hexToBytes(t2Hex); bad[0] = 0x7F
        link.emit(GattEvent.CharacteristicChanged(KlUuids.TELEMETRY, bad)); runCurrent()
        assertEquals(ConnectionState.ProtocolMismatch, source.connectionState.value)
    }

    @Test
    fun `dtc frames update the report`() = runTest {
        val link = FakeLink()
        val source = BleTelemetrySource(backgroundScope, link, now = { 42L })
        source.start(); runCurrent()
        happyPathToReady(link); runCurrent()
        link.emit(GattEvent.CharacteristicChanged(KlUuids.DTC, hexToBytes("0101000171")))
        runCurrent()
        assertEquals(listOf("P0171"), source.dtcReport.value!!.stored)
    }

    @Test
    fun `mtu below 91 aborts the connection instead of truncating`() = runTest {
        val link = FakeLink()
        val source = BleTelemetrySource(backgroundScope, link, now = { 42L })
        source.start(); runCurrent()
        link.emit(GattEvent.Connected); runCurrent()
        link.emit(GattEvent.MtuChanged(23)); runCurrent()
        assertEquals("disconnect", link.calls.last())
    }

    @Test
    fun `disconnect schedules a backoff retry`() = runTest {
        val link = FakeLink()
        val source = BleTelemetrySource(backgroundScope, link, now = { 42L })
        source.start(); runCurrent()
        link.emit(GattEvent.Disconnected(status = 133)); runCurrent()
        assertEquals(ConnectionState.Disconnected, source.connectionState.value)
        val connectsBefore = link.calls.count { it == "connect" }
        advanceTimeBy(1_001); runCurrent()  // first backoff step is 1 s
        assertEquals(connectsBefore + 1, link.calls.count { it == "connect" })
    }
}
```

- [ ] **Step 2: Run to verify failure** — compile FAIL.

- [ ] **Step 3: Write `BleTelemetrySource.kt`:**

```kotlin
package com.kompressorlink.app.telemetry.ble

import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// The tested half of the raw-GATT decision: a strictly linear state machine
// over GattLink events (which is also the op serializer — Task 18 note).
// `now` injected for testable time-sync payloads.
class BleTelemetrySource(
    private val scope: CoroutineScope,
    private val link: GattLink,
    private val now: () -> Long = { System.currentTimeMillis() },
) : TelemetrySource {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableSharedFlow<TelemetrySnapshot>(extraBufferCapacity = 8)
    override val telemetry: Flow<TelemetrySnapshot> = _telemetry.asSharedFlow()

    private val _dtcReport = MutableStateFlow<DtcReport?>(null)
    override val dtcReport: StateFlow<DtcReport?> = _dtcReport.asStateFlow()

    private var retryAttempt = 0
    private var stopped = false

    fun start() {
        stopped = false
        scope.launch { link.events.collect { onEvent(it) } }
        beginConnect()
    }

    fun stop() {
        stopped = true
        link.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun beginConnect() {
        _connectionState.value = ConnectionState.Connecting
        link.connect()
    }

    private suspend fun onEvent(event: GattEvent) {
        when (event) {
            GattEvent.Connected -> link.requestMtu(REQUESTED_MTU)

            is GattEvent.MtuChanged ->
                if (event.mtu >= MIN_MTU) {
                    link.discoverServices()
                } else {
                    // Contract: never stream through an MTU that would
                    // truncate the 88-byte frame (docs/ble_protocol.md).
                    link.disconnect()
                }

            GattEvent.ServicesDiscovered -> link.enableNotifications(KlUuids.TELEMETRY)

            is GattEvent.NotifyEnabled -> when (event.charUuid) {
                KlUuids.TELEMETRY -> link.enableNotifications(KlUuids.DTC)
                KlUuids.DTC -> {
                    retryAttempt = 0
                    _connectionState.value =
                        ConnectionState.Ready(demo = false, klineConnected = false)
                    sendTimeSync()
                }
            }

            is GattEvent.CharacteristicChanged -> when (event.charUuid) {
                KlUuids.TELEMETRY -> {
                    val snapshot = FrameCodec.parseTelemetry(event.value)
                    if (snapshot == null) {
                        _connectionState.value = ConnectionState.ProtocolMismatch
                    } else {
                        _connectionState.value =
                            ConnectionState.Ready(snapshot.isDemo, snapshot.klineConnected)
                        _telemetry.emit(snapshot)
                    }
                }
                KlUuids.DTC -> FrameCodec.parseDtcReport(event.value)?.let { _dtcReport.value = it }
            }

            is GattEvent.Disconnected -> {
                _connectionState.value = ConnectionState.Disconnected
                if (!stopped) scheduleRetry()
            }

            GattEvent.WriteCompleted -> Unit

            is GattEvent.OperationFailed -> link.disconnect()  // funnel into the retry path
        }
    }

    private fun scheduleRetry() {
        val delayMs = RETRY_BACKOFF_MS[minOf(retryAttempt, RETRY_BACKOFF_MS.lastIndex)]
        retryAttempt++
        scope.launch {
            delay(delayMs)
            if (!stopped) beginConnect()
        }
    }

    override suspend fun sendTimeSync() {
        if (_connectionState.value is ConnectionState.Ready) {
            link.writeControl(FrameCodec.buildTimeSync(now()))
        }
    }

    private companion object {
        const val REQUESTED_MTU = 517
        const val MIN_MTU = 91  // 88-byte frame + 3-byte ATT header
        // 1 s, 2 s, 5 s, then steady 15 s [Best estimate — covers the
        // status-133 flake pattern without hammering the radio].
        val RETRY_BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 15_000)
    }
}
```

- [ ] **Step 4: Run the tests** — `.\gradlew.bat :app:testDebugUnitTest` → all pass (6 new).
- [ ] **Step 5: Leave for review** (no commit).

## Task 20: CDM auto-connect — `BleSession`, `CdmAssociator`, `KlCompanionService`, `ConnectionService`

**Honest verification note:** CDM association needs a real advertising BLE
peripheral, which does not exist until the final session. This task's gate:
unit tests for `BleSession` pass, everything compiles, code review. The
association/presence path is first *exercised* in Task 21.

**Files:**
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\telemetry\ble\BleSession.kt`
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\connection\CdmAssociator.kt`
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\connection\KlCompanionService.kt`
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\connection\ConnectionService.kt`
- Modify (full replacement): `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\KompressorLinkApp.kt`
- Modify (full replacement): `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\MainActivity.kt`
- Test: `D:\C180 Project\android\app\src\test\java\com\kompressorlink\app\telemetry\ble\BleSessionTest.kt`

- [ ] **Step 1: Write the failing tests:**

```kotlin
package com.kompressorlink.app.telemetry.ble

import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.SourceChoice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BleSessionTest {

    private class CountingLink : GattLink {
        var connects = 0
        override val events: SharedFlow<GattEvent> = MutableSharedFlow(extraBufferCapacity = 4)
        override fun connect() { connects++ }
        override fun disconnect() {}
        override fun requestMtu(mtu: Int) {}
        override fun discoverServices() {}
        override fun enableNotifications(charUuid: String) {}
        override fun writeControl(value: ByteArray) {}
    }

    @Test
    fun `stays idle while a simulated source is selected`() = runTest {
        val link = CountingLink()
        val choice = MutableStateFlow(SourceChoice.SIMULATED_HEALTHY)
        val mac = MutableStateFlow<String?>("AA:BB:CC:DD:EE:FF")
        val session = BleSession(backgroundScope, mac, choice) { link }
        runCurrent()
        assertEquals(ConnectionState.NotAssociated, session.connectionState.value)
        assertEquals(0, link.connects)
    }

    @Test
    fun `real ble with no association reports NotAssociated`() = runTest {
        val link = CountingLink()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        val mac = MutableStateFlow<String?>(null)
        val session = BleSession(backgroundScope, mac, choice) { link }
        runCurrent()
        assertEquals(ConnectionState.NotAssociated, session.connectionState.value)
        assertEquals(0, link.connects)
    }

    @Test
    fun `real ble with an association starts connecting`() = runTest {
        val link = CountingLink()
        val choice = MutableStateFlow(SourceChoice.REAL_BLE)
        val mac = MutableStateFlow<String?>("AA:BB:CC:DD:EE:FF")
        val session = BleSession(backgroundScope, mac, choice) { link }
        runCurrent()
        assertEquals(1, link.connects)
        assertEquals(ConnectionState.Connecting, session.connectionState.value)
    }
}
```

- [ ] **Step 2: Run to verify failure** — compile FAIL.

- [ ] **Step 3: Write `BleSession.kt`** (gates BLE activity on both "user selected Real BLE" and "an association exists" — no pointless retry loop draining the battery while simulating):

```kotlin
package com.kompressorlink.app.telemetry.ble

import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.DtcReport
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.telemetry.TelemetrySnapshot
import com.kompressorlink.app.telemetry.TelemetrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// The app's one long-lived REAL_BLE TelemetrySource. Creates/starts a
// BleTelemetrySource only while (choice == REAL_BLE && a MAC is stored);
// otherwise idles at NotAssociated. SourceSwitcher maps REAL_BLE to this.
@OptIn(ExperimentalCoroutinesApi::class)
class BleSession(
    scope: CoroutineScope,
    macFlow: Flow<String?>,
    choiceFlow: Flow<SourceChoice>,
    private val linkFactory: (String) -> GattLink,
) : TelemetrySource {

    private object Idle : TelemetrySource {
        override val connectionState: StateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.NotAssociated)
        override val telemetry: Flow<TelemetrySnapshot> = emptyFlow()
        override val dtcReport: StateFlow<DtcReport?> = MutableStateFlow(null)
        override suspend fun sendTimeSync() {}
    }

    private val inner = MutableStateFlow<TelemetrySource>(Idle)

    init {
        scope.launch {
            combine(macFlow, choiceFlow) { mac, choice ->
                if (choice == SourceChoice.REAL_BLE) mac else null
            }.distinctUntilChanged().collect { mac ->
                (inner.value as? BleTelemetrySource)?.stop()
                inner.value = if (mac == null) {
                    Idle
                } else {
                    BleTelemetrySource(scope, linkFactory(mac)).also { it.start() }
                }
            }
        }
    }

    override val connectionState: StateFlow<ConnectionState> = inner
        .flatMapLatest { it.connectionState }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionState.NotAssociated)

    override val telemetry: Flow<TelemetrySnapshot> = inner.flatMapLatest { it.telemetry }

    override val dtcReport: StateFlow<DtcReport?> = inner
        .flatMapLatest { it.dtcReport }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun sendTimeSync() = inner.value.sendTimeSync()
}
```

- [ ] **Step 4: Write `CdmAssociator.kt`:**

```kotlin
package com.kompressorlink.app.connection

import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import com.kompressorlink.app.telemetry.ble.KlUuids

// One-time CDM association (PLAN.md §6.1's sanctioned auto-connect path).
// API 33+ only: the S23 FE is API 34; older devices get a toast from
// MainActivity instead — the manual-connect fallback is explicitly not a
// Phase 4 deliverable (spec §4.4).
class CdmAssociator(
    private val activity: ComponentActivity,
    private val onAssociated: (String) -> Unit,
) {
    // Must be registered before the activity is RESUMED — construct this
    // object in MainActivity.onCreate.
    private val chooserLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* the MAC arrives via onAssociationCreated, not this result */ }

    @RequiresApi(33)
    fun associate() {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java)
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(KlUuids.SERVICE))
            .build()
        val deviceFilter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(scanFilter)
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(true)
            .build()
        cdm.associate(request, activity.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                chooserLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                val mac = associationInfo.deviceMacAddress?.toString()?.uppercase()
                if (mac == null) {
                    Log.w(TAG, "association created without a MAC address")
                    return
                }
                // Presence observation: after this, KlCompanionService gets
                // onDeviceAppeared/onDeviceDisappeared. The String overload
                // is the API 31+ path [Likely deprecated in favor of a
                // request-object API in newer SDKs; functional at 34/35].
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(mac)
                onAssociated(mac)
            }

            override fun onFailure(error: CharSequence?) {
                Log.w(TAG, "association failed: $error")
            }
        })
    }

    private companion object {
        const val TAG = "CdmAssociator"
    }
}
```

- [ ] **Step 5: Write `KlCompanionService.kt`:**

```kotlin
package com.kompressorlink.app.connection

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import androidx.annotation.RequiresApi

// Receives CDM presence events (declared in the manifest with
// BIND_COMPANION_DEVICE_SERVICE). This is the cold-start entry point:
// ignition on -> ESP32 advertises -> onDeviceAppeared -> foreground
// ConnectionService, no manual app launch (spec §4.4, PLAN.md §6.1).
@RequiresApi(31)
class KlCompanionService : CompanionDeviceService() {

    // API 33+ overloads.
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        ConnectionService.start(this, appeared = true)
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        ConnectionService.start(this, appeared = false)
    }

    // API 31-32 String overloads (deprecated in 33, still dispatched there).
    @Deprecated("API 31-32 path")
    override fun onDeviceAppeared(address: String) {
        ConnectionService.start(this, appeared = true)
    }

    @Deprecated("API 31-32 path")
    override fun onDeviceDisappeared(address: String) {
        ConnectionService.start(this, appeared = false)
    }
}
```

- [ ] **Step 6: Write `ConnectionService.kt`:**

```kotlin
package com.kompressorlink.app.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kompressorlink.app.KompressorLinkApp
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.SourceChoice
import kotlinx.coroutines.launch

// Foreground service holding the app alive while the device is in range.
// Connection logic itself lives in BleSession (always running, but gated
// on choice+association) — this service's jobs are the foreground
// notification and flipping the source to REAL_BLE on appearance.
class ConnectionService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_APPEARED -> onAppeared()
            ACTION_DISAPPEARED -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun onAppeared() {
        createChannel()
        // targetSdk 34: the typed startForeground overload + the
        // FOREGROUND_SERVICE_CONNECTED_DEVICE manifest permission are both
        // REQUIRED — missing either is a SecurityException at runtime.
        val notification = buildNotification("Device in range — connecting…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val container = (application as KompressorLinkApp).container
        // The device physically appeared => live data is what the user
        // wants: flip the source so BleSession connects (cold-start gate).
        lifecycleScope.launch { container.choiceStore.set(SourceChoice.REAL_BLE) }
        lifecycleScope.launch {
            container.bleSession.connectionState.collect { state ->
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(stateText(state)))
            }
        }
    }

    private fun stateText(state: ConnectionState): String = when (state) {
        is ConnectionState.Ready ->
            if (state.demo) "Streaming (demo device)" else "Streaming live data"
        ConnectionState.Connecting -> "Connecting…"
        ConnectionState.Disconnected -> "Disconnected — retrying"
        ConnectionState.NotAssociated -> "No device associated"
        ConnectionState.ProtocolMismatch -> "Protocol mismatch — update app/firmware"
        is ConnectionState.Simulated -> "Simulated data active"
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KompressorLink")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "kl_connection"
        private const val NOTIFICATION_ID = 1

        const val ACTION_APPEARED = "com.kompressorlink.app.DEVICE_APPEARED"
        const val ACTION_DISAPPEARED = "com.kompressorlink.app.DEVICE_DISAPPEARED"

        fun start(context: Context, appeared: Boolean) {
            val intent = Intent(context, ConnectionService::class.java)
                .setAction(if (appeared) ACTION_APPEARED else ACTION_DISAPPEARED)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
```

- [ ] **Step 7: Full replacement `KompressorLinkApp.kt`** (adds `AssociationStore` + `bleSession`; REAL_BLE now maps to the real session — `NotAssociatedSource` is gone, `BleSession.Idle` took its job):

```kotlin
package com.kompressorlink.app

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kompressorlink.app.reference.ReferenceRepository
import com.kompressorlink.app.telemetry.FakeScenario
import com.kompressorlink.app.telemetry.FakeTelemetrySource
import com.kompressorlink.app.telemetry.SourceChoice
import com.kompressorlink.app.telemetry.SourceChoiceStore
import com.kompressorlink.app.telemetry.SourceSwitcher
import com.kompressorlink.app.telemetry.ble.BleSession
import com.kompressorlink.app.telemetry.ble.GattClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.klDataStore: DataStore<Preferences> by preferencesDataStore(name = "kompressorlink")

class KompressorLinkApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class DataStoreSourceChoiceStore(
    private val dataStore: DataStore<Preferences>,
) : SourceChoiceStore {
    private val key = stringPreferencesKey("source_choice")

    override val choice: Flow<SourceChoice> = dataStore.data.map { prefs ->
        prefs[key]?.let { stored ->
            runCatching { SourceChoice.valueOf(stored) }.getOrNull()
        } ?: SourceChoice.SIMULATED_HEALTHY
    }

    override suspend fun set(choice: SourceChoice) {
        dataStore.edit { it[key] = choice.name }
    }
}

interface AssociationStore {
    val mac: Flow<String?>
    suspend fun set(mac: String)
}

class DataStoreAssociationStore(
    private val dataStore: DataStore<Preferences>,
) : AssociationStore {
    private val key = stringPreferencesKey("associated_mac")
    override val mac: Flow<String?> = dataStore.data.map { it[key] }
    override suspend fun set(mac: String) {
        dataStore.edit { it[key] = mac }
    }
}

// Manual DI for a 3-screen personal tool — no Hilt (spec §4).
class AppContainer(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val choiceStore: SourceChoiceStore = DataStoreSourceChoiceStore(app.klDataStore)
    val associationStore: AssociationStore = DataStoreAssociationStore(app.klDataStore)

    val referenceRepository = ReferenceRepository { name ->
        app.assets.open(name).bufferedReader().readText()
    }

    val bleSession = BleSession(scope, associationStore.mac, choiceStore.choice) { mac ->
        GattClient(app, mac)
    }

    private val fakes = mapOf(
        SourceChoice.SIMULATED_HEALTHY to FakeTelemetrySource(FakeScenario.HEALTHY),
        SourceChoice.SIMULATED_FAULT to FakeTelemetrySource(FakeScenario.FAULT),
        SourceChoice.SIMULATED_SPARSE to FakeTelemetrySource(FakeScenario.SPARSE),
    )

    val telemetrySource: SourceSwitcher = SourceSwitcher(scope, choiceStore) { choice ->
        fakes[choice] ?: bleSession
    }
}
```

- [ ] **Step 8: Full replacement `MainActivity.kt`** (adds the associator + "Pair with device…" menu entry; otherwise Task 16's shell):

```kotlin
package com.kompressorlink.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kompressorlink.app.connection.CdmAssociator
import com.kompressorlink.app.dashboard.DashboardScreen
import com.kompressorlink.app.dashboard.DashboardViewModel
import com.kompressorlink.app.dtc.DtcScreen
import com.kompressorlink.app.dtc.DtcViewModel
import com.kompressorlink.app.telemetry.SourceChoice
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var associator: CdmAssociator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as KompressorLinkApp).container
        associator = CdmAssociator(this) { mac ->
            lifecycleScope.launch {
                container.associationStore.set(mac)
                container.choiceStore.set(SourceChoice.REAL_BLE)
            }
        }
        setContent {
            MaterialTheme {
                AppUi(
                    container = container,
                    onPairRequest = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            associator.associate()
                        } else {
                            Toast.makeText(this, "Pairing requires Android 13+",
                                           Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppUi(container: AppContainer, onPairRequest: () -> Unit) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val choice by container.choiceStore.choice
        .collectAsState(initial = SourceChoice.SIMULATED_HEALTHY)
    var menuOpen by remember { mutableStateOf(false) }

    // Runtime permissions, requested once at startup. BLUETOOTH_CONNECT is
    // an API 31+ runtime permission; POST_NOTIFICATIONS is API 33+.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* denial surfaces visibly: BLE connect / notifications just fail */ }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KompressorLink") },
                actions = {
                    TextButton(onClick = { menuOpen = true }) {
                        Text(if (choice == SourceChoice.REAL_BLE) "BLE" else "SIM")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Pair with device…") },
                            onClick = { menuOpen = false; onPairRequest() },
                        )
                        HorizontalDivider()
                        SourceChoice.entries.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(if (c == choice) "✓ ${c.displayName}" else c.displayName) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { container.choiceStore.set(c) }
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            val backStack by navController.currentBackStackEntryAsState()
            val route = backStack?.destination?.route
            NavigationBar {
                NavigationBarItem(
                    selected = route == "dashboard",
                    onClick = { navController.navigate("dashboard") { launchSingleTop = true } },
                    icon = {}, label = { Text("Dashboard") },
                )
                NavigationBarItem(
                    selected = route == "dtc",
                    onClick = { navController.navigate("dtc") { launchSingleTop = true } },
                    icon = {}, label = { Text("DTCs") },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding),
        ) {
            composable("dashboard") {
                val vm: DashboardViewModel = viewModel(initializer = {
                    DashboardViewModel(container.telemetrySource, container.referenceRepository)
                })
                DashboardScreen(vm)
            }
            composable("dtc") {
                val vm: DtcViewModel = viewModel(initializer = {
                    DtcViewModel(container.telemetrySource, container.referenceRepository)
                })
                DtcScreen(vm)
            }
        }
    }
}
```

- [ ] **Step 9: Run everything** — `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` → all unit tests pass (Task 19's + 3 new), BUILD SUCCESSFUL.
- [ ] **Step 10 (user, quick sanity):** install on the S23 FE; verify the app still runs on simulated data, "Pair with device…" opens and then reports failure/empty (nothing is advertising yet — that is the expected result today), and no crash occurs. The real association happens in Task 21.
- [ ] **Step 11: Leave for review** (no commit).

---

## Task 21: The final hardware session — the ONE ESP32 plug-in (USER + agent)

Bare ESP32 on its own USB cable. No USB-TTL, no L9637D, no 12 V, no car
(the handover's hard constraint, honored to the end). Gate = spec §6's
bench restatement of PLAN.md §6.3.

**Files:**
- Create: `D:\C180 Project\docs\phase4_acceptance.md` (agent writes it from the user's observations in Step 8)

- [ ] **Step 1 (agent): Preflight.** `pio test -e native` all green; `pio run -e esp32dev_demo` SUCCESS; `.\gradlew.bat :app:testDebugUnitTest` all green; latest app installed on the S23 FE (`.\gradlew.bat :app:installDebug`).
- [ ] **Step 2 (user): Flash the demo target.** ESP32 on USB: `pio run -e esp32dev_demo -t upload`, then `pio device monitor -b 115200`. Expected on Serial: `KompressorLink DEMO firmware boot` + `ble_svc: advertising as KompressorLink`.
- [ ] **Step 3 (user): Associate.** In the app: menu → "Pair with device…". Expected: the system CDM sheet finds **KompressorLink** within a few seconds; select it. App flips to BLE, dashboard streams demo telemetry (idle → rev sweep → cruise, repeating each minute), chip shows the demo state.
- [ ] **Step 4 (user): Verify the screens.** DTC tab: stored **P0171** with the breather-hose interpretation chain, pending empty. Dashboard: battery gauge shows ≈14.25 V (demo-only — it will honestly read "Not provided by ECU" on the real target until Phase 2); MAF sits green in its 3–5 g/s band during idle phases.
- [ ] **Step 5 (user): Verify time sync.** Serial monitor shows `time sync applied, epoch_s=…` shortly after the connection reached Ready.
- [ ] **Step 6 (user): The cold-start gate.** Force-stop the app (App info → Force stop), lock the phone, unplug the ESP32, wait 10 s, replug it. Expected within **20 s**: the KompressorLink foreground notification appears on its own; unlocking shows live streaming without ever launching the app manually. Record the actual latency. If it fails: check battery optimization is off for the app (Settings → Apps → KompressorLink → Battery → Unrestricted) and retry once — record both attempts.
- [ ] **Step 7 (user): Soak.** Leave it streaming ≥ 10 minutes with the dashboard open. Expected: no visible jank, connection stays up (brief seq gaps are tolerable; disconnects are not).
- [ ] **Step 8 (agent): Write `docs/phase4_acceptance.md`** recording: date, fw/app versions, each step's observed result (including the cold-start latency), and the explicit **deferred list** — §6.3's literal in-car criteria (45-min ride, < 6 %/hr battery, phone-in-pocket ignition test) await Phases 2–3; the DTC K-line count-byte framing stays [Guessing] until Phase 3's probe; `BATT_V_ADC` stays masked-out on the real target until Phase 2's divider.
- [ ] **Step 9: Leave for review** (no commit). **Phase 4's gate is met** when Steps 3–7 pass.

---

## Plan self-review notes (kept for the executor)

- **Spec coverage:** §2 protocol → Tasks 1/4/12; §3.1 DTC reads → Task 2;
  §3.2 cache → Task 3; §3.3 packer → Task 4; §3.4 demo → Task 5; §3.5
  wrapper → Task 7; §3.6 wiring/snapshot-lite/time-marker → Tasks 6/8; §4.1
  seam → Tasks 11/15; §4.2 fakes → Task 13; §4.3 GATT client → Tasks 18/19;
  §4.4 CDM chain → Task 20; §4.5 dashboard → Task 16; §4.6 DTC screen →
  Task 17; §4.7 JSONs → Task 14; §5 ladder → per-task verify steps + Task 21;
  §6 gate → Task 21; Task 0/9 = the user-install + headless-build decisions.
- **Ordering/parallelism:** Tasks 1–8 (firmware) need no Android tooling and
  can run before/while the user does Task 0. Tasks 10–20 hard-require Task 9.
  Task 16 Step 8 runs after Task 17. Task 21 is last, by design.
- **Verification honesty:** three things in this plan are compile/review-only
  until Task 21 — `ble_svc.cpp` (Task 7), `GattClient.kt` (Task 18), and the
  CDM classes (Task 20). Each says so in place. Everything else has a real
  failing-then-passing test command.
- **Type consistency spot-checks done while writing:** `DtcList`/`kMaxDtcs`
  shared via `dtc_list.h` (Tasks 2/4/5/6/8); `LatestValues` field set matches
  packer offsets (Tasks 3/4); Kotlin `Signal` ordinals asserted against the
  golden vectors (Tasks 11/12); `KlUuids` matches `ble_svc.h` UUIDs (Tasks
  7/18); `SourceChoice.REAL_BLE` wiring consistent across Tasks 15/20;
  `AppContainer` evolves via full-file replacements in Tasks 10 → 15 → 20
  (never incremental diffs).







