# Phase 1: Firmware Core + kline_sim.py Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `kline_sim.py` (PC-side KWP2000 ECU simulator) and the firmware core (`kline_kwp`, `pid_scheduler`, `ride_logger`, plus `KLineTransport`/`RideStorage` abstractions) so a bare ESP32 running this firmware, wired directly to a USB-TTL adapter running `kline_sim.py`, completes init, polls the full PID schedule, and writes a real ride CSV — PLAN.md §9 Phase 1's gate.

**Architecture:** See `docs/superpowers/specs/2026-07-09-phase1-firmware-core-design.md` for full rationale. Summary: `KLineTransport`/`RideStorage` interfaces decouple protocol/logging logic from ESP32 hardware so it's testable in PlatformIO's `native` env; `kline_sim.py`'s response-building logic is dumped to a C fixture header consumed by native tests, so simulator and tests can't drift apart; exact KWP2000 byte values (target address, timing constants) are `[Likely]`/`[Best estimate]` named constants, shared between firmware and simulator, correctness against the real SIM4LKE deferred to Phase 3.

**Scope boundary (read before implementing):** ISO 14230's *physical* fast-init/5-baud wake pulses are bit-banged line-level timing that only matters once the L9637D + real K-line electrics exist (Phase 2/3). Phase 1's bench topology is a direct TTL UART wire — no physical wake pulse. `kline_kwp` implements the *logical* StartCommunication request/response exchange (frame + checksum + key bytes) as its "init," not the physical wake pattern. Don't add wake-pulse bit-banging in this plan — it's Phase 2/3 scope once real K-line timing is being validated against real hardware.

**Tech Stack:** C++17 (firmware, framework-agnostic core + Arduino glue), Python 3.11 + pyserial via PEP 723 inline script metadata (`kline_sim.py`), PlatformIO native + esp32dev envs, Unity (native tests).

**Execution addendum (2026-07-10, after the first real `pio test -e native` run):**
the test-file code listings in Tasks 3-9 below are missing two things that only
surfaced once a host compiler existed — both are fixed in the repo, so treat
the repo (not these listings) as the source of truth when regenerating:
1. Every Unity test `.cpp` must define `void setUp(void) {}` and
   `void tearDown(void) {}` — Unity's runner links against them even when
   unused; their absence is a hard link error.
2. `[env:native]` needs `test_build_src = yes` — PlatformIO does not compile
   `src/` into test binaries by default, so without it every suite fails to
   link against the production code under test.
All 42 tests across 6 suites pass as of 2026-07-10 (GCC 16.1.0 via w64devkit).

---

## Task 1: `KLineTransport` interface + `Esp32UartTransport`

**Files:**
- Create: `D:\C180 Project\firmware\src\kline_transport.h`
- Create: `D:\C180 Project\firmware\src\esp32_uart_transport.h`
- Create: `D:\C180 Project\firmware\src\esp32_uart_transport.cpp`

- [ ] **Step 1: Write the abstract interface**

`firmware/src/kline_transport.h`:
```cpp
#pragma once

#include <cstddef>
#include <cstdint>

// Abstracts the K-line byte stream so protocol logic (kline_kwp) compiles
// and tests identically under PlatformIO's native env (no hardware) and the
// esp32dev env (real UART2). See docs/superpowers/specs/2026-07-09-phase1-
// firmware-core-design.md §1.
class KLineTransport {
 public:
  virtual ~KLineTransport() = default;

  virtual void write(const uint8_t* data, size_t len) = 0;

  // Reads up to `len` bytes, blocking until `timeout_ms` elapses. Returns
  // the number of bytes actually read (may be less than `len` on timeout).
  virtual size_t read(uint8_t* buf, size_t len, uint32_t timeout_ms) = 0;

  virtual uint32_t now_ms() = 0;
};
```

- [ ] **Step 2: Write the ESP32 hardware implementation header**

`firmware/src/esp32_uart_transport.h`:
```cpp
#pragma once

#include <HardwareSerial.h>

#include "kline_transport.h"

// Real hardware: UART2 on GPIO17 (TX) / GPIO16 (RX) per hardware/wiring.md.
// Only compiled for the esp32dev env (uses Arduino/HardwareSerial) — never
// included from native tests.
class Esp32UartTransport : public KLineTransport {
 public:
  explicit Esp32UartTransport(HardwareSerial& serial);

  void write(const uint8_t* data, size_t len) override;
  size_t read(uint8_t* buf, size_t len, uint32_t timeout_ms) override;
  uint32_t now_ms() override;

 private:
  HardwareSerial& serial_;
};
```

- [ ] **Step 3: Write the implementation**

`firmware/src/esp32_uart_transport.cpp`:
```cpp
#include "esp32_uart_transport.h"

#include <Arduino.h>

Esp32UartTransport::Esp32UartTransport(HardwareSerial& serial) : serial_(serial) {}

void Esp32UartTransport::write(const uint8_t* data, size_t len) {
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

- [ ] **Step 4: Verify it compiles as part of the real ESP32 build**

This file isn't wired into `main.cpp` yet (that's Task 10), so it won't be
compiled standalone until then. Skip a build check here — Task 10's build
covers it. Just confirm no syntax typos by re-reading the three files.

- [ ] **Step 5: Leave for review** (no commit — user commits via VS Code GUI)

---

## Task 2: `RideStorage` interface + `LittleFsStorage`

**Files:**
- Create: `D:\C180 Project\firmware\src\ride_storage.h`
- Create: `D:\C180 Project\firmware\src\littlefs_storage.h`
- Create: `D:\C180 Project\firmware\src\littlefs_storage.cpp`

- [ ] **Step 1: Write the abstract interface**

`firmware/src/ride_storage.h`:
```cpp
#pragma once

// Abstracts ride-file storage so ride_logger doesn't care whether it's
// writing to the ESP32's internal flash (Phase 1, no SD card wired yet) or
// a real SD card (Phase 2, once hardware/wiring.md's SPI pins are wired).
class RideStorage {
 public:
  virtual ~RideStorage() = default;

  virtual bool open_for_write(const char* filename) = 0;
  virtual void write_line(const char* line) = 0;
  virtual void flush() = 0;
  virtual void close() = 0;
};
```

- [ ] **Step 2: Write the LittleFS implementation header**

`firmware/src/littlefs_storage.h`:
```cpp
#pragma once

#include <LittleFS.h>

#include "ride_storage.h"

// Phase 1 storage backend: ESP32 internal flash via LittleFS. No extra
// wiring needed (Phase 0's build used ~20% of flash, plenty of headroom for
// bench-test ride files). Replaced by an SdStorage in Phase 2 behind the
// same RideStorage interface once the SD card is wired.
class LittleFsStorage : public RideStorage {
 public:
  bool open_for_write(const char* filename) override;
  void write_line(const char* line) override;
  void flush() override;
  void close() override;

 private:
  File file_;
};
```

- [ ] **Step 3: Write the implementation**

`firmware/src/littlefs_storage.cpp`:
```cpp
#include "littlefs_storage.h"

bool LittleFsStorage::open_for_write(const char* filename) {
  if (!LittleFS.begin(/*formatOnFail=*/true)) {
    return false;
  }
  file_ = LittleFS.open(filename, "w");
  return static_cast<bool>(file_);
}

void LittleFsStorage::write_line(const char* line) {
  file_.println(line);
}

void LittleFsStorage::flush() {
  file_.flush();
}

void LittleFsStorage::close() {
  file_.close();
}
```

- [ ] **Step 4: Add the LittleFS filesystem size to `platformio.ini`**

LittleFS needs a partition table entry with a filesystem region. Modify
`firmware/platformio.ini`'s `[env:esp32dev]` section to add:
```ini
board_build.filesystem = littlefs
board_build.partitions = default.csv
```
(the `esp32dev` board's stock `default.csv` partition table already reserves
a SPIFFS/LittleFS region — no custom partitions CSV needed for Phase 1's
small ride files).

- [ ] **Step 5: Leave for review** (no commit)

---

## Task 3: Shared PID wire-format tables (C++ decode + Python encode)

Separate from Phase 0's `pid_schema.h` (bounds/tiers) — this is protocol
*encoding*, a different responsibility, kept in its own file so Phase 0's
already-reviewed module isn't touched. Covers only the 14 signals actually
polled over K-line (Tier F/M/S) plus the 4 Legacy-tier signals (need a PID
byte to build the request even though no response is expected); `BATT_V_ADC`
(Local, ADC-only) is excluded — it never goes over K-line.

**Files:**
- Create: `D:\C180 Project\firmware\src\pid_wire_format.h`
- Create: `D:\C180 Project\firmware\src\pid_wire_format.cpp`
- Test: `D:\C180 Project\firmware\test\test_pid_wire_format\test_pid_wire_format.cpp`
- Create: `D:\C180 Project\firmware\tools\kl_sim\wire_format.py`

- [ ] **Step 1: Write the C++ header**

`firmware/src/pid_wire_format.h`:
```cpp
#pragma once

#include <cstdint>

#include "pid_schema.h"

