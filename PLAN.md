# KOMPRESSORLINK — Execution Plan for Claude Code (Sonnet)

**Project:** Car-resident OBD logger (ESP32) + Android live-dashboard app (Samsung S23 FE)
+ PC analytics pipeline, purpose-built for ONE car: Mercedes-Benz W203 C180 Kompressor 2004
(M271.946 engine, SIM4LKE ECU, 722.6 auto). Personal tool, not commercial.

**This is a NEW project.** Do not modify the graduation repo
(github.com/AdamMohamedOmar/Predictive-MaintenanceV1). Carry its *lessons* (listed in §2),
write everything fresh. New monorepo, suggested name `kompressorlink`.

---

## 0. Non-negotiable ground rules

1. **Honesty about scope.** Standard OBD-II on this 2004 car = ENGINE ONLY, over K-line
   (ISO 14230 KWP2000 / ISO 9141-2, pin 7, ~10.4 kbps). Throughput ceiling ≈ 8–15 PID
   requests/sec TOTAL. Do not promise or design UI implying transmission/ABS/SRS access in
   v1. Proprietary-module scanning is Phase 7 (experimental).
2. **"Continuous learning" = baseline + drift learning.** Personal-normal envelopes update
   per healthy ride; slow-trend models retrain on per-ride aggregates; supervised models
   retrain offline on PC. Never claim self-labeling fault learning.
3. **The device must never harm the car.**
   - CAN transceiver in LISTEN-ONLY mode; TX line not connected in hardware (jumper open).
   - Only ECU *writes* allowed: Mode 04 clear-DTC, behind a double-confirm dialog with a
     warning that it erases evidence and resets readiness monitors.
   - Total sleep current budget: **< 5 mA** (car's healthy-sleep target is < 50 mA total;
     the owner's guide DD.3 procedure will be used to verify).
   - Fused, TVS-protected, reverse-polarity-protected 12 V front end (load dump is real on
     a 20-year-old alternator).
   - Instruction to user in docs: UNPLUG the device before any XENTRY/coding session.
4. **Confidence tags in docs and code comments** where a value is unverified for this exact
   ECU: [Confirmed] / [Likely] / [Guessing]. The SIM4LKE's exact supported-PID set and init
   mode are [Likely] until the first car probe (Phase 3 empirically resolves them).
5. **User workflow prefs:** full replacement files (not diffs) when asked for changes; he
   runs tests locally; commits via VS Code Source Control GUI. Keep modules small enough
   to regenerate whole.

---

## 1. Repo layout (Phase 0 scaffolds this)

```
kompressorlink/
  CLAUDE.md                  # conventions, schema constants, ground rules (mirror §0)
  hardware/
    BOM.md                   # §3 bill of materials
    build_guide.md           # §4 step-by-step assembly + bring-up (human tasks)
    wiring.md                # OBD pin -> module pin tables
  firmware/                  # PlatformIO project, Arduino framework on ESP32
    platformio.ini
    src/ ...                 # §5
    test/                    # native unit tests (protocol framing, CSV writer, scheduler)
    tools/kline_sim.py       # PC-side K-line ECU simulator over USB-serial for bench tests
  android/                   # Kotlin + Jetpack Compose, single-module app  §6
  pipeline/                  # Python 3.11+, uv-managed venv  §7
    pyproject.toml
    src/kl/ ...
    tests/
  docs/
    w203_reference.md        # healthy bands + DTC interpretations extracted from the
                             # owner's manual (source values listed in §8)
```

---

## 2. Lessons carried from the graduation project (concepts, not code)

- **Canonical wide schema at 1 Hz** with strict physical-bounds guards (null impossible
  values, e.g. RPM 0–8000, ECT −40–130 °C). Raw capture stays long-format & lossless;
  canonicalisation happens in the pipeline, never in firmware.
- **The grad repo's 14-PID set** (RPM, SPEED, THROTTLE, LOAD, ECT, LTFT1, STFT1, MAP,
  PEDAL_D, PEDAL_E, CMD_THROTTLE, IAT, TIMING_ADV, CTRL_MODULE_V). **W203 extension:**
  add `MAF_GS`, `O2_B1S1_V`, `O2_B1S2_V`, `BATT_V_ADC` (ESP32's own measurement),
  `FUEL_STATUS`. [Likely] PEDAL_D/E, CMD_THROTTLE, CTRL_MODULE_V are unsupported on a
  2004 K-line ECU — keep columns, expect them empty.
- **"Untested contract":** every analytics output carries a PID-availability mask; never
  alert on a signal the ECU doesn't provide. This is load-bearing for this car.
- **Persistence baseline is mandatory** for every forecaster; a trend model that can't
  beat persistence doesn't ship (the grad LTFT forecaster lost to persistence — the fix
  is per-ride aggregation over multi-week horizons, not fancier models).
