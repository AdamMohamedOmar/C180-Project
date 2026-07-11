# Phase 4 Design Handover — read this first

**Who this is for:** a fresh Claude session (Fable) taking over the
KompressorLink project to **design and plan Phase 4** (Android app + firmware
BLE). You are NOT executing the plan — after the plan document is written and
approved, the user switches the model to Sonnet, and Sonnet executes it via
`superpowers:subagent-driven-development`. Your deliverables are exactly two
files, then you stop:

1. `docs/superpowers/specs/2026-MM-DD-phase4-android-ble-design.md`
2. `docs/superpowers/plans/2026-MM-DD-phase4-android-ble.md`

**Process:** `superpowers:brainstorming` (one question at a time — the open
questions are listed below so you don't rediscover them) → design doc → user
approval → `superpowers:writing-plans` → plan self-review → tell the user to
switch to Sonnet. Do not invoke implementation skills. Do not write any
product code.

## Read before designing (in this order)

1. `CLAUDE.md` — ground rules, canonical 19-signal schema, conventions.
   Non-negotiable: **never run `git commit`** (user commits via VS Code GUI);
   full replacement files; confidence tags on anything unverified for this ECU.
2. `PLAN.md` §6 (Android), §5.1 (`ble_svc` spec), §6.3 (acceptance), §8
   (reference data → `w203_bands.json`), §9 (phase table), §10 (risks).
3. `docs/superpowers/plans/2026-07-09-phase1-firmware-core.md` — the format
   your plan must match: bite-sized tasks, exact file paths, complete code in
   every step, TDD with real run commands, honest scope notes. Sonnet +
   subagents execute it task-by-task with two-stage review (spec compliance,
   then code quality) — that review discipline caught 6+ real bugs in Phase 1;
   the plan should preserve it.
4. `docs/w203_reference.md` — healthy bands the dashboard gauges must carry.
5. Skim `firmware/src/` (each file is small) and `android/` (scaffold only).

## Verified current state (as of 2026-07-10)

- **Phase 0 + Phase 1 software: complete and verified.** Firmware core
  (`kline_frame`, `kline_kwp`, `pid_scheduler` (earliest-deadline-first),
  `ride_logger`, `KLineTransport`/`RideStorage` abstractions, `main.cpp`
  wiring) builds for the real ESP32 target (`pio run -e esp32dev` SUCCESS,
  RAM 6.6% / Flash 24%). All **42 native Unity tests pass** (6 suites,
  `pio test -e native`). The PC-side KWP2000 simulator `kline_sim.py` has
  24/24 pytest passing and 4 scenarios (healthy / fault-injected / slow /
  unresponsive). The pipeline scaffold (`pipeline/`, package `kl`) has 10/10
  tests and ruff clean.
- **Nothing is committed yet** — the user was advised to commit Phase 0+1 via
  VS Code before Phase 4 work starts. If `git log` shows commits, that
  happened; if not, remind them once and continue regardless.
- **Deferred (do not design around their absence being a problem):**
  Phase 1's bench-run acceptance gate (needs USB-TTL wiring), Phases 2–3
  (hardware/car). The `android/` scaffold has **never been gradle-synced or
  built** — no Android SDK/Gradle existed on this machine as of Phase 0.

## The user's hard constraint for Phase 4

**The ESP32 gets plugged in exactly once, as the LAST step.** Everything must
be developable and testable software-first:

- The Android app needs a **fake telemetry source** behind the same interface
  as the real BLE client — the whole dashboard/DTC UI must run against
  simulated data on an emulator or the phone with zero hardware.
- Firmware `ble_svc` work is verified by ESP32 compile + native tests only
  (BLE itself can't be exercised natively — design the module so the
  packing/framing logic is framework-agnostic and natively testable, with a
  thin ESP32-only GATT wrapper, same pattern as `KLineTransport`/
  `Esp32UartTransport`).
- One final flash + CDM pair + live-data session at the very end proves the
  real link. (Note: BLE needs only the bare ESP32 over its own USB cable —
  no bench wiring, no USB-TTL, no K-line. The ESP32 can serve *simulated*
  telemetry over real BLE without any car data; consider a firmware demo
  mode for exactly this.)

## Known gaps the design MUST address (discovered in Phase 1, not in PLAN.md)

1. **Firmware has no Mode 03 (DTC read) support.** `KlineKwp` implements only
   StartCommunication / tester-present / Mode 01 `read_pid`. The DTC screen
   (part of Phase 4's gate) needs stored-DTC data, so Phase 4's firmware
   scope includes a `read_stored_dtcs` capability (and probably the
   `startup_snapshot`-lite that captures it). `kline_sim.py` ALREADY answers
   Mode 03 (and Mode 07 pending, empty) with SAE J2012-encoded DTCs — the
   `fault-injected` scenario serves P0171 — so the PC-side test harness for
   this is already built and tested.
2. **`BATT_V_ADC` is not implemented in firmware** (Local tier, reads GPIO34
   ADC). Trivial to add, BUT the 100k/10k divider hardware doesn't exist
   until Phase 2 — on a bare ESP32 the pin floats and reads garbage. The
   dashboard's battery gauge design must handle "unavailable" honestly (the
   PID-availability mask / Untested contract already covers this pattern).
3. **`android/.../w203_bands.json` doesn't exist** (PLAN.md §8). Phase 4
   needs it for gauge bands; derive from `docs/w203_reference.md`.
4. **The BLE protocol contract is undesigned.** PLAN.md §5.1 says: telemetry
   notify characteristic (latest values, ~2 Hz, packed binary), control
   characteristic (time sync from phone; clear-DTC is guarded and can be
   deferred — Mode 04 write is a scary-path item, recommend deferring it out
   of Phase 4), ride index characteristic (arguably Phase 5, when WiFi sync
   lands — recommend deferring). Service/characteristic UUIDs, packet layout,
   PID-availability signalling, and versioning are yours to design. Keep the
   packing code shared-testable (see constraint above) and consider mirroring
   the packet spec in one place both codebases reference — schema drift
   between firmware and app is the Phase-4 equivalent of Phase 1's
   caught-in-review CTRL_MODULE_V formula bug.
5. **Scope trim.** PLAN.md §6.2 lists 7 Android screens. Phase 4's gate (§9,
   §6.3) is only: CDM auto-connect + live dashboard + DTC screen. Rides,
   Trends, Guided tests, Export are Phases 5–6. Confirm the trim with the
   user during brainstorming; resist scope creep into the deferred screens.