enum class FormulaKind : uint8_t {
  TWO_BYTE_DIV4,          // RPM
  ONE_BYTE_RAW,           // SPEED, MAP
  TWO_BYTE_DIV100,        // MAF_GS
  ONE_BYTE_PCT255,        // THROTTLE, LOAD
  ONE_BYTE_TRIM,          // STFT1, LTFT1
  ONE_BYTE_HALF_MINUS64,  // TIMING_ADV
  TWO_BYTE_VOLT_DIV200,   // O2_B1S1_V, O2_B1S2_V
  ONE_BYTE_MINUS40,       // ECT, IAT
  ONE_BYTE_RAW_ENUM,      // FUEL_STATUS (simplified — not real SAE bit-flag
                          // decoding, see PLAN.md §0.4 confidence tags; also
                          // reads bank 1 only — M271 is inline-4, no bank 2)
  TWO_BYTE_DIV1000,       // CTRL_MODULE_V
};

struct WireFormat {
  Signal signal;
  uint8_t mode01_pid;  // e.g. 0x0C for RPM. SAE J1979 Mode 01 PID byte.
  FormulaKind formula;
  uint8_t byte_count;  // 1 or 2 — how many data bytes this formula reads.
};

// 18 entries: all Signal values except BATT_V_ADC (Local, never on K-line).
inline constexpr size_t kWireFormatCount = 18;
extern const WireFormat kWireFormatTable[kWireFormatCount];

// Returns nullptr if `signal` has no K-line wire format (i.e. BATT_V_ADC).
const WireFormat* find_wire_format(Signal signal);

// Decodes `byte_count` raw response bytes (big-endian, per SAE J1979) into a
// physical value using `formula`. Caller must pass exactly
// `WireFormat::byte_count` bytes.
float decode_formula(FormulaKind formula, const uint8_t* data);
```

- [ ] **Step 2: Write the C++ implementation**

`firmware/src/pid_wire_format.cpp`:
```cpp
#include "pid_wire_format.h"

const WireFormat kWireFormatTable[kWireFormatCount] = {
    {Signal::RPM, 0x0C, FormulaKind::TWO_BYTE_DIV4, 2},
    {Signal::SPEED, 0x0D, FormulaKind::ONE_BYTE_RAW, 1},
    {Signal::MAF_GS, 0x10, FormulaKind::TWO_BYTE_DIV100, 2},
    {Signal::THROTTLE, 0x11, FormulaKind::ONE_BYTE_PCT255, 1},
    {Signal::LOAD, 0x04, FormulaKind::ONE_BYTE_PCT255, 1},
    {Signal::STFT1, 0x06, FormulaKind::ONE_BYTE_TRIM, 1},
    {Signal::LTFT1, 0x07, FormulaKind::ONE_BYTE_TRIM, 1},
    {Signal::MAP, 0x0B, FormulaKind::ONE_BYTE_RAW, 1},
    {Signal::TIMING_ADV, 0x0E, FormulaKind::ONE_BYTE_HALF_MINUS64, 1},
    {Signal::O2_B1S1_V, 0x14, FormulaKind::TWO_BYTE_VOLT_DIV200, 2},
    {Signal::O2_B1S2_V, 0x15, FormulaKind::TWO_BYTE_VOLT_DIV200, 2},
    {Signal::ECT, 0x05, FormulaKind::ONE_BYTE_MINUS40, 1},
    {Signal::IAT, 0x0F, FormulaKind::ONE_BYTE_MINUS40, 1},
    {Signal::FUEL_STATUS, 0x03, FormulaKind::ONE_BYTE_RAW_ENUM, 1},
    {Signal::PEDAL_D, 0x49, FormulaKind::ONE_BYTE_PCT255, 1},
    {Signal::PEDAL_E, 0x4A, FormulaKind::ONE_BYTE_PCT255, 1},
    {Signal::CMD_THROTTLE, 0x4C, FormulaKind::ONE_BYTE_PCT255, 1},
    {Signal::CTRL_MODULE_V, 0x42, FormulaKind::TWO_BYTE_DIV1000, 2},
};

const WireFormat* find_wire_format(Signal signal) {
  for (size_t i = 0; i < kWireFormatCount; ++i) {
    if (kWireFormatTable[i].signal == signal) {
      return &kWireFormatTable[i];
    }
  }
  return nullptr;
}

float decode_formula(FormulaKind formula, const uint8_t* data) {
  switch (formula) {
    case FormulaKind::TWO_BYTE_DIV4:
      return ((data[0] * 256.0f) + data[1]) / 4.0f;
    case FormulaKind::ONE_BYTE_RAW:
      return static_cast<float>(data[0]);
    case FormulaKind::TWO_BYTE_DIV100:
      return ((data[0] * 256.0f) + data[1]) / 100.0f;
    case FormulaKind::ONE_BYTE_PCT255:
      return data[0] * 100.0f / 255.0f;
    case FormulaKind::ONE_BYTE_TRIM:
      return (data[0] - 128.0f) * 100.0f / 128.0f;
    case FormulaKind::ONE_BYTE_HALF_MINUS64:
      return data[0] / 2.0f - 64.0f;
    case FormulaKind::TWO_BYTE_VOLT_DIV200:
      return data[0] / 200.0f;  // data[1] is an unused trim byte here.
    case FormulaKind::ONE_BYTE_MINUS40:
      return data[0] - 40.0f;
    case FormulaKind::ONE_BYTE_RAW_ENUM:
      return static_cast<float>(data[0]);
    case FormulaKind::TWO_BYTE_DIV1000:
      return ((data[0] * 256.0f) + data[1]) / 1000.0f;
  }
  return 0.0f;  // Unreachable if all enum values are handled above.
}
```

- [ ] **Step 3: Write the native test**

`firmware/test/test_pid_wire_format/test_pid_wire_format.cpp` (PlatformIO
treats each `test_`-prefixed subfolder under `test/` as an independent
suite with its own `main()` — a flat directory with multiple `main()`s
would fail to link; shared helpers like `fake_transport.h` live at `test/`
root instead, where every suite's `#include` can still find them):
```cpp
#include <unity.h>

#include "pid_wire_format.h"

void test_rpm_decodes_correctly(void) {
  const uint8_t data[2] = {0x0C, 0x80};  // (12*256+128)/4 = 800.0 rpm
  TEST_ASSERT_EQUAL_FLOAT(800.0f, decode_formula(FormulaKind::TWO_BYTE_DIV4, data));
}

void test_ect_decodes_correctly(void) {
  const uint8_t data[1] = {0x50};  // 80 - 40 = 40.0 C
  TEST_ASSERT_EQUAL_FLOAT(40.0f, decode_formula(FormulaKind::ONE_BYTE_MINUS40, data));
}

void test_stft_decodes_negative_trim(void) {
  const uint8_t data[1] = {0x00};  // (0-128)*100/128 = -100.0 %
  TEST_ASSERT_EQUAL_FLOAT(-100.0f, decode_formula(FormulaKind::ONE_BYTE_TRIM, data));
}

void test_find_wire_format_returns_rpm_entry(void) {
  const WireFormat* wf = find_wire_format(Signal::RPM);
  TEST_ASSERT_NOT_NULL(wf);
  TEST_ASSERT_EQUAL_UINT8(0x0C, wf->mode01_pid);
  TEST_ASSERT_EQUAL_UINT8(2, wf->byte_count);
}

void test_find_wire_format_returns_null_for_batt_v_adc(void) {
  TEST_ASSERT_NULL(find_wire_format(Signal::BATT_V_ADC));
}

void test_wire_format_table_has_18_entries(void) {
  TEST_ASSERT_EQUAL(18, kWireFormatCount);
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_rpm_decodes_correctly);
  RUN_TEST(test_ect_decodes_correctly);
  RUN_TEST(test_stft_decodes_negative_trim);
  RUN_TEST(test_find_wire_format_returns_rpm_entry);
  RUN_TEST(test_find_wire_format_returns_null_for_batt_v_adc);
  RUN_TEST(test_wire_format_table_has_18_entries);
  return UNITY_END();
}
```

- [ ] **Step 4: Write the Python mirror (encode direction) for `kline_sim.py`**