- **Adapter pattern:** one shared raw→canonical adapter used by every consumer (CLI,
  dashboard, trainer) so no path silently diverges.
- **Trailing-comma/CSV-integrity paranoia:** a malformed-CSV bug once silently shrank the
  grad dataset from 129 files to 8. The pipeline's ingest must validate row width, and
  firmware must flush+fsync ride files periodically (power can drop any time).

---

## 3. Hardware — Bill of Materials (module-based, no custom PCB in v1)

| # | Part | Purpose | Notes |
|---|------|---------|-------|
| 1 | ESP32-WROOM-32 DevKitC | MCU: TWAI(CAN) + BLE + WiFi + ULP deep sleep | Classic ESP32, NOT S3/C3 (widest lib support, has BT Classic fallback) |
| 2 | L9637D (SO-8 on breakout, or DIP adapter) | K-line transceiver (ISO 9141/14230 physical layer) | The standard hobby K-line PHY |
| 3 | SN65HVD230 breakout | CAN transceiver for CAN-C listen-only | TX pin left DISCONNECTED via jumper |
| 4 | Buck converter module, Vin ≥ 40 V rated (e.g. MP9486A/LM2596HV-based), 12 V→5 V | Power | Automotive transients demand headroom; cheap 28 V-max MP1584 boards are marginal — avoid |
| 5 | TVS diode SMBJ33CA (or 5KP33A for more margin) | Load-dump clamp across 12 V input | |
| 6 | 1 A blade/inline fuse + holder | Input protection | Fuse FIRST in line from pin 16 |
| 7 | Schottky diode SS34 (or P-FET) | Reverse-polarity protection | |
| 8 | microSD SPI breakout + 8–32 GB card (A1 class) | Ride logging | |
| 9 | OBD-II male plug with breakout screw terminals (J1962M) | Car connection | Buy the pigtail/breakout type |
| 10 | Voltage divider resistors 100 kΩ / 10 kΩ + 100 nF | Battery-voltage sense into ADC (12 V → ~1.1 V) | High values = negligible drain |
| 11 | Perfboard, DuPont/JST wire, small ABS enclosure | Assembly | Enclosure must fit in driver footwell by OBD socket |
| 12 | (Optional) DS3231 RTC module | Accurate timestamps without phone | ESP32 RTC drifts minutes/day; phone time-sync makes this optional |

