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
  test/                   host-native Unity tests, one test_<name>/ suite
                          folder per module (PlatformIO requires this —
                          sibling test_*.cpp files with their own main()
                          in one folder fail to link); shared test doubles
                          (fake_transport.h etc.) live at test/ root
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
| O2_B1S1_V | V | 01 14 | M | 0 | 1.275 | Likely | Assumes narrowband-style Mode01 response; SIM4LKE support unconfirmed, resolves at Phase 3 probe |
| O2_B1S2_V | V | 01 15 | M | 0 | 1.275 | Likely | Post-cat; SIM4LKE support unconfirmed, resolves at Phase 3 probe |
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