`firmware/tools/kl_sim/wire_format.py` — a small package alongside
`kline_sim.py` (not part of `pipeline/`; this is firmware-tooling-local).
Mirrors the same 18-entry table and formula kinds, but ENCODES a physical
value into response bytes (the simulator's job), rather than decoding:

```python
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, auto


class FormulaKind(Enum):
    TWO_BYTE_DIV4 = auto()          # RPM
    ONE_BYTE_RAW = auto()           # SPEED, MAP
    TWO_BYTE_DIV100 = auto()        # MAF_GS
    ONE_BYTE_PCT255 = auto()        # THROTTLE, LOAD
    ONE_BYTE_TRIM = auto()          # STFT1, LTFT1
    ONE_BYTE_HALF_MINUS64 = auto()  # TIMING_ADV
    TWO_BYTE_VOLT_DIV200 = auto()   # O2_B1S1_V, O2_B1S2_V
    ONE_BYTE_MINUS40 = auto()       # ECT, IAT
    ONE_BYTE_RAW_ENUM = auto()      # FUEL_STATUS (simplified, bank 1 only)
    TWO_BYTE_DIV1000 = auto()       # CTRL_MODULE_V


@dataclass(frozen=True)
class WireFormat:
    signal: str
    mode01_pid: int
    formula: FormulaKind
    byte_count: int


WIRE_FORMAT_TABLE: dict[str, WireFormat] = {
    "RPM": WireFormat("RPM", 0x0C, FormulaKind.TWO_BYTE_DIV4, 2),
    "SPEED": WireFormat("SPEED", 0x0D, FormulaKind.ONE_BYTE_RAW, 1),
    "MAF_GS": WireFormat("MAF_GS", 0x10, FormulaKind.TWO_BYTE_DIV100, 2),
    "THROTTLE": WireFormat("THROTTLE", 0x11, FormulaKind.ONE_BYTE_PCT255, 1),
    "LOAD": WireFormat("LOAD", 0x04, FormulaKind.ONE_BYTE_PCT255, 1),
    "STFT1": WireFormat("STFT1", 0x06, FormulaKind.ONE_BYTE_TRIM, 1),
    "LTFT1": WireFormat("LTFT1", 0x07, FormulaKind.ONE_BYTE_TRIM, 1),
    "MAP": WireFormat("MAP", 0x0B, FormulaKind.ONE_BYTE_RAW, 1),
    "TIMING_ADV": WireFormat("TIMING_ADV", 0x0E, FormulaKind.ONE_BYTE_HALF_MINUS64, 1),
    "O2_B1S1_V": WireFormat("O2_B1S1_V", 0x14, FormulaKind.TWO_BYTE_VOLT_DIV200, 2),
    "O2_B1S2_V": WireFormat("O2_B1S2_V", 0x15, FormulaKind.TWO_BYTE_VOLT_DIV200, 2),
    "ECT": WireFormat("ECT", 0x05, FormulaKind.ONE_BYTE_MINUS40, 1),
    "IAT": WireFormat("IAT", 0x0F, FormulaKind.ONE_BYTE_MINUS40, 1),
    "FUEL_STATUS": WireFormat("FUEL_STATUS", 0x03, FormulaKind.ONE_BYTE_RAW_ENUM, 1),
    "PEDAL_D": WireFormat("PEDAL_D", 0x49, FormulaKind.ONE_BYTE_PCT255, 1),
    "PEDAL_E": WireFormat("PEDAL_E", 0x4A, FormulaKind.ONE_BYTE_PCT255, 1),
    "CMD_THROTTLE": WireFormat("CMD_THROTTLE", 0x4C, FormulaKind.ONE_BYTE_PCT255, 1),
    "CTRL_MODULE_V": WireFormat("CTRL_MODULE_V", 0x42, FormulaKind.TWO_BYTE_DIV1000, 2),
}

# Signals kline_sim.py answers by default (the "healthy" scenario). The 4
# PEDAL_D/PEDAL_E/CMD_THROTTLE/CTRL_MODULE_V entries stay in the table above
# (so a request for them can still be recognized/logged) but are excluded
# here — omitting them from this set is what makes the simulator correctly
# NOT respond, per PLAN.md §2's "expect them empty" on a 2004 K-line ECU.
ANSWERED_SIGNALS = {
    "RPM", "SPEED", "MAF_GS", "THROTTLE", "LOAD", "STFT1", "LTFT1", "MAP",
    "TIMING_ADV", "O2_B1S1_V", "O2_B1S2_V", "ECT", "IAT", "FUEL_STATUS",
}


def encode_formula(formula: FormulaKind, value: float) -> bytes:
    if formula is FormulaKind.TWO_BYTE_DIV4:
        raw = round(value * 4)
        return bytes([(raw >> 8) & 0xFF, raw & 0xFF])
    if formula is FormulaKind.ONE_BYTE_RAW:
        return bytes([round(value) & 0xFF])
    if formula is FormulaKind.TWO_BYTE_DIV100:
        raw = round(value * 100)
        return bytes([(raw >> 8) & 0xFF, raw & 0xFF])
    if formula is FormulaKind.ONE_BYTE_PCT255:
        return bytes([round(value * 255 / 100) & 0xFF])
    if formula is FormulaKind.ONE_BYTE_TRIM:
        return bytes([round(value * 128 / 100 + 128) & 0xFF])
    if formula is FormulaKind.ONE_BYTE_HALF_MINUS64:
        return bytes([round((value + 64) * 2) & 0xFF])
    if formula is FormulaKind.TWO_BYTE_VOLT_DIV200:
        return bytes([round(value * 200) & 0xFF, 0xFF])
    if formula is FormulaKind.ONE_BYTE_MINUS40:
        return bytes([round(value + 40) & 0xFF])
    if formula is FormulaKind.ONE_BYTE_RAW_ENUM:
        return bytes([round(value) & 0xFF])
    if formula is FormulaKind.TWO_BYTE_DIV1000:
        raw = round(value * 1000)
        return bytes([(raw >> 8) & 0xFF, raw & 0xFF])
    raise ValueError(f"unhandled formula kind: {formula}")
```

- [ ] **Step 5: Write the Python test**

`firmware/tools/kl_sim/test_wire_format.py`:
```python
from wire_format import FormulaKind, WIRE_FORMAT_TABLE, encode_formula


def test_rpm_encodes_to_two_bytes():
    assert encode_formula(FormulaKind.TWO_BYTE_DIV4, 800.0) == bytes([0x0C, 0x80])


def test_ect_encodes_to_one_byte():
    assert encode_formula(FormulaKind.ONE_BYTE_MINUS40, 40.0) == bytes([0x50])


def test_stft_encodes_negative_trim():
    assert encode_formula(FormulaKind.ONE_BYTE_TRIM, -100.0) == bytes([0x00])


def test_table_has_18_entries():
    assert len(WIRE_FORMAT_TABLE) == 18


def test_rpm_pid_byte_matches_claude_md():
    assert WIRE_FORMAT_TABLE["RPM"].mode01_pid == 0x0C
```

Run: `cd "D:\C180 Project\firmware\tools\kl_sim" && uv run --with pytest pytest test_wire_format.py -v`
Expected: 5 passed.

- [ ] **Step 6: Verify cross-language consistency**

For every one of the 18 signals, confirm the `mode01_pid` byte and
`FormulaKind` match exactly between `pid_wire_format.cpp` (C++) and
`wire_format.py` (Python) — this table is the one place a mismatch would
cause the C++ decoder and Python encoder to silently disagree about what a
byte means. Re-read both files side by side and check all 18 rows.

- [ ] **Step 7: Leave for review** (no commit; native C++ test unrun in this
      environment per Task 8's precedent — no host compiler. Python test IS
      runnable via `uv` — actually run it.)

---

## Task 4: `kline_sim.py` core protocol module (framing + response builders)

Frame format (shared with firmware's `kline_kwp`, Task 6): `[FMT][TGT][SRC][DATA...][CS]`
where `FMT = 0x80 | len(DATA)` (Phase 1 only handles `len(DATA) <= 63` — no
extended-length variant, small PID payloads don't need it), and
`CS = sum(FMT, TGT, SRC, *DATA) & 0xFF`. This general structure + checksum
algorithm is `[Confirmed]` (ISO 14230-2). `kTargetAddress`/timing constants
below are `[Likely]`/`[Best estimate]` — see the design doc §5.

**Files:**
- Create: `D:\C180 Project\firmware\tools\kl_sim\protocol.py`
- Create: `D:\C180 Project\firmware\tools\kl_sim\test_protocol.py`

Do NOT create `kl_sim/__init__.py` — Task 3 found that having one turns
`kl_sim` into a real Python package, which breaks pytest's ability to
resolve `test_*.py`'s flat `from wire_format import ...` / `from protocol
import ...` style, the same style `kline_sim.py` (Task 5) uses via
`sys.path.insert()`. Keep `kl_sim/` a plain (non-package) script directory.

- [ ] **Step 1: Write the failing tests first**

`firmware/tools/kl_sim/test_protocol.py`:
```python
from protocol import (
    TARGET_ADDRESS,
    TESTER_ADDRESS,
    build_frame,
    checksum,
    parse_frame,
)


def test_checksum_matches_hand_computed_value():
    # FMT=0x82, TGT=0x33, SRC=0xF1, DATA=[0x01, 0x0C] -> sum & 0xFF
    assert checksum(bytes([0x82, 0x33, 0xF1, 0x01, 0x0C])) == (
        0x82 + 0x33 + 0xF1 + 0x01 + 0x0C
    ) & 0xFF


def test_build_frame_round_trips_through_parse_frame():
    frame = build_frame(TARGET_ADDRESS, TESTER_ADDRESS, bytes([0x41, 0x0C, 0x03, 0x20]))
    target, source, data = parse_frame(frame)
    assert target == TARGET_ADDRESS
    assert source == TESTER_ADDRESS
    assert data == bytes([0x41, 0x0C, 0x03, 0x20])


def test_parse_frame_rejects_bad_checksum():
    frame = bytearray(build_frame(TARGET_ADDRESS, TESTER_ADDRESS, bytes([0x01])))
    frame[-1] ^= 0xFF  # corrupt the checksum byte
    assert parse_frame(bytes(frame)) is None
```

Run: `cd "D:\C180 Project\firmware\tools\kl_sim" && uv run --with pytest pytest test_protocol.py -v`
Expected: fails — `protocol` module doesn't exist yet.

- [ ] **Step 2: Write `protocol.py`**

```python
from __future__ import annotations

TARGET_ADDRESS = 0x33  # [Likely] placeholder ECU address — confirm in Phase 3
TESTER_ADDRESS = 0xF1  # [Confirmed] standard ISO 14230 tester address

SID_START_COMMUNICATION = 0x81
SID_START_COMMUNICATION_POSITIVE = 0xC1
SID_TESTER_PRESENT = 0x3E
SID_TESTER_PRESENT_POSITIVE = 0x7E
SID_SHOW_CURRENT_DATA = 0x01
SID_SHOW_CURRENT_DATA_POSITIVE = 0x41
SID_SHOW_FREEZE_FRAME = 0x02
SID_SHOW_FREEZE_FRAME_POSITIVE = 0x42
SID_STORED_DTCS = 0x03
SID_STORED_DTCS_POSITIVE = 0x43
SID_PENDING_DTCS = 0x07
SID_PENDING_DTCS_POSITIVE = 0x47
SID_VEHICLE_INFO = 0x09
SID_VEHICLE_INFO_POSITIVE = 0x49

# [Best estimate] KWP2000 default timing (ms) — negotiated values from a
# real ECU's StartCommunication response supersede these; unused by the
# simulator itself, kept here so firmware and simulator agree on the
# defaults if either side needs them.
P2_MIN_MS = 25
P2_MAX_MS = 50
P3_MIN_MS = 55


def checksum(data: bytes) -> int:
    return sum(data) & 0xFF


def build_frame(target: int, source: int, data: bytes) -> bytes:
    if len(data) > 63:
        raise ValueError("Phase 1 only supports payloads <= 63 bytes")
    fmt = 0x80 | len(data)
    body = bytes([fmt, target, source]) + data
    return body + bytes([checksum(body)])


def parse_frame(frame: bytes) -> tuple[int, int, bytes] | None:
    if len(frame) < 4:
        return None
    fmt, target, source = frame[0], frame[1], frame[2]
    length = fmt & 0x3F
    if len(frame) != 3 + length + 1:
        return None
    data = frame[3 : 3 + length]
    received_cs = frame[3 + length]
    if checksum(frame[: 3 + length]) != received_cs:
        return None
    return target, source, data
```

- [ ] **Step 3: Run the tests, confirm they pass**

```powershell
cd "D:\C180 Project\firmware\tools\kl_sim"
uv run --with pytest pytest test_protocol.py -v
```
Expected: 3 passed.

- [ ] **Step 4: Write the failing tests for response builders**

Append to `firmware/tools/kl_sim/test_protocol.py`:
```python
from protocol import (
    build_start_communication_response,
    build_tester_present_response,
    build_mode01_response,
    build_dtc_response,
    encode_dtc,
)


def test_start_communication_response_uses_positive_sid():
    _, _, data = parse_frame(build_start_communication_response())
    assert data[0] == 0xC1


def test_tester_present_response_uses_positive_sid():
    _, _, data = parse_frame(build_tester_present_response())
    assert data[0] == 0x7E


def test_mode01_response_rpm_encodes_correctly():
    _, _, data = parse_frame(build_mode01_response(pid=0x0C, value=800.0))
    assert data == bytes([0x41, 0x0C, 0x0C, 0x80])  # 800*4=3200=0x0C80


def test_encode_dtc_p0171_matches_sae_j2012():
    assert encode_dtc("P0171") == bytes([0x01, 0x71])


def test_build_dtc_response_stored_dtcs():
    _, _, data = parse_frame(build_dtc_response(sid_positive=0x43, dtcs=["P0171"]))
    assert data == bytes([0x43, 0x01, 0x01, 0x71])
```

- [ ] **Step 5: Run, confirm failure (functions don't exist yet)**

- [ ] **Step 6: Add the response builders to `protocol.py`**

Append:
```python
from wire_format import WIRE_FORMAT_TABLE, encode_formula


def build_start_communication_response() -> bytes:
    # [Best estimate] key bytes — not yet verified against a real SIM4LKE.
    return build_frame(TESTER_ADDRESS, TARGET_ADDRESS, bytes([SID_START_COMMUNICATION_POSITIVE, 0x8F, 0xE9]))


def build_tester_present_response() -> bytes:
    return build_frame(TESTER_ADDRESS, TARGET_ADDRESS, bytes([SID_TESTER_PRESENT_POSITIVE]))


def build_mode01_response(pid: int, value: float) -> bytes:
    wf = next(w for w in WIRE_FORMAT_TABLE.values() if w.mode01_pid == pid)
    payload = bytes([SID_SHOW_CURRENT_DATA_POSITIVE, pid]) + encode_formula(wf.formula, value)
    return build_frame(TESTER_ADDRESS, TARGET_ADDRESS, payload)


def encode_dtc(code: str) -> bytes:
    # SAE J2012 2-byte DTC encoding. code like "P0171".
    letter_bits = {"P": 0b00, "C": 0b01, "B": 0b10, "U": 0b11}[code[0]]
    digits = [int(c, 16) for c in code[1:]]
    byte1 = (letter_bits << 6) | (digits[0] << 4) | digits[1]
    byte2 = (digits[2] << 4) | digits[3]
    return bytes([byte1, byte2])


def build_dtc_response(sid_positive: int, dtcs: list[str]) -> bytes:
    payload = bytes([sid_positive, len(dtcs)])
    for code in dtcs:
        payload += encode_dtc(code)
    return build_frame(TESTER_ADDRESS, TARGET_ADDRESS, payload)
```

- [ ] **Step 7: Run all `protocol.py` tests, confirm they pass**

```powershell
uv run --with pytest pytest test_protocol.py -v
```
Expected: 8 passed.

- [ ] **Step 8: Leave for review** (no commit)

---

## Task 5: `kline_sim.py` — CLI/serial runner + scenarios + fixture-dump

**Files:**
- Create: `D:\C180 Project\firmware\tools\kline_sim.py`
- Create: `D:\C180 Project\firmware\tools\kl_sim\scenarios.py`
- Create: `D:\C180 Project\firmware\tools\kl_sim\test_scenarios.py`

- [ ] **Step 1: Write the failing test for scenario data**

`firmware/tools/kl_sim/test_scenarios.py`:
```python
from scenarios import SCENARIOS


def test_healthy_scenario_has_no_dtcs():
    assert SCENARIOS["healthy"].dtcs == []


def test_fault_injected_scenario_has_p0171():
    assert SCENARIOS["fault-injected"].dtcs == ["P0171"]


def test_healthy_scenario_idle_values_within_w203_reference_bands():
    values = SCENARIOS["healthy"].idle_values
    assert 3.0 <= values["MAF_GS"] <= 5.0  # docs/w203_reference.md healthy band
    assert -10.0 <= values["STFT1"] <= 10.0
    assert -10.0 <= values["LTFT1"] <= 10.0


def test_all_four_scenarios_exist():
    assert set(SCENARIOS) == {"healthy", "fault-injected", "slow", "unresponsive"}
```

- [ ] **Step 2: Run, confirm failure**

- [ ] **Step 3: Write `kl_sim/scenarios.py`**

Idle values are static (not a real drive cycle) — sufficient for Phase 1's
bench gate; a fuller drive-cycle simulation isn't required until later
guided-test work (PLAN.md §6.2) needs it.

```python
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Scenario:
    name: str
    idle_values: dict[str, float]
    dtcs: list[str] = field(default_factory=list)
    response_delay_ms: float = 0.0
    drop_first_n_requests: int = 0


_HEALTHY_IDLE = {
    "RPM": 850.0,
    "SPEED": 0.0,
    "MAF_GS": 4.0,       # docs/w203_reference.md: warm idle 3-5 g/s
    "THROTTLE": 0.0,
    "LOAD": 20.0,
    "STFT1": 2.0,        # within +-10% healthy band
    "LTFT1": 3.0,
    "MAP": 100.0,
    "TIMING_ADV": 10.0,
    "O2_B1S1_V": 0.45,
    "O2_B1S2_V": 0.60,
    "ECT": 90.0,         # near thermostat opening (87 C)
    "IAT": 25.0,
    "FUEL_STATUS": 2.0,  # closed loop
}

SCENARIOS: dict[str, Scenario] = {
    "healthy": Scenario(name="healthy", idle_values=_HEALTHY_IDLE),
    "fault-injected": Scenario(
        name="fault-injected", idle_values=_HEALTHY_IDLE, dtcs=["P0171"]
    ),
    "slow": Scenario(name="slow", idle_values=_HEALTHY_IDLE, response_delay_ms=60.0),
    "unresponsive": Scenario(
        name="unresponsive", idle_values=_HEALTHY_IDLE, drop_first_n_requests=3
    ),
}
```

- [ ] **Step 4: Run, confirm the 4 scenario tests pass**

```powershell
cd "D:\C180 Project\firmware\tools\kl_sim"
uv run --with pytest pytest test_scenarios.py -v
```
Expected: 4 passed.

- [ ] **Step 5: Write `firmware/tools/kline_sim.py`**

PEP 723 inline metadata makes `uv run firmware/tools/kline_sim.py ...` work
without a separate venv. `sys.path` manipulation lets it import the sibling
`kl_sim/` package without installing it.

```python
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyserial>=3.5"]
# ///
"""PC-side KWP2000 ECU simulator for KompressorLink firmware bench tests.

Bench topology (Phase 1, no L9637D yet): bare ESP32 UART2 (GPIO17/16) wired
directly to a USB-TTL adapter's RX/TX (crossed) + common GND. See
docs/superpowers/specs/2026-07-09-phase1-firmware-core-design.md.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "kl_sim"))

import serial  # noqa: E402

from protocol import (  # noqa: E402
    SID_PENDING_DTCS,
    SID_PENDING_DTCS_POSITIVE,
    SID_SHOW_CURRENT_DATA,
    SID_START_COMMUNICATION,
    SID_STORED_DTCS,
    SID_STORED_DTCS_POSITIVE,
    SID_TESTER_PRESENT,
    build_dtc_response,
    build_mode01_response,
    build_start_communication_response,
    build_tester_present_response,
    parse_frame,
)
from scenarios import SCENARIOS
from wire_format import ANSWERED_SIGNALS, WIRE_FORMAT_TABLE


def run(port: str, baud: int, scenario_name: str) -> None:
    scenario = SCENARIOS[scenario_name]
    pid_to_signal = {wf.mode01_pid: name for name, wf in WIRE_FORMAT_TABLE.items()}
    request_count = 0

    with serial.Serial(port, baud, timeout=1.0) as ser:
        print(f"kline_sim: listening on {port} @ {baud} baud, scenario={scenario_name}")
        while True:
            frame = ser.read(64)
            if not frame:
                continue
            parsed = parse_frame(frame)
            if parsed is None:
                continue  # bad checksum / partial frame — ignore, real ECUs do too
            _target, _source, data = parsed

            request_count += 1
            if request_count <= scenario.drop_first_n_requests:
                print(f"  dropping request #{request_count} (unresponsive scenario)")
                continue
            if scenario.response_delay_ms:
                time.sleep(scenario.response_delay_ms / 1000.0)

            sid = data[0]
            response = None
            if sid == SID_START_COMMUNICATION:
                response = build_start_communication_response()
            elif sid == SID_TESTER_PRESENT:
                response = build_tester_present_response()
            elif sid == SID_SHOW_CURRENT_DATA and len(data) >= 2:
                pid = data[1]
                signal = pid_to_signal.get(pid)
                if signal in ANSWERED_SIGNALS:
                    response = build_mode01_response(pid, scenario.idle_values[signal])
                # else: no response — simulates an unsupported PID, matching
                # PLAN.md §2's "expect them empty" for a 2004 K-line ECU.
            elif sid == SID_STORED_DTCS:
                response = build_dtc_response(SID_STORED_DTCS_POSITIVE, scenario.dtcs)
            elif sid == SID_PENDING_DTCS:
                response = build_dtc_response(SID_PENDING_DTCS_POSITIVE, [])

            if response is not None:
                ser.write(response)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=sorted(SCENARIOS), default="healthy", nargs="?")
    parser.add_argument("--port", required=True, help="e.g. COM5")
    parser.add_argument("--baud", type=int, default=10400, help="ISO 14230 standard K-line baud rate")
    args = parser.parse_args()
    run(args.port, args.baud, args.scenario)


if __name__ == "__main__":
    main()
```

- [ ] **Step 6: Manual smoke check (no hardware needed for this check)**

```powershell
cd "D:\C180 Project\firmware\tools"
uv run kline_sim.py --help
```
Expected: argparse help text listing the 4 scenario choices and `--port`/`--baud`. This confirms the PEP 723 dependency block resolves and the script imports cleanly, without needing a real serial port yet.

- [ ] **Step 7: Leave for review** (no commit)

---

## Task 6: `kline_frame` (C++ mirror of `protocol.py`'s framing) + `FakeTransport` test double

**Files:**
- Create: `D:\C180 Project\firmware\src\kline_frame.h`
- Create: `D:\C180 Project\firmware\src\kline_frame.cpp`
- Create: `D:\C180 Project\firmware\test\fake_transport.h`
- Test: `D:\C180 Project\firmware\test\test_kline_frame\test_kline_frame.cpp`

- [ ] **Step 1: Write the failing test**

`firmware/test/test_kline_frame/test_kline_frame.cpp`:
```cpp
#include <unity.h>

#include "kline_frame.h"

void test_checksum_matches_hand_computed_value(void) {
  const uint8_t data[] = {0x82, 0x33, 0xF1, 0x01, 0x0C};
  TEST_ASSERT_EQUAL_UINT8(0x82 + 0x33 + 0xF1 + 0x01 + 0x0C, kline_checksum(data, 5));
}

void test_build_frame_round_trips_through_parse_frame(void) {
  const uint8_t data[] = {0x41, 0x0C, 0x03, 0x20};
  uint8_t frame[16];
  size_t frame_len = kline_build_frame(kTargetAddress, kTesterAddress, data, 4, frame, sizeof(frame));

  ParsedFrame parsed;
  TEST_ASSERT_TRUE(kline_parse_frame(frame, frame_len, &parsed));
  TEST_ASSERT_EQUAL_UINT8(kTargetAddress, parsed.target);
  TEST_ASSERT_EQUAL_UINT8(kTesterAddress, parsed.source);
  TEST_ASSERT_EQUAL_UINT8(4, parsed.data_len);
  TEST_ASSERT_EQUAL_UINT8_ARRAY(data, parsed.data, 4);
}

void test_parse_frame_rejects_bad_checksum(void) {
  const uint8_t data[] = {0x01};
  uint8_t frame[16];
  size_t frame_len = kline_build_frame(kTargetAddress, kTesterAddress, data, 1, frame, sizeof(frame));
  frame[frame_len - 1] ^= 0xFF;

  ParsedFrame parsed;
  TEST_ASSERT_FALSE(kline_parse_frame(frame, frame_len, &parsed));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_checksum_matches_hand_computed_value);
  RUN_TEST(test_build_frame_round_trips_through_parse_frame);
  RUN_TEST(test_parse_frame_rejects_bad_checksum);
  return UNITY_END();
}
```

- [ ] **Step 2: Write `kline_frame.h`**

```cpp
#pragma once

#include <cstddef>
#include <cstdint>

// [Likely] placeholder ECU address — confirm against real SIM4LKE in Phase 3.
inline constexpr uint8_t kTargetAddress = 0x33;
// [Confirmed] standard ISO 14230 tester address.
inline constexpr uint8_t kTesterAddress = 0xF1;

struct ParsedFrame {
  uint8_t target;
  uint8_t source;
  uint8_t data[63];
  uint8_t data_len;
};

uint8_t kline_checksum(const uint8_t* data, size_t len);

// Writes a frame ([FMT][TGT][SRC][DATA...][CS]) into `out` (capacity
// `out_capacity`). Returns the frame length, or 0 if `out_capacity` is too
// small or `data_len` > 63.
size_t kline_build_frame(uint8_t target, uint8_t source, const uint8_t* data,
                          size_t data_len, uint8_t* out, size_t out_capacity);

// Parses `frame` into `*out`. Returns false on a length mismatch or bad
// checksum (mirrors protocol.py's parse_frame returning None).
bool kline_parse_frame(const uint8_t* frame, size_t frame_len, ParsedFrame* out);
```

- [ ] **Step 3: Write `kline_frame.cpp`**

```cpp
#include "kline_frame.h"

uint8_t kline_checksum(const uint8_t* data, size_t len) {
  uint32_t sum = 0;
  for (size_t i = 0; i < len; ++i) {
    sum += data[i];
  }
  return static_cast<uint8_t>(sum & 0xFF);
}

size_t kline_build_frame(uint8_t target, uint8_t source, const uint8_t* data,
                          size_t data_len, uint8_t* out, size_t out_capacity) {
  if (data_len > 63) {
    return 0;
  }
  const size_t frame_len = 3 + data_len + 1;
  if (out_capacity < frame_len) {
    return 0;
  }
  out[0] = static_cast<uint8_t>(0x80 | data_len);
  out[1] = target;
  out[2] = source;
  for (size_t i = 0; i < data_len; ++i) {
    out[3 + i] = data[i];
  }
  out[3 + data_len] = kline_checksum(out, 3 + data_len);
  return frame_len;
}

bool kline_parse_frame(const uint8_t* frame, size_t frame_len, ParsedFrame* out) {
  if (frame_len < 4) {
    return false;
  }
  const uint8_t fmt = frame[0];
  const size_t length = fmt & 0x3F;
  if (frame_len != 3 + length + 1) {
    return false;
  }
  if (kline_checksum(frame, 3 + length) != frame[3 + length]) {
    return false;
  }
  out->target = frame[1];
  out->source = frame[2];
  out->data_len = static_cast<uint8_t>(length);
  for (size_t i = 0; i < length; ++i) {
    out->data[i] = frame[3 + i];
  }
  return true;
}
```

- [ ] **Step 4: Run the test, confirm it passes**

Same caveat as Phase 0's `pid_schema` test: no host C++ compiler in this
environment, so `pio test -e native` can't run here. Verify by careful
manual trace instead (as Phase 0 did): checksum arithmetic, round-trip
byte-for-byte, and the corrupted-checksum case all check out by inspection.
Task 11 covers running this for real on the ESP32 target build, and Task 12
(bench run) is the real end-to-end proof.

- [ ] **Step 5: Write the shared `FakeTransport` test double**

`firmware/test/fake_transport.h` (at `test/` root, not inside a `test_`
suite folder, so PlatformIO compiles it into every suite that includes it)
— used by every `kline_kwp` test in Tasks 7+, not just this one:
```cpp
#pragma once

#include <cstring>
#include <vector>

#include "kline_transport.h"

// Test double: queue bytes for read() to hand back, capture what write()
// sends, and control the fake clock so timeout logic is testable without
// real time passing.
class FakeTransport : public KLineTransport {
 public:
  void write(const uint8_t* data, size_t len) override {
    written_.insert(written_.end(), data, data + len);
  }

  size_t read(uint8_t* buf, size_t len, uint32_t timeout_ms) override {
    (void)timeout_ms;  // FakeTransport never blocks — tests control timing via advance_ms().
    const size_t available = queued_.size() - read_pos_;
    const size_t n = available < len ? available : len;
    std::memcpy(buf, queued_.data() + read_pos_, n);
    read_pos_ += n;
    return n;
  }

  uint32_t now_ms() override { return fake_time_ms_; }

  void queue_response(const std::vector<uint8_t>& bytes) {
    queued_ = bytes;
    read_pos_ = 0;
  }

  void advance_ms(uint32_t delta) { fake_time_ms_ += delta; }

  std::vector<uint8_t> written_;

 private:
  std::vector<uint8_t> queued_;
  size_t read_pos_ = 0;
  uint32_t fake_time_ms_ = 0;
};
```

- [ ] **Step 6: Leave for review** (no commit)

---

## Task 7: `kline_kwp` — StartCommunication, tester-present, PID reads, re-init tracking

**Files:**
- Create: `D:\C180 Project\firmware\src\kline_kwp.h`
- Create: `D:\C180 Project\firmware\src\kline_kwp.cpp`
- Test: `D:\C180 Project\firmware\test\test_kline_kwp\test_kline_kwp.cpp`

- [ ] **Step 1: Write the failing tests**

`firmware/test/test_kline_kwp/test_kline_kwp.cpp`:
```cpp
#include <unity.h>

#include "fake_transport.h"
#include "kline_frame.h"
#include "kline_kwp.h"

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

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_start_communication_succeeds_on_positive_response);
  RUN_TEST(test_start_communication_fails_when_no_response_queued);
  RUN_TEST(test_read_pid_decodes_rpm_from_response);
  RUN_TEST(test_read_pid_returns_false_and_counts_timeout_when_unanswered);
  RUN_TEST(test_needs_reinit_becomes_true_after_3_consecutive_timeouts);
  RUN_TEST(test_successful_read_resets_consecutive_timeouts);
  return UNITY_END();
}
```

- [ ] **Step 2: Write `kline_kwp.h`**

```cpp
#pragma once

#include <cstdint>

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
  // NOT the ISO 14230 physical wake-pulse timing. See this plan's Scope
  // boundary note.
  bool start_communication();

  bool send_tester_present();

  // Requests Mode 01 data for `mode01_pid`. On success, decodes the response
  // via pid_wire_format's table and writes to *out_value, returns true. On
  // timeout (e.g. an unsupported PID — PLAN.md §2 expects several PIDs to
  // go unanswered on this ECU), returns false and increments
  // consecutive_timeouts() rather than blocking or erroring.
  bool read_pid(uint8_t mode01_pid, float* out_value);

  int consecutive_timeouts() const { return consecutive_timeouts_; }
  bool needs_reinit() const { return consecutive_timeouts_ >= 3; }

 private:
  bool send_request_and_get_response(const uint8_t* data, uint8_t data_len, ParsedFrame* out);

  KLineTransport& transport_;
  int consecutive_timeouts_ = 0;
};
```

- [ ] **Step 3: Write `kline_kwp.cpp`**

```cpp
#include "kline_kwp.h"

#include "kline_frame.h"
#include "pid_wire_format.h"

bool KlineKwp::send_request_and_get_response(const uint8_t* data, uint8_t data_len, ParsedFrame* out) {
  uint8_t frame[16];
  size_t frame_len = kline_build_frame(kTargetAddress, kTesterAddress, data, data_len, frame, sizeof(frame));
  transport_.write(frame, frame_len);

  uint8_t response[16];
  size_t response_len = transport_.read(response, sizeof(response), kResponseTimeoutMs);
  if (response_len == 0 || !kline_parse_frame(response, response_len, out)) {
    ++consecutive_timeouts_;
    return false;
  }
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
      *out_value = decode_formula(kWireFormatTable[i].formula, &response.data[2]);
      return true;
    }
  }
  return false;  // PID not in our schema — shouldn't happen if callers use pid_schema's table.
}
```

- [ ] **Step 4: Verify by manual trace**

No host compiler in this environment (same as Tasks 1-6) — trace each test
by hand against the implementation: confirm `test_start_communication_
fails_when_no_response_queued` hits `response_len == 0` (FakeTransport's
`read()` on an empty `queued_` returns 0), confirm the 3-timeouts test
increments correctly across 3 separate `read_pid` calls with nothing queued
between them, confirm the "successful read resets" test's second `read_pid`
call finds the freshly-queued frame. Task 11 covers the real ESP32 compile.

- [ ] **Step 5: Leave for review** (no commit)

---

## Task 8: `pid_scheduler` — one-signal-per-tick round-robin poller

Deliberately polls **one** signal per `tick()` call rather than bursting all
due signals at once — a burst design risks blowing the ~10 req/s K-line
budget the moment two tiers come due together; one-per-tick paces requests
naturally as long as `tick()` is called reasonably often from `loop()`.
Uses `next_due_ms` (not `last_poll_ms`) per signal, initialized to 0, so
everything is due immediately at boot rather than waiting a full interval
before the first poll of each signal.

**Files:**
- Create: `D:\C180 Project\firmware\src\pid_scheduler.h`
- Create: `D:\C180 Project\firmware\src\pid_scheduler.cpp`
- Test: `D:\C180 Project\firmware\test\test_pid_scheduler\test_pid_scheduler.cpp`

- [ ] **Step 1: Write the failing tests**

`firmware/test/test_pid_scheduler/test_pid_scheduler.cpp`:
```cpp
#include <unity.h>

#include "fake_transport.h"
#include "kline_frame.h"
#include "kline_kwp.h"
#include "pid_scheduler.h"

void test_first_tick_polls_the_first_due_signal(void) {
  FakeTransport transport;
  const uint8_t response_data[] = {0x41, 0x0C, 0x0C, 0x80};  // RPM=800.0
  uint8_t frame[16];
  size_t len = kline_build_frame(kTesterAddress, kTargetAddress, response_data, 4, frame, sizeof(frame));
  transport.queue_response(std::vector<uint8_t>(frame, frame + len));

  KlineKwp kwp(transport);
  PidScheduler scheduler(kwp);

  PidScheduler::Reading reading;
  TEST_ASSERT_TRUE(scheduler.tick(0, &reading));
  TEST_ASSERT_EQUAL(static_cast<int>(Signal::RPM), static_cast<int>(reading.signal));
  TEST_ASSERT_TRUE(reading.available);
  TEST_ASSERT_EQUAL_FLOAT(800.0f, reading.value);
}

void test_second_tick_at_same_time_polls_the_next_signal(void) {
  FakeTransport transport;
  const uint8_t rpm_data[] = {0x41, 0x0C, 0x0C, 0x80};
  const uint8_t speed_data[] = {0x41, 0x0D, 0x32};  // SPEED=50
  uint8_t rpm_frame[16], speed_frame[16];
  size_t rpm_len = kline_build_frame(kTesterAddress, kTargetAddress, rpm_data, 4, rpm_frame, sizeof(rpm_frame));
  size_t speed_len = kline_build_frame(kTesterAddress, kTargetAddress, speed_data, 3, speed_frame, sizeof(speed_frame));

  KlineKwp kwp(transport);
  PidScheduler scheduler(kwp);
  PidScheduler::Reading reading;

  transport.queue_response(std::vector<uint8_t>(rpm_frame, rpm_frame + rpm_len));
  scheduler.tick(0, &reading);  // consumes RPM's slot, sets its next_due_ms

  transport.queue_response(std::vector<uint8_t>(speed_frame, speed_frame + speed_len));
  TEST_ASSERT_TRUE(scheduler.tick(0, &reading));
  TEST_ASSERT_EQUAL(static_cast<int>(Signal::SPEED), static_cast<int>(reading.signal));
  TEST_ASSERT_EQUAL_FLOAT(50.0f, reading.value);
}

void test_unanswered_pid_produces_unavailable_reading_not_a_crash(void) {
  FakeTransport transport;  // nothing queued for anyone yet
  KlineKwp kwp(transport);
  PidScheduler scheduler(kwp);
  PidScheduler::Reading reading;

  scheduler.tick(0, &reading);  // RPM: unanswered
  TEST_ASSERT_FALSE(reading.available);
  TEST_ASSERT_EQUAL(static_cast<int>(Signal::RPM), static_cast<int>(reading.signal));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_first_tick_polls_the_first_due_signal);
  RUN_TEST(test_second_tick_at_same_time_polls_the_next_signal);
  RUN_TEST(test_unanswered_pid_produces_unavailable_reading_not_a_crash);
  return UNITY_END();
}
```

- [ ] **Step 2: Write `pid_scheduler.h`**

```cpp
#pragma once

#include <cstdint>

#include "kline_kwp.h"
#include "pid_schema.h"
#include "pid_wire_format.h"

// Polling intervals per tier, from PLAN.md §5.1's ~10 req/s K-line budget.
// LEGACY is polled slowly (not "not at all") specifically so the firmware's
// graceful-timeout handling for an unsupported PID gets exercised on real
// hardware, not just assumed — PLAN.md §2's Untested contract. LOCAL
// (BATT_V_ADC) isn't on K-line at all; excluded from this scheduler.
uint32_t tier_interval_ms(Tier tier);

class PidScheduler {
 public:
  struct Reading {
    Signal signal;
    float value;
    bool available;
  };

  explicit PidScheduler(KlineKwp& kwp);

  // Polls the single most-overdue signal at `now_ms`, if any. Returns true
  // and fills *out_reading if a poll happened (whether or not the ECU
  // answered — see `Reading::available`); false if nothing is due yet.
  bool tick(uint32_t now_ms, Reading* out_reading);

 private:
  KlineKwp& kwp_;
  uint32_t next_due_ms_[kWireFormatCount];
};
```

- [ ] **Step 3: Write `pid_scheduler.cpp`**

```cpp
#include "pid_scheduler.h"

uint32_t tier_interval_ms(Tier tier) {
  switch (tier) {
    case Tier::F:
      return 500;
    case Tier::M:
      return 2000;
    case Tier::S:
      return 10000;
    case Tier::LEGACY:
      return 30000;
    case Tier::LOCAL:
      return 0;
  }
  return 10000;
}

PidScheduler::PidScheduler(KlineKwp& kwp) : kwp_(kwp) {
  for (size_t i = 0; i < kWireFormatCount; ++i) {
    next_due_ms_[i] = 0;
  }
}

bool PidScheduler::tick(uint32_t now_ms, Reading* out_reading) {
  // Earliest-deadline-first: among all signals whose next_due_ms_ has
  // arrived, serve the one that's been overdue longest (ties go to the
  // lowest index, for deterministic behavior at boot when everything is
  // tied at 0). A fixed lowest-index-first scan would let Tier F
  // (indices 0-3, due every 500ms) permanently starve Tier S/LEGACY
  // signals further down the table -- including ECT, this car's most
  // safety-relevant signal -- once ideal demand (~11.9 req/s across all
  // tiers) exceeds the ~10 req/s K-line budget the schedule is meant to
  // fit inside. Caught in code review: an earlier draft of this function
  // broke on the first due index found, not the most overdue one.
  //
  // Uses signed-difference comparison rather than `next_due_ms_[i] <=
  // now_ms` so this stays correct across millis() wraparound instead of a
  // burst of early re-polls at the wrap boundary (~49.7 days of
  // continuous uptime). The signed-diff trick's own disambiguation window
  // is half that, ~24.8 days -- a signal overdue by more than that would
  // itself look "not due" -- which relies on power_mgr (a later module)
  // actually sleeping/rebooting the device between rides, well inside
  // that window; cheap to get this right regardless.
  size_t due_index = kWireFormatCount;
  int32_t most_overdue_by = -1;
  for (size_t i = 0; i < kWireFormatCount; ++i) {
    const int32_t overdue_by = static_cast<int32_t>(now_ms - next_due_ms_[i]);
    if (overdue_by >= 0 && overdue_by > most_overdue_by) {
      most_overdue_by = overdue_by;
      due_index = i;
    }
  }
  if (due_index == kWireFormatCount) {
    return false;
  }

  const WireFormat& wf = kWireFormatTable[due_index];
  const Tier tier = kSignalTable[static_cast<size_t>(wf.signal)].tier;
  next_due_ms_[due_index] = now_ms + tier_interval_ms(tier);

  out_reading->signal = wf.signal;
  out_reading->available = kwp_.read_pid(wf.mode01_pid, &out_reading->value);
  if (!out_reading->available) {
    out_reading->value = 0.0f;
  }
  return true;
}
```

- [ ] **Step 4: Verify by manual trace** (no host compiler; same as prior tasks)

Confirm: `kWireFormatTable[0]` is `Signal::RPM` (Task 3's table order), so
the first two tests' index-order assumptions hold. Confirm
`test_unanswered_pid_produces_unavailable_reading_not_a_crash` doesn't
dereference anything invalid when `read_pid` returns false — `out_reading->value`
gets explicitly zeroed, not left uninitialized.

- [ ] **Step 5: Leave for review** (no commit)

---

## Task 9: `ride_logger` — CSV writer + header block

Absence of a CSV row (not a blank-value row) represents "unavailable" —
matches PLAN.md §2's Untested contract with no extra parsing burden on
whatever ingests these files later.

**Files:**
- Create: `D:\C180 Project\firmware\src\ride_logger.h`
- Create: `D:\C180 Project\firmware\src\ride_logger.cpp`
- Create: `D:\C180 Project\firmware\test\fake_storage.h`
- Test: `D:\C180 Project\firmware\test\test_ride_logger\test_ride_logger.cpp`

- [ ] **Step 1: Write the `FakeStorage` test double**

`firmware/test/fake_storage.h` (at `test/` root, shared across suites, same
reasoning as `fake_transport.h` in Task 6):
```cpp
#pragma once

#include <string>
#include <vector>

#include "ride_storage.h"

class FakeStorage : public RideStorage {
 public:
  bool open_for_write(const char* filename) override {
    opened_filename = filename;
    return true;
  }
  void write_line(const char* line) override { lines.push_back(line); }
  void flush() override { ++flush_count; }
  void close() override { closed = true; }

  std::string opened_filename;
  std::vector<std::string> lines;
  int flush_count = 0;
  bool closed = false;
};
```

- [ ] **Step 2: Write the failing tests**

`firmware/test/test_ride_logger/test_ride_logger.cpp`:
```cpp
#include <unity.h>

#include "fake_storage.h"
#include "ride_logger.h"

void test_start_ride_writes_header_and_column_row(void) {
  FakeStorage storage;
  RideLogger logger(storage);
  TEST_ASSERT_TRUE(logger.start_ride("ride_001.csv", "0.0.1-phase1", "fast"));
  TEST_ASSERT_EQUAL_STRING("ride_001.csv", storage.opened_filename.c_str());
  TEST_ASSERT_EQUAL(3, storage.lines.size());
  TEST_ASSERT_EQUAL_STRING("#fw_version=0.0.1-phase1", storage.lines[0].c_str());
  TEST_ASSERT_EQUAL_STRING("#init_mode=fast", storage.lines[1].c_str());
  TEST_ASSERT_EQUAL_STRING("t_ms,signal,value", storage.lines[2].c_str());
}

void test_log_reading_writes_a_csv_row_for_available_reading(void) {
  FakeStorage storage;
  RideLogger logger(storage);
  logger.start_ride("r.csv", "v", "fast");

  PidScheduler::Reading reading{Signal::RPM, 800.0f, true};
  logger.log_reading(1234, reading);

  TEST_ASSERT_EQUAL(4, storage.lines.size());
  TEST_ASSERT_EQUAL_STRING("1234,RPM,800.000", storage.lines[3].c_str());
}

void test_log_reading_skips_row_for_unavailable_reading(void) {
  FakeStorage storage;
  RideLogger logger(storage);
  logger.start_ride("r.csv", "v", "fast");

  PidScheduler::Reading reading{Signal::PEDAL_D, 0.0f, false};
  logger.log_reading(1234, reading);

  TEST_ASSERT_EQUAL(3, storage.lines.size());  // still just the header block
}

void test_signal_name_returns_correct_string(void) {
  TEST_ASSERT_EQUAL_STRING("ECT", signal_name(Signal::ECT));
  TEST_ASSERT_EQUAL_STRING("O2_B1S1_V", signal_name(Signal::O2_B1S1_V));
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_start_ride_writes_header_and_column_row);
  RUN_TEST(test_log_reading_writes_a_csv_row_for_available_reading);
  RUN_TEST(test_log_reading_skips_row_for_unavailable_reading);
  RUN_TEST(test_signal_name_returns_correct_string);
  return UNITY_END();
}
```

- [ ] **Step 3: Write `ride_logger.h`**

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
  void flush();
  void close_ride();

 private:
  RideStorage& storage_;
};
```

- [ ] **Step 4: Write `ride_logger.cpp`**

```cpp
#include "ride_logger.h"

#include <cstdio>

const char* signal_name(Signal signal) {
  switch (signal) {
    case Signal::RPM: return "RPM";
    case Signal::SPEED: return "SPEED";
    case Signal::MAF_GS: return "MAF_GS";
    case Signal::THROTTLE: return "THROTTLE";
    case Signal::LOAD: return "LOAD";
    case Signal::STFT1: return "STFT1";
    case Signal::LTFT1: return "LTFT1";
    case Signal::MAP: return "MAP";
    case Signal::TIMING_ADV: return "TIMING_ADV";
    case Signal::O2_B1S1_V: return "O2_B1S1_V";
    case Signal::O2_B1S2_V: return "O2_B1S2_V";
    case Signal::ECT: return "ECT";
    case Signal::IAT: return "IAT";
    case Signal::FUEL_STATUS: return "FUEL_STATUS";
    case Signal::PEDAL_D: return "PEDAL_D";
    case Signal::PEDAL_E: return "PEDAL_E";
    case Signal::CMD_THROTTLE: return "CMD_THROTTLE";
    case Signal::CTRL_MODULE_V: return "CTRL_MODULE_V";
    case Signal::BATT_V_ADC: return "BATT_V_ADC";
    case Signal::COUNT: return "";
  }
  return "";
}

bool RideLogger::start_ride(const char* filename, const char* fw_version, const char* init_mode) {
  if (!storage_.open_for_write(filename)) {
    return false;
  }
  char header[64];
  snprintf(header, sizeof(header), "#fw_version=%s", fw_version);
  storage_.write_line(header);
  snprintf(header, sizeof(header), "#init_mode=%s", init_mode);
  storage_.write_line(header);
  storage_.write_line("t_ms,signal,value");
  storage_.flush();
  return true;
}

void RideLogger::log_reading(uint32_t t_ms, const PidScheduler::Reading& reading) {
  if (!reading.available) {
    return;
  }
  char line[64];
  snprintf(line, sizeof(line), "%lu,%s,%.3f", static_cast<unsigned long>(t_ms),
           signal_name(reading.signal), static_cast<double>(reading.value));
  storage_.write_line(line);
}

void RideLogger::flush() { storage_.flush(); }
void RideLogger::close_ride() { storage_.close(); }
```

- [ ] **Step 5: Verify by manual trace** (no host compiler; same as prior tasks)

Confirm `%.3f` formatting of `800.0f` produces `"800.000"` matching the test's
expected string exactly.

- [ ] **Step 6: Leave for review** (no commit)

---

## Task 10: Wire it together in `main.cpp` (esp32dev)

**Scope note:** `kwp.start_communication()` (Task 7) is the *logical*
StartCommunication exchange only — it doesn't distinguish fast-init vs
5-baud, since that distinction is a physical wake-pulse-timing difference
that only matters once real K-line hardware (L9637D) exists. Phase 2/3 adds
the real dual-attempt state machine. Don't write code here implying fast/
5-baud selection is already happening.

**Files:**
- Modify: `D:\C180 Project\firmware\src\main.cpp` (full replacement)

- [ ] **Step 1: Replace `main.cpp`**

```cpp
#include <Arduino.h>

#include "esp32_uart_transport.h"
#include "kline_kwp.h"
#include "littlefs_storage.h"
#include "pid_scheduler.h"
#include "ride_logger.h"
#include "version.h"

namespace {
Esp32UartTransport transport(Serial2);
KlineKwp kwp(transport);
PidScheduler scheduler(kwp);
LittleFsStorage storage;
RideLogger logger(storage);

uint32_t last_tester_present_ms = 0;
uint32_t last_flush_ms = 0;
constexpr uint32_t kTesterPresentIntervalMs = 2000;
constexpr uint32_t kFlushIntervalMs = 5000;
bool ride_active = false;
}  // namespace

void setup() {
  Serial.begin(115200);
  Serial2.begin(10400, SERIAL_8N1, /*RX=*/16, /*TX=*/17);
  delay(200);
  Serial.print("KompressorLink firmware boot, fw=");
  Serial.println(FW_VERSION);

  // Logical StartCommunication only (see kline_kwp.h) -- one retry on
  // failure. Real fast-init/5-baud dual-attempt with physical wake-pulse
  // timing is Phase 2/3 scope, once the L9637D exists.
  bool ok = kwp.start_communication();
  if (!ok) {
    Serial.println("StartCommunication failed, retrying once...");
    delay(500);
    ok = kwp.start_communication();
  }
  if (!ok) {
    Serial.println("StartCommunication failed twice -- check wiring / confirm kline_sim.py is running");
    return;
  }
  Serial.println("StartCommunication OK");

  if (!logger.start_ride("/ride_phase1.csv", FW_VERSION, "logical-init")) {
    Serial.println("Failed to open ride file on LittleFS");
    return;
  }
  ride_active = true;
  Serial.println("Ride file opened, polling...");
}

void loop() {
  if (!ride_active) {
    delay(1000);
    return;
  }

  const uint32_t now = millis();

  PidScheduler::Reading reading;
  if (scheduler.tick(now, &reading)) {
    logger.log_reading(now, reading);
    Serial.print(now);
    Serial.print(",");
    Serial.print(signal_name(reading.signal));
    Serial.print(",");
    if (reading.available) {
      Serial.println(reading.value, 3);
    } else {
      Serial.println("(unavailable)");
    }
  }

  if (now - last_tester_present_ms >= kTesterPresentIntervalMs) {
    kwp.send_tester_present();
    last_tester_present_ms = now;
  }

  if (now - last_flush_ms >= kFlushIntervalMs) {
    logger.flush();
    last_flush_ms = now;
  }

  if (kwp.needs_reinit()) {
    Serial.println("3 consecutive timeouts -- re-running StartCommunication");
    kwp.start_communication();
  }
}
```

- [ ] **Step 2: Leave for review** (no commit — Task 11 covers the real build)

---

## Task 11: Verify — real ESP32 build + `kl_sim` Python tests

**Files:** none — verification only.

- [ ] **Step 1: Run every `kl_sim` Python test suite for real**

```powershell
cd "D:\C180 Project\firmware\tools\kl_sim"
uv run --with pytest pytest -v
```
Expected: 17 passed (3 from `test_protocol.py`'s framing tests + 5 from its
response-builder tests + 4 from `test_scenarios.py` + the 5 wire-format
tests from Task 3's `test_wire_format.py`, which also lives in this
directory).

- [ ] **Step 2: Smoke-test `kline_sim.py`'s CLI resolves cleanly**

```powershell
cd "D:\C180 Project\firmware\tools"
uv run kline_sim.py --help
```
Expected: argparse help output, no import errors.

- [ ] **Step 3: Real ESP32 build**

```powershell
cd "D:\C180 Project\firmware"
pio run -e esp32dev
```
Expected: `SUCCESS`. This compiles every new file from Tasks 1–3 and 6–10
(`kline_transport.h`, `esp32_uart_transport.*`, `ride_storage.h`,
`littlefs_storage.*`, `pid_wire_format.*`, `kline_frame.*`, `kline_kwp.*`,
`pid_scheduler.*`, `ride_logger.*`, `main.cpp`) for the real target — the
same trustworthy verification path Phase 0 established (watch for the same
`build_unflags`/pip-in-venv gotchas if PlatformIO or its venv were
reinstalled since Phase 0; if either resurfaces, the fixes are already
documented in `platformio.ini`'s comment and this repo's history). If it
fails, read the actual error text before assuming success — don't trust a
misleading shell exit code (see Phase 0's build log lesson).

- [ ] **Step 4: Attempt native tests (likely still blocked, confirm honestly)**

```powershell
pio test -e native
```
Phase 0 found no host C++ compiler on this machine. If that's still true,
this will fail to even start compiling — that's expected, not a regression;
record it as still-unverified-natively rather than silently skipping the
check. If a compiler now exists (e.g. installed between sessions), run this
for real and report actual pass/fail for all native test files (`test_
pid_schema.cpp`, `test_pid_wire_format.cpp`, `test_kline_frame.cpp`, `test_
kline_kwp.cpp`, `test_pid_scheduler.cpp`, `test_ride_logger.cpp`).

- [ ] **Step 5: Leave for review** (no commit)

---

## Task 12: Real bench run — the Phase 1 acceptance gate

**Scope of what this proves:** firmware completes the logical
StartCommunication exchange, polls the full Tier F/M/S schedule (14
signals) plus the slow Legacy-tier probe (4 signals, expected unanswered),
and produces a real ride log — satisfying PLAN.md §9's "Full simulated ride
CSV on bench" for Phase 1. DTC/freeze-frame capture is explicitly NOT part
of this gate — that's `startup_snapshot`, out of scope per this plan's
opening scope section; `kline_sim.py`'s `fault-injected` scenario exists and
is tested (Task 5) but nothing in this phase's firmware calls Mode 03/02/07
yet, so don't wire it into this bench run.

**Files:** none — this is a hands-on verification task for you, not
something I can run headlessly (needs the real serial ports).

- [ ] **Step 1: Wire the bench topology**

Per the design doc §2 and `hardware/wiring.md`: your bare ESP32's UART2
(GPIO17 TX, GPIO16 RX) to a USB-TTL/FTDI adapter's RX/TX **crossed** (ESP32
TX17 → adapter RX, ESP32 RX16 → adapter TX) + common GND. No L9637D, no 12V,
no pull-up — that's Phase 2. This is separate from the ESP32's own
USB-to-PC connection (used for flashing and the `Serial`/debug monitor).

- [ ] **Step 2: Flash the firmware**

```powershell
cd "D:\C180 Project\firmware"
pio run -e esp32dev -t upload
```

- [ ] **Step 3: Start `kline_sim.py` on the USB-TTL adapter's COM port**

```powershell
cd "D:\C180 Project\firmware\tools"
uv run kline_sim.py healthy --port COMx   # replace COMx with the adapter's port
```
Expected console line: `kline_sim: listening on COMx @ 10400 baud, scenario=healthy`

- [ ] **Step 4: Watch the firmware's own debug output**

In a second terminal (the ESP32's *own* USB port, not the USB-TTL adapter):
```powershell
pio device monitor -e esp32dev
```
Expected sequence: `KompressorLink firmware boot, fw=0.0.1-phase1` →
`StartCommunication OK` → `Ride file opened, polling...` → a stream of
`t_ms,SIGNAL,value` lines. Confirm Tier F signals (RPM, SPEED, MAF_GS,
THROTTLE) appear roughly every ~500 ms, Tier M roughly every ~2 s, Tier S
every ~10 s. This live stream, captured to a file
(`pio device monitor -e esp32dev | Tee-Object bench_ride.log` in
PowerShell), *is* the "full simulated ride CSV" — PLAN.md's gate doesn't
require pulling the LittleFS-internal file off-device in Phase 1; that
retrieval path (WiFi sync or SD) doesn't exist until later phases.

- [ ] **Step 5: Confirm the Untested-contract behavior for real**

Somewhere in the captured log, confirm you do **not** see rows for
`PEDAL_D`/`PEDAL_E`/`CMD_THROTTLE`/`CTRL_MODULE_V` (the Legacy tier,
expected unanswered on this ECU) — their absence, not a blank value, is the
correct behavior per `ride_logger`'s design (Task 9).

- [ ] **Step 6: Re-run with `unresponsive` to prove the re-init path**

```powershell
uv run kline_sim.py unresponsive --port COMx
```
Confirm the monitor log shows `3 consecutive timeouts -- re-running
StartCommunication` after the simulator drops its first 3 responses, then
recovers into normal polling once `kline_sim.py`'s later responses succeed.

- [ ] **Step 7: Report back**

This step has no further automation — once you've run Steps 1–6, that *is*
Phase 1's acceptance gate satisfied. Nothing to commit (per this repo's
convention) beyond what's already sitting in the working tree from Tasks
1–10 for your review in VS Code.

