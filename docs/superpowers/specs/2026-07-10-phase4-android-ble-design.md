# Phase 4: Android App + Firmware BLE — Design

**Status:** Approved by user 2026-07-10 (brainstorming sections 1–3). Feeds into
a `writing-plans` implementation plan executed by Sonnet via
`superpowers:subagent-driven-development`.

**Scope:** PLAN.md §9 Phase 4 row, trimmed as confirmed with the user:
**CDM auto-connect + live dashboard + read-only DTC screen.** Everything is
developed and verified software-first; the ESP32 is plugged in exactly once,
as the last step, over its own USB cable (no bench wiring, no USB-TTL, no
K-line, no car).

**Decisions settled during brainstorming (2026-07-10):**

1. Android Studio is NOT installed on this machine (verified: no
   `ANDROID_HOME`, no `%LOCALAPPDATA%\Android\Sdk`, no Studio in Program
   Files, no `gradlew`, only Java 8 on PATH). The user installs it — the
   plan's explicit **Task 0** (user step), gating all Android work.
2. Day-to-day app testing happens on the **real S23 FE over USB**, driven by
   the fake telemetry source. Emulator optional, never required.
3. Scope trim confirmed; **Clear-DTC (Mode 04) is deferred** out of Phase 4.
   The DTC screen is read-only. Ride index/sync, Trends, Guided tests, Export
   are Phases 5–6.
4. After Android Studio's first sync, **agents run Android builds/tests
   headlessly** (`gradlew.bat`); the user handles everything phone-side
   (install APK, look at screens, grant permissions).
5. BLE contract: **packed binary + spec doc + shared golden test vectors**
   (approach A). Rejected: JSON-over-BLE (needs fragmentation anyway, weaker
   drift protection); codegen from a schema file (toolchain overhead for a
   19-signal schema that rarely changes).
6. Android BLE client: **raw `BluetoothGatt` behind a ~250-line wrapper we
   own** (zero third-party BLE deps, every workaround explicit and reviewed).
   Rejected: Kable/Nordic library (a dependency to pin, trust, and debug
   through). The wrapper sits behind the `TelemetrySource` interface, so
   swapping to a library later is contained if raw GATT proves nasty.