Rough cost: ~£20–30 in parts. Tools assumed: soldering iron, multimeter (owner has one and
knows how to use it — reference the owner's guide §0.7 techniques).

**OBD-II socket wiring (from the owner's guide, [Confirmed] for W203):**

| OBD pin | Signal | Connects to |
|---------|--------|-------------|
| 16 | +12 V permanent (Terminal 30) | Fuse → reverse diode → TVS → buck Vin; also divider → ESP32 ADC (GPIO34) |
| 4, 5 | Chassis / signal ground | Common ground |
| 7 | K-line | L9637D K pin; L9637D TX/RX → ESP32 UART2 (GPIO17/16) |
| 6, 14 | CAN-C High / Low (500 kbps, MB-proprietary) | SN65HVD230 H/L; RX → ESP32 GPIO (TWAI RX + wake); **TX jumper OPEN** |

---

## 4. Hardware build guide — step-by-step (HUMAN tasks; Claude Code writes this doc + the bench-test firmware)

**Stage A — bench power (no car):**
1. Solder the power chain on perfboard: input screw terminals → fuse → SS34 → TVS across
   rails → buck module → 5 V to ESP32 VIN. 
2. Feed 12 V from a bench supply (or a battery charger). Verify 5.0–5.2 V at ESP32 VIN
   with a multimeter BEFORE connecting the ESP32.
3. Add the 100k/10k divider from the protected 12 V node to GPIO34, 100 nF to ground.
   Verify divider output ≈ 1.09 V at 12.0 V input.

**Stage B — peripherals:**
4. Wire microSD (SPI: CS=GPIO5, SCK=18, MISO=19, MOSI=23). Flash the bench-test firmware
   (`firmware` target `bench_selftest`) — it must report SD write/read OK and print the
   ADC-derived input voltage within ±0.2 V of the multimeter reading (calibrate constant).
5. Wire L9637D: VS=12 V protected rail, GND, K→screw terminal (later OBD pin 7),
   TX→GPIO17, RX→GPIO16. 
6. Wire SN65HVD230: 3V3, GND, CANH/CANL→terminals (later pins 6/14), RX→GPIO4 (TWAI RX
   **and** wired to RTC-capable wake GPIO), TX→header pin with NO jumper fitted.

**Stage C — bench protocol test (still no car):**
7. Run `firmware/tools/kline_sim.py` on the PC with a USB-UART adapter wired to the
   L9637D K-line through a 1 kΩ pull-up to 12 V — it emulates a KWP2000 ECU (fast-init +
   5-baud init, Mode 01/03/02 responses with canned M271-plausible data). The firmware
   must complete init, poll the tier schedule, and write a valid ride CSV to SD.
   **This simulator is the single most important de-risking artifact in the project —
   build it early and well.**

**Stage D — car bring-up (see Phase 3 acceptance):**
8. With everything verified on bench: connect OBD breakout pigtail pins per §3 table.
9. First plug-in with engine OFF, ignition ON: firmware probe logs which init succeeded
   (fast vs 5-baud), the supported-PID bitmask (Mode 01 PIDs 0x00/0x20/0x40), Mode 09 VIN
   attempt, then idles.
10. Parasitic check per the owner's guide DD.3: after 35 min locked, clamp/multimeter the
    device feed — must be **< 5 mA**. If not, fix before leaving it plugged in overnight.

---

## 5. Firmware (PlatformIO, Arduino framework, ESP32)

### 5.1 Modules
- `power_mgr` — deep sleep; wake sources: (a) GPIO wake on CAN-RX edge activity
  ([Likely] CAN-C goes active with ignition — verify in Phase 3; fallback (b) ULP timer
  wake every 30 s to sample battery ADC, wake fully if V > 13.0 V or V rose > 0.4 V from
  baseline (cranking/charging signature). Sleep trigger: K-line dead > 60 s AND V < 12.9 V.
- `kline_kwp` — ISO 14230 stack: fast init (0x81) first, 5-baud fallback [Likely SIM4LKE
  answers fast init; both required]; tester-present keepalive; Mode 01/02/03/07/09;
  request pacing with P2/P3 timing; re-init on 3 consecutive timeouts.
- `pid_scheduler` — tiered polling within the ~10 req/s budget:
  - Tier F (each ~2 Hz): RPM, MAF, TPS, SPEED
  - Tier M (each ~0.5 Hz): STFT, LTFT, LOAD, MAP, TIMING_ADV, O2_B1S1, O2_B1S2
  - Tier S (each ~0.1 Hz): ECT, IAT, FUEL_STATUS
  - Local (10 Hz, no bus cost): BATT_V via ADC — and **100 Hz burst during cranking**
    (voltage < 11 V transition) to capture crank-dip shape.
- `ride_logger` — one file per ignition cycle: `ride_YYYYMMDD_HHMMSS.csv`, long format
  `t_ms,signal,value`; header block with fw version, init mode, PID bitmask, DTC snapshot;
  flush+fsync every 5 s; finalize marker row on clean sleep.
- `startup_snapshot` — on each wake: Mode 03 stored DTCs, Mode 07 pending, Mode 02 freeze
  frame (if DTC present), Mode 01 PID 01 readiness — written into the ride header block.
- `ble_svc` — GATT: (a) live telemetry notify characteristic (latest values, ~2 Hz packed
  binary), (b) control (time sync from phone, clear-DTC command — guarded, (c) ride index.
  Advertise ONLY while awake (ignition on) → phone-side auto-connect trigger.
- `wifi_sync` — on phone command, start SoftAP + tiny HTTP server; phone downloads ride
  files in bulk; auto-off after idle. (BLE is too slow for MB-scale files.)
- `can_sniffer` (Phase 7 flag, default off) — TWAI LISTEN-ONLY frame counter/ID histogram
  dump to SD for later reverse-engineering. Never TX (also physically impossible: jumper).
- `selftest` targets: `bench_selftest`, `kline_probe` (Phase 3 car probe).

### 5.2 Firmware acceptance
- Unit tests (native env): KWP frame build/parse, checksum, CSV writer integrity
  (simulated power-cut mid-write leaves parseable file), scheduler budget math.
- Bench: full ride recorded against `kline_sim.py`, including simulated DTC + freeze frame.
- Car: Phase 3 checklist below.

---

## 6. Android app (Kotlin, Jetpack Compose, minSdk 26, target 34+; primary device S23 FE)

### 6.1 Auto-connect (the "connects every time ignition is on" requirement)
Use **CompanionDeviceManager** (associate once with the logger) +
`startObservingDevicePresence` → `onDeviceAppeared` launches the foreground
`ConnectionService` even from cold. This is the sanctioned Samsung/Android-14 path;
document the one-time exemptions the user must grant (battery optimization off for the
app, nearby-devices permission). Fallback: BOOT_COMPLETED-registered BLE scan worker.

### 6.2 Screens
1. **Live dashboard** — gauges/sparklines for RPM, MAF, trims (STFT+LTFT), ECT, MAP/boost,
   O2s, battery V, timing. **Every gauge carries the W203 healthy band** from
   `docs/w203_reference.md` (e.g. MAF idle 3–5 g/s; trims ±10 %; ECT settling ~87–95 °C;
   boost peak ~0.4–0.55 bar [Best estimate]; charging 13.8–14.5 V). Out-of-band values
   tint amber/red with a one-line hint linking to the interpretation sheet
   ("LTFT +18 % at warm idle → classic M271 breather-hose leak — see guide §2 tree").
3. **DTC screen** — stored/pending codes with the curated W203 interpretation layer
   (§8 JSON); freeze-frame view; Clear-DTC behind double confirmation + evidence warning.
4. **Rides** — list from logger index; sync over WiFi-AP flow; per-ride summary after
   pipeline-style quick stats computed on-device (min/max/mean per signal, warm-idle trim
   means, time-to-87 °C if cold start).
5. **Trends** — charts of per-ride aggregates pulled from synced history: LTFT drift,
   MAF-idle, cold-start warm-up time, crank-dip depth, rest voltage. Local Room DB.
6. **Guided tests** (the "ultimate tool" differentiators, all engine-side & feasible):
   - **Smoke/leak assistant:** big live STFT+RPM readout; user sprays suspect joints;
     app flags the timestamped dip/jump.
   - **Cat health snapshot:** 60 s capture at warm idle + 2500 rpm hold; overlays O2 B1S1
     vs B1S2 and reports post-cat steadiness verdict per the guide's criteria.
   - **Thermostat report:** automatic on any cold start — time from start to 87 °C
     normalized by IAT, verdict bands.
   - **Crank health:** ESP32 crank-dip trace displayed per start; trend across weeks
     (battery aging proxy per guide's voltage figures; note it is NOT a true relative-
     compression test — the device measures at the OBD socket, not the starter cable).
   - **Boost curve check:** MAP vs RPM during a user-initiated WOT pull (with a
     do-this-safely disclaimer), overlaid on the M271 reference curve.
7. **Export** — share raw + canonical CSVs (SAF/Storage Access Framework) to PC.

### 6.3 Android acceptance
- Cold-start auto-connect demonstrated: phone in pocket, turn ignition on → notification
  + live data within 20 s, no manual app launch.
- A 45-minute ride streams live with zero UI-thread jank and < 6 %/hr battery.
- Ride sync of a 5 MB file completes < 60 s over WiFi-AP.

---

## 7. PC pipeline (new Python project, `uv venv`, Python 3.11+)

- `kl ingest <dir>` — raw long CSV → canonical wide 1 Hz parquet, physical-bounds guards,
  availability mask, ride manifest (SQLite index).
- `kl features` — per-ride aggregates: warm-idle windows (RPM 600–900, speed 0, ECT > 80),
  trim means/percentiles, MAF-idle stats, cold-start time-to-87 °C, crank-dip depth/width,
  rest-voltage-before-start, O2 switching rate, boost-at-load percentiles.
- `kl baseline` — per-regime robust envelopes (median ± MAD bands) updated per healthy
  ride = the "always learning" layer; changepoint detection (CUSUM) on aggregate series
  for slow-drift alarms.
- `kl trend` — forecasters for LTFT, MAF-idle, warm-up time, crank-dip; each model ships
  ONLY if it beats persistence on rolling backtests (hard gate, grad-project lesson).
- `kl report <ride>` — markdown health report per ride (styled after the grad dashboard's
  end-of-read report), including Untested-contract disclosure of unavailable PIDs.
- (Later) supervised fault models: retrain grad-style XGBoost on W203-corrected fault
  injection — the M271 is MAF-primary WITH MAP, i.e. the correct platform for the
  air-system fault family the grad audit flagged; document honestly that real fault
  labels remain scarce.
- Tests: golden-file ingest tests incl. corrupted/truncated CSVs; feature unit tests.

---

## 8. W203 reference data to embed (from the owner's workshop guide)

Build `docs/w203_reference.md` + `android/.../w203_bands.json` + DTC JSON from these
[values verified in the owner's guide project]:
- MAF warm idle 3–5 g/s [Confirmed]; battery ≥12.5 rest / 13.8–14.5 charging / ≥9.6
  cranking [Confirmed]; thermostat 87 °C [Confirmed]; boost peak ≈0.4–0.55 bar
  [Best estimate]; trims normal ±10 %; fuel rail ≈3.8 bar (not OBD-visible, reference
  only); coil primary 0.4–1.0 Ω / injector 12–16 Ω [Best estimate] (for the DTC hints).
- DTC interpretation layer (W203-specific likely causes): P0170/P0171 → partial-load
  breather hoses (THE M271 fault), then MAF, then fuel pressure; P0016/P0017 → chain
  stretch / cam-magnet; P0010–P0014 → cam adjuster magnet oil ingress; P0300-family →
  coils/plugs (correct plug FR6MPP332 / NGK ILFR6A note); P0420 → verify with cat
  snapshot test before condemning; cooling codes → fan-side vs flow-side split.
- Include the guide's caveat text verbatim-in-spirit: confirm safety-critical values
  against WIS for the VIN.

---

## 9. Phases, order of execution, and acceptance gates

| Phase | Deliverable | Gate to pass |
|-------|-------------|--------------|
| 0 | Repo scaffold, CLAUDE.md, schema constants, w203_reference docs | Builds/lints clean |
| 1 | `kline_sim.py` + firmware core (KWP stack, scheduler, logger) | Full simulated ride CSV on bench |
| 2 | Hardware build (HUMAN, guide §4 A–C) | Bench selftest green; sim ride recorded through real L9637D |
| 3 | Car bring-up: protocol probe, PID bitmask, first real ride, sleep/wake, parasitic < 5 mA | 3 consecutive daily rides captured with zero intervention |
| 4 | Android: CDM auto-connect + live dashboard + DTC screen | §6.3 criteria |
| 5 | WiFi sync + pipeline ingest/features/report | A week of rides flows car→phone→PC hands-free |
| 6 | Baselines, trends, guided tests | Trend charts populated; ≥1 forecaster beats persistence |
| 7 | EXPERIMENTAL: CAN-C ID census, MB-proprietary KWP addresses (EGS?) | No gate — research; listen-only always |

Phase 3 is the moment of truth for every [Likely] in this plan (init mode, PID set, CAN
wake). The firmware probe must write a `car_capabilities.json` that all later phases
consume — nothing downstream hardcodes assumptions the probe can settle.

## 10. Known risks (do not hide these from the user)

- SIM4LKE may expose fewer PIDs than hoped (no MAP or no O2S2 via Mode 01) → Untested
  contract absorbs it; dashboard hides unavailable gauges.
- K-line init quirks on MB (fast-init address, keepalive timing) may cost bench/car
  iteration cycles — the simulator reduces but can't eliminate this.
- CAN-RX wake may false-trigger (bus chatter after lock) or not trigger (bus asleep at
  ignition-on-engine-off) → the ULP voltage fallback is mandatory, not optional.
- Samsung battery management can still kill the service despite exemptions → document
  the exact S23 FE settings; the SD card guarantees no ride is ever lost regardless.
