# Firmware Non-Blocking Capture & WiFi Ride Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decouple K-line polling from storage I/O via a FreeRTOS capture/storage task split, give rides unique never-reused filenames with FIFO retention, and add the Phase-5 `wifi_sync` module (SoftAP + HTTP manifest/chunk endpoints, CRC32-verified, BLE-triggered) — all host-native-tested, no car or extra hardware required.

**Architecture:** The Arduino `loop()` currently runs `scheduler.tick()` (synchronous K-line request/response), `logger.log_reading()`, and `logger.flush()` inline, so a slow storage flush stretches the polling period; worse, `on_time_sync` already calls `logger.write_header_line()` from the NimBLE host-task context — a latent cross-task race. The fix: a high-priority **capture task** (core 1) owns all K-line I/O and pushes fixed-size `CaptureEvent`s into a FreeRTOS queue; a lower-priority **storage task** (core 0) is the single writer that drains the queue into `RideLogger`. All new decision logic (event routing, ride naming, retention, CRC, manifest/chunk serving) lives in pure modules tested in the `native` env against fakes; FreeRTOS/LittleFS/WiFi glue stays in thin ESP32-only files excluded from the native build (established pattern: `esp32_uart_transport.cpp`, `littlefs_storage.cpp`, `ble_svc.cpp`).

**Tech Stack:** PlatformIO (Arduino framework on ESP32, C++17), FreeRTOS primitives from the ESP32 Arduino core (already present — **no platformio.ini framework change**), arduino-esp32 built-in `WiFi.h`/`WebServer.h` (no new library deps), Unity host-native tests.

---

## Validation context (why this plan exists, and what was rejected)

This plan is the **validated subset** of an external "Enhancement Proposal" document (2026-07-17). Verdicts, so future sessions don't re-litigate:

- **Proposal 1 (FreeRTOS split + resumable sync): ACCEPTED with corrections.** Real problem, but: FreeRTOS needs no enabling (the Arduino core runs on it — `loop()` *is* a FreeRTOS task); the data-point struct must carry the `available` flag (Untested contract); the queue must also carry header-line/ride-lifecycle events so the storage task is the *only* writer; a sync manifest is meaningless while rides all share the fixed name `/ride_phase4.csv` overwritten each boot — unique naming is a prerequisite task; `RideStorage` is write-only, so a read/list/delete-side interface is new work the proposal missed.
- **Proposal 3 (journal + 1 GB circular buffer + binary format): REJECTED as architecture.** Binary format violates PLAN.md §2 (raw capture stays long-format CSV; the pipeline and CSV-column stability depend on it). A userspace journal can't make FAT metadata updates atomic (its own claimed guarantee), and the *current* backend, LittleFS, is already power-loss-resilient copy-on-write. The circular buffer breaks the one-file-per-ride model that the BLE ride index, this plan's sync manifest, the app's sessions, and the pipeline manifest all assume — proposal 3 §6 even has to redesign proposal 1's sync API to cope. Its two *legitimate* underlying gaps are kept as small tasks here: **storage-full FIFO retention** (Task 4) and **flush discipline** (already implemented: 5 s cadence; preserved by the storage task).
- **Proposals 2 and 4** are Android-side; see `docs/superpowers/plans/2026-07-17-app-dynamic-health-and-sync.md` (proposal 2, the LSTM, is rejected outright there).

## Execution notes (read first — hard-won facts, do not rediscover)

