# KompressorLink Phase 0 Scaffold — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scaffold the `kompressorlink` monorepo at repo root `D:\C180 Project` — CLAUDE.md, hardware docs, a buildable-but-empty firmware PlatformIO project, a buildable-but-empty Android Compose project, and a working (lint+test-passing) Python pipeline skeleton — per `PLAN.md` §1 and the Phase 0 row of §9.

**Architecture:** `D:\C180 Project` becomes the repo root directly (it's already empty and dedicated — no nested `kompressorlink/` folder). One canonical 19-signal schema (name, unit, OBD Mode 01 PID, polling tier, physical bounds) is derived once here from PLAN.md §2/§5.1/§8 and then mirrored verbatim into three places: `CLAUDE.md` (human-readable source of truth), `firmware/src/pid_schema.h/.cpp` (C++), and `pipeline/src/kl/schema.py` (Python). Everything else in this phase is scaffold: valid, minimal, real (not TODO-stub) code that compiles/lints/tests green, with actual feature logic deferred to the phases PLAN.md assigns them to.

**Tech Stack:** PlatformIO (Arduino framework, ESP32), Kotlin + Jetpack Compose (Android), Python 3.11 + uv + click + ruff + pytest (pipeline), git.

**Environment findings this plan accounts for** (checked directly on the target machine before writing this plan):
- `D:\C180 Project` is empty except for `.claude/`. Not yet a git repo. Git 2.54 is available.
- Python 3.11.9 and `uv` 0.11.1 are installed — pipeline tasks are fully runnable and verifiable in this session.
- PlatformIO CLI is **not** installed. No host C/C++ compiler (no gcc/g++/cl.exe/MinGW) is present.
- No Android SDK, no Gradle, `ANDROID_HOME`/`ANDROID_SDK_ROOT` unset. Only Java 1.8 is on PATH (irrelevant — Android Studio bundles its own JDK 17+).
- **Consequence:** pipeline scaffold is verified for real in this session (`uv sync`, `ruff check`, `pytest`). Firmware is verified via `pio run -e esp32dev` — PlatformIO downloads its own Xtensa cross-compiler, so this doesn't need a host compiler, but it does mean a one-time ~300–500 MB download; **Task 11 asks for explicit confirmation before doing that.** The native-host unit test (`pio test -e native`) is written now but **cannot execute in this environment** (no host compiler) — it will run once you have PlatformIO + a compiler, or from Android/VS Code tooling that bundles one. Android scaffold cannot be gradle-synced or build-verified here at all; **Task 12 is a manual step for you in Android Studio.**
- Per PLAN.md §0.5, you commit via the VS Code Source Control GUI — **no task in this plan runs `git commit`.** Every task ends with the working tree ready for your review/commit.

---

## Task 1: Repo root, PLAN.md, .gitignore, directory skeleton

**Files:**
- Create: `D:\C180 Project\.gitignore`
- Create: `D:\C180 Project\PLAN.md` (copy of the source plan, so it's version-controlled with the code it governs)
- Create directories: `hardware/`, `firmware/src/`, `firmware/test/test_native/`, `firmware/tools/`, `android/app/src/main/java/com/kompressorlink/app/`, `android/gradle/`, `pipeline/src/kl/`, `pipeline/tests/`, `docs/`

- [ ] **Step 1: Initialize git**

Run (PowerShell, cwd `D:\C180 Project`):
```powershell
git init
```
Expected: `Initialized empty Git repository in D:/C180 Project/.git/`

- [ ] **Step 2: Create the directory skeleton**

```powershell
New-Item -ItemType Directory -Force -Path `
  "hardware", `
  "firmware\src", "firmware\test\test_native", "firmware\tools", `
  "android\app\src\main\java\com\kompressorlink\app", "android\gradle", `
  "pipeline\src\kl", "pipeline\tests", `
  "docs" | Out-Null
```

- [ ] **Step 3: Copy the source plan into the repo**

```powershell
Copy-Item "C:\Users\DELL\Downloads\KOMPRESSORLINK_PLAN.md" "D:\C180 Project\PLAN.md"
```

- [ ] **Step 4: Write `.gitignore`**

```gitignore
# Python / uv
pipeline/.venv/
**/__pycache__/
*.pyc

# PlatformIO
firmware/.pio/

# Android / Gradle
android/.gradle/
android/build/
android/*/build/
android/local.properties
android/.idea/
android/*.iml
android/captures/

# General
.DS_Store
Thumbs.db
*.log
```

- [ ] **Step 5: Verify**

```powershell
git status
```
Expected: `PLAN.md`, `.gitignore`, and the (currently empty, untracked-by-git-until-populated) directories show as untracked. No commit yet — leave for VS Code review.

---

## Task 2: `CLAUDE.md` — ground rules, schema constants, conventions

**Files:**
- Create: `D:\C180 Project\CLAUDE.md`

- [ ] **Step 1: Write the file**

```markdown
# CLAUDE.md — KompressorLink

Car-resident OBD logger (ESP32) + Android live-dashboard app + PC analytics
pipeline, purpose-built for ONE car: Mercedes-Benz W203 C180 Kompressor 2004
(M271.946, SIM4LKE ECU, 722.6 auto). Personal tool, not commercial. Full plan
and rationale: [`PLAN.md`](PLAN.md) — this file is the condensed, load-bearing
subset an agent needs on every task.

## Ground rules (non-negotiable — PLAN.md §0)

1. **Scope honesty.** This is a 2004 car: standard OBD-II here means K-line
   (ISO 14230 KWP2000 / ISO 9141-2, OBD pin 7, ~10.4 kbps), ENGINE ONLY.
   Throughput ceiling ≈ 8–15 PID requests/sec TOTAL. Never design or word UI
   as if transmission/ABS/SRS data is available. Proprietary-module scanning
   is Phase 7, experimental, listen-only, no gate.
2. **"Continuous learning" = baseline + drift learning only.** Personal-normal
   envelopes update per healthy ride; slow-trend models retrain on per-ride
   aggregates; supervised models retrain offline on PC. Never claim or build
   self-labeling fault learning.
3. **The device must never harm the car.**
   - CAN transceiver LISTEN-ONLY. TX line physically unconnected (jumper open).
   - Only ECU write ever allowed: Mode 04 clear-DTC, behind a double-confirm
     dialog warning it erases evidence and resets readiness monitors.
   - Sleep current budget: **< 5 mA** total device draw.
   - Fused, TVS-protected, reverse-polarity-protected 12 V front end.
   - Docs must tell the user to UNPLUG the device before XENTRY/coding sessions.
4. **Confidence tags** on anything unverified for this exact ECU:
   `[Confirmed]` / `[Likely]` / `[Guessing]` (or `[Best estimate]` for a
   reasoned-but-unsourced physical bound). The SIM4LKE's exact PID set and
   init mode are `[Likely]` until Phase 3's car probe settles them.
5. **Workflow preferences:**
   - Full replacement files (not diffs) when asked to change a file — keep
     modules small enough to regenerate whole.
   - **Never run `git commit`.** The user commits via the VS Code Source
     Control GUI. An agent's job ends with a clean, reviewable working tree.
   - The user runs tests locally unless explicitly asked to run them here.

## Repo layout

```
CLAUDE.md               this file
PLAN.md                 full execution plan, all phases
hardware/                BOM, wiring tables, human build/bring-up guide
firmware/                PlatformIO project (ESP32, Arduino framework)
  src/                    pid_schema (shared) now; kline_kwp/pid_scheduler/
                          ride_logger/power_mgr/ble_svc/wifi_sync/... in
                          Phase 1+ (see firmware/src/README.md)
  test/test_native/       host-native Unity tests
  tools/                  kline_sim.py (Phase 1) — PC-side K-line ECU simulator
android/                  Kotlin + Jetpack Compose app (minSdk 26, target 34+)
pipeline/                 Python 3.11 / uv, package `kl`
docs/                     w203_reference.md — healthy bands, DTC interpretation
```

## Canonical signal schema (single source of truth)

19 signals total: the graduation project's 14-PID set plus 5 added for this
car (`MAF_GS`, `O2_B1S1_V`, `O2_B1S2_V`, `BATT_V_ADC`, `FUEL_STATUS`). This
table is mirrored **verbatim** in `firmware/src/pid_schema.h/.cpp` and
`pipeline/src/kl/schema.py` — if you change a bound here, change it in both.

Tiers (from PLAN.md §5.1's polling budget, ≈10 req/s total on K-line):
- **F** ≈2 Hz, **M** ≈0.5 Hz, **S** ≈0.1 Hz — actively scheduled OBD polls.
- **Local** — read off the ESP32's own hardware (ADC), no bus cost.
- **Legacy** — kept in the schema for CSV-column stability but not actively
  polled: PLAN.md §2 expects these unsupported on a 2004 K-line ECU.

| Signal | Unit | Mode/PID | Tier | Min | Max | Confidence | Note |
|---|---|---|---|---|---|---|---|
| RPM | rpm | 01 0C | F | 0 | 8000 | Confirmed | SAE J1979; M271 redline ≈6500 rpm |
| SPEED | km/h | 01 0D | F | 0 | 255 | Confirmed | SAE J1979 raw range |
| MAF_GS | g/s | 01 10 | F | 0 | 400 | Best estimate | SAE raw range 0–655.35; capped for M271 supercharged plausibility |
| THROTTLE | % | 01 11 | F | 0 | 100 | Confirmed | SAE J1979 TPS |
| LOAD | % | 01 04 | M | 0 | 100 | Confirmed | SAE J1979 calculated engine load |
| STFT1 | % | 01 06 | M | -100 | 99.2 | Confirmed | SAE J1979 bank 1 short-term fuel trim |
| LTFT1 | % | 01 07 | M | -100 | 99.2 | Confirmed | SAE J1979 bank 1 long-term fuel trim |
| MAP | kPa | 01 0B | M | 0 | 250 | Best estimate | SAE raw range 0–255; capped near boost ceiling (~0.55 bar ≈155 kPa abs) + margin |
| TIMING_ADV | ° | 01 0E | M | -64 | 63.5 | Confirmed | SAE J1979 |
| O2_B1S1_V | V | 01 14 | M | 0 | 1.275 | Likely | Assumes narrowband-style Mode01 response; SIM4LKE support TBD Phase 3 |
| O2_B1S2_V | V | 01 15 | M | 0 | 1.275 | Likely | Post-cat; SIM4LKE support TBD Phase 3 |
| ECT | °C | 01 05 | S | -40 | 130 | Confirmed | PLAN.md §0.4 reference value |
| IAT | °C | 01 0F | S | -40 | 80 | Best estimate | Engine-bay intake air; tighter than SAE raw -40–215 |
| FUEL_STATUS | enum | 01 03 | S | 1 | 8 | Confirmed | SAE J1979 enumerated states |
| PEDAL_D | % | 01 49 | Legacy | 0 | 100 | Likely unsupported | 2004 K-line ECU; column kept, expect empty |
| PEDAL_E | % | 01 4A | Legacy | 0 | 100 | Likely unsupported | same |
| CMD_THROTTLE | % | 01 4C | Legacy | 0 | 100 | Likely unsupported | same |
| CTRL_MODULE_V | V | 01 42 | Legacy | 0 | 20 | Likely unsupported | same |
| BATT_V_ADC | V | local ADC (GPIO34) | Local | 0 | 18 | Confirmed | ESP32 self-measurement via divider, not ECU-dependent |

## Untested contract

Never alert on, or design a UI element around, a signal the ECU doesn't
provide. Every analytics output must carry (or respect) a PID-availability
mask. This is load-bearing for this specific car (PLAN.md §2).

## Persistence baseline

No trend/forecaster ships unless it beats a persistence baseline on rolling
backtests (PLAN.md §2, §7 `kl trend`). This is a hard gate, not a suggestion.

## Phase index

See `PLAN.md` §9 for the full table and acceptance gates. Phase 0 (this
scaffold) has no downstream dependency on hardware, a car, or a phone — it
is pure repo/schema/doc setup.
```

- [ ] **Step 2: Verify**

Read the file back and confirm the schema table has exactly 19 data rows and no `TBD`/`TODO` markers.

---

## Task 3: `docs/w203_reference.md`

**Files:**
- Create: `D:\C180 Project\docs\w203_reference.md`

- [ ] **Step 1: Write the file**

```markdown
# W203 C180 Kompressor (2004, M271.946) — Reference Data

Source: owner's workshop guide for this specific car (see PLAN.md §8). Confirm
any safety-critical value against WIS for the VIN before acting on it.

## Healthy bands

| Signal | Healthy band | Confidence |
|---|---|---|
| MAF (warm idle) | 3–5 g/s | Confirmed |
| Battery, resting | ≥ 12.5 V | Confirmed |
| Battery, charging | 13.8–14.5 V | Confirmed |
| Battery, cranking | ≥ 9.6 V | Confirmed |
| Thermostat opening | 87 °C | Confirmed |
| Boost, peak | ≈ 0.4–0.55 bar | Best estimate |
| Fuel trims (STFT/LTFT) | ±10 % | Confirmed |
| Fuel rail pressure | ≈ 3.8 bar | Best estimate (not OBD-visible on this ECU — reference only) |
| Ignition coil primary resistance | 0.4–1.0 Ω | Best estimate |
| Injector resistance | 12–16 Ω | Best estimate |

## DTC interpretation layer (W203-specific likely causes)

| DTC family | Likely cause, in order | Notes |
|---|---|---|
| P0170 / P0171 | Partial-load breather hoses (**the** M271 fault) → MAF → fuel pressure | Check breather hoses first, every time |
| P0016 / P0017 | Timing chain stretch / cam-magnet | |
| P0010–P0014 | Cam adjuster magnet, oil ingress | |
| P0300 family | Coils / plugs | Correct plug: FR6MPP332 or NGK ILFR6A |
| P0420 | Verify with the in-app cat-health snapshot test before condemning the catalyst | See PLAN.md §6.2 guided test |
| Cooling-system codes | Split diagnosis: fan-side vs. flow-side | |

## Caveat

These are reference values from the owner's guide project for this specific
VIN/spec, not a substitute for the factory workshop manual. Confirm
safety-critical values (torque specs, clearances, electrical limits) against
WIS before acting on them.
```

- [ ] **Step 2: Verify**

Read the file back; confirm both tables are present and no row says `TBD`.

---

## Task 4: `hardware/BOM.md`

**Files:**
- Create: `D:\C180 Project\hardware\BOM.md`

- [ ] **Step 1: Write the file**

```markdown
# Bill of Materials — KompressorLink logger (module-based, no custom PCB)

| # | Part | Purpose | Notes |
|---|---|---|---|
| 1 | ESP32-WROOM-32 DevKitC | MCU: TWAI(CAN) + BLE + WiFi + ULP deep sleep | Classic ESP32, NOT S3/C3 — widest lib support, BT Classic fallback |
| 2 | L9637D (SO-8 breakout or DIP adapter) | K-line transceiver (ISO 9141/14230 PHY) | Standard hobby K-line PHY |
| 3 | SN65HVD230 breakout | CAN transceiver, CAN-C listen-only | TX pin left DISCONNECTED via jumper |
| 4 | Buck converter, Vin ≥ 40 V rated (e.g. MP9486A / LM2596HV-based), 12 V→5 V | Power | Automotive transients need headroom; avoid cheap 28 V-max MP1584 boards |
| 5 | TVS diode SMBJ33CA (or 5KP33A for more margin) | Load-dump clamp on 12 V input | |
| 6 | 1 A blade/inline fuse + holder | Input protection | FIRST in line from OBD pin 16 |
| 7 | Schottky diode SS34 (or P-FET) | Reverse-polarity protection | |
| 8 | microSD SPI breakout + 8–32 GB card (A1 class) | Ride logging | |
| 9 | OBD-II male plug, breakout screw terminals (J1962M) | Car connection | Buy the pigtail/breakout type |
| 10 | Voltage divider 100 kΩ / 10 kΩ + 100 nF | Battery-voltage sense into ADC (12 V → ≈1.1 V) | High values = negligible drain |
| 11 | Perfboard, DuPont/JST wire, small ABS enclosure | Assembly | Must fit in driver footwell by OBD socket |
| 12 | (Optional) DS3231 RTC module | Accurate timestamps without phone | ESP32 RTC drifts minutes/day; phone time-sync makes this optional |

Rough cost: ≈£20–30 in parts. Tools assumed: soldering iron, multimeter.
```

- [ ] **Step 2: Verify**

Confirm the table has all 12 rows and matches PLAN.md §3.

---

## Task 5: `hardware/wiring.md`

**Files:**
- Create: `D:\C180 Project\hardware\wiring.md`

- [ ] **Step 1: Write the file**

```markdown
# Wiring — OBD-II socket and ESP32 GPIO map

## OBD-II socket (J1962, [Confirmed] for W203)

| OBD pin | Signal | Connects to |
|---|---|---|
| 16 | +12 V permanent (Terminal 30) | Fuse → reverse diode (SS34) → TVS → buck Vin; also divider → ESP32 ADC (GPIO34) |
| 4, 5 | Chassis / signal ground | Common ground |
| 7 | K-line | L9637D K pin; L9637D TX/RX → ESP32 UART2 |
| 6, 14 | CAN-C High / Low (500 kbps, MB-proprietary) | SN65HVD230 H/L; RX → ESP32 TWAI RX; **TX jumper OPEN, always** |

## ESP32 GPIO map

| GPIO | Function | Source |
|---|---|---|
| 34 (ADC1, input-only) | Battery voltage sense (through 100k/10k divider) | Correct pin choice — GPIO34 is ADC1-capable and input-only |
| 17 | UART2 TX → L9637D TX | K-line |
| 16 | UART2 RX ← L9637D RX | K-line |
| 4 | TWAI (CAN) RX ← SN65HVD230 RX; also the RTC-capable wake pin for CAN-activity wake | GPIO4 is RTC-IO-capable on classic ESP32 — consistent with the wake design in PLAN.md §5.1 |
| — | TWAI TX | **Not wired.** Jumper physically open — safety requirement, not just a firmware setting |
| 5 | SD card SPI CS | |
| 18 | SD card SPI SCK | |
| 19 | SD card SPI MISO | |
| 23 | SD card SPI MOSI | |

## Notes

- 100 nF ceramic cap from the ADC34 divider node to ground, close to the pin,
  to tame injected noise from the buck converter.
- Fuse is the *first* component in the 12 V chain from OBD pin 16 — nothing
  upstream of it.
```

- [ ] **Step 2: Verify**

Cross-check every GPIO number against PLAN.md §3 and §4 Stage B — no invented pin numbers.

---

## Task 6: `hardware/build_guide.md`

**Files:**
- Create: `D:\C180 Project\hardware\build_guide.md`

- [ ] **Step 1: Write the file**

```markdown
# Build guide — bench to car bring-up

Human tasks. Reference `hardware/BOM.md` for parts and `hardware/wiring.md` for
pin assignments. Firmware targets referenced below (`bench_selftest`,
`kline_probe`) are built in later phases (PLAN.md §9) — this guide is written
now so it's ready when they land.

## Stage A — bench power (no car)

- [ ] 1. Solder the power chain on perfboard: input screw terminals → fuse →
      SS34 → TVS across rails → buck module → 5 V to ESP32 VIN.
- [ ] 2. Feed 12 V from a bench supply (or battery charger). Verify
      5.0–5.2 V at ESP32 VIN with a multimeter **before** connecting the ESP32.
- [ ] 3. Add the 100k/10k divider from the protected 12 V node to GPIO34, and
      100 nF to ground. Verify divider output ≈1.09 V at 12.0 V input.

## Stage B — peripherals

- [ ] 4. Wire microSD (SPI: CS=GPIO5, SCK=18, MISO=19, MOSI=23). Flash the
      `bench_selftest` firmware target once it exists (Phase 1/2) — it must
      report SD write/read OK and print the ADC-derived input voltage within
      ±0.2 V of the multimeter reading (calibrate the constant).
- [ ] 5. Wire L9637D: VS=12 V protected rail, GND, K→screw terminal (later
      OBD pin 7), TX→GPIO17, RX→GPIO16.
- [ ] 6. Wire SN65HVD230: 3V3, GND, CANH/CANL→terminals (later pins 6/14),
      RX→GPIO4 (TWAI RX **and** wired to the RTC-capable wake path),
      TX→header pin with **no jumper fitted**.

## Stage C — bench protocol test (still no car)

- [ ] 7. Run `firmware/tools/kline_sim.py` (Phase 1 deliverable) on the PC
      with a USB-UART adapter wired to the L9637D K-line through a 1 kΩ
      pull-up to 12 V — it emulates a KWP2000 ECU (fast-init + 5-baud init,
      Mode 01/03/02 responses with canned M271-plausible data). The firmware
      must complete init, poll the tier schedule, and write a valid ride CSV
      to SD. **This simulator is the single most important de-risking
      artifact in the project.**

## Stage D — car bring-up

- [ ] 8. With everything verified on bench: connect the OBD breakout pigtail
      per `hardware/wiring.md`.
- [ ] 9. First plug-in, engine OFF, ignition ON: firmware probe (`kline_probe`
      target, Phase 3) logs which init succeeded (fast vs 5-baud), the
      supported-PID bitmask (Mode 01 PIDs 0x00/0x20/0x40), attempts Mode 09
      VIN, then idles.
- [ ] 10. Parasitic check per the owner's guide DD.3 procedure: after 35 min
      locked, clamp/multimeter the device feed — must be **< 5 mA**. Fix
      before leaving it plugged in overnight.
```

- [ ] **Step 2: Verify**

Confirm all 10 numbered steps are present and match PLAN.md §4 A–D.

---

## Task 7: Firmware scaffold — PlatformIO project skeleton

**Files:**
- Create: `D:\C180 Project\firmware\platformio.ini`
- Create: `D:\C180 Project\firmware\src\version.h`
- Create: `D:\C180 Project\firmware\src\main.cpp`
- Create: `D:\C180 Project\firmware\src\README.md`
- Create: `D:\C180 Project\firmware\tools\README.md`

- [ ] **Step 1: Write `platformio.ini`**

```ini
[env:esp32dev]
platform = espressif32
board = esp32dev
framework = arduino
monitor_speed = 115200
build_flags = -std=gnu++17

[env:native]
platform = native
test_framework = unity
build_flags = -std=gnu++17 -I src
build_src_filter = +<*> -<main.cpp>
```

- [ ] **Step 2: Write `src/version.h`**

```cpp
#pragma once

#define FW_VERSION "0.0.1-phase0"
```

- [ ] **Step 3: Write `src/main.cpp`**

```cpp
#include <Arduino.h>

#include "version.h"

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.printf("KompressorLink firmware boot, fw=%s\n", FW_VERSION);
}

void loop() {
  delay(1000);
}
```

- [ ] **Step 4: Write `src/README.md`** (module map for Phase 1+, keeps Phase 0 from inventing empty stub modules it doesn't need yet)

```markdown
# firmware/src/ — module map

Phase 0 only scaffolds the build (`platformio.ini`, `main.cpp`, `version.h`)
and the shared `pid_schema` (signal table + physical-bounds guard — also
mirrored in `pipeline/src/kl/schema.py`; keep both in sync).

Modules below are Phase 1+ (PLAN.md §5.1, §9) — not yet created:

| Module | File(s) | Responsibility |
|---|---|---|
| power_mgr | power_mgr.h/.cpp | Deep sleep, wake sources, parasitic-draw budget |
| kline_kwp | kline_kwp.h/.cpp | ISO 14230 KWP2000 stack: init, tester-present, Mode 01/02/03/07/09 |
| pid_scheduler | pid_scheduler.h/.cpp | Tiered polling against `pid_schema`'s Tier field |
| ride_logger | ride_logger.h/.cpp | Long-format CSV writer, flush+fsync, header block |
| startup_snapshot | startup_snapshot.h/.cpp | DTC/freeze-frame/readiness capture on wake |
| ble_svc | ble_svc.h/.cpp | GATT telemetry/control/ride-index service |
| wifi_sync | wifi_sync.h/.cpp | SoftAP + HTTP bulk ride-file transfer |
| can_sniffer | can_sniffer.h/.cpp | Phase 7, default off. Listen-only TWAI frame census |

`pid_schema.h/.cpp` (Phase 0) is the one piece of shared state these all
depend on — don't duplicate the signal table elsewhere in firmware.
```

- [ ] **Step 5: Write `tools/README.md`**

```markdown
# firmware/tools/

`kline_sim.py` — the PC-side K-line ECU simulator — is a Phase 1 deliverable
(PLAN.md §4 Stage C, §9). Not created yet.
```

- [ ] **Step 6: Verify directory structure**

```powershell
Get-ChildItem -Recurse "D:\C180 Project\firmware" | Select-Object FullName
```
Expected: `platformio.ini`, `src/version.h`, `src/main.cpp`, `src/README.md`, `tools/README.md`, plus the empty `test/test_native/` directory from Task 1.

---

## Task 8: Firmware — shared `pid_schema` (signal table + bounds guard) with native unit test

This is the one piece of real logic in the firmware scaffold — small enough to
TDD properly, and it's the thing Phase 1's `ride_logger` and `pid_scheduler`
will both depend on.

**Files:**
- Create: `D:\C180 Project\firmware\test\test_native\test_pid_schema.cpp`
- Create: `D:\C180 Project\firmware\src\pid_schema.h`
- Create: `D:\C180 Project\firmware\src\pid_schema.cpp`

- [ ] **Step 1: Write the test first**

```cpp
#include <unity.h>

#include "pid_schema.h"

void test_rpm_within_bounds(void) {
  TEST_ASSERT_TRUE(pid_schema_in_bounds(Signal::RPM, 850.0f));
}

void test_rpm_rejects_above_max(void) {
  TEST_ASSERT_FALSE(pid_schema_in_bounds(Signal::RPM, 9000.0f));
}

void test_ect_rejects_below_min(void) {
  TEST_ASSERT_FALSE(pid_schema_in_bounds(Signal::ECT, -50.0f));
}

void test_signal_table_size_matches_enum_count(void) {
  TEST_ASSERT_EQUAL(19, static_cast<int>(Signal::COUNT));
}

int main(int argc, char **argv) {
  UNITY_BEGIN();
  RUN_TEST(test_rpm_within_bounds);
  RUN_TEST(test_rpm_rejects_above_max);
  RUN_TEST(test_ect_rejects_below_min);
  RUN_TEST(test_signal_table_size_matches_enum_count);
  return UNITY_END();
}
```

- [ ] **Step 2: Note on running it now**

This environment has no host C/C++ compiler (checked: no gcc, g++, cl.exe, or
MinGW), so `pio test -e native` cannot execute here. Write the test now anyway
(TDD discipline, and it documents intended behavior) — it will run once you
have PlatformIO + a host compiler (e.g. via MSYS2/MinGW-w64 or VS Build Tools
with the C++ workload), or once real hardware exists in Phase 2/3. Task 11
verifies this scaffold compiles a different way: the real ESP32 target build.

- [ ] **Step 3: Write `src/pid_schema.h`**

```cpp
#pragma once

#include <cstddef>
#include <cstdint>

enum class Signal : uint8_t {
  RPM,
  SPEED,
  MAF_GS,
  THROTTLE,
  LOAD,
  STFT1,
  LTFT1,
  MAP,
  TIMING_ADV,
  O2_B1S1_V,
  O2_B1S2_V,
  ECT,
  IAT,
  FUEL_STATUS,
  PEDAL_D,
  PEDAL_E,
  CMD_THROTTLE,
  CTRL_MODULE_V,
  BATT_V_ADC,
  COUNT
};

enum class Tier : uint8_t { F, M, S, LOCAL, LEGACY };

struct SignalBounds {
  Signal signal;
  float min;
  float max;
  Tier tier;
};

extern const SignalBounds kSignalTable[static_cast<size_t>(Signal::COUNT)];

bool pid_schema_in_bounds(Signal signal, float value);
```

- [ ] **Step 4: Write `src/pid_schema.cpp`**

Bounds below are the CLAUDE.md schema table (Task 2), transcribed exactly —
array order must match the `Signal` enum order in `pid_schema.h`.

```cpp
#include "pid_schema.h"

const SignalBounds kSignalTable[static_cast<size_t>(Signal::COUNT)] = {
    {Signal::RPM, 0.0f, 8000.0f, Tier::F},
    {Signal::SPEED, 0.0f, 255.0f, Tier::F},
    {Signal::MAF_GS, 0.0f, 400.0f, Tier::F},
    {Signal::THROTTLE, 0.0f, 100.0f, Tier::F},
    {Signal::LOAD, 0.0f, 100.0f, Tier::M},
    {Signal::STFT1, -100.0f, 99.2f, Tier::M},
    {Signal::LTFT1, -100.0f, 99.2f, Tier::M},
    {Signal::MAP, 0.0f, 250.0f, Tier::M},
    {Signal::TIMING_ADV, -64.0f, 63.5f, Tier::M},
    {Signal::O2_B1S1_V, 0.0f, 1.275f, Tier::M},
    {Signal::O2_B1S2_V, 0.0f, 1.275f, Tier::M},
    {Signal::ECT, -40.0f, 130.0f, Tier::S},
    {Signal::IAT, -40.0f, 80.0f, Tier::S},
    {Signal::FUEL_STATUS, 1.0f, 8.0f, Tier::S},
    {Signal::PEDAL_D, 0.0f, 100.0f, Tier::LEGACY},
    {Signal::PEDAL_E, 0.0f, 100.0f, Tier::LEGACY},
    {Signal::CMD_THROTTLE, 0.0f, 100.0f, Tier::LEGACY},
    {Signal::CTRL_MODULE_V, 0.0f, 20.0f, Tier::LEGACY},
    {Signal::BATT_V_ADC, 0.0f, 18.0f, Tier::LOCAL},
};

bool pid_schema_in_bounds(Signal signal, float value) {
  const SignalBounds &b = kSignalTable[static_cast<size_t>(signal)];
  return value >= b.min && value <= b.max;
}
```

- [ ] **Step 5: Verify by inspection**

Count the initializer rows in `kSignalTable` — must be exactly 19, in the same
order as the `Signal` enum. Cross-check every min/max against the CLAUDE.md
table from Task 2. (Compiled verification happens in Task 11 via the ESP32
target build, which doesn't need a host compiler.)

---

## Task 9: Pipeline scaffold — `pyproject.toml`, `kl.schema`, `kl.cli`

This is fully runnable and verifiable in this session (`uv` and Python 3.11
are installed).

**Files:**
- Create: `D:\C180 Project\pipeline\pyproject.toml`
- Create: `D:\C180 Project\pipeline\src\kl\__init__.py`
- Create: `D:\C180 Project\pipeline\src\kl\schema.py`
- Create: `D:\C180 Project\pipeline\src\kl\cli.py`
- Test: `D:\C180 Project\pipeline\tests\test_schema.py`
- Test: `D:\C180 Project\pipeline\tests\test_cli.py`

- [ ] **Step 1: Write `pyproject.toml`**

```toml
[project]
name = "kompressorlink-pipeline"
version = "0.1.0"
description = "PC analytics pipeline for KompressorLink (W203 C180 Kompressor OBD logger)"
requires-python = ">=3.11"
dependencies = [
    "click>=8.1",
]

[project.scripts]
kl = "kl.cli:cli"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["src/kl"]

[tool.ruff]
line-length = 100
target-version = "py311"

[tool.ruff.lint]
select = ["E", "F", "I", "UP"]

[dependency-groups]
dev = [
    "pytest>=8.0",
    "ruff>=0.6",
]
```

- [ ] **Step 2: Write `src/kl/__init__.py`**

```python
```

(empty — marks `kl` as a package)

- [ ] **Step 3: Write the failing test for `schema.py`**

```python
from kl.schema import Signal, in_bounds


def test_rpm_within_bounds():
    assert in_bounds(Signal.RPM, 850.0) is True


def test_rpm_rejects_above_max():
    assert in_bounds(Signal.RPM, 9000.0) is False


def test_ect_rejects_below_min():
    assert in_bounds(Signal.ECT, -50.0) is False


def test_signal_table_covers_all_signals():
    assert len(Signal) == 19
```

Save as `pipeline/tests/test_schema.py`.

- [ ] **Step 4: Run it, confirm it fails on import**

```powershell
cd "D:\C180 Project\pipeline"
uv sync
uv run pytest tests/test_schema.py -v
```
Expected: `ModuleNotFoundError: No module named 'kl.schema'` (or import error) — `schema.py` doesn't exist yet.

- [ ] **Step 5: Write `src/kl/schema.py`**

Same 19-signal table as `CLAUDE.md` (Task 2) and `firmware/src/pid_schema.cpp`
(Task 8) — keep all three in sync if a bound ever changes.

```python
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, auto


class Signal(Enum):
    RPM = auto()
    SPEED = auto()
    MAF_GS = auto()
    THROTTLE = auto()
    LOAD = auto()
    STFT1 = auto()
    LTFT1 = auto()
    MAP = auto()
    TIMING_ADV = auto()
    O2_B1S1_V = auto()
    O2_B1S2_V = auto()
    ECT = auto()
    IAT = auto()
    FUEL_STATUS = auto()
    PEDAL_D = auto()
    PEDAL_E = auto()
    CMD_THROTTLE = auto()
    CTRL_MODULE_V = auto()
    BATT_V_ADC = auto()


class Tier(Enum):
    F = auto()
    M = auto()
    S = auto()
    LOCAL = auto()
    LEGACY = auto()


@dataclass(frozen=True)
class SignalBounds:
    signal: Signal
    min: float
    max: float
    tier: Tier


SIGNAL_TABLE: dict[Signal, SignalBounds] = {
    Signal.RPM: SignalBounds(Signal.RPM, 0.0, 8000.0, Tier.F),
    Signal.SPEED: SignalBounds(Signal.SPEED, 0.0, 255.0, Tier.F),
    Signal.MAF_GS: SignalBounds(Signal.MAF_GS, 0.0, 400.0, Tier.F),
    Signal.THROTTLE: SignalBounds(Signal.THROTTLE, 0.0, 100.0, Tier.F),
    Signal.LOAD: SignalBounds(Signal.LOAD, 0.0, 100.0, Tier.M),
    Signal.STFT1: SignalBounds(Signal.STFT1, -100.0, 99.2, Tier.M),
    Signal.LTFT1: SignalBounds(Signal.LTFT1, -100.0, 99.2, Tier.M),
    Signal.MAP: SignalBounds(Signal.MAP, 0.0, 250.0, Tier.M),
    Signal.TIMING_ADV: SignalBounds(Signal.TIMING_ADV, -64.0, 63.5, Tier.M),
    Signal.O2_B1S1_V: SignalBounds(Signal.O2_B1S1_V, 0.0, 1.275, Tier.M),
    Signal.O2_B1S2_V: SignalBounds(Signal.O2_B1S2_V, 0.0, 1.275, Tier.M),
    Signal.ECT: SignalBounds(Signal.ECT, -40.0, 130.0, Tier.S),
    Signal.IAT: SignalBounds(Signal.IAT, -40.0, 80.0, Tier.S),
    Signal.FUEL_STATUS: SignalBounds(Signal.FUEL_STATUS, 1.0, 8.0, Tier.S),
    Signal.PEDAL_D: SignalBounds(Signal.PEDAL_D, 0.0, 100.0, Tier.LEGACY),
    Signal.PEDAL_E: SignalBounds(Signal.PEDAL_E, 0.0, 100.0, Tier.LEGACY),
    Signal.CMD_THROTTLE: SignalBounds(Signal.CMD_THROTTLE, 0.0, 100.0, Tier.LEGACY),
    Signal.CTRL_MODULE_V: SignalBounds(Signal.CTRL_MODULE_V, 0.0, 20.0, Tier.LEGACY),
    Signal.BATT_V_ADC: SignalBounds(Signal.BATT_V_ADC, 0.0, 18.0, Tier.LOCAL),
}


def in_bounds(signal: Signal, value: float) -> bool:
    bounds = SIGNAL_TABLE[signal]
    return bounds.min <= value <= bounds.max
```

- [ ] **Step 6: Run the test again, confirm it passes**

```powershell
uv run pytest tests/test_schema.py -v
```
Expected: 4 passed.

- [ ] **Step 7: Write the failing test for `cli.py`**

```python
from click.testing import CliRunner

from kl.cli import cli


def test_ingest_reports_not_implemented():
    result = CliRunner().invoke(cli, ["ingest", "some_dir"])
    assert result.exit_code == 0
    assert "not implemented yet" in result.output.lower()
    assert "phase 5" in result.output.lower()


def test_report_reports_not_implemented():
    result = CliRunner().invoke(cli, ["report", "ride_001"])
    assert result.exit_code == 0
    assert "not implemented yet" in result.output.lower()


def test_trend_reports_not_implemented():
    result = CliRunner().invoke(cli, ["trend"])
    assert result.exit_code == 0
    assert "phase 6" in result.output.lower()
```

Save as `pipeline/tests/test_cli.py`.

- [ ] **Step 8: Run it, confirm it fails on import**

```powershell
uv run pytest tests/test_cli.py -v
```
Expected: `ModuleNotFoundError: No module named 'kl.cli'`.

- [ ] **Step 9: Write `src/kl/cli.py`**

```python
from __future__ import annotations

import click


@click.group()
def cli() -> None:
    """KompressorLink pipeline CLI."""


@cli.command()
@click.argument("directory", type=click.Path(exists=False))
def ingest(directory: str) -> None:
    """Raw long CSV -> canonical wide parquet (Phase 5)."""
    click.echo(f"kl ingest: not implemented yet (Phase 5). directory={directory}")


@cli.command()
def features() -> None:
    """Per-ride aggregate features (Phase 5)."""
    click.echo("kl features: not implemented yet (Phase 5)")


@cli.command()
def baseline() -> None:
    """Per-regime healthy-ride envelopes (Phase 6)."""
    click.echo("kl baseline: not implemented yet (Phase 6)")


@cli.command()
def trend() -> None:
    """Drift forecasters, gated on beating persistence (Phase 6)."""
    click.echo("kl trend: not implemented yet (Phase 6)")


@cli.command()
@click.argument("ride")
def report(ride: str) -> None:
    """Markdown health report for one ride (Phase 5)."""
    click.echo(f"kl report: not implemented yet (Phase 5). ride={ride}")


if __name__ == "__main__":
    cli()
```

- [ ] **Step 10: Run the full pipeline test suite, confirm it passes**

```powershell
uv run pytest -v
```
Expected: 7 passed (4 from `test_schema.py`, 3 from `test_cli.py`).

- [ ] **Step 11: Lint**

```powershell
uv run ruff check .
```
Expected: `All checks passed!`

---

## Task 10: Android scaffold — minimal Compose project skeleton

No Android SDK, Gradle, or JDK 17+ is available in this environment, so this
task creates source/config files only — it cannot be gradle-synced or
build-verified here. Task 12 covers the manual verification you'll do in
Android Studio.

**Files:**
- Create: `D:\C180 Project\android\settings.gradle.kts`
- Create: `D:\C180 Project\android\build.gradle.kts`
- Create: `D:\C180 Project\android\gradle.properties`
- Create: `D:\C180 Project\android\gradle\libs.versions.toml`
- Create: `D:\C180 Project\android\app\build.gradle.kts`
- Create: `D:\C180 Project\android\app\src\main\AndroidManifest.xml`
- Create: `D:\C180 Project\android\app\src\main\java\com\kompressorlink\app\MainActivity.kt`
- Create: `D:\C180 Project\android\README.md`

- [ ] **Step 1: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kompressorlink"
include(":app")
```

- [ ] **Step 2: Write `build.gradle.kts` (root)**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

- [ ] **Step 3: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 4: Write `gradle/libs.versions.toml`**

Versions are best-effort-current as of plan authoring; Android Studio may
prompt an AGP/Gradle upgrade on first open — accept it if so.

```toml
[versions]
agp = "8.7.2"
kotlin = "2.0.20"
coreKtx = "1.13.1"
lifecycleRuntimeKtx = "2.8.6"
activityCompose = "1.9.2"
composeBom = "2024.09.00"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 5: Write `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kompressorlink.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kompressorlink.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-phase0"
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
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
}
```

- [ ] **Step 6: Write `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
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
    </application>
</manifest>
```

- [ ] **Step 7: Write `app/src/main/java/com/kompressorlink/app/MainActivity.kt`**

```kotlin
package com.kompressorlink.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BootScreen()
                }
            }
        }
    }
}

@Composable
fun BootScreen() {
    Text("KompressorLink — Phase 0 scaffold. Live dashboard arrives in Phase 4.")
}
```

- [ ] **Step 8: Write `android/README.md`**

```markdown
# android/ — scaffold status

This is a minimal Compose skeleton created in Phase 0. It has **not** been
gradle-synced or build-verified — the machine that scaffolded it has no
Android SDK, Gradle, or JDK 17+ installed.

To bring it up:
1. Open this `android/` folder as a project in Android Studio (Koala or newer).
2. Let Android Studio generate the Gradle wrapper and sync
   (File > Sync Project with Gradle Files). It will offer to create
   `gradlew`/`gradlew.bat` if they're missing — accept.
3. Confirm it builds and shows "KompressorLink — Phase 0 scaffold..." on an
   emulator or the S23 FE.
4. Real screens (dashboard, DTC, rides, trends, guided tests) are built in
   Phase 4 per `PLAN.md` §6.

Versions pinned in `gradle/libs.versions.toml` (AGP 8.7.2, Kotlin 2.0.20,
Compose BOM 2024.09.00) are best-effort-current as of plan authoring —
Android Studio may prompt an AGP/Gradle upgrade on first open; accept it.
```

- [ ] **Step 9: Verify structure**

```powershell
Get-ChildItem -Recurse "D:\C180 Project\android" | Select-Object FullName
```
Confirm all 8 files above are present. No build attempted here — see Task 12.

---

## Task 11: Verify pipeline (real) and firmware (ESP32 target build)

**Files:** none — this task runs verification, doesn't create files.

- [ ] **Step 1: Full pipeline verification**

```powershell
cd "D:\C180 Project\pipeline"
uv sync
uv run ruff check .
uv run pytest -v
```
Expected: sync succeeds, ruff reports no issues, all 7 tests pass. This is the
real, executable Phase 0 gate for the pipeline.

- [ ] **Step 2: Confirm before the firmware build**

Installing PlatformIO CLI (`uv tool install platformio`) is a small,
lightweight, reversible install. But the *first* `pio run -e esp32dev`
downloads PlatformIO's bundled Espressif Xtensa toolchain — roughly
300–500 MB, taking several minutes depending on connection. **Ask the user to
confirm before running this step**, since it's a meaningful one-time
bandwidth/disk cost even though it's fully local and reversible
(`pio system prune` or deleting `~/.platformio` undoes it).

- [ ] **Step 3: Install PlatformIO CLI and build the ESP32 target**

Once confirmed:
```powershell
uv tool install platformio
cd "D:\C180 Project\firmware"
pio run -e esp32dev
```
Expected: `SUCCESS` — this compiles `main.cpp` + `pid_schema.cpp` for the real
ESP32 target, which is the meaningful Phase 0 "builds clean" gate for
firmware (it doesn't need a host compiler — PlatformIO's Xtensa toolchain is
separate from the host gcc/MinGW/MSVC that `pio test -e native` would need).

- [ ] **Step 4: Record what remains unverified**

Note in the task tracker / final summary to the user:
- `pio test -e native` (the Unity test from Task 8) is written but unrun —
  needs a host C++ compiler.
- Android (Task 10) is unrun — needs Android Studio (Task 12).

---

## Task 12: Manual step for the user — Android Studio sync

Not automatable in this environment. After the plan is otherwise complete,
tell the user directly (this step doesn't run any tool):

> Open `D:\C180 Project\android` in Android Studio, let it sync/generate the
> Gradle wrapper, and confirm the app builds and shows the Phase 0 boot
> screen on an emulator or your S23 FE. Report back if AGP/Gradle/Kotlin
> versions needed bumping — we'll update `gradle/libs.versions.toml` to match
> so the repo reflects what actually built.

---

## Task 13: Final review — confirm nothing was committed

**Files:** none.

- [ ] **Step 1: Confirm git status shows everything staged for *review*, not committed**

```powershell
cd "D:\C180 Project"
git status
git log --oneline
```
Expected: `git log` shows **no commits** (or only commits the user made
themselves outside this plan). All new files show as untracked/modified,
ready for the user to review and commit via the VS Code Source Control GUI
per PLAN.md §0.5 / CLAUDE.md.

- [ ] **Step 2: Summarize for the user**

List what was created, what was verified for real (pipeline: yes; firmware:
ESP32 target build, if Task 11 Step 3 was confirmed and run; native unit
test: no, needs a host compiler; Android: no, needs Android Studio — Task 12).
State clearly this satisfies the Phase 0 row of `PLAN.md` §9
("Repo scaffold, CLAUDE.md, schema constants, w203_reference docs" /
"Builds/lints clean") to the extent automatable on this machine, and name the
two follow-ups (host compiler for native tests; Android Studio for the app)
explicitly rather than implying they're done.