**Out of scope for Phase 4 (explicitly deferred):** Mode 04 clear-DTC (and
its double-confirm UI), ride index characteristic, WiFi sync, `power_mgr`
(BLE advertises whenever powered — sleep gating arrives with `power_mgr`),
`BATT_V_ADC` implementation (divider hardware is Phase 2; see §3.2), Rides /
Trends / Guided-tests / Export screens, any in-car validation (Phases 2–3
hardware doesn't exist yet).

---

## 1. Architecture

Firmware keeps doing what Phase 1 built (poll K-line, log CSV) and
additionally maintains a **latest-values cache** served over three GATT
characteristics. The Android app never speaks OBD — it only decodes BLE
frames. The contract lives in one new document, **`docs/ble_protocol.md`**,
mirrored verbatim by both codebases — the same discipline as the 19-signal
schema table (CLAUDE.md ↔ `pid_schema.h` ↔ `schema.py`).

```
firmware                                          android
────────                                          ───────
pid_scheduler ─► latest_values ─► telemetry_frame │ GattClient (~250-line raw wrapper)
kline_kwp Mode03/07 ─► dtc cache      (packer,    │   └► FrameCodec (parser, golden-tested)
demo_feed ────► same caches       native-tested)  │        └► BleTelemetrySource ┐
                       │                          │ FakeTelemetrySource ─────────┤
                  ble_svc (thin NimBLE      ═BLE═ │        TelemetrySource (interface)
                  wrapper, esp32dev-only)         │        └► ViewModels ─► Compose screens
                                                  │
              docs/ble_protocol.md — single source of truth for both sides
```

The firmware split follows the Phase 1 `KLineTransport`/`Esp32UartTransport`
pattern: everything that can be native-tested (packing, parsing, demo data,
DTC decode) is framework-agnostic; the ESP32-only GATT wrapper is as thin as
possible and verified by compilation only.

## 2. BLE protocol contract (v1)

Full normative text, including canonical hex test vectors, is authored as
`docs/ble_protocol.md` during execution. This section fixes the design.

### 2.1 GATT layout

One custom service, three characteristics, plus the standard Device
Information Service (0x180A) carrying `FW_VERSION` (Firmware Revision String,
0x2A26) and "KompressorLink" (Manufacturer Name, 0x2A29). Fixed 128-bit
UUIDs, chosen once, stable forever:

| UUID | Role | Properties |
|---|---|---|
| `c1800001-4b4c-4d27-b946-c180c0deba5e` | KompressorLink service | — |
| `c1800002-4b4c-4d27-b946-c180c0deba5e` | Telemetry | notify |
| `c1800003-4b4c-4d27-b946-c180c0deba5e` | DTC report | read + notify |
| `c1800004-4b4c-4d27-b946-c180c0deba5e` | Control | write (with response) |

Advertising: device name `KompressorLink` + the 128-bit service UUID,
whenever powered (Phase 4 has no `power_mgr`; PLAN.md §5.1's
"advertise only while awake" lands when sleep does). The ESP32's public BT
address is fixed per chip — stable for CDM association. [Confirmed]

### 2.2 Telemetry frame — 88 bytes, little-endian, notified at ~2 Hz

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `protocol_version` = 0x01 |
| 1 | 1 | `flags`: bit0 `demo_mode`, bit1 `kline_connected`, bits 2–7 reserved (0) |
| 2 | 2 | `seq` — uint16, +1 per notification, wraps (app detects drops) |
| 4 | 4 | `uptime_ms` — uint32, firmware `millis()`, wraps at ~49.7 days |
| 8 | 4 | `avail_mask` — uint32; bit *i* = `Signal` enum ordinal *i* available; bits 19–31 reserved (0) |
| 12 | 76 | 19 × float32 (IEEE 754 LE), `Signal` enum declaration order (RPM … BATT_V_ADC) |

- A signal whose mask bit is clear has its value transmitted as `0.0f` and
  **must be ignored by the receiver**. The mask is the Untested contract on
  the wire (CLAUDE.md): Legacy PIDs and the unimplemented `BATT_V_ADC` never
  set their bit, and the dashboard renders "not provided," never a zero.
- All 19 signals ship every frame — 88 B × 2 Hz is trivial bandwidth; sending
  everything beats per-tier delta cleverness.
- Requires MTU ≥ 91 (88 + 3-byte ATT header). The app requests MTU 517
  immediately after connect; if the negotiated MTU is < 91 it treats the
  connection as failed and retries — never truncates silently. [Likely: S23
  FE ↔ NimBLE negotiates ≥ 185 without fuss; the guard exists for honesty.]

### 2.3 DTC report frame — read anytime, notified on change

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `protocol_version` = 0x01 |
| 1 | 1 | `stored_count` (N ≤ 8) |
| 2 | 1 | `pending_count` (M ≤ 8) |
| 3 | 2·N | stored DTCs, SAE J2012 2-byte encoding each |
| 3+2·N | 2·M | pending DTCs, same encoding |

Max frame 35 bytes. The 2-byte-per-DTC encoding (SAE J2012) is [Confirmed];
the leading explicit count byte is [Guessing] — it mirrors
`firmware/tools/kl_sim/protocol.py`'s `build_dtc_response` (built for Phase 1
testability, not sourced from a real K-line capture). Real SAE J1979 Mode 03
responses over ISO 9141-2/14230 typically carry no separate count byte at
all — the DTC count is implicit in the KWP frame's own length field
(`data_len / 2`), unlike the CAN/ISO 15765 variant of Mode 03 which does use
an explicit count because CAN's fixed 8-byte frames need one. `kline_sim.py`
may be replicating the CAN-style framing rather than real K-line framing.
Kept as-is for Phase 4 (firmware and sim must agree with each other to be
testable at all, and this is exactly the class of `[Likely]`/`[Guessing]`
byte-format detail PLAN.md §0.4 defers to Phase 3's real car probe — same
treatment as `kTargetAddress`). Cap of 8+8: [Best estimate] a real ECU rarely
stores more; keeps the frame far under any MTU.