- Execute in the **MAIN tree on master** (not the phase45 worktree).
- **Never run `git commit`.** The user commits via the VS Code Source Control GUI. End every task with a clean, reviewable working tree. This overrides any generic "commit each step" habit.
- Test command: `cd firmware` then `pio test -e native` (all suites) or `pio test -e native -f test_<name>` (one suite). No JAVA/Android involvement in this plan.
- Compile checks for ESP32-only glue: `pio run -e esp32dev` and `pio run -e esp32dev_demo` (both must stay green; the demo env is the user's only flashable target today — bare devkit, no K-line PHY, no SD).
- PlatformIO **requires one `test_<name>/` folder per suite** — sibling `test_*.cpp` files with their own `main()` in one folder fail to link. Shared doubles live at `firmware/test/` root (`fake_transport.h`, `fake_storage.h`).
- The `native` env compiles ALL of `src/` (`test_build_src = yes`); every new ESP32-only `.cpp` MUST be added to `build_src_filter` exclusions in `platformio.ini` or every native suite breaks. Header-only ESP32 glue included solely by excluded files needs no entry.
- Baseline before Task 0: all native suites green (post-KWP-audit state: `test_kline_kwp` 18, `test_kwp_dtc` 14).
- Style: match existing firmware code — classes without project namespace, `snake_case` methods, `k`-prefixed constants, comments only for constraints, confidence tags (`[Confirmed]/[Likely]/[Guessing]/[Best estimate]`) on anything unverified for this ECU/board.
- STL (`<string>`, `<vector>`, `<map>`) is fine in **test doubles** (host-only); production `src/` code sticks to fixed-size buffers/arrays like the existing modules.

## File map

```
firmware/src/
  crc32.h/.cpp                    NEW  pure CRC-32/ISO-HDLC (zlib poly), incremental
  ride_file_store.h               NEW  read/list/delete-side storage interface (pure)
  ride_naming.h/.cpp              NEW  ride_NNNNN.csv format/parse + crash-safe seq allocation
  ride_crc.h/.cpp                 NEW  CRC sidecar read/compute/backfill helpers
  ride_retention.h/.cpp           NEW  delete-oldest-closed-ride FIFO under a free-space floor
  capture_event.h                 NEW  fixed-size queue event struct
  capture_queue.h                 NEW  queue interface (pure)
  freertos_capture_queue.h        NEW  FreeRTOS impl (header-only, ESP32-only includes)
  capture_pipeline.h/.cpp         NEW  producer helper: readings/headers/lifecycle → events, drop counting
  storage_drain.h/.cpp            NEW  consumer: events → RideLogger; naming+retention+sidecar at start/close
  wifi_sync_core.h/.cpp           NEW  manifest JSON + chunk-read logic (pure)
  wifi_sync.h/.cpp                NEW  ESP32-only SoftAP + WebServer glue (excluded from native)
  littlefs_ride_file_store.h/.cpp NEW  LittleFS impl of RideFileStore (excluded from native)
  telemetry_frame.h/.cpp          MOD  control opcode 0x03 START_WIFI_SYNC
  ble_svc.h/.cpp                  MOD  second control callback (wifi-sync request)
  main.cpp                        MOD  full replacement: two tasks + queue + wifi_sync wiring
  README.md                       MOD  module table additions
firmware/test/
  fake_ride_file_store.h          NEW  shared in-memory RideFileStore double
  test_crc32/                     NEW
  test_ride_naming/               NEW
  test_ride_crc/                  NEW
  test_ride_retention/            NEW
  test_capture_pipeline/          NEW  (pipeline + drain in one suite)
  test_wifi_sync_core/            NEW
  test_telemetry_frame/           MOD  0x03 vectors
firmware/platformio.ini           MOD  native exclusions for the two new ESP32-only .cpp files
docs/ble_protocol.md              MOD  0x03 documented (additive; version byte stays 0x01)
docs/wifi_sync_protocol.md        NEW  HTTP sync protocol v1 (manifest + chunk + resume rules)
```

Signal flow after this plan:

```
capture task (core 1, prio 2)          storage task (core 0, prio 1)
  KlineKwp + PidScheduler                 RideLogger + LittleFsStorage
  tester-present / reinit / DTC poll      StorageDrain: naming, retention,
  CapturePipeline ──► FreeRTOS queue ──►  CRC sidecar, 5 s flush
loop() / loopTask (core 1, prio 1)      NimBLE host task
  BLE notify @2 Hz (mutex-copied)         control writes → callbacks
  WifiSync.handle() when active           (time-sync marker via queue, not logger)
```

---

### Task 0: Preflight

**Files:** none (verification only)

- [ ] **Step 1: Verify tree and baseline.**

Run (from repo root):
```powershell
git status --short
cd firmware
pio test -e native
```
Expected: empty status (or only this plan file); ALL suites PASS (`test_kline_kwp` 18, `test_kwp_dtc` 14 among them). If anything fails, STOP and report — do not build on a red baseline.

- [ ] **Step 2: Confirm both ESP32 envs compile pre-change.**

Run: `pio run -e esp32dev; pio run -e esp32dev_demo`
Expected: SUCCESS twice. (Establishes that any later compile break is caused by this plan.)

---

### Task 1: CRC-32 module

CRC-32/ISO-HDLC (the zlib/PNG polynomial, reflected, init `0xFFFFFFFF`, final XOR) — chosen because Kotlin's `java.util.zip.CRC32` computes the identical function, so the app verifies downloads with zero extra code. Bitwise implementation (no 1 KB table): ~10 MB/s at 240 MHz, far above SPI-flash read speed. [Confirmed algorithm; golden vector below is the standard check value.]

**Files:**
- Create: `firmware/src/crc32.h`
- Create: `firmware/src/crc32.cpp`
- Test: `firmware/test/test_crc32/test_crc32.cpp`

- [ ] **Step 1: Write the failing tests** — `firmware/test/test_crc32/test_crc32.cpp`:

```cpp
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
```

- [ ] **Step 2: Run to verify failure.**

Run: `pio test -e native -f test_crc32`
Expected: FAIL to build — `crc32.h: No such file or directory`.

- [ ] **Step 3: Implement** — `firmware/src/crc32.h`:

```cpp
#pragma once

#include <cstddef>
#include <cstdint>

// CRC-32/ISO-HDLC (zlib polynomial 0xEDB88320, reflected, init 0xFFFFFFFF,
// final XOR 0xFFFFFFFF). Deliberately identical to Kotlin's
// java.util.zip.CRC32 so the Android sync client verifies downloads without
// a custom implementation. value() does not consume state — update() may
// continue afterwards.
class Crc32 {
 public:
  void update(const uint8_t* data, size_t len);
  uint32_t value() const { return state_ ^ 0xFFFFFFFFu; }

 private:
  uint32_t state_ = 0xFFFFFFFFu;
};
```

`firmware/src/crc32.cpp`:

```cpp
#include "crc32.h"

void Crc32::update(const uint8_t* data, size_t len) {
  uint32_t c = state_;
  for (size_t i = 0; i < len; ++i) {
    c ^= data[i];
    for (int k = 0; k < 8; ++k) {
      c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u)));
    }
  }
  state_ = c;
}
```

- [ ] **Step 4: Run to verify pass.**

Run: `pio test -e native -f test_crc32`
Expected: 4 tests PASS.

---

### Task 2: `RideFileStore` interface, shared fake, and ride naming

`RideStorage` is the write path and stays untouched. `RideFileStore` is the new read/list/delete side (sync manifest, retention, seq counter, CRC sidecars). Interface speaks **bare names** (`ride_00001.csv`); the LittleFS impl (Task 8) prepends `/`. Ride numbers are **never reused**: the next-seq counter is persisted *before* a name is used, so a crash skips a number instead of ever recycling one — recycling would corrupt the app's name-keyed sync state.

**Files:**
- Create: `firmware/src/ride_file_store.h`
- Create: `firmware/test/fake_ride_file_store.h`
- Create: `firmware/src/ride_naming.h`
- Create: `firmware/src/ride_naming.cpp`
- Test: `firmware/test/test_ride_naming/test_ride_naming.cpp`

- [ ] **Step 1: Write `firmware/src/ride_file_store.h`:**

```cpp
#pragma once

#include <cstddef>
#include <cstdint>

// One manifest/retention listing can hold this many closed rides. On the
// ~1.4 MB LittleFS partition real counts stay far below this; retention
// (ride_retention.h) deletes oldest long before 24 accumulate.
// [Best estimate]
inline constexpr int kMaxRideFiles = 24;

struct RideFileInfo {
  char name[40];  // bare name, e.g. "ride_00042.csv"
  uint32_t size;
};

// Read/list/delete side of ride storage (RideStorage stays the write side).
// Names are BARE (no leading '/'); backends map to their own paths.
class RideFileStore {
 public:
  virtual ~RideFileStore() = default;

  // CLOSED ride CSVs only: files matching ride_*.csv, minus the active
  // ride registered via set_active_ride(). Returns count written (<= cap).
  virtual int list_rides(RideFileInfo* out, int cap) = 0;

  // Reads up to len bytes at offset from any file (rides, sidecars).
  // Returns bytes read (0 at/after EOF), or -1 if the file doesn't exist.
  virtual int32_t read(const char* name, uint32_t offset, uint8_t* buf,
                       uint32_t len) = 0;

  virtual bool remove(const char* name) = 0;
  virtual uint64_t free_bytes() = 0;

  // Whole-small-file text helpers (seq counter, CRC sidecars).
  virtual bool read_text(const char* name, char* out, size_t cap) = 0;
  virtual bool write_text(const char* name, const char* text) = 0;

  // The ride currently being written; excluded from list_rides(). Pass
  // nullptr on close. Callers pass BARE names.
  virtual void set_active_ride(const char* name_or_null) = 0;
};
```

- [ ] **Step 2: Write `firmware/test/fake_ride_file_store.h`** (shared double — test-root, STL fine here):

```cpp
#pragma once

#include <cstring>
#include <map>
#include <string>

#include "ride_file_store.h"

class FakeRideFileStore : public RideFileStore {
 public:
  int list_rides(RideFileInfo* out, int cap) override {
    int n = 0;
    for (const auto& [name, data] : files) {
      if (name.rfind("ride_", 0) != 0) continue;
      if (name.size() < 4 || name.compare(name.size() - 4, 4, ".csv") != 0) continue;
      if (name == active) continue;
      if (n >= cap) break;
      snprintf(out[n].name, sizeof(out[n].name), "%s", name.c_str());
      out[n].size = static_cast<uint32_t>(data.size());
      ++n;
    }
    return n;
  }

  int32_t read(const char* name, uint32_t offset, uint8_t* buf,
               uint32_t len) override {
    auto it = files.find(name);
    if (it == files.end()) return -1;
    if (offset >= it->second.size()) return 0;
    const uint32_t n =
        std::min<uint32_t>(len, static_cast<uint32_t>(it->second.size()) - offset);
    memcpy(buf, it->second.data() + offset, n);
    return static_cast<int32_t>(n);
  }

  bool remove(const char* name) override { return files.erase(name) > 0; }

  uint64_t free_bytes() override {
    uint64_t used = 0;
    for (const auto& [_, data] : files) used += data.size();
    return used >= capacity_bytes ? 0 : capacity_bytes - used;
  }

  bool read_text(const char* name, char* out, size_t cap) override {
    auto it = files.find(name);
    if (it == files.end()) return false;
    snprintf(out, cap, "%s", it->second.c_str());
    return true;
  }

  bool write_text(const char* name, const char* text) override {
    files[name] = text;
    return true;
  }

  void set_active_ride(const char* name_or_null) override {
    active = name_or_null ? name_or_null : "";
  }

  void add_ride(const std::string& name, size_t size, char fill = 'x') {
    files[name] = std::string(size, fill);
  }

  std::map<std::string, std::string> files;  // ordered => deterministic listing
  std::string active;
  uint64_t capacity_bytes = 1400 * 1024;
};
```

- [ ] **Step 3: Write the failing tests** — `firmware/test/test_ride_naming/test_ride_naming.cpp`:

```cpp
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

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_format_and_parse_round_trip);
  RUN_TEST(test_parse_rejects_non_ride_names);
  RUN_TEST(test_first_allocation_on_empty_store_is_one);
  RUN_TEST(test_allocation_advances_past_existing_files);
  RUN_TEST(test_counter_wins_over_listing_after_deletions);
  RUN_TEST(test_allocation_persists_next_before_returning);
  return UNITY_END();
}
```

- [ ] **Step 4: Run to verify failure.**

Run: `pio test -e native -f test_ride_naming`
Expected: FAIL to build — `ride_naming.h` missing.

- [ ] **Step 5: Implement** — `firmware/src/ride_naming.h`:

```cpp
#pragma once

#include <cstddef>
#include <cstdint>

#include "ride_file_store.h"

inline constexpr const char* kRideSeqFile = "ride_next.txt";

// "ride_%05u.csv". Buffer must hold >= 15 chars + NUL.
void format_ride_name(char* out, size_t cap, uint32_t seq);

// Returns the NNNNN of a "ride_NNNNN.csv" name, or 0 if the name doesn't
// match exactly (0 is never a valid ride seq).
uint32_t parse_ride_seq(const char* name);

// Crash-safe monotonic allocation: seq = max(persisted next, highest
// existing + 1), and "next = seq + 1" is persisted BEFORE the caller ever
// opens the file. Worst case after a power cut is a skipped number — a
// reused number would corrupt the phone's name-keyed sync state.
uint32_t allocate_ride_seq(RideFileStore& store);
```

`firmware/src/ride_naming.cpp`:

```cpp
#include "ride_naming.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>

void format_ride_name(char* out, size_t cap, uint32_t seq) {
  snprintf(out, cap, "ride_%05u.csv", static_cast<unsigned>(seq));
}

uint32_t parse_ride_seq(const char* name) {
  // Exact shape: "ride_" + 5 digits + ".csv" (14 chars for seq <= 99999;
  // longer digit runs still parse so growth past 99999 keeps working).
  if (strncmp(name, "ride_", 5) != 0) return 0;
  const char* p = name + 5;
  const char* d = p;
  while (*d >= '0' && *d <= '9') ++d;
  if (d == p) return 0;               // no digits
  if (strcmp(d, ".csv") != 0) return 0;  // trailing junk / sidecar / txt
  return static_cast<uint32_t>(strtoul(p, nullptr, 10));
}

uint32_t allocate_ride_seq(RideFileStore& store) {
  char buf[16] = {0};
  uint32_t persisted = 0;
  if (store.read_text(kRideSeqFile, buf, sizeof(buf))) {
    persisted = static_cast<uint32_t>(strtoul(buf, nullptr, 10));
  }
  RideFileInfo rides[kMaxRideFiles];
  const int n = store.list_rides(rides, kMaxRideFiles);
  uint32_t max_seen = 0;
  for (int i = 0; i < n; ++i) {
    const uint32_t s = parse_ride_seq(rides[i].name);
    if (s > max_seen) max_seen = s;
  }
  uint32_t seq = persisted > max_seen + 1 ? persisted : max_seen + 1;
  if (seq == 0) seq = 1;
  char next[16];
  snprintf(next, sizeof(next), "%u", static_cast<unsigned>(seq + 1));
  store.write_text(kRideSeqFile, next);  // persist BEFORE use (crash-safe)
  return seq;
}
```

- [ ] **Step 6: Run to verify pass.**

Run: `pio test -e native -f test_ride_naming`
Expected: 6 tests PASS.

---

### Task 3: CRC sidecars

Manifest requests must not re-read every ride on every call once files reach MB scale (SD, Phase 2+). Sidecar files (`ride_00042.csv.crc`, 8 hex chars) cache the CRC; written at ride close by the storage task (Task 5) and lazily backfilled by the manifest builder for files that predate this plan. Sidecars end in `.crc`, so `list_rides`' `.csv` filter never lists them.

**Files:**
- Create: `firmware/src/ride_crc.h`
- Create: `firmware/src/ride_crc.cpp`
- Test: `firmware/test/test_ride_crc/test_ride_crc.cpp`

- [ ] **Step 1: Write the failing tests** — `firmware/test/test_ride_crc/test_ride_crc.cpp`:

```cpp
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
  TEST_ASSERT_EQUAL_HEX32(0xCBF43926u, crc_for_ride(store, info));
}

void test_backfills_sidecar_on_first_compute() {
  FakeRideFileStore store;
  store.files["ride_00001.csv"] = "123456789";
  RideFileInfo info;
  snprintf(info.name, sizeof(info.name), "ride_00001.csv");
  info.size = 9;
  crc_for_ride(store, info);
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
  TEST_ASSERT_EQUAL_HEX32(0xDEADBEEFu, crc_for_ride(store, info));
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
  return UNITY_END();
}
```

- [ ] **Step 2: Run to verify failure.**

Run: `pio test -e native -f test_ride_crc`
Expected: FAIL to build — `ride_crc.h` missing.

- [ ] **Step 3: Implement** — `firmware/src/ride_crc.h`:

```cpp
#pragma once

#include <cstddef>
#include <cstdint>

#include "ride_file_store.h"

// "<ride>.crc" sidecar: 8 uppercase hex chars, the file's CRC-32/ISO-HDLC.
// Cache written at ride close; manifest backfills lazily for older files.
void crc_sidecar_name(const char* ride_name, char* out, size_t cap);

// Streams the whole ride through Crc32 (512 B chunks) and writes the
// sidecar. Returns false if the ride can't be read.
bool write_crc_sidecar(RideFileStore& store, const char* ride_name);

// Sidecar value if present, else compute + backfill. A stale sidecar is
// trusted by design: rides are append-once and never modified after close.
uint32_t crc_for_ride(RideFileStore& store, const RideFileInfo& ride);
```

`firmware/src/ride_crc.cpp`:

```cpp
#include "ride_crc.h"

#include <cstdio>
#include <cstdlib>

#include "crc32.h"

namespace {

bool compute_crc(RideFileStore& store, const char* name, uint32_t* out) {
  Crc32 crc;
  uint8_t buf[512];
  uint32_t offset = 0;
  for (;;) {
    const int32_t n = store.read(name, offset, buf, sizeof(buf));
    if (n < 0) return false;
    if (n == 0) break;
    crc.update(buf, static_cast<size_t>(n));
    offset += static_cast<uint32_t>(n);
  }
  *out = crc.value();
  return true;
}

}  // namespace

void crc_sidecar_name(const char* ride_name, char* out, size_t cap) {
  snprintf(out, cap, "%s.crc", ride_name);
}

bool write_crc_sidecar(RideFileStore& store, const char* ride_name) {
  uint32_t crc = 0;
  if (!compute_crc(store, ride_name, &crc)) return false;
  char sidecar[48];
  crc_sidecar_name(ride_name, sidecar, sizeof(sidecar));
  char text[16];
  snprintf(text, sizeof(text), "%08X", static_cast<unsigned>(crc));
  return store.write_text(sidecar, text);
}

uint32_t crc_for_ride(RideFileStore& store, const RideFileInfo& ride) {
  char sidecar[48];
  crc_sidecar_name(ride.name, sidecar, sizeof(sidecar));
  char text[16];
  if (store.read_text(sidecar, text, sizeof(text))) {
    return static_cast<uint32_t>(strtoul(text, nullptr, 16));
  }
  uint32_t crc = 0;
  if (!compute_crc(store, ride.name, &crc)) return 0;
  char out[16];
  snprintf(out, sizeof(out), "%08X", static_cast<unsigned>(crc));
  store.write_text(sidecar, out);
  return crc;
}
```

- [ ] **Step 4: Run to verify pass.**

Run: `pio test -e native -f test_ride_crc`
Expected: 5 tests PASS.

---

### Task 4: FIFO retention (the honest replacement for proposal 3's circular buffer)

Delete oldest CLOSED rides (lowest seq) until a free-space floor is met, at ride start. Same goal as a circular buffer — never die on a full filesystem — with none of its costs. The active ride is structurally protected (`list_rides` excludes it).

**Files:**
- Create: `firmware/src/ride_retention.h`
- Create: `firmware/src/ride_retention.cpp`
- Test: `firmware/test/test_ride_retention/test_ride_retention.cpp`

- [ ] **Step 1: Write the failing tests** — `firmware/test/test_ride_retention/test_ride_retention.cpp`:

```cpp
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

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_noop_when_enough_free_space);
  RUN_TEST(test_deletes_oldest_first_until_floor_met);
  RUN_TEST(test_deletes_crc_sidecar_with_its_ride);
  RUN_TEST(test_never_deletes_active_ride);
  RUN_TEST(test_stops_when_nothing_deletable_remains);
  return UNITY_END();
}
```

- [ ] **Step 2: Run to verify failure.**

Run: `pio test -e native -f test_ride_retention`
Expected: FAIL to build — `ride_retention.h` missing.

- [ ] **Step 3: Implement** — `firmware/src/ride_retention.h`:

```cpp
#pragma once

#include <cstdint>

#include "ride_file_store.h"

// Free-space floor enforced at every ride start. [Best estimate] 256 KiB ≈
// several bench-hours of long-format CSV headroom on the ~1.4 MB LittleFS
// partition; revisit when the SD backend lands (Phase 2 hardware).
inline constexpr uint64_t kMinFreeRideBytes = 256 * 1024;

// Deletes oldest closed rides (lowest ride seq, sidecars too) until
// free_bytes() >= min_free_bytes or nothing deletable remains. Returns the
// number of ride files deleted. Never touches the active ride (list_rides
// excludes it) or non-ride files.
int enforce_retention(RideFileStore& store, uint64_t min_free_bytes);
```

`firmware/src/ride_retention.cpp`:

```cpp
#include "ride_retention.h"

#include "ride_crc.h"
#include "ride_naming.h"

int enforce_retention(RideFileStore& store, uint64_t min_free_bytes) {
  int deleted = 0;
  while (store.free_bytes() < min_free_bytes) {
    RideFileInfo rides[kMaxRideFiles];
    const int n = store.list_rides(rides, kMaxRideFiles);
    uint32_t oldest_seq = 0;
    int oldest = -1;
    for (int i = 0; i < n; ++i) {
      const uint32_t seq = parse_ride_seq(rides[i].name);
      if (seq == 0) continue;  // unparseable names are never auto-deleted
      if (oldest < 0 || seq < oldest_seq) {
        oldest_seq = seq;
        oldest = i;
      }
    }
    if (oldest < 0) break;  // nothing deletable — bail rather than spin
    if (!store.remove(rides[oldest].name)) break;
    char sidecar[48];
    crc_sidecar_name(rides[oldest].name, sidecar, sizeof(sidecar));
    store.remove(sidecar);  // best-effort; may not exist
    ++deleted;
  }
  return deleted;
}
```

- [ ] **Step 4: Run to verify pass.**

Run: `pio test -e native -f test_ride_retention`
Expected: 5 tests PASS.

---

### Task 5: Capture events, queue interface, producer pipeline, storage drain

The heart of the refactor. **Single-writer discipline:** after this plan, *only* the storage task touches `RideLogger`/`RideStorage`. Everything that used to call the logger directly (readings from `loop()`, DTC header lines, the BLE time-sync marker — which today races from the NimBLE task) becomes a queue event. Readings are droppable under backpressure (counted, reported as a `#dropped=` header line); lifecycle/header events return `false` on a full queue so callers can log.

**Files:**
- Create: `firmware/src/capture_event.h`
- Create: `firmware/src/capture_queue.h`
- Create: `firmware/src/capture_pipeline.h`
- Create: `firmware/src/capture_pipeline.cpp`
- Create: `firmware/src/storage_drain.h`
- Create: `firmware/src/storage_drain.cpp`
- Test: `firmware/test/test_capture_pipeline/test_capture_pipeline.cpp`

- [ ] **Step 1: Write `firmware/src/capture_event.h`:**

```cpp
#pragma once

#include <cstdint>

#include "pid_schema.h"

// Fixed-size event for the capture -> storage FreeRTOS queue (queues copy
// by value; no pointers may cross tasks). ~132 B; depth 128 => ~17 KB heap.
// [Best estimate — comfortable within the ESP32's free heap next to NimBLE.]
struct CaptureEvent {
  enum Type : uint8_t {
    kReading = 0,     // t_ms + signal/value/available
    kHeaderLine = 1,  // text = "#"-prefixed-by-logger header content
    kRideStart = 2,   // text2 = init_mode (filename is allocated storage-side)
    kRideClose = 3,
  };

  Type type;
  uint32_t t_ms;
  Signal signal;
  float value;
  bool available;   // Untested contract: unavailable PIDs stay visible
  char text[96];    // kHeaderLine payload
  char text2[24];   // kRideStart init_mode
};
```

- [ ] **Step 2: Write `firmware/src/capture_queue.h`:**

```cpp
#pragma once

#include <cstdint>

#include "capture_event.h"

inline constexpr int kCaptureQueueDepth = 128;

// Task-safe FIFO boundary between the capture and storage tasks. The
// FreeRTOS impl lives in freertos_capture_queue.h (ESP32-only); tests use
// an in-memory fake. send() must never block — the capture task's K-line
// timing is the whole point of this boundary.
class CaptureQueue {
 public:
  virtual ~CaptureQueue() = default;
  virtual bool send(const CaptureEvent& e) = 0;  // false = queue full
  virtual bool receive(CaptureEvent* out, uint32_t timeout_ms) = 0;
};
```

- [ ] **Step 3: Write `firmware/src/capture_pipeline.h`:**

```cpp
#pragma once

#include <cstdint>

#include "capture_queue.h"
#include "pid_scheduler.h"

// Producer-side helper (capture task + BLE time-sync callback): shapes
// readings/headers/lifecycle into CaptureEvents. Readings are droppable
// under backpressure (counted); lifecycle/header sends report failure so
// the caller can log. NOT itself thread-safe — one logical producer at a
// time; the underlying FreeRTOS queue makes concurrent send() calls from
// the NimBLE callback safe in production.
class CapturePipeline {
 public:
  explicit CapturePipeline(CaptureQueue& q) : q_(q) {}

  bool ride_start(const char* init_mode);
  bool header_line(const char* text);
  bool ride_close();
  void reading(uint32_t t_ms, const PidScheduler::Reading& r);

  uint32_t dropped() const { return dropped_; }

  // Emits "dropped=<total>" as a header line when the count changed since
  // the last emit — cheap engineering honesty about backpressure loss.
  void emit_drop_marker();

 private:
  CaptureQueue& q_;
  uint32_t dropped_ = 0;
  uint32_t last_reported_dropped_ = 0;
};
```

- [ ] **Step 4: Write `firmware/src/capture_pipeline.cpp`:**

```cpp
#include "capture_pipeline.h"

#include <cstdio>
#include <cstring>

namespace {

CaptureEvent make(CaptureEvent::Type type) {
  CaptureEvent e;
  memset(&e, 0, sizeof(e));
  e.type = type;
  return e;
}

}  // namespace

bool CapturePipeline::ride_start(const char* init_mode) {
  CaptureEvent e = make(CaptureEvent::kRideStart);
  snprintf(e.text2, sizeof(e.text2), "%s", init_mode);
  return q_.send(e);
}

bool CapturePipeline::header_line(const char* text) {
  CaptureEvent e = make(CaptureEvent::kHeaderLine);
  snprintf(e.text, sizeof(e.text), "%s", text);
  return q_.send(e);
}

bool CapturePipeline::ride_close() {
  return q_.send(make(CaptureEvent::kRideClose));
}

void CapturePipeline::reading(uint32_t t_ms, const PidScheduler::Reading& r) {
  CaptureEvent e = make(CaptureEvent::kReading);
  e.t_ms = t_ms;
  e.signal = r.signal;
  e.value = r.value;
  e.available = r.available;
  if (!q_.send(e)) ++dropped_;
}

void CapturePipeline::emit_drop_marker() {
  if (dropped_ == last_reported_dropped_) return;
  char line[32];
  snprintf(line, sizeof(line), "dropped=%lu",
           static_cast<unsigned long>(dropped_));
  if (header_line(line)) last_reported_dropped_ = dropped_;
}
```

- [ ] **Step 5: Write `firmware/src/storage_drain.h`:**

```cpp
#pragma once

#include <cstdint>

#include "capture_event.h"
#include "ride_file_store.h"
#include "ride_logger.h"

// Same 5 s flush cadence main.cpp used inline (PLAN.md §5.1 ride_logger).
inline constexpr uint32_t kDrainFlushIntervalMs = 5000;

// Consumer side — runs ONLY on the storage task. The single owner of
// RideLogger after the split: allocates the ride name, enforces retention,
// opens/closes the file, writes the CRC sidecar at close.
class StorageDrain {
 public:
  StorageDrain(RideLogger& logger, RideFileStore& store,
               const char* fw_version)
      : logger_(logger), store_(store), fw_version_(fw_version) {}

  void handle(const CaptureEvent& e);
  void maybe_flush(uint32_t now_ms);

  bool ride_open() const { return ride_open_; }
  const char* ride_name() const { return ride_name_; }

 private:
  RideLogger& logger_;
  RideFileStore& store_;
  const char* fw_version_;
  bool ride_open_ = false;
  char ride_name_[40] = {0};
  uint32_t last_flush_ms_ = 0;
};
```

- [ ] **Step 6: Write `firmware/src/storage_drain.cpp`:**

```cpp
#include "storage_drain.h"

#include "ride_crc.h"
#include "ride_naming.h"
#include "ride_retention.h"

void StorageDrain::handle(const CaptureEvent& e) {
  switch (e.type) {
    case CaptureEvent::kRideStart: {
      if (ride_open_) return;  // duplicate start — keep the open ride
      enforce_retention(store_, kMinFreeRideBytes);
      const uint32_t seq = allocate_ride_seq(store_);
      format_ride_name(ride_name_, sizeof(ride_name_), seq);
      if (!logger_.start_ride(ride_name_, fw_version_, e.text2)) {
        ride_name_[0] = '\0';
        return;  // storage failure: swallow events until the next start
      }
      store_.set_active_ride(ride_name_);
      ride_open_ = true;
      break;
    }
    case CaptureEvent::kHeaderLine:
      if (ride_open_) logger_.write_header_line(e.text);
      break;
    case CaptureEvent::kReading:
      if (ride_open_) {
        PidScheduler::Reading r;
        r.signal = e.signal;
        r.value = e.value;
        r.available = e.available;
        logger_.log_reading(e.t_ms, r);
      }
      break;
    case CaptureEvent::kRideClose:
      if (!ride_open_) return;
      logger_.flush();
      logger_.close_ride();
      ride_open_ = false;
      store_.set_active_ride(nullptr);
      write_crc_sidecar(store_, ride_name_);
      break;
  }
}

void StorageDrain::maybe_flush(uint32_t now_ms) {
  if (!ride_open_) return;
  if (now_ms - last_flush_ms_ < kDrainFlushIntervalMs) return;
  last_flush_ms_ = now_ms;
  logger_.flush();
}
```

- [ ] **Step 7: Write the failing tests** — `firmware/test/test_capture_pipeline/test_capture_pipeline.cpp`:

```cpp
#include <unity.h>

#include <deque>

#include "capture_pipeline.h"
#include "fake_ride_file_store.h"
#include "fake_storage.h"
#include "ride_logger.h"
#include "storage_drain.h"

// In-memory queue double. Single-threaded by design — thread-safety in
// production comes from the FreeRTOS queue, not from this logic.
class FakeCaptureQueue : public CaptureQueue {
 public:
  bool send(const CaptureEvent& e) override {
    if (static_cast<int>(items.size()) >= capacity) return false;
    items.push_back(e);
    return true;
  }
  bool receive(CaptureEvent* out, uint32_t) override {
    if (items.empty()) return false;
    *out = items.front();
    items.pop_front();
    return true;
  }
  std::deque<CaptureEvent> items;
  int capacity = kCaptureQueueDepth;
};

void setUp() {}
void tearDown() {}

PidScheduler::Reading reading(Signal s, float v, bool avail) {
  PidScheduler::Reading r;
  r.signal = s;
  r.value = v;
  r.available = avail;
  return r;
}

void test_reading_event_preserves_availability_flag() {
  FakeCaptureQueue q;
  CapturePipeline p(q);
  p.reading(1234, reading(Signal::RPM, 800.0f, false));
  TEST_ASSERT_EQUAL_size_t(1, q.items.size());
  const CaptureEvent& e = q.items.front();
  TEST_ASSERT_EQUAL(CaptureEvent::kReading, e.type);
  TEST_ASSERT_EQUAL_UINT32(1234u, e.t_ms);
  TEST_ASSERT_FALSE(e.available);  // Untested contract survives the queue
}

void test_full_queue_drops_and_counts_readings() {
  FakeCaptureQueue q;
  q.capacity = 2;
  CapturePipeline p(q);
  p.reading(1, reading(Signal::RPM, 800.0f, true));
  p.reading(2, reading(Signal::RPM, 810.0f, true));
  p.reading(3, reading(Signal::RPM, 820.0f, true));  // dropped
  TEST_ASSERT_EQUAL_UINT32(1u, p.dropped());
  TEST_ASSERT_EQUAL_size_t(2, q.items.size());
}

void test_drop_marker_emitted_once_per_change() {
  FakeCaptureQueue q;
  q.capacity = 1;
  CapturePipeline p(q);
  p.reading(1, reading(Signal::RPM, 800.0f, true));  // fills queue
  p.reading(2, reading(Signal::RPM, 810.0f, true));  // dropped
  q.items.clear();
  p.emit_drop_marker();
  TEST_ASSERT_EQUAL_size_t(1, q.items.size());
  TEST_ASSERT_EQUAL(CaptureEvent::kHeaderLine, q.items.front().type);
  TEST_ASSERT_EQUAL_STRING("dropped=1", q.items.front().text);
  q.items.clear();
  p.emit_drop_marker();  // unchanged count -> silent
  TEST_ASSERT_EQUAL_size_t(0, q.items.size());
}

void test_drain_opens_ride_with_allocated_name_and_retention() {
  FakeStorage storage;
  RideLogger logger(storage);
  FakeRideFileStore store;
  store.write_text("ride_next.txt", "7");
  StorageDrain drain(logger, store, "fw-test");

  FakeCaptureQueue q;
  CapturePipeline p(q);
  p.ride_start("logical-init");
  CaptureEvent e;
  while (q.receive(&e, 0)) drain.handle(e);

  TEST_ASSERT_TRUE(drain.ride_open());
  TEST_ASSERT_EQUAL_STRING("ride_00007.csv", drain.ride_name());
  TEST_ASSERT_EQUAL_STRING("ride_00007.csv", storage.opened_filename.c_str());
  TEST_ASSERT_EQUAL_STRING("ride_00007.csv", store.active.c_str());
}

void test_drain_routes_headers_and_readings_in_order() {
  FakeStorage storage;
  RideLogger logger(storage);
  FakeRideFileStore store;
  StorageDrain drain(logger, store, "fw-test");

  FakeCaptureQueue q;
  CapturePipeline p(q);
  p.ride_start("logical-init");
  p.header_line("dtc_stored=");
  p.reading(500, reading(Signal::RPM, 800.0f, true));
  CaptureEvent e;
  while (q.receive(&e, 0)) drain.handle(e);

  // start_ride writes its own header block first; our two lines follow in
  // queue order. Just assert relative order of the two payloads we sent.
  int header_idx = -1, reading_idx = -1;
  for (size_t i = 0; i < storage.lines.size(); ++i) {
    if (storage.lines[i].find("dtc_stored=") != std::string::npos)
      header_idx = static_cast<int>(i);
    if (storage.lines[i].find("500") == 0)
      reading_idx = static_cast<int>(i);
  }
  TEST_ASSERT_TRUE(header_idx >= 0);
  TEST_ASSERT_TRUE(reading_idx > header_idx);
}

void test_drain_ignores_events_before_ride_start() {
  FakeStorage storage;
  RideLogger logger(storage);
  FakeRideFileStore store;
  StorageDrain drain(logger, store, "fw-test");
  CaptureEvent e;
  memset(&e, 0, sizeof(e));
  e.type = CaptureEvent::kReading;
  e.signal = Signal::RPM;
  e.available = true;
  drain.handle(e);
  TEST_ASSERT_EQUAL_size_t(0, storage.lines.size());
  TEST_ASSERT_FALSE(drain.ride_open());
}

void test_drain_flushes_on_cadence_only_while_open() {
  FakeStorage storage;
  RideLogger logger(storage);
  FakeRideFileStore store;
  StorageDrain drain(logger, store, "fw-test");

  drain.maybe_flush(10000);  // closed -> no flush
  TEST_ASSERT_EQUAL_INT(0, storage.flush_count);

  FakeCaptureQueue q;
  CapturePipeline p(q);
  p.ride_start("logical-init");
  CaptureEvent e;
  while (q.receive(&e, 0)) drain.handle(e);

  drain.maybe_flush(20000);
  const int after_first = storage.flush_count;
  TEST_ASSERT_TRUE(after_first >= 1);
  drain.maybe_flush(20001);  // within cadence -> no extra flush
  TEST_ASSERT_EQUAL_INT(after_first, storage.flush_count);
  drain.maybe_flush(25001);  // past cadence -> flush
  TEST_ASSERT_EQUAL_INT(after_first + 1, storage.flush_count);
}

void test_drain_close_writes_crc_sidecar_and_clears_active() {
  FakeStorage storage;
  RideLogger logger(storage);
  FakeRideFileStore store;
  // Give the fake store ride content so the sidecar has bytes to CRC. The
  // drain writes via RideLogger/FakeStorage (write side), so mirror the
  // closed file into the read-side fake by hand.
  StorageDrain drain(logger, store, "fw-test");
  FakeCaptureQueue q;
  CapturePipeline p(q);
  p.ride_start("logical-init");
  CaptureEvent e;
  while (q.receive(&e, 0)) drain.handle(e);
  store.files[drain.ride_name()] = "123456789";

  p.ride_close();
  while (q.receive(&e, 0)) drain.handle(e);

  TEST_ASSERT_FALSE(drain.ride_open());
  TEST_ASSERT_TRUE(storage.closed);
  TEST_ASSERT_EQUAL_STRING("", store.active.c_str());
  char text[16];
  TEST_ASSERT_TRUE(store.read_text("ride_00001.csv.crc", text, sizeof(text)));
  TEST_ASSERT_EQUAL_STRING("CBF43926", text);
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_reading_event_preserves_availability_flag);
  RUN_TEST(test_full_queue_drops_and_counts_readings);
  RUN_TEST(test_drop_marker_emitted_once_per_change);
  RUN_TEST(test_drain_opens_ride_with_allocated_name_and_retention);
  RUN_TEST(test_drain_routes_headers_and_readings_in_order);
  RUN_TEST(test_drain_ignores_events_before_ride_start);
  RUN_TEST(test_drain_flushes_on_cadence_only_while_open);
  RUN_TEST(test_drain_close_writes_crc_sidecar_and_clears_active);
  return UNITY_END();
}
```

Note: `test_drain_close_writes_crc_sidecar_and_clears_active` expects `ride_00001.csv` because its fake store has no persisted counter — first allocation is seq 1.

- [ ] **Step 8: Run to verify failure, then implement Steps 1–6 files, then verify pass.**

Run: `pio test -e native -f test_capture_pipeline`
Expected first: build FAIL (missing headers). After implementation: 8 tests PASS.

- [ ] **Step 9: Run the FULL native suite** (`pio test -e native`) — everything green (this task touches no existing module, but `test_build_src = yes` compiles the new .cpp files into every suite — catches stray includes).

---

### Task 6: `wifi_sync_core` — manifest + chunk logic (pure)

**Files:**
- Create: `firmware/src/wifi_sync_core.h`
- Create: `firmware/src/wifi_sync_core.cpp`
- Test: `firmware/test/test_wifi_sync_core/test_wifi_sync_core.cpp`

- [ ] **Step 1: Write the failing tests** — `firmware/test/test_wifi_sync_core/test_wifi_sync_core.cpp`:

```cpp
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

void test_chunk_unknown_file_is_error() {
  FakeRideFileStore store;
  uint8_t buf[8];
  TEST_ASSERT_EQUAL_INT32(-1, read_ride_chunk(store, "nope.csv", 0, 4, buf, sizeof(buf)));
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_manifest_empty_store);
  RUN_TEST(test_manifest_lists_rides_with_size_and_crc);
  RUN_TEST(test_manifest_excludes_active_ride);
  RUN_TEST(test_manifest_overflow_returns_zero);
  RUN_TEST(test_chunk_happy_path);
  RUN_TEST(test_chunk_clamps_to_eof_and_max);
  RUN_TEST(test_chunk_unknown_file_is_error);
  return UNITY_END();
}
```

- [ ] **Step 2: Run to verify failure.**

Run: `pio test -e native -f test_wifi_sync_core`
Expected: FAIL to build.

- [ ] **Step 3: Implement** — `firmware/src/wifi_sync_core.h`:

```cpp
#pragma once

#include <cstddef>
#include <cstdint>

#include "ride_file_store.h"

// Max bytes per /data response. 4 KiB fits one static buffer in the HTTP
// glue and keeps chunk latency low over SoftAP. [Best estimate]
inline constexpr uint32_t kSyncMaxChunk = 4096;

// {"rides":[{"name":"...","size":N,"crc32":"XXXXXXXX"},...]} — closed rides
// only (the store excludes the active ride). Returns bytes written (excl.
// NUL), or 0 if `cap` is too small (caller answers HTTP 500).
size_t build_manifest_json(RideFileStore& store, char* out, size_t cap);

// Copies min(length, kSyncMaxChunk, cap, bytes-remaining) bytes at `offset`
// into out. 0 = at/after EOF (client interprets as done); -1 = no such file.
int32_t read_ride_chunk(RideFileStore& store, const char* name,
                        uint32_t offset, uint32_t length, uint8_t* out,
                        uint32_t cap);
```

`firmware/src/wifi_sync_core.cpp`:

```cpp
#include "wifi_sync_core.h"

#include <cstdio>
#include <cstring>

#include "ride_crc.h"

size_t build_manifest_json(RideFileStore& store, char* out, size_t cap) {
  RideFileInfo rides[kMaxRideFiles];
  const int n = store.list_rides(rides, kMaxRideFiles);
  size_t o = 0;
  const auto append = [&](const char* fmt, auto... args) -> bool {
    const int w = snprintf(out + o, cap - o, fmt, args...);
    if (w < 0 || o + static_cast<size_t>(w) >= cap) return false;
    o += static_cast<size_t>(w);
    return true;
  };
  if (!append("{\"rides\":[")) return 0;
  for (int i = 0; i < n; ++i) {
    const uint32_t crc = crc_for_ride(store, rides[i]);
    if (!append("%s{\"name\":\"%s\",\"size\":%lu,\"crc32\":\"%08X\"}",
                i ? "," : "", rides[i].name,
                static_cast<unsigned long>(rides[i].size),
                static_cast<unsigned>(crc))) {
      return 0;
    }
  }
  if (!append("]}")) return 0;
  return o;
}

int32_t read_ride_chunk(RideFileStore& store, const char* name,
                        uint32_t offset, uint32_t length, uint8_t* out,
                        uint32_t cap) {
  uint32_t want = length;
  if (want > kSyncMaxChunk) want = kSyncMaxChunk;
  if (want > cap) want = cap;
  return store.read(name, offset, out, want);
}
```

- [ ] **Step 4: Run to verify pass.**

Run: `pio test -e native -f test_wifi_sync_core`
Expected: 7 tests PASS.

---

### Task 7: BLE control opcode 0x03 (START_WIFI_SYNC) + protocol docs

Additive change: the 9-byte control frame gains opcode `0x03` (payload ignored, client sends zeros). Version byte stays `0x01`. The Kotlin mirror change (`FrameCodec.kt`) belongs to the app plan — here only firmware + docs.

**Files:**
- Modify: `firmware/src/telemetry_frame.h` (add constant)
- Modify: `firmware/src/telemetry_frame.cpp` (accept opcode in `parse_control`)
- Modify: `firmware/src/ble_svc.h` + `firmware/src/ble_svc.cpp` (second callback)
- Test: `firmware/test/test_telemetry_frame/test_telemetry_frame.cpp` (add cases)
- Modify: `docs/ble_protocol.md`
- Create: `docs/wifi_sync_protocol.md`

- [ ] **Step 1: Add failing test cases** to `firmware/test/test_telemetry_frame/test_telemetry_frame.cpp` (append to the existing suite — read the file first, add these two functions and their `RUN_TEST` lines to `main()`, keeping everything else intact):

```cpp
void test_parse_control_accepts_start_wifi_sync() {
  uint8_t frame[kControlFrameLen] = {0};
  frame[0] = kControlOpStartWifiSync;
  ControlCommand cmd;
  TEST_ASSERT_TRUE(parse_control(frame, sizeof(frame), &cmd));
  TEST_ASSERT_EQUAL_UINT8(kControlOpStartWifiSync, cmd.opcode);
  TEST_ASSERT_EQUAL_UINT64(0u, cmd.epoch_ms);
}

void test_parse_control_still_rejects_reserved_clear_dtc() {
  uint8_t frame[kControlFrameLen] = {0};
  frame[0] = 0x02;  // CLEAR_DTC stays reserved & unimplemented by design
  ControlCommand cmd;
  TEST_ASSERT_FALSE(parse_control(frame, sizeof(frame), &cmd));
}
```

- [ ] **Step 2: Run to verify failure.**

Run: `pio test -e native -f test_telemetry_frame`
Expected: build FAIL (`kControlOpStartWifiSync` undeclared).

- [ ] **Step 3: Implement.** In `firmware/src/telemetry_frame.h`, directly under the `kControlOpTimeSync` line, add:

```cpp
// 0x03 = START_WIFI_SYNC: phone asks the logger to raise its SoftAP + HTTP
// sync server (docs/wifi_sync_protocol.md). epoch_ms payload is ignored —
// clients send zeros. 0x02 remains reserved/unimplemented.
inline constexpr uint8_t kControlOpStartWifiSync = 0x03;
```

In `firmware/src/telemetry_frame.cpp`, find `parse_control`'s opcode check (it currently accepts only `kControlOpTimeSync`) and extend it to also accept `kControlOpStartWifiSync`, e.g. change the known-opcode condition to:

```cpp
  if (opcode != kControlOpTimeSync && opcode != kControlOpStartWifiSync) {
    return false;
  }
```

(Adapt to the file's exact local variable names; behavior for 0x02/unknown/short frames must remain "return false".)

- [ ] **Step 4: Run to verify pass.**

Run: `pio test -e native -f test_telemetry_frame`
Expected: all suite tests PASS including the two new ones.

- [ ] **Step 5: Extend `BleSvc` with the second callback.** In `firmware/src/ble_svc.h`, replace the `begin` declaration block with:

```cpp
  using TimeSyncCallback = void (*)(uint64_t epoch_ms);
  using WifiSyncRequestCallback = void (*)();

  // Init NimBLE, create service/characteristics/DIS, start advertising.
  // on_wifi_sync fires on control opcode 0x03 (may be nullptr to ignore).
  void begin(const char* fw_version, TimeSyncCallback on_time_sync,
             WifiSyncRequestCallback on_wifi_sync);
```

In `firmware/src/ble_svc.cpp` (read it first; it is ESP32-only, compile-verified not unit-tested): store the new callback alongside the existing time-sync one, and in the control-write dispatch (where `parse_control` succeeds and the opcode switch/if runs) add:

```cpp
      if (cmd.opcode == kControlOpStartWifiSync) {
        if (wifi_sync_cb_ != nullptr) wifi_sync_cb_();
        return;
      }
```

Keep the existing TIME_SYNC path and the log-and-ignore behavior for anything else exactly as-is. NOTE: the callback runs on the NimBLE host task — implementations must only set flags/send queue events (main.cpp Task 9 does exactly that).

- [ ] **Step 6: Compile check** (BLE code is ESP32-only): `pio run -e esp32dev`
Expected: SUCCESS.

- [ ] **Step 7: Update `docs/ble_protocol.md`.** In the control-frame table row for `opcode`, document `0x03 = START_WIFI_SYNC (epoch_ms ignored, send zeros)` and add below the table: on 0x03 the firmware raises its WiFi SoftAP + HTTP sync server (see `docs/wifi_sync_protocol.md`); auto-off after 5 min idle. Version byte unchanged (additive opcode).

- [ ] **Step 8: Create `docs/wifi_sync_protocol.md`:**

```markdown
# WiFi ride-sync protocol v1

Phase-5 scope (PLAN.md §5.1 `wifi_sync`): BLE is too slow for ride files, so
bulk transfer runs over a temporary SoftAP raised on demand.

## Session lifecycle
1. Phone writes BLE control opcode `0x03` (START_WIFI_SYNC).
2. Firmware raises SoftAP `KompressorLink` / WPA2 PSK `kompressor-link`
   (static — personal single-user tool [Best estimate: adequate]), IP
   `192.168.4.1`, HTTP on port 80. BLE stays up (same 2.4 GHz radio;
   [Likely] fine for a parked sync session — not designed for while-driving
   use).
3. Phone joins the AP (Android `WifiNetworkSpecifier`) and syncs.
4. Firmware drops the AP after **5 min without any HTTP request**
   (`kWifiIdleOffMs`), or on the next boot.

## Endpoints
### `GET /rides`
`200 application/json`:
`{"rides":[{"name":"ride_00042.csv","size":123456,"crc32":"89ABCDEF"}]}`
- CLOSED rides only — the ride currently being written never appears.
- `crc32` = CRC-32/ISO-HDLC of the full file, 8 uppercase hex chars
  (matches `java.util.zip.CRC32`).

### `GET /data?name=<file>&offset=<n>&length=<n>`
`200 application/octet-stream`, body = up to `min(length, 4096)` bytes of
the file starting at `offset`.
- Empty body = at/after EOF.
- `404` = unknown file. `400` = missing/invalid params.

## Client resume rules (implemented app-side)
- Persist per-file `downloadedBytes` after every chunk append; resume from
  it after any disconnection.
- A file is complete when `downloadedBytes == size`; then verify CRC-32 of
  the local bytes against the manifest before ingesting. Mismatch: delete
  local bytes and re-download once; a second mismatch marks the file FAILED.
- Ride names are NEVER reused by the firmware (monotonic counter), so
  `name` is a stable identity key; `size`+`crc32` changing for a known name
  means a firmware-side anomaly — treat as a fresh file.
```

---

### Task 8: ESP32 glue — `LittleFsRideFileStore` + `WifiSync` (+ native exclusions)

Compile-verified only (the established pattern for `<LittleFS.h>`/`<WiFi.h>` code). Keep both files thin — all decisions already live in tested modules.

**Files:**
- Create: `firmware/src/littlefs_ride_file_store.h`
- Create: `firmware/src/littlefs_ride_file_store.cpp`
- Create: `firmware/src/wifi_sync.h`
- Create: `firmware/src/wifi_sync.cpp`
- Create: `firmware/src/freertos_capture_queue.h` (header-only; used by main.cpp in Task 9)
- Modify: `firmware/platformio.ini` (exclude the two new .cpp from native)

- [ ] **Step 1: Write `firmware/src/littlefs_ride_file_store.h`:**

```cpp
#pragma once

#include "ride_file_store.h"

// LittleFS impl of the read/list/delete side. Shares the mount with
// LittleFsStorage (LittleFS.begin() is idempotent once mounted). Interface
// names are BARE; this impl prepends "/" for LittleFS paths.
class LittleFsRideFileStore : public RideFileStore {
 public:
  int list_rides(RideFileInfo* out, int cap) override;
  int32_t read(const char* name, uint32_t offset, uint8_t* buf,
               uint32_t len) override;
  bool remove(const char* name) override;
  uint64_t free_bytes() override;
  bool read_text(const char* name, char* out, size_t cap) override;
  bool write_text(const char* name, const char* text) override;
  void set_active_ride(const char* name_or_null) override;

 private:
  bool ensure_mounted();
  char active_[40] = {0};
};
```

- [ ] **Step 2: Write `firmware/src/littlefs_ride_file_store.cpp`:**

```cpp
#include "littlefs_ride_file_store.h"

#include <LittleFS.h>

#include <cstdio>
#include <cstring>

namespace {
void to_path(const char* name, char* out, size_t cap) {
  snprintf(out, cap, "/%s", name);
}
}  // namespace

bool LittleFsRideFileStore::ensure_mounted() {
  // Same formatOnFail rationale as littlefs_storage.cpp; begin() after a
  // successful mount is a cheap no-op, so no static guard duplication.
  return LittleFS.begin(/*formatOnFail=*/true);
}

int LittleFsRideFileStore::list_rides(RideFileInfo* out, int cap) {
  if (!ensure_mounted()) return 0;
  File root = LittleFS.open("/");
  if (!root) return 0;
  int n = 0;
  for (File f = root.openNextFile(); f && n < cap; f = root.openNextFile()) {
    const char* base = f.name();  // arduino-esp32 v2 returns bare names here
    const size_t len = strlen(base);
    if (strncmp(base, "ride_", 5) != 0) continue;
    if (len < 4 || strcmp(base + len - 4, ".csv") != 0) continue;
    if (active_[0] != '\0' && strcmp(base, active_) == 0) continue;
    snprintf(out[n].name, sizeof(out[n].name), "%s", base);
    out[n].size = static_cast<uint32_t>(f.size());
    ++n;
  }
  return n;
}

int32_t LittleFsRideFileStore::read(const char* name, uint32_t offset,
                                    uint8_t* buf, uint32_t len) {
  if (!ensure_mounted()) return -1;
  char path[48];
  to_path(name, path, sizeof(path));
  File f = LittleFS.open(path, "r");
  if (!f) return -1;
  if (offset >= static_cast<uint32_t>(f.size())) {
    f.close();
    return 0;
  }
  if (!f.seek(offset)) {
    f.close();
    return -1;
  }
  const size_t n = f.read(buf, len);
  f.close();
  return static_cast<int32_t>(n);
}

bool LittleFsRideFileStore::remove(const char* name) {
  if (!ensure_mounted()) return false;
  char path[48];
  to_path(name, path, sizeof(path));
  return LittleFS.remove(path);
}

uint64_t LittleFsRideFileStore::free_bytes() {
  if (!ensure_mounted()) return 0;
  const uint64_t total = LittleFS.totalBytes();
  const uint64_t used = LittleFS.usedBytes();
  return used >= total ? 0 : total - used;
}

bool LittleFsRideFileStore::read_text(const char* name, char* out,
                                      size_t cap) {
  if (!ensure_mounted()) return false;
  char path[48];
  to_path(name, path, sizeof(path));
  File f = LittleFS.open(path, "r");
  if (!f) return false;
  const size_t n = f.read(reinterpret_cast<uint8_t*>(out),
                          cap > 0 ? cap - 1 : 0);
  out[n] = '\0';
  f.close();
  return true;
}

bool LittleFsRideFileStore::write_text(const char* name, const char* text) {
  if (!ensure_mounted()) return false;
  char path[48];
  to_path(name, path, sizeof(path));
  File f = LittleFS.open(path, "w");
  if (!f) return false;
  f.print(text);
  f.close();
  return true;
}

void LittleFsRideFileStore::set_active_ride(const char* name_or_null) {
  if (name_or_null == nullptr) {
    active_[0] = '\0';
  } else {
    snprintf(active_, sizeof(active_), "%s", name_or_null);
  }
}
```

VERIFICATION NOTE for the executor: arduino-esp32 changed `File::name()` semantics between cores (bare name vs full path). After writing, check the installed core's behavior (grep `name()` usage in `.pio/packages` docs or simply handle both: if the returned name starts with `/`, skip the leading slash before the `ride_` prefix test). Adjust `list_rides` accordingly — this is exactly the kind of [Likely] that must not silently break listing.

- [ ] **Step 3: Write `firmware/src/freertos_capture_queue.h`** (header-only, included only by main.cpp — no native exclusion needed):

```cpp
#pragma once

#include <Arduino.h>

#include "capture_queue.h"

// FreeRTOS-backed CaptureQueue. xQueueSend with 0 timeout never blocks the
// capture task; xQueueSend/xQueueReceive are task-safe, so the NimBLE
// callback may also send (time-sync markers) without extra locking.
class FreeRtosCaptureQueue : public CaptureQueue {
 public:
  FreeRtosCaptureQueue() {
    handle_ = xQueueCreate(kCaptureQueueDepth, sizeof(CaptureEvent));
  }

  bool send(const CaptureEvent& e) override {
    return handle_ != nullptr && xQueueSend(handle_, &e, 0) == pdTRUE;
  }

  bool receive(CaptureEvent* out, uint32_t timeout_ms) override {
    return handle_ != nullptr &&
           xQueueReceive(handle_, out, pdMS_TO_TICKS(timeout_ms)) == pdTRUE;
  }

 private:
  QueueHandle_t handle_ = nullptr;
};
```

- [ ] **Step 4: Write `firmware/src/wifi_sync.h`:**

```cpp
#pragma once

#include <cstdint>

#include "ride_file_store.h"

inline constexpr const char* kWifiSyncSsid = "KompressorLink";
// Static WPA2 PSK for a personal single-user device; documented in
// docs/wifi_sync_protocol.md. [Best estimate: adequate threat model]
inline constexpr const char* kWifiSyncPsk = "kompressor-link";
inline constexpr uint32_t kWifiIdleOffMs = 5 * 60 * 1000;

// ESP32-only SoftAP + WebServer glue around wifi_sync_core. start() is
// idempotent; handle() must be called from loop(); auto-stops after
// kWifiIdleOffMs without a request.
class WifiSync {
 public:
  void start(RideFileStore& store, uint32_t now_ms);
  void stop();
  void handle(uint32_t now_ms);
  bool active() const { return active_; }

 private:
  bool active_ = false;
  uint32_t last_activity_ms_ = 0;
};
```

- [ ] **Step 5: Write `firmware/src/wifi_sync.cpp`:**

```cpp
#include "wifi_sync.h"

#include <WebServer.h>
#include <WiFi.h>

#include <cstdlib>

#include "wifi_sync_core.h"

namespace {

// WebServer is single-threaded (serviced from loop()); statics keep the
// 4 KiB chunk buffer off the loop task's stack.
WebServer g_server(80);
RideFileStore* g_store = nullptr;
uint32_t* g_last_activity = nullptr;
uint32_t g_now = 0;
uint8_t g_chunk[kSyncMaxChunk];
char g_manifest[2048];

void handle_rides() {
  *g_last_activity = g_now;
  const size_t n = build_manifest_json(*g_store, g_manifest, sizeof(g_manifest));
  if (n == 0) {
    g_server.send(500, "text/plain", "manifest too large");
    return;
  }
  g_server.send(200, "application/json", g_manifest);
}

void handle_data() {
  *g_last_activity = g_now;
  if (!g_server.hasArg("name") || !g_server.hasArg("offset") ||
      !g_server.hasArg("length")) {
    g_server.send(400, "text/plain", "name/offset/length required");
    return;
  }
  const String name = g_server.arg("name");
  const uint32_t offset = strtoul(g_server.arg("offset").c_str(), nullptr, 10);
  const uint32_t length = strtoul(g_server.arg("length").c_str(), nullptr, 10);
  const int32_t n = read_ride_chunk(*g_store, name.c_str(), offset, length,
                                    g_chunk, sizeof(g_chunk));
  if (n < 0) {
    g_server.send(404, "text/plain", "no such ride");
    return;
  }
  g_server.setContentLength(static_cast<size_t>(n));
  g_server.send(200, "application/octet-stream", "");
  if (n > 0) {
    g_server.sendContent(reinterpret_cast<const char*>(g_chunk),
                         static_cast<size_t>(n));
  }
}

}  // namespace

void WifiSync::start(RideFileStore& store, uint32_t now_ms) {
  if (active_) return;
  g_store = &store;
  g_last_activity = &last_activity_ms_;
  WiFi.mode(WIFI_AP);
  WiFi.softAP(kWifiSyncSsid, kWifiSyncPsk);
  g_server.on("/rides", handle_rides);
  g_server.on("/data", handle_data);
  g_server.begin();
  last_activity_ms_ = now_ms;
  active_ = true;
  Serial.print("wifi_sync: AP up, IP ");
  Serial.println(WiFi.softAPIP());
}

void WifiSync::stop() {
  if (!active_) return;
  g_server.stop();
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_OFF);
  active_ = false;
  Serial.println("wifi_sync: AP down");
}

void WifiSync::handle(uint32_t now_ms) {
  if (!active_) return;
  g_now = now_ms;
  g_server.handleClient();
  if (now_ms - last_activity_ms_ >= kWifiIdleOffMs) stop();
}
```

- [ ] **Step 6: Update `firmware/platformio.ini`** — in `[env:native]`, extend the filter line to:

```ini
build_src_filter = +<*> -<main.cpp> -<esp32_uart_transport.cpp> -<littlefs_storage.cpp> -<ble_svc.cpp> -<littlefs_ride_file_store.cpp> -<wifi_sync.cpp>
```

(Also append a sentence to the existing comment naming the two new files and why: `<LittleFS.h>`, `<WiFi.h>`/`<WebServer.h>` don't exist on host.)

- [ ] **Step 7: Verify both worlds still build.**

Run: `pio test -e native` then `pio run -e esp32dev`
Expected: all native suites PASS; esp32dev SUCCESS. (wifi_sync.cpp/littlefs_ride_file_store.cpp compile into esp32dev even before main.cpp uses them.)

---### Task 9: `main.cpp` full replacement — task split + wiring (both modes)

Ownership after this task: **capture task** (core 1, prio 2) = Serial2/KWP/scheduler/tester-present/reinit/DTC-poll; **storage task** (core 0, prio 1) = the only `RideLogger` writer via `StorageDrain`; **loop()** (loopTask, core 1, prio 1) = BLE notify + WiFi sync servicing. `latest` is shared via a spinlock; `on_time_sync` now sends a queue event instead of touching the logger (fixes today's cross-task race). NimBLE calls from the capture task (`ble.update_dtc`) are [Likely] safe — NimBLE queues host work internally; flagged for the final hardware session.

**Files:**
- Modify: `firmware/src/main.cpp` (FULL replacement below)
- Modify: `firmware/src/README.md`

- [ ] **Step 1: Replace `firmware/src/main.cpp` entirely with:**

```cpp
#include <Arduino.h>

#include <sys/time.h>

#include <atomic>

#include "ble_svc.h"
#include "latest_values.h"
#include "littlefs_ride_file_store.h"
#include "version.h"
#include "wifi_sync.h"

#ifdef KL_DEMO_MODE
#include "demo_feed.h"
#else
#include "capture_pipeline.h"
#include "esp32_uart_transport.h"
#include "freertos_capture_queue.h"
#include "kline_kwp.h"
#include "littlefs_storage.h"
#include "pid_scheduler.h"
#include "ride_logger.h"
#include "storage_drain.h"
#endif

namespace {

BleSvc ble;
LatestValues latest;
// loop() packs/notifies while the capture task (or demo_fill) updates —
// copy under a spinlock; both sides hold it only for a struct copy/update.
portMUX_TYPE latest_mux = portMUX_INITIALIZER_UNLOCKED;
uint32_t last_notify_ms = 0;
constexpr uint32_t kNotifyIntervalMs = 500;  // ~2 Hz, per docs/ble_protocol.md

LittleFsRideFileStore file_store;
WifiSync wifi_sync;
// Set from the NimBLE host task (control opcode 0x03); consumed by loop().
std::atomic<bool> wifi_sync_requested{false};

void on_wifi_sync_request() { wifi_sync_requested = true; }

#ifndef KL_DEMO_MODE
FreeRtosCaptureQueue capture_queue;
CapturePipeline pipeline(capture_queue);
std::atomic<bool> kline_up{false};

constexpr uint32_t kTesterPresentIntervalMs = 2000;
// Bench-test-scoped retry cadence (see Phase 4 notes): the firmware keeps
// trying rather than going silent after a failed boot-time attempt.
constexpr uint32_t kSetupRetryIntervalMs = 5000;
constexpr uint32_t kReinitRetryIntervalMs = 3000;
// [Best estimate] DTCs change rarely; one request pair per minute is
// negligible inside the ~10 req/s budget (spec §3.6).
constexpr uint32_t kDtcReadIntervalMs = 60000;

// Reads stored+pending DTCs, pushes them to the BLE cache, and (optionally)
// queues the startup-snapshot-lite header lines. Publish only when BOTH
// reads succeed in the same poll — the BLE wire format carries no per-list
// freshness marker (Phase 4 decision, unchanged).
void read_and_publish_dtcs(KlineKwp& kwp, bool write_csv_header) {
  DtcList stored, pending;
  const bool got_stored = kwp.read_stored_dtcs(&stored);
  const bool got_pending = kwp.read_pending_dtcs(&pending);
  if (!got_stored || !got_pending) return;
  ble.update_dtc(stored, pending);  // [Likely] NimBLE-safe off-loopTask
  if (!write_csv_header) return;
  char line[96];
  size_t o = snprintf(line, sizeof(line), "dtc_stored=");
  for (uint8_t i = 0; i < stored.count && o + 7 < sizeof(line); ++i) {
    char code[6];
    dtc_code_to_string(stored.codes[i], code);
    o += snprintf(line + o, sizeof(line) - o, "%s%s", i ? "," : "", code);
  }
  pipeline.header_line(line);
  o = snprintf(line, sizeof(line), "dtc_pending=");
  for (uint8_t i = 0; i < pending.count && o + 7 < sizeof(line); ++i) {
    char code[6];
    dtc_code_to_string(pending.codes[i], code);
    o += snprintf(line + o, sizeof(line) - o, "%s%s", i ? "," : "", code);
  }
  pipeline.header_line(line);
}

// Capture task: owns ALL K-line I/O. Constructed locals => nothing else can
// touch kwp/scheduler. Storage never blocks this task: readings go through
// the non-blocking queue (drops counted + reported as a header line).
void capture_task(void*) {
  Esp32UartTransport transport(Serial2);
  KlineKwp kwp(transport);
  PidScheduler scheduler(kwp);

  bool ride_active = false;
  uint32_t last_tester_present_ms = 0;
  uint32_t last_setup_retry_ms = 0;
  uint32_t last_reinit_attempt_ms = 0;
  uint32_t last_dtc_read_ms = 0;
  uint32_t last_drop_marker_ms = 0;

  for (;;) {
    const uint32_t now = millis();
    kline_up = ride_active && !kwp.needs_reinit();

    if (!ride_active) {
      if (now - last_setup_retry_ms >= kSetupRetryIntervalMs) {
        last_setup_retry_ms = now;
        if (kwp.start_communication()) {
          Serial.println("StartCommunication OK");
          // Logical StartCommunication only — physical fast-init/5-baud is
          // Phase 2/3 scope (needs the L9637D). The storage task allocates
          // the filename and opens the file when it drains this event.
          pipeline.ride_start("logical-init");
          read_and_publish_dtcs(kwp, /*write_csv_header=*/true);
          ride_active = true;
        } else {
          Serial.println(
              "StartCommunication failed -- retrying (check wiring / "
              "kline_sim.py)");
        }
      }
      vTaskDelay(pdMS_TO_TICKS(100));
      continue;
    }

    PidScheduler::Reading reading;
    if (scheduler.tick(now, &reading)) {
      pipeline.reading(now, reading);
      portENTER_CRITICAL(&latest_mux);
      latest_values_apply(&latest, reading.signal, reading.value,
                          reading.available);
      portEXIT_CRITICAL(&latest_mux);
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
      vTaskDelay(pdMS_TO_TICKS(1));
    }

    if (now - last_tester_present_ms >= kTesterPresentIntervalMs) {
      kwp.send_tester_present();
      last_tester_present_ms = now;
    }

    if (now - last_dtc_read_ms >= kDtcReadIntervalMs) {
      last_dtc_read_ms = now;
      read_and_publish_dtcs(kwp, /*write_csv_header=*/false);
    }

    if (now - last_drop_marker_ms >= 60000) {
      last_drop_marker_ms = now;
      pipeline.emit_drop_marker();
    }

    if (kwp.needs_reinit() &&
        now - last_reinit_attempt_ms >= kReinitRetryIntervalMs) {
      last_reinit_attempt_ms = now;
      Serial.print("3 consecutive timeouts -- re-running StartCommunication: ");
      Serial.println(kwp.start_communication() ? "OK" : "failed");
    }
  }
}

// Storage task: the ONLY RideLogger/RideStorage writer after the split.
void storage_task(void*) {
  LittleFsStorage storage;
  RideLogger logger(storage);
  StorageDrain drain(logger, file_store, FW_VERSION);
  CaptureEvent e;
  for (;;) {
    if (capture_queue.receive(&e, 500)) drain.handle(e);
    drain.maybe_flush(millis());
  }
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
  // Runs on the NimBLE host task. The queue is the task-safe path into the
  // ride file (the pre-split code wrote to the logger from here — a race).
  char marker[48];
  snprintf(marker, sizeof(marker), "time_sync=%lu:%llu",
           static_cast<unsigned long>(millis()),
           static_cast<unsigned long long>(epoch_ms));
  pipeline.header_line(marker);
#endif
}

void notify_tick(uint32_t now) {
  if (now - last_notify_ms < kNotifyIntervalMs) {
    return;
  }
  last_notify_ms = now;
  LatestValues snapshot;
  portENTER_CRITICAL(&latest_mux);
  ++latest.seq;
  latest.uptime_ms = now;
#ifndef KL_DEMO_MODE
  latest_values_set_flag(&latest, kFlagKlineConnected, kline_up.load());
#endif
  snapshot = latest;
  portEXIT_CRITICAL(&latest_mux);
  ble.notify_telemetry(snapshot);
}

void service_wifi_sync(uint32_t now) {
  if (wifi_sync_requested.exchange(false)) {
    wifi_sync.start(file_store, now);
  }
  wifi_sync.handle(now);
}

}  // namespace

#ifdef KL_DEMO_MODE

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.print("KompressorLink DEMO firmware boot, fw=");
  Serial.println(FW_VERSION);
  ble.begin(FW_VERSION, on_time_sync, on_wifi_sync_request);
  DtcList stored, pending;
  demo_dtcs(&stored, &pending);
  ble.update_dtc(stored, pending);
}

void loop() {
  const uint32_t now = millis();
  portENTER_CRITICAL(&latest_mux);
  demo_fill(now, &latest);
  portEXIT_CRITICAL(&latest_mux);
  notify_tick(now);
  service_wifi_sync(now);
  delay(10);
}

#else  // real target: capture/storage tasks + BLE + wifi sync

void setup() {
  Serial.begin(115200);
  Serial2.begin(10400, SERIAL_8N1, /*RX=*/16, /*TX=*/17);
  delay(200);
  Serial.print("KompressorLink firmware boot, fw=");
  Serial.println(FW_VERSION);

  ble.begin(FW_VERSION, on_time_sync, on_wifi_sync_request);

  // Stack sizes in BYTES on ESP32 (ESP-IDF semantics). [Best estimate]
  // 8 KiB each: both tasks stick to small fixed buffers.
  // Capture on core 1 above loopTask; storage on core 0 beside the radio
  // stack — storage latency can never stall K-line timing from there.
  xTaskCreatePinnedToCore(capture_task, "kl_capture", 8192, nullptr,
                          /*prio=*/2, nullptr, /*core=*/1);
  xTaskCreatePinnedToCore(storage_task, "kl_storage", 8192, nullptr,
                          /*prio=*/1, nullptr, /*core=*/0);
}

void loop() {
  const uint32_t now = millis();
  notify_tick(now);
  service_wifi_sync(now);
  delay(10);
}

#endif  // KL_DEMO_MODE
```

- [ ] **Step 2: Compile both targets.**

Run: `pio run -e esp32dev; pio run -e esp32dev_demo`
Expected: SUCCESS twice. If `portMUX_TYPE`/`xTaskCreatePinnedToCore` are unknown, `<Arduino.h>` on ESP32 provides them — do NOT add an include of FreeRTOS headers unless the compiler demands it (then use `#include "freertos/FreeRTOS.h"` + `#include "freertos/task.h"` in that order).

- [ ] **Step 3: Run the full native suite once more** (`pio test -e native`) — main.cpp is excluded from native, so this guards against accidental breakage elsewhere.

- [ ] **Step 4: Update `firmware/src/README.md`** — add the new modules to its table with one-liners (crc32, ride_file_store + littlefs impl, ride_naming, ride_crc, ride_retention, capture_event/queue/pipeline, storage_drain, wifi_sync_core, wifi_sync, freertos_capture_queue), and a short "Task model" paragraph naming the three tasks, their cores/priorities, and the single-writer rule.

- [ ] **Step 5: Review checklist (self-review before finishing):**
- No call path touches `RideLogger`/`RideStorage` outside `storage_task` (grep for `logger.` in main.cpp — must be zero).
- No call path touches `KlineKwp`/`PidScheduler` outside `capture_task`.
- Every `latest` access is inside the spinlock (grep `latest.` / `latest_values_` in main.cpp).
- `pipeline.header_line` is the ONLY thing `on_time_sync` does besides `settimeofday`.
- Demo env unchanged in behavior except added wifi-sync servicing.

---

### Task 10: Final verification + bare-board acceptance

**Files:** none (verification only; user steps documented here)

- [ ] **Step 1: Full native suite.**

Run: `cd firmware; pio test -e native`
Expected: ALL suites PASS — the 9 pre-existing plus `test_crc32` (4), `test_ride_naming` (6), `test_ride_crc` (5), `test_ride_retention` (5), `test_capture_pipeline` (8), `test_wifi_sync_core` (7). Report the full suite/case counts.

- [ ] **Step 2: Both device targets compile.**

Run: `pio run -e esp32dev; pio run -e esp32dev_demo`
Expected: SUCCESS twice. Note flash/RAM deltas in the report (WiFi + WebServer add ~few hundred KB flash; must still fit the default partition — if overflow, report, do not silently change partitions).

- [ ] **Step 3 (USER, optional but recommended — bare devkit smoke test):** document these steps in the final report for the user to run when convenient; the agent does NOT flash hardware:
  1. `pio run -e esp32dev_demo -t upload`, open serial monitor.
  2. From a BLE app (nRF Connect): connect to `KompressorLink`, write `03 00 00 00 00 00 00 00 00` to the control characteristic (`c1800004-…`).
  3. Serial shows `wifi_sync: AP up, IP 192.168.4.1`. Join WiFi `KompressorLink` / `kompressor-link` from any device.
  4. Browse `http://192.168.4.1/rides` → `{"rides":[]}` (demo board has no rides — an empty manifest IS the pass condition; it proves BLE trigger → AP → HTTP → storage listing end-to-end).
  5. Wait 5 min idle → serial shows `wifi_sync: AP down`.

- [ ] **Step 4: Leave a clean reviewable tree.** `git status --short` must show only this plan's intended files. Do NOT commit — the user commits via the VS Code GUI.

---

## Self-review notes (already applied)

- Proposal 1's original struct lacked the `available` flag — added (Untested contract).
- Proposal 1 wanted the capture task to name the file; naming moved storage-side so the seq counter, retention, and `set_active_ride` all mutate storage state from ONE task.
- `kFlushIntervalMs` behavior preserved exactly (5 s, storage task).
- CLEAR_DTC (0x02) stays reserved/unimplemented — this plan adds no ECU writes; listen-only ground rules untouched.
- Sleep/power_mgr interplay (queue drain before deep sleep) is deliberately OUT of scope: power_mgr doesn't exist yet (Phase 2/3); `kRideClose` + drain-then-close in `StorageDrain` is the hook it will use.