## Open brainstorming questions (ask these, one at a time)

1. **Is Android Studio installed and working?** The scaffold was never
   synced. If not installed, that's a ~1GB+ install the user does manually —
   it gates ALL Android work and should be the plan's explicit Task 0 (user
   step). Also: versions pinned in `android/gradle/libs.versions.toml`
   (AGP 8.7.2 / Kotlin 2.0.20 / Compose BOM 2024.09.00) may prompt upgrades
   on first sync — the plan should say "accept and report back."
2. **Day-to-day app testing: emulator, or the real S23 FE over USB?** Affects
   how the fake-data mode is exposed (emulators have no real BLE) and whether
   CDM flows can be tested before the final hardware session at all (CDM
   association needs a real BLE peripheral — likely final-session-only;
   confirm the user is OK with that being the last-step test together with
   the ESP32 plug-in).
3. **Scope confirmation** (the §6.2 trim above).
4. **Who runs Android builds during execution?** No Gradle/SDK on PATH for
   the agent as of Phase 0 — after Android Studio is installed, builds may be
   runnable headlessly via the SDK's bundled tools (worth probing in the new
   session before writing the plan: check `ANDROID_HOME`, `%LOCALAPPDATA%\
   Android\Sdk`, gradle wrapper presence after first sync). If not, every
   Android task's verification is a manual user step — the plan must be
   honest about that (Phase 1's plan is the model for honest verification
   notes).

## Environment facts (hard-won; don't rediscover)

- Windows 10, PowerShell primary; Bash (git-bash) available. `uv` 0.11.1,
  Python 3.11.9. PlatformIO CLI installed via `uv tool install platformio`
  (its venv needed `pip` injected — already fixed).
- Host C++ compiler: **w64devkit GCC 16.1.0** at `C:\Users\DELL\tools\
  w64devkit\bin`, on the user PATH. `pio test -e native` works. (New
  terminals inherit it; a session started before the PATH change may need
  `export PATH="/c/Users/DELL/tools/w64devkit/bin:$PATH"`.)
- PlatformIO gotchas already handled in-repo (see comments in
  `firmware/platformio.ini`): `build_unflags = -std=gnu++11` for the ESP32
  env; `test_build_src = yes` for native tests; Arduino-only `.cpp` files
  excluded from `[env:native]` via `build_src_filter`; each native test suite
  in its own `test/test_<name>/` folder; every Unity test file must define
  `setUp()`/`tearDown()`.
- `firmware/tools/kl_sim/` must NOT contain `__init__.py` (breaks the flat
  import style; documented in the Phase 1 plan).
- Shell trap: piping to `tee` masks exit codes — use `set -o pipefail` and
  read actual log output before claiming success (a Phase 0 build "passed"
  with exit 0 while the log showed FAILED).
- Subagent economics: mechanical, fully-specified tasks ran fine on cheaper
  model overrides; design/review used the session model. Session limits were
  hit twice during Phase 1 execution — plans with fully-specified code make
  interrupted subagents cheap to resume (verify what exists on disk, continue).

## After the plan is approved

Tell the user: switch to Sonnet (`/model`), then start execution with
`superpowers:subagent-driven-development` against the plan file. The task
list should be recreated via TaskCreate from the plan's tasks (task lists
don't reliably carry across sessions — the plan file is the source of truth).