### 2.4 Control frame — 9 bytes, write with response

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `opcode`: 0x01 = TIME_SYNC (0x02 = CLEAR_DTC **reserved, unimplemented** — deferred) |
| 1 | 8 | `epoch_ms` — uint64 LE, Unix epoch milliseconds |

On TIME_SYNC the firmware sets its clock and, if a ride is active, writes a
marker line into the ride CSV mapping `t_ms → epoch_ms` (comment-prefixed,
consistent with the existing header-block conventions), so rides carry
absolute time without waiting for Phase 5. Unknown opcodes are ignored (and
logged to Serial).

### 2.5 Versioning & drift protection

- `protocol_version` is byte 0 of every frame. The app rejects frames with an
  unknown version and surfaces a "firmware/app protocol mismatch" connection
  state instead of mis-parsing.
- `docs/ble_protocol.md` carries canonical hex test vectors: (a) a fully
  populated telemetry frame, (b) a sparse-mask telemetry frame, (c) a DTC
  frame with stored P0171 + empty pending, (d) a TIME_SYNC control frame.
  The firmware native test asserts the packer emits exactly those bytes; the
  Android unit test asserts the parser decodes those bytes back to the same
  values. Both embed the vectors as constants with a mirror-verbatim comment
  pointing at the doc (the repo's established pattern). Either side drifting
  goes red.
- No pairing/bonding in v1. CDM association doesn't require it, and the data
  is read-only telemetry. [Accepted risk for a personal tool: anyone in BLE
  range can read engine data while the device is awake. Control writes are
  limited to time sync; clear-DTC stays deferred partly for this reason.]

## 3. Firmware design

### 3.1 `kline_kwp` — Mode 03/07 DTC reads (closes handover gap 1)

```cpp
struct DtcList {
  uint8_t count;        // clamped to kMaxDtcs
  uint16_t codes[8];    // SAE J2012 2-byte values, big-endian as received
};
bool read_stored_dtcs(DtcList* out);   // Mode 03, expects SID 0x43
bool read_pending_dtcs(DtcList* out);  // Mode 07, expects SID 0x47
```

Parses `SID, count, [2 bytes]×count`. No heap. A response advertising more
codes than fit is truncated to 8 with count clamped. [Guessing: whether the
real SIM4LKE pages long DTC responses across multiple frames is unknowable
until Phase 3; `kline_sim.py` doesn't page, and 8 codes fit in one KWP frame
(63-byte data limit).] Native tests run against fixtures from the sim's
`--fixture-dump`, which is extended to cover Mode 03/07 request/response
pairs (the sim already answers both — Phase 1 built and tested that).

### 3.2 `latest_values` — the cache

Framework-agnostic struct owning `float values[19]`, `uint32_t avail_mask`,
flags, and the seq counter. `main.cpp` feeds it every
`PidScheduler::Reading`: available → set bit + store value; unavailable →
clear bit. **Mask semantics: bit = the most recent poll of that signal
succeeded.** Legacy signals keep getting polled slowly (as today) and
honestly clear their bits when unanswered. `BATT_V_ADC` is **never set in
Phase 4** — the 100k/10k divider doesn't exist until Phase 2, and a floating
GPIO34 must not masquerade as a battery reading (handover gap 2). The
dashboard's battery gauge therefore shows "not provided" until Phase 2 —
by design, not by accident.

### 3.3 `telemetry_frame` — the packer (native-tested)

Pure functions, no Arduino headers:

```cpp
void pack_telemetry(const LatestValues& lv, uint8_t out[kTelemetryFrameLen]);   // 88 bytes
size_t pack_dtc_report(const DtcList& stored, const DtcList& pending, uint8_t* out, size_t cap);
bool parse_control(const uint8_t* data, size_t len, ControlCommand* out);
```

This is where `ble_protocol.md`'s golden vectors are enforced natively.

### 3.4 `demo_feed` — synthetic telemetry (native-tested)

Framework-agnostic generator producing a deterministic, plausible M271 cycle:
warm idle (≈750 rpm, MAF ≈4 g/s, closed throttle) → rev sweep → ≈90 km/h
cruise, values consistent with `w203_bands.json` healthy bands. Availability
mask matches what a real healthy session would show, **except** it also
serves `BATT_V_ADC` ≈ 14.2 V (so the battery gauge is exercised at least once
before Phase 2) and sets the `demo_mode` flag. Serves stored `P0171` + empty
pending, so the DTC screen is proven over real BLE end-to-end. Native tests
assert every generated value stays inside `pid_schema` bounds and the mask is
exactly the expected constant.

### 3.5 `ble_svc` — thin ESP32-only NimBLE wrapper (compile-verified only)

Built on **NimBLE-Arduino**, version pinned in `platformio.ini` `lib_deps`.
[Likely the right library: dramatically lower RAM/flash than the stock
Bluedroid stack; the build currently uses 6.6 % RAM / 24 % flash, so either
fits, but NimBLE leaves headroom for Phase 5's WiFi coexistence.]
Responsibilities, and nothing else: init + advertise, own the three
characteristics + DIS, notify telemetry every 500 ms **from the cache** while
a client is subscribed, notify the DTC characteristic when its cached frame
changes (memcmp), dispatch control writes to a time-sync callback. Zero
protocol logic inside — it calls `telemetry_frame` for all bytes. Verified by
`pio run -e esp32dev` (and `-e esp32dev_demo`) compiling; the plan states
this honestly (BLE cannot be exercised natively).

### 3.6 Build targets & main-loop integration

- `[env:esp32dev]` — real target: K-line polling + BLE together. The
  `kline_connected` flag mirrors link state (StartCommunication succeeded and
  not currently in reinit). BLE tick, tester-present, flush, and scheduler
  polling all run from the existing single loop.
- `[env:esp32dev_demo]` — extends `esp32dev` with `-DKL_DEMO_MODE`:
  `demo_feed` replaces scheduler/KWP entirely (no UART use). This is the
  final-hardware-session target: bare ESP32 on USB power, nothing else.
- **Startup-snapshot-lite:** after `start_communication()` succeeds, read
  stored+pending DTCs once, push to the BLE cache, and write one
  comment-prefixed line into the ride CSV header block (small `ride_logger`
  extension: `write_header_line()`); re-read every 60 s while the link is up.
  [Best estimate: DTCs change rarely; one extra request per minute is
  negligible inside the ~10 req/s budget.]

## 4. Android app design

Single module, manual DI in the `Application` class (no Hilt — three screens,
one user). New dependencies (versions pinned during execution, after Task 0's
first sync settles the toolchain): `kotlinx-serialization-json` (asset JSON,
JVM-testable), `navigation-compose`, `datastore-preferences`,
`lifecycle-service`, `kotlinx-coroutines`. Package layout:

```
com.kompressorlink.app/
  KompressorLinkApp.kt        Application + DI container
  MainActivity.kt             NavHost: Dashboard, Dtc
  telemetry/
    TelemetrySnapshot.kt      values FloatArray(19), mask, flags, seq, uptimeMs, receivedAt
    DtcReport.kt              stored: List<String>, pending: List<String>
    TelemetrySource.kt        the load-bearing interface (§4.1)
    FakeTelemetrySource.kt    2 Hz ticker, 3 scenarios (§4.2)
    SourceSwitcher.kt         delegates to active source; DataStore-persisted choice
    ble/
      GattClient.kt           the ~250-line raw BluetoothGatt wrapper (§4.3)
      FrameCodec.kt           pure-Kotlin frame parse/build — golden-tested
      BleTelemetrySource.kt   GattClient + FrameCodec → TelemetrySource
  connection/
    ConnectionService.kt      foreground service, type connectedDevice
    CdmAssociator.kt          one-time association + presence observation
  dashboard/                  DashboardViewModel, DashboardScreen, gauge composables
  dtc/                        DtcViewModel, DtcScreen
  reference/                  BandsRepository — w203_bands.json + w203_dtc.json from assets/
```

### 4.1 `TelemetrySource` — the seam that makes hardware-free development work

```kotlin
interface TelemetrySource {
  val connectionState: StateFlow<ConnectionState>
  val telemetry: Flow<TelemetrySnapshot>
  val dtcReport: StateFlow<DtcReport?>
  suspend fun sendTimeSync()
}
```

ViewModels only ever see this. `SourceSwitcher` (itself a `TelemetrySource`)
delegates to the active implementation; the choice — Simulated-healthy /
Simulated-fault / Simulated-sparse / Real BLE — is an overflow-menu toggle on
the dashboard, persisted in DataStore. Fresh install defaults to
Simulated-healthy (there's no ESP32 until the final session). No separate
settings screen.

### 4.2 `FakeTelemetrySource` scenarios

- **healthy** — mirrors `demo_feed`'s cycle and `kline_sim.py`'s healthy
  scenario magnitudes.
- **fault** — LTFT ≈ +18 % at warm idle + stored P0171: lights up the
  out-of-band tinting, the breather-hose hint, and the DTC screen.
- **sparse** — clears the O2/MAP mask bits, simulating a stingy SIM4LKE —
  rehearses exactly what Phase 3 might reveal (PLAN.md §10 risk 1).

All three keep Legacy + `BATT_V_ADC` bits clear (matching real Phase 4
firmware), except demo parity: the fake healthy scenario also exercises the
battery gauge with mask-set 14.2 V, mirroring `demo_feed`.

### 4.3 `GattClient` — raw BluetoothGatt wrapper

States: `Disconnected → Connecting → Discovering → Ready` (+ `ProtocolMismatch`).
Explicit, confidence-tagged handling of the known sharp edges:

- All GATT operations serialized through a single queue (Android silently
  drops concurrent GATT calls). [Confirmed — documented Android behavior]
- Connect with `TRANSPORT_LE`, retry status 133 with backoff 1 s → 2 s → 5 s
  → steady 15 s. [Likely — community-established workaround, exact behavior
  on One UI 6 unverifiable until the final session]
- MTU 517 requested post-connect; < 91 ⇒ disconnect + retry (§2.2).
- CCCD descriptor writes to enable both notify characteristics, serialized.
- Callbacks arrive on binder threads → immediately marshalled onto a single
  coroutine dispatcher; UI state only via StateFlow.

### 4.4 Auto-connect chain (PLAN.md §6.1's sanctioned path)

One-time CDM `associate()` with a `BluetoothLeDeviceFilter` scan filter on
the service UUID → `startObservingDevicePresence` → `onDeviceAppeared`
launches `ConnectionService` (foreground, `connectedDevice` type) even from
cold → `GattClient` connects → subscribe → `sendTimeSync()`. Presence APIs
are API 31+; the S23 FE is API 34, so this is the real path. minSdk stays 26:
guarded by `Build.VERSION` checks with a manual-connect fallback so the app
still compiles and runs honestly on older APIs, but the fallback is not a
Phase 4 deliverable. Permissions: `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN`
(API 31+ runtime), `POST_NOTIFICATIONS` (API 33+),
`REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` (needed to call
`startObservingDevicePresence`/receive `onDeviceAppeared`),
`foregroundServiceType="connectedDevice"` **plus** the matching
`FOREGROUND_SERVICE_CONNECTED_DEVICE` manifest permission — targetSdk 34
requires a typed permission per declared foreground-service type, not just
the type attribute itself; omitting it is a `SecurityException` at
`startForeground()`, not a lint warning. Documented one-time manual steps
for the user: grant nearby-devices permission, disable battery optimization
for the app (PLAN.md §6.1 + §10's Samsung risk — exact One UI menu path goes
in the app README).

### 4.5 Dashboard screen

Gauge/sparkline tiles: RPM, MAF, STFT+LTFT (one trims tile), ECT, MAP/boost,
O2 B1S1 / B1S2, battery V, timing advance (PLAN.md §6.2 list). Each tile:

- carries its healthy band from `w203_bands.json`; out-of-band → amber/red
  tint + the one-line W203 hint (e.g. "LTFT +18 % at warm idle → classic M271
  breather-hose leak — see guide §2 tree");
- band `context` gating: `always` bands apply continuously; `warm_idle` bands
  (e.g. MAF 3–5 g/s) apply only when the snapshot itself satisfies warm idle
  (RPM 600–900, SPEED = 0, ECT > 80 — the same definition PLAN.md §7 gives
  `kl features`); otherwise the tile renders neutral;
- mask bit clear → "not provided by ECU," never a value (Untested contract).
  `BATT_V_ADC` will honestly show that against the real target until Phase 2.

Top bar: connection chip — Live / Demo device / Simulated (scenario) /
Disconnected — plus `kline_connected` state when on BLE.

### 4.6 DTC screen (read-only)

Stored + pending lists from `dtcReport`. Each code maps through
`w203_dtc.json` to the W203 likely-cause chain (ordered) from
`docs/w203_reference.md`, with its confidence tag rendered in the UI. Codes
with no W203-specific entry render the raw code + "no W203-specific
interpretation" (no generic SAE database in Phase 4). Empty state: "No stored
codes." Clear-DTC does not exist in this phase.

### 4.7 Reference JSONs (closes handover gap 3)

Two new assets in `android/app/src/main/assets/`, derived from
`docs/w203_reference.md`, every entry carrying its confidence tag:

```json
// w203_bands.json (shape)
{ "signal": "MAF_GS", "context": "warm_idle", "lo": 3.0, "hi": 5.0,
  "unit": "g/s", "confidence": "Confirmed",
  "hint": "Warm-idle MAF outside 3–5 g/s → check intake/breather leaks first" }

// w203_dtc.json (shape)
{ "code": "P0171", "title": "System too lean (bank 1)",
  "likely_causes": ["Partial-load breather hoses (THE M271 fault)", "MAF", "Fuel pressure"],
  "confidence": "Confirmed" }
```

The P0300 and P0010–P0014 families are expanded to individual codes; cooling
codes get family entries per the reference doc. A JVM unit test asserts both
files parse and that every dashboard signal with a band in
`docs/w203_reference.md` has an entry — the doc and the JSON can't drift
silently.

## 5. Testing & verification ladder

Everything before the last rung is hardware-free, per the user's hard
constraint.

1. **Android JVM unit tests** (`gradlew.bat testDebugUnitTest`, run by
   agents): FrameCodec golden vectors (§2.5), DTC 2-byte→"P0171" decode
   (inverse-of-sim table test), bands/DTC JSON parse + coverage, ViewModel
   logic driven by `FakeTelemetrySource`, SourceSwitcher behavior.
2. **Firmware native tests** (`pio test -e native`, run by agents): packer
   golden vectors, Mode 03/07 parsing against sim-dumped fixtures, demo-feed
   bounds + mask, control-frame parsing. Plus `pio run -e esp32dev` and
   `-e esp32dev_demo` both compiling.
3. **Manual milestones on the S23 FE** (user, at defined checkpoints): app
   renders dashboard + DTC screens on fake data; scenario toggle works;
   permission flow completes; foreground-service notification behaves.
4. **Final hardware session** (user + agent, the one ESP32 plug-in): flash
   `esp32dev_demo` over USB → one-time CDM associate → kill app, power-cycle
   ESP32 → auto-connect fires with the phone locked → live demo telemetry
   streams, P0171 shows on the DTC screen, battery gauge shows demo 14.2 V,
   time sync lands (checked via Serial log).

**Task 0 (user) + Task 0b (agent):** install Android Studio, open `android/`,
generate wrapper, sync (accept AGP/Kotlin/Compose upgrade prompts, report
what changed); then the agent probes `ANDROID_HOME` / `%LOCALAPPDATA%\
Android\Sdk` / Studio's bundled JBR, and verifies headless
`gradlew.bat testDebugUnitTest` works, recording the exact invocation
(JAVA_HOME etc.) in the plan's environment notes for all later tasks.

## 6. Acceptance gate (PLAN.md §9 Phase 4 row, restated honestly)

§6.3's criteria assume a car; Phases 2–3 haven't happened. Phase 4's gate is
their bench-demo equivalents:

- **Auto-connect:** phone locked, app killed → ESP32 powered on → notification
  + live data within 20 s, no manual app launch. (§6.3 bullet 1, bench form.)
- **Live dashboard:** demo telemetry streams at 2 Hz with no visible UI jank
  during a ≥ 10-minute soak. The literal 45-minute-ride / < 6 %/hr battery
  criterion **can only be confirmed once Phases 2–3 put the device in the
  car** — explicitly carried forward, not silently dropped.
- **DTC screen:** stored P0171 from the demo feed renders with the W203
  interpretation chain; pending shows empty.
- Ride-sync (§6.3 bullet 3) is Phase 5 — not part of this gate.

## 7. Risks (carried into the plan, not hidden)

- **First Gradle sync may bump AGP/Kotlin/Compose.** Task 0 says accept and
  report; the plan pins whatever the sync settles on. Library versions in
  this spec are placeholders until then.
- **NimBLE footprint** [Likely fine: 6.6 % RAM / 24 % flash today; NimBLE is
  the lighter stack]. If flash pressure appears, it's visible at compile time.
- **CDM presence latency on One UI** [Likely seconds-not-minutes; only the
  final session proves it]. Fallback if `onDeviceAppeared` is flaky: the
  foreground service falls back to periodic scan-and-connect — PLAN.md §6.1
  already sanctions a fallback path; kept minimal.
- **Real SIM4LKE DTC behavior** (paging, and whether Mode 03 responses carry
  an explicit count byte at all — §2.3/§3.1 flag this may be CAN-style
  framing borrowed by `kline_sim.py` rather than real K-line framing)
  [Guessing until Phase 3]; the 8-code cap and clamping are deliberately
  defensive, and `read_stored_dtcs`/`read_pending_dtcs` are exactly where a
  Phase 3 fix lands if the real ECU's framing differs — isolated behind the
  `DtcList` interface, nothing downstream (BLE packer, app parser) needs to
  change since both already speak in `DtcList`/decoded-code terms.
- **Samsung battery management killing the service** despite exemptions
  (PLAN.md §10) — mitigated by documented settings; the SD card (Phase 2+)
  ultimately guarantees no data loss regardless; in Phase 4 nothing is lost
  because nothing is only-on-phone.
- **Golden vectors are hand-mirrored constants**, not a build-time link.
  Accepted (approach A): review discipline + the mirror-verbatim comment
  convention already police identical mirrors in this repo.

---

## Self-review notes

- **Placeholder scan:** no TBD/TODO. Android dependency versions are
  deliberately "pinned at execution after first sync" — that is a stated
  decision with a mechanism (Task 0 report), not an omission.
- **Internal consistency:** frame length (88 = 12 + 76) matches the field
  table; mask semantics identical in §2.2, §3.2, §4.5; the demo battery
  exception is stated in both §3.4 and §4.2; DTC cap 8 consistent across
  §2.3/§3.1/§7; UUID tail identical across the four rows.
- **Scope check:** one implementation plan's worth: ~5 firmware modules +
  1 protocol doc + 1 app with 2 screens; the deferred list is explicit.
- **Ambiguity check:** mask bit = "most recent poll succeeded" (not "ever
  succeeded") — stated once, normatively, in §3.2. "Not provided" rendering
  is defined by mask bit, not by tier. Demo-mode flag lets the app label the
  connection chip truthfully.
- **Review process note:** a 5-lens adversarial multi-agent review
  (Android-API correctness, firmware/protocol correctness, internal
  consistency, ground-rule/handover compliance, planability) was launched
  but every lens failed on a session-limit error before producing findings —
  no automated findings exist to report. In its place, a manual technical
  pass fixed two concrete issues inline: (1) §2.3/§3.1/§7 now flag that the
  DTC response's leading count byte mirrors `kline_sim.py`'s own invention
  and may be CAN-style framing rather than confirmed K-line/J1979 framing
  (real K-line Mode 03 typically has no separate count byte — DTC count is
  implicit in frame length); isolated behind `DtcList` so a Phase 3 fix
  doesn't ripple. (2) §4.4 now lists the `FOREGROUND_SERVICE_CONNECTED_DEVICE`
  and `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` manifest permissions the
  original draft omitted — targetSdk 34 requires a typed permission per
  declared foreground-service type. The multi-agent review can be re-run
  after the session limit resets if a second independent pass is wanted.
