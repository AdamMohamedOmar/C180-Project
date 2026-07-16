# Next-Phase Design Handover — read this first

**Who this is for:** a fresh Claude session (Fable) taking over KompressorLink
to **design and plan the next phase**. You are NOT executing — after the plan
document is written and approved, the user switches to Sonnet, who executes it
via `superpowers:subagent-driven-development` (the same two-review-per-task
discipline that built Phase 4). Your deliverables are exactly two files, then
you stop:

1. `docs/superpowers/specs/2026-MM-DD-<phase>-design.md`
2. `docs/superpowers/plans/2026-MM-DD-<phase>.md`

**Process:** `superpowers:brainstorming` (one question at a time — the open
questions are listed below so you don't rediscover them) → design doc → user
approval → `superpowers:writing-plans` → plan self-review → tell the user to
switch to Sonnet. Do not write product code in the planning session.

> **The single most important thing to settle first (Open Question 1): which
> phase is actually next.** The project has been built entirely software-first
> and has **never touched real hardware or a real car**. That makes the next-
> phase choice a genuine fork, not a given. Do not assume "Phase 5" — brainstorm
> the sequencing decision before anything else.

## Read before designing (in this order)

1. `CLAUDE.md` — ground rules, canonical 19-signal schema, conventions.
   Non-negotiable: **never run `git commit`** (user commits via VS Code GUI);
   full-replacement files when changing a file; confidence tags
   (`[Confirmed]`/`[Likely]`/`[Best estimate]`/`[Guessing]`) on anything
   unverified for this exact ECU.
2. `PLAN.md` — the full 7-phase execution plan. §5 (`wifi_sync` firmware
   module spec), §6.2 (the 4 not-yet-built app screens: Rides, Trends,
   Guided tests, Export), §7 (the whole PC pipeline: `kl ingest/features/
   baseline/trend/report`), §8 (W203 reference data), §9 (phase table + gates),
   §10 (risks). This is the master roadmap; the phases below all live here.
3. `docs/PHASE4_HANDOVER.md` — the handover that seeded the Phase 4 planning
   session. **Mirror its structure and level of honesty** — it worked well.
4. `docs/superpowers/specs/2026-07-10-phase4-android-ble-design.md` and
   `docs/superpowers/plans/2026-07-10-phase4-android-ble.md` — the Phase 4
   spec + plan you should match in format: bite-sized tasks, exact file paths,
   complete code in every step, TDD with real run commands, honest
   verification notes, and — critically — a `docs/<contract>.md` normative
   doc mirrored by both codebases whenever two languages must agree on a wire
   format (this caught real drift bugs in Phase 4).
5. `docs/ble_protocol.md` — the BLE wire contract Phase 4 established. Any new
   transport (e.g. Phase 5's WiFi file sync) should follow the same
   "one normative doc + golden vectors on both sides" discipline.
6. `docs/w203_reference.md` — healthy bands + DTC interpretation, the source
   of truth for anything analytics-facing.

## Verified current state (as of 2026-07-13)

**Phases 0, 1, and 4 (software) are COMPLETE and verified. Nothing is
committed yet** — the user commits via the VS Code Source Control GUI; the
working tree currently holds all of Phase 4 as reviewable, uncommitted
changes (38 paths). Remind the user once to commit before the next phase
starts, then continue regardless.

- **Phase 0 + 1 (firmware core):** `kline_frame`, `kline_kwp` (now with
  Mode 01 + Mode 03/07 DTC reads), `pid_scheduler` (earliest-deadline-first),
  `ride_logger`, `KLineTransport`/`RideStorage` abstractions. Plus the
  Phase-1 PC-side KWP2000 simulator `firmware/tools/kline_sim.py`.
- **Phase 4 (Android app + firmware BLE):** built task-by-task, each with
  spec-compliance + code-quality review. Firmware gained `dtc_list`,
  `latest_values`, `telemetry_frame` (golden-vector-tested BLE packer),
  `demo_feed` (synthetic telemetry), `ble_svc` (NimBLE GATT wrapper), and a
  demo build target. The Android app is a full Kotlin/Compose app:
  `FrameCodec` (Kotlin mirror of the BLE contract), `FakeTelemetrySource`
  (3 scenarios), `SourceSwitcher` + DataStore, live dashboard with W203
  healthy-band gauges, read-only DTC screen, raw-`BluetoothGatt`
  `GattClient`, tested `BleTelemetrySource` state machine, and the full
  CompanionDeviceManager auto-connect chain (`BleSession`, `CdmAssociator`,
  `KlCompanionService`, `ConnectionService`).
- **Test/build status (all re-verified 2026-07-13):**
  - Firmware: `pio test -e native` → **76/76** across 10 suites. Both ESP32
    targets link: `pio run -e esp32dev` (RAM 11.1 % / Flash 49.5 %) and
    `pio run -e esp32dev_demo` (RAM 11.0 % / Flash 46.8 %).
  - Android: `.\gradlew.bat :app:testDebugUnitTest` → **53/53** across 11
    suites; `.\gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL, real APK
    at `android/app/build/outputs/apk/debug/app-debug.apk`.
  - `kline_sim.py` 24/24 pytest; pipeline scaffold 10/10, ruff clean.

## The ONE piece of Phase 4 still open (carried into the next phase)

**Task 21 — the final hardware session — was deliberately postponed.** It is
the only part of Phase 4 that needs the physical ESP32 + phone, and the user
chose to fold it into the next hardware push rather than do it standalone.
Its full checklist is in the Phase 4 plan (`docs/superpowers/plans/
2026-07-10-phase4-android-ble.md`, "## Task 21"). In brief, it proves the
real BLE link end-to-end with **no car** required:

1. Flash `esp32dev_demo` to a bare ESP32 over USB (`pio run -e esp32dev_demo
   -t upload`) — it serves synthetic telemetry + a stored P0171 over real BLE.
2. Install the debug APK on the S23 FE.
3. In-app "Pair with device…" → CDM association → dashboard streams demo
   telemetry, DTC screen shows P0171 with the breather-hose interpretation,
   battery gauge shows the demo ~14.25 V.
4. Cold-start gate: force-stop the app, power-cycle the ESP32, confirm the
   foreground-service auto-connect fires within ~20 s with the phone locked.

This is the acceptance gate for Phase 4's §6.3 criteria in their bench form.
Until it runs, everything BLE is **compile-and-unit-test-verified only** —
`ble_svc.cpp`, `GattClient.kt`, and the CDM/service classes have never
executed on real hardware. The design work already accounts for this
honestly; the planning session just needs to schedule it as the first
hands-on step of whatever comes next.

## Open brainstorming questions (ask these, one at a time)

**1. Which phase is next? (settle this FIRST — it shapes everything else.)**
Because the project skipped hardware and went software-first, the next-phase
choice is a real fork:

- **Option A — Hardware bring-up phase** (consolidate PLAN.md Phases 2 + 3
  + the deferred Phase-1 bench gate + Phase-4 Task 21). This is the
  "plug everything in for the first time" phase: build the physical device
  (L9637D K-line transceiver, buck converter, protection, microSD, OBD
  pigtail — PLAN.md §3/§4 BOM + build guide), bench-test firmware through the
  real transceiver against `kline_sim.py`, then the first real car plug-in
  (protocol probe, PID bitmask, `car_capabilities.json`, sleep/wake,
  parasitic-draw < 5 mA). **This is where every `[Likely]`/`[Guessing]` in
  the firmware gets settled** (init mode, real PID set, the DTC count-byte
  framing question flagged in `ble_protocol.md`). Much of it is HUMAN work
  (soldering, wiring, multimeter) — the agent's role is the build/bring-up
  guide, the `kline_probe` firmware target, and `car_capabilities.json`
  handling.
- **Option B — Phase 5 (WiFi sync + PC pipeline), software-first.** Add the
  `wifi_sync` firmware module (SoftAP + HTTP bulk ride-file transfer), the
  Android Rides screen + sync flow, and flesh out the PC pipeline (`kl
  ingest/features/baseline/report`). This can be built and tested software-
  first exactly like Phase 4 (fake ride files, the simulator), with real
  rides deferred. **Caveat the planning session must confront:** Phase 5's
  written gate ("a week of rides flows car→phone→PC hands-free") *needs* the
  car, so a software-first Phase 5 would still end with a hardware-gated
  acceptance step — same shape as Phase 4's Task 21.
- **Option C — a smaller slice** (e.g. just the PC pipeline `kl ingest`/
  `features`/`report` against simulated ride CSVs, deferring `wifi_sync`),
  if the user wants to keep momentum on pure-software analytics without
  committing to the WiFi transport yet.

The user's revealed preference across Phases 1 and 4 is strongly software-
first with a single hardware session at the very end — but they've now
accumulated **two** deferred hardware sessions (Phase 1 bench gate + Phase 4
Task 21), which argues for eventually doing a consolidated hardware phase so
the deferrals don't pile up further. Surface this tension; let the user choose.

**2. Hardware on hand?** The user has a bare ESP32 dev board (used for the
Phase 1/4 software work). For Option A, ask what else exists yet vs. needs
buying from the §3 BOM (L9637D, buck converter, protection parts, microSD
breakout, OBD-II pigtail). For any software-first option, confirm the bare
ESP32 + `kline_sim.py` remains the whole test rig.

**3. Scope discipline.** Whatever phase is chosen, resist pulling in the
other deferred app screens (§6.2 Trends/Guided-tests/Export) unless they're
genuinely part of the chosen phase's gate — Phase 4 stayed tight by trimming
to exactly its gate, and that discipline is worth keeping.

**4. Who runs what during execution.** Same split that worked in Phase 4:
firmware native tests + Android headless Gradle builds are agent-runnable;
flashing the ESP32, installing the APK on the phone, and anything
visual/physical are user steps. The plan must be honest per-task about which
is which (Phase 4's plan is the model).

## Environment facts (hard-won across Phases 0/1/4 — don't rediscover)

- Windows 10, PowerShell primary; Bash (git-bash) available. `uv` 0.11.1,
  Python 3.11.9. PlatformIO CLI installed via `uv tool install platformio`.
- **Host C++ compiler:** w64devkit GCC at `C:\Users\DELL\tools\w64devkit\bin`,
  on PATH. `pio test -e native` works. A bash session started before the PATH
  change may need `export PATH="/c/Users/DELL/tools/w64devkit/bin:$PATH"`.
- **Android toolchain (set up during Phase 4 — see `android/AGENT_ENV.md` for
  the full story):** Android Studio installed via winget; SDK cmdline-tools +
  platform-tools + `platforms;android-35` + `build-tools;35.0.0` under
  `%LOCALAPPDATA%\Android\Sdk`; Gradle wrapper (8.9) generated in `android/`.
  **To run any Gradle command:** set `$env:JAVA_HOME = "C:\Program Files\
  Android\Android Studio\jbr"` (bundled JDK 21) first, then run
  `.\gradlew.bat ...` from `D:\C180 Project\android\`. `local.properties`
  points at the SDK; `ANDROID_HOME` is NOT set system-wide (Gradle finds the
  SDK via `local.properties`). `adb` is at
  `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (not on PATH).
- **NimBLE-Arduino resolved to 2.5.0** (not the plan's guessed 2.1.0);
  `ble_svc.cpp` was adapted to its API (no `getSubscribedCount`, server-level
  `start()` required). If Phase 5 adds `wifi_sync`, watch for the same
  "pinned lib version differs from the guess" pattern and quarantine it in a
  thin wrapper like `ble_svc` did.
- PlatformIO gotchas already handled in `firmware/platformio.ini`:
  `build_unflags = -std=gnu++11` for ESP32; `test_build_src = yes` for native;
  `build_src_filter` excludes Arduino/NimBLE-only `.cpp` from `[env:native]`;
  each native test suite in its own `test/test_<name>/` folder with its own
  `main()` + `setUp`/`tearDown`. `firmware/tools/kl_sim/` must NOT contain
  `__init__.py`.
- Shell trap: piping to `tee` masks exit codes — use `set -o pipefail` and
  read the real log before claiming success.
- **Subagent-driven execution worked extremely well in Phase 4:** fully-
  specified plan tasks, one implementer subagent + a spec-compliance review +
  a code-quality review each, with a fix pass when a review found something.
  That two-stage review caught ~10 genuine bugs during Phase 4 execution
  (array-aliasing, cold-start data-freshness, a DataStore crash risk, a
  GattClient "stuck connecting forever" bug, a BleTelemetrySource start/stop
  reentrancy bug, a ConnectionService coroutine leak, and more). Preserve
  that discipline — it is the reason the code is trustworthy despite never
  running on hardware yet.

## After the plan is approved

Tell the user: switch to Sonnet (`/model claude-sonnet-5`), then start
execution with `superpowers:subagent-driven-development` against the plan
file. Recreate the task list via TaskCreate from the plan's tasks (task lists
don't reliably carry across sessions — the plan file is the source of truth).
