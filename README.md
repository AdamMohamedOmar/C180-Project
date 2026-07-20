# KompressorLink

Car-resident OBD logger + Android live dashboard + PC analytics pipeline,
purpose-built for **one car**: a 2004 Mercedes-Benz W203 C180 Kompressor
(M271.946 engine, SIM4LKE ECU, 722.6 automatic transmission).

This is a personal project, not a commercial product or a generic OBD-II
tool — every design decision (PID list, throughput budget, "healthy"
reference bands) is tuned to this one vehicle. See [`PLAN.md`](PLAN.md) for
the full rationale and [`CLAUDE.md`](CLAUDE.md) for the condensed ground
rules and schema that govern this repo.

## What it does

- An ESP32 rides in the car, wakes on ignition, and polls the ECU over the
  factory K-line at a tiered rate, logging every ride to flash as CSV.
- An Android app (phone mounted in the car) connects over BLE for a live
  gauge dashboard, DTC lookups with plain-English explanations, a
  personal-baseline health/drift engine, and a maintenance tracker — then
  pulls finished ride files over a WiFi SoftAP link when in range.
- A Python pipeline on the PC ingests synced rides for longer-horizon
  analysis (trend/drift models gated on beating a persistence baseline).

## Scope — read this before assuming capability

This is a **2004 car**. Standard OBD-II here means **K-line**
(ISO 14230 KWP2000 / ISO 9141-2, OBD pin 7, ~10.4 kbps), **engine data
only** — there is no transmission, ABS, or SRS bus access. Total
throughput is roughly 8–15 PID requests/second, shared across every signal
the app shows. CAN-C (the Mercedes proprietary bus) is scanned
**listen-only**, experimentally, for research — it is not a supported data
source and gates nothing.

Docs and code mark anything unverified for this exact ECU with a
confidence tag — `[Confirmed]`, `[Likely]`, `[Guessing]`, or
`[Best estimate]` — so it's always clear what's measured versus assumed
until the Phase 3 car probe settles it.

## The device must never harm the car

- The CAN transceiver is listen-only; its TX line is physically
  unconnected in hardware.
- The only ECU write path anywhere in this project is Mode 04 (clear
  DTCs), gated behind a double-confirm dialog warning that it erases
  evidence and resets readiness monitors.
- Sleep current budget is under 5 mA for the whole device.
- The 12 V input is fused, TVS-clamped, and reverse-polarity protected.
- **Unplug the device before any XENTRY/coding session.**

## Repo layout

```
CLAUDE.md      conventions + ground rules an agent needs on every task
PLAN.md        full execution plan: phases, hardware BOM, protocol notes
hardware/      BOM, wiring tables, build/bring-up guide (human tasks)
firmware/      PlatformIO project — ESP32, Arduino framework
  src/           KWP2000 stack, PID scheduler, ride logger, BLE + WiFi sync
  test/          native (host) Unity test suites, one per module
  tools/         kline_sim.py — PC-side K-line ECU simulator for bench tests
android/       Kotlin + Jetpack Compose app (minSdk 26, target 35)
pipeline/      Python 3.11, uv-managed, package `kl`
docs/          protocol docs + w203_reference.md (healthy bands, DTCs)
```

## Status

Hardware has not been assembled or run against the car yet (Phases 2–3 in
[`PLAN.md`](PLAN.md) §9) — only a bare ESP32 devkit exists on hand, for
demo-mode bring-up. Everything below has been built and tested off the
real K-line bus, against a simulator or a synthetic demo feed.

| Component | State |
|---|---|
| Firmware | KWP2000 init/poll stack, tiered PID scheduler, LittleFS ride storage (CRC sidecars, FIFO retention), BLE telemetry/control service, WiFi SoftAP ride sync. Builds clean for `esp32dev`, `esp32dev_demo`, and `native`; the native Unity suite covers protocol framing, scheduling, storage, and sync logic. |
| Android | 5-tab Compose app — Dashboard, Health, DTCs, Maintenance, Rides — with Companion Device Manager auto-connect, live BLE telemetry, a personal-baseline drift/severity health engine, maintenance reminders, layered DTC explanations, and resumable WiFi ride sync/ingest into local Room history. |
| Pipeline | Schema mirror (`kl/schema.py`) and CLI scaffold (`kl ingest / features / baseline / trend / report`) are in place; the commands themselves are stubs — Phase 5/6 analytics aren't implemented yet. |

## Getting started

Each component has its own toolchain; there's no single top-level build.

### Firmware (PlatformIO)

```sh
cd firmware
pio run -e esp32dev        # or esp32dev_demo — no UART/K-line, synthetic demo feed
pio test -e native         # host-native unit tests, no hardware needed
```

### Android (Android Studio / Gradle)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd android
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

Or open `android/` directly in Android Studio and let it sync. See
[`android/README.md`](android/README.md).

### Pipeline (uv)

```sh
cd pipeline
uv sync
uv run kl --help
uv run pytest
```

## Signal schema

The 19-signal canonical schema (PIDs, units, polling tier, physical
bounds, confidence) is defined once in [`CLAUDE.md`](CLAUDE.md) and
mirrored verbatim in `firmware/src/pid_schema.h/.cpp` and
`pipeline/src/kl/schema.py`. Every analytics output is expected to respect
a PID-availability mask — nothing should alert on a signal this ECU
doesn't actually provide.

## Documentation map

- [`PLAN.md`](PLAN.md) — full plan: phases, BOM, protocol design, acceptance gates
- [`CLAUDE.md`](CLAUDE.md) — condensed ground rules and schema
- [`docs/w203_reference.md`](docs/w203_reference.md) — healthy bands + DTC interpretations
- [`docs/ble_protocol.md`](docs/ble_protocol.md) — phone↔device BLE GATT protocol
- [`docs/wifi_sync_protocol.md`](docs/wifi_sync_protocol.md) — WiFi ride-sync manifest/chunk protocol
- [`hardware/BOM.md`](hardware/BOM.md), [`hardware/wiring.md`](hardware/wiring.md), [`hardware/build_guide.md`](hardware/build_guide.md) — parts, wiring, assembly

## Project status & license

Solo personal project built around one specific car; not seeking external
contributions. No license file is present, so no license is granted — all
rights reserved by default.
