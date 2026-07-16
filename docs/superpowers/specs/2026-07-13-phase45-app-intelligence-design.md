# Phase 4.5: App Intelligence & UX — Design

**Status:** Approved by user 2026-07-13 (brainstorming parts 1–3). Feeds into a
`writing-plans` implementation plan executed by Sonnet via
`superpowers:subagent-driven-development`.

**Scope:** a software-only, Android-only phase between Phase 4 (done) and the
future hardware phases. Four workstreams, one plan: (1) the app gains memory —
a Room database recording per-session aggregates; (2) an honest
failure-early-warning system (severity + personal baseline + drift, never
claimed as ML fault prediction — CLAUDE.md rule 2); (3) a preventive-
maintenance tracker with service log and background reminders; (4) a full UI
redesign with friendlier-but-accurate DTC explanations. Firmware, PC pipeline,
and everything hardware/car-dependent are untouched.

**Decisions settled during brainstorming (2026-07-13):**

1. **No hardware, no car this phase.** The user owns neither yet. Phase 4's
   deferred Task 21 (bench BLE session) stays deferred; nothing in this phase
   may require an ESP32, the car, or any purchase. The S23 FE phone itself IS
   available — manual checkpoints run against simulated sources.
2. **Full session history** (not light memory): one summary row per
   drive/sim-run plus per-signal stats, in Room. Trends and early warnings are
   computed from this history.
3. **Odometer = manual anchors + auto-estimate.** The 2004 SIM4LKE does not
   expose odometer over OBD [Confirmed — PID 0xA6 is a much-later addition].
   The user occasionally types the real odometer; between anchors the app adds
   distance integrated from REAL drives only. Simulated sessions never move it.
4. **Maintenance depth: due-tracking + service log.** Seeded, editable
   M271/W203 schedule; marking a service done records date/km/note and resets
   the interval; history stays viewable. (Full logbook with costs/photos:
   rejected as scope creep.)
5. **DTC coverage: three layers.** Curated W203/M271 set (~50–80 codes, full
   friendly treatment) → generic OBD-II library (~250 common codes, title +
   short meaning + urgency) → structural family decoder (never a bare code).
6. **UI: full redesign.** Material 3 dark automotive theme, four bottom-nav
   tabs (Dashboard / Health / DTCs / Maintenance), band-bar gauges, connection
   banner, expandable DTC cards. Mockup: `2026-07-13-phase45-ui-mockup.html`
   (directional — structure and states are normative, pixels are not).
7. **Health presentation: subsystem statuses + worst-of overall verdict** plus
   a warnings feed. A single averaged "health %" was explicitly rejected
   (averages can mask a single serious problem; a percentage implies precision
   the data can't back).
8. **Approach 1 accepted:** one phase, everything on-phone, logic-first task
   ordering. (Rejected: two sequential phases — little visible payoff after
   the first; UI-first with stubbed logic — inverts the project's honesty
   culture and guarantees rework.)

**Out of scope for Phase 4.5 (explicitly deferred):** Task 21 bench session and
anything needing hardware; Rides screen, WiFi sync, ride-file ingest; guided
tests; export; clear-DTC (Mode 04) and its double-confirm UI; PC pipeline
(`kl ingest/features/baseline/trend/report` stay stubs); firmware changes of
any kind; localization; cloud anything.

---

## 1. Architecture

Everything new lives in the Android app. The existing `TelemetrySource` seam
is untouched — the new layer *observes* it, so sim sources exercise the entire
new stack today and real BLE exercises it identically later.

```
TelemetrySource (SourceSwitcher: FakeTelemetrySource | BleTelemetrySource)
      │ snapshots (2 Hz) + DtcReport + ConnectionState
      ▼
SessionRecorder ──► SessionAggregator (streaming stats, no per-sample storage)
      │ on session close: SessionEntity + SessionStatEntity rows (+ distance if REAL)
      ▼
Room DB (sessions, session_stats, warnings, odometer_entries,
         maintenance_items, service_log)
      ▲                    ▲                       ▲
      │                    │                       │
HealthEngine          MaintenanceEngine        DtcRepository (3-layer lookup,
(live severity ·      (DueCalculator ·         no DB — assets + decoder)
 personal baseline ·   OdometerEstimator ·
 drift · subsystems ·  ReminderPolicy →
 warnings)             WorkManager daily check)
      │                    │                       │
      ▼                    ▼                       ▼
HealthViewModel       MaintenanceViewModel     DtcViewModel (rewritten)
DashboardViewModel (extended: contexts + hysteresis)
      │
      ▼
Compose UI — Material 3 dark theme, bottom nav: Dashboard | Health | DTCs | Maintenance
```

**The quarantine rule (load-bearing, tested, non-negotiable):** every row
derived from telemetry carries its source (`SIM_HEALTHY`, `SIM_FAULT`,
`SIM_SPARSE`, `REAL_BLE`). Health verdicts, baselines, drift, odometer
estimation, and maintenance state read **REAL_BLE rows only**. Sim-derived
rows are reachable solely through the Health screen's explicit Demo mode
(amber ribbon, §8.3). Sim data can never move the odometer, never feeds
reminders, never colors a real verdict. This is the app-side extension of the
Untested contract.

**Purity rule:** engine code (`health/`, `maintenance/` calculators,
aggregation, DTC decode) is pure Kotlin — no `android.*` imports — tested on
the JVM exactly like `DashboardLogic` today. Time is always injected
(`nowMs: Long` / `Clock` parameters), never read inside engines.

## 2. Data layer (Room)

New dependencies: `androidx.room:room-runtime/-compiler(-ksp)/-ktx`,
`androidx.work:work-runtime-ktx`, `org.robolectric:robolectric` (test),
`androidx.room:room-testing` (test). Versions pinned at execution against the
existing toolchain (AGP 8.x/Kotlin as settled in Phase 4 — same
"pin at execution" pattern as Phase 4 §7). Database `kl.db`, version 1,
`exportSchema = true` (schemas checked into `android/app/schemas/` for future
migration honesty). Fresh install only — no migrations this phase.

Entities (columns abridged to the normative essentials):

- **`sessions`** — `id` PK autogen; `startedAtEpochMs`; `endedAtEpochMs`;
  `source` (TEXT: `SIM_HEALTHY|SIM_FAULT|SIM_SPARSE|REAL_BLE`);
  `snapshotCount`; `warmIdleSeconds`; `distanceKm` (REAL, nullable — non-null
  only for REAL_BLE sessions with SPEED available); `hasStoredDtc` (BOOLEAN —
  any stored DTC reported during the session).
- **`session_stats`** — composite PK (`sessionId` FK CASCADE, `signal` TEXT =
  `Signal` enum name); `sampleCount`; `mean`; `min`; `max`; `stdDev`
  (Welford); `secondsOutOfBand` (REAL — raw band violations, pre-hysteresis);
  `worstLevel` (TEXT: `OK|AMBER|RED`, raw); contextual means, each nullable
  `mean`+`count` pair: `warmIdleMean/warmIdleCount` (populated for MAF_GS,
  STFT1, LTFT1, ECT), `engineRunningMean/engineRunningCount` and
  `engineOffMean/engineOffCount` (populated for BATT_V_ADC only).
- **`warnings`** — `id` PK; `createdAtEpochMs`; `lastSeenAtEpochMs`;
  `sessionId` (nullable FK — drift/baseline warnings span sessions);
  `subsystem` (TEXT, §5.4); `signal` (nullable TEXT); `level`
  (`WATCH|ATTENTION`); `kind`
  (`LIVE_OUT_OF_BAND|BASELINE_DEVIATION|DRIFT|DTC`); `title`; `detail`;
  `acknowledged` (BOOLEAN); `source` (`SIM|REAL`); `dedupeKey` (TEXT).
  Dedupe rule: if an **unacknowledged** warning with the same `dedupeKey`
  exists, update its `lastSeenAtEpochMs`/`detail` instead of inserting;
  acknowledged warnings are history — a recurrence inserts a new row.
  `dedupeKey` = `kind:subsystem:signalOrCode`.
- **`odometer_entries`** — `id` PK; `epochMs`; `km` (INTEGER). Manual anchors
  only; estimates are derived, never stored as anchors. Insert validation:
  `km` ≥ the latest existing anchor's km (reject with a clear message
  otherwise — odometers don't run backwards).
- **`maintenance_items`** — `id` PK; `name`; `category`
  (`ENGINE|TRANSMISSION|BRAKES|FLUIDS|INSPECTION`); `intervalKm` (nullable
  INTEGER); `intervalMonths` (nullable INTEGER; at least one interval non-null
  — enforced in repository, not SQL); `note` (the "why this matters on this
  engine" line); `confidence` (TEXT tag per CLAUDE.md rule 4); `builtin`
  (BOOLEAN); `enabled` (BOOLEAN); `lastNotifiedAtEpochMs` (nullable).
- **`service_log`** — `id` PK; `itemId` FK CASCADE; `epochMs`; `km`
  (nullable INTEGER); `note` (nullable TEXT).

Seeding: on first database create, insert the §6.2 builtin schedule
(`builtin = true`). Builtins are editable (intervals, enabled) but not
deletable; custom items (user-added) are deletable.

Repositories wrap DAOs behind interfaces (`SessionRepository`,
`WarningRepository`, `MaintenanceRepository`, `OdometerRepository`) so engines
test against in-memory fakes on the JVM; DAO correctness itself is covered by
Robolectric tests against an in-memory Room instance.

## 3. Session recording

`SessionRecorder` is app-scoped (created in `AppContainer`, started from
`KompressorLinkApp`), collecting the active `SourceSwitcher` flows.

- **Open:** first `TelemetrySnapshot` from the active source opens a session
  stamped with the current `SourceChoice`.
- **Close:** whichever comes first — no snapshot for **30 s**, source switch,
  or `ConnectionState` leaving the streaming state. `endedAtEpochMs` = the
  last snapshot's receive time (a disconnection can't inflate duration).
- **Persist gate:** only sessions with duration ≥ **60 s** AND ≥ **30**
  snapshots are written; shorter ones are discarded (parking-lot blips and
  test flickers don't pollute history). Constants live in `HealthTuning`
  (§5.6) [Best estimate defaults, tunable in one place].
- **Aggregation** is streaming (Welford mean/stdDev, min/max, counters) —
  per-sample data is never stored. Contextual means accumulate only while
  their context holds: warm idle = existing `DashboardLogic.isWarmIdle`
  (RPM 600–900 ∧ SPEED = 0 ∧ ECT > 80); engine running = RPM ≥ 400; engine
  off = RPM present ∧ < 400. A context whose gating signal is unavailable is
  simply not accumulated — never guessed (Untested contract).
- **Stats honesty:** `secondsOutOfBand`/`worstLevel` use **raw** band
  comparisons (no hysteresis) — stored stats record what the car did;
  hysteresis (§4) is a UI/alerting smoothing only.
- **Distance:** for REAL_BLE sessions, `distanceKm` = Σ SPEED·dt/3600 over
  consecutive snapshot pairs with dt ≤ **5 s** and SPEED available in both —
  connection gaps contribute zero. Sim sessions store NULL.
- **DTCs:** `hasStoredDtc` latches true if any `DtcReport` during the session
  carries a stored code. DTC-kind warnings are generated at report time
  (§5.5), tagged with the session's source.

## 4. Band system upgrades (existing-model fixes)

- **New band contexts.** `w203_bands.json` `context` gains `engine_running`
  and `engine_off` alongside `always`/`warm_idle`. Battery gets two rows:
  `engine_running` 13.8–14.5 V [Confirmed] (existing charging band) and
  `engine_off` 12.5–13.2 V [Best estimate — ≥ 12.5 V healthy rest per
  w203_reference.md; upper bound allows post-shutdown surface charge; hint
  wording: "resting below 12.5 V suggests a weak or draining battery"].
  Context predicates are the §3 definitions, evaluated from the same
  snapshot. RPM unavailable ⇒ neither engine context applies ⇒ battery tile
  renders NEUTRAL (no false verdicts from missing data). This fixes the
  audit finding that a healthy parked car showed RED.
- **Hysteresis.** UI gauge levels and live-warning triggering escalate only
  after the raw level has been continuously at-or-worse for ≥ **3 s**, and
  de-escalate only after continuously better for ≥ **10 s** [Best estimate
  defaults, in `HealthTuning`]. Applies per signal in the ViewModel layer
  (pure `HysteresisFilter` class, JVM-tested). Kills the band-edge color
  flicker. Raw stats (§3) bypass it.
- `DashboardLogic.levelFor` (in-band OK / ≤ half-width beyond AMBER / else
  RED) is unchanged — it becomes the "raw level" input to the filter.

## 5. Health engine (`health/`, pure Kotlin)

### 5.1 Metrics registry

Cross-session intelligence operates on **metrics** — one scalar per session,
extracted from `session_stats`:

| Metric | Source column | Subsystem | Absolute band |
|---|---|---|---|
| `LTFT_WARM_IDLE` | LTFT1 `warmIdleMean` | FUELING | ±10 % |
| `STFT_WARM_IDLE` | STFT1 `warmIdleMean` | FUELING | ±10 % |
| `MAF_WARM_IDLE` | MAF_GS `warmIdleMean` | AIR_INTAKE | 3–5 g/s |
| `ECT_WARM_IDLE` | ECT `warmIdleMean` | COOLING | 87–95 °C |
| `BATT_CHARGING` | BATT_V_ADC `engineRunningMean` | BATTERY | 13.8–14.5 V |
| `BATT_REST` | BATT_V_ADC `engineOffMean` | BATTERY | 12.5–13.2 V |

A session contributes a metric only when the contextual `count` ≥ **30**
samples [Best estimate — ~15 s of context dwell at 2 Hz; below that the mean
is noise]. Bands are read from the same parsed `w203_bands.json` the
dashboard uses — one source of truth, no duplicated constants.

### 5.2 Personal baseline (the sanctioned "learning")

Per metric, over the last ≤ **20** *baseline-eligible* REAL sessions:
envelope = median ± **3 × MAD**. Baseline-eligible session = that metric's
signal had `worstLevel ≠ RED` in the session AND `hasStoredDtc = false`
(don't learn "normal" from a car that was visibly faulting). Degenerate
guard: if MAD < 1 % of the absolute band width, widen to ±5 % of band width
[Best estimate]. **Activation gate:** ≥ **8** eligible sessions spanning ≥
**14 days**; until then the UI shows "Collecting baseline · N of 8 drives"
and no baseline verdicts fire. A new session's metric outside the personal
envelope but inside the absolute band ⇒ `BASELINE_DEVIATION` warning, level
WATCH: *"{metric} is outside your car's usual range ({value} vs typical
{median} ± {spread}) — inside absolute limits, worth watching."*

### 5.3 Drift detection (the honest "predict before it happens")

Per metric, over the last ≤ **20** REAL sessions carrying it (eligible or
not — drift must see deterioration), x = session end time in days, y =
metric value: **Theil–Sen slope** (median of pairwise slopes — robust to
outlier sessions). Preconditions: ≥ **8** points spanning ≥ **14 days**.
A drift verdict fires when the projected crossing of the nearest absolute
band edge (in the slope's direction) lands within **1–26 weeks**:
`weeksToEdge = (edge − current) / (slope · 7)`, where `current` = median of
the last 3 points. Wording template (normative): *"{Metric} is
{rising|falling} ≈ {|slope·7| formatted}/week — at this rate it crosses
{edge} in ~{N} weeks. {W203 hint from the band}."* Level: WATCH (it is an
extrapolation, never ATTENTION on its own). If the metric is already beyond
the edge, drift stays silent — live severity and baseline own that case.
Slopes projecting beyond 26 weeks render as "stable" in the Health detail,
fire nothing. No fake probabilities, no "87 % chance of failure" — time-to-
threshold with explicit "~" and "at this rate" phrasing only.

### 5.4 Subsystems and rollup

Fixed subsystem set and signal/DTC mapping (normative):

| Subsystem | Live signals | DTC families (match on P0xxx number; ranges are disjoint) |
|---|---|---|
| FUELING | STFT1, LTFT1, FUEL_STATUS, O2_B1S1_V | 0130–0135 (pre-cat O2), 0170–0175 (trim/lean-rich), 0200–0209 (injector circuits), 0440–0457 (EVAP) |
| AIR_INTAKE | MAF_GS, MAP, THROTTLE | 0100–0114 (MAF/IAT circuits), 0120–0124 (TPS), 0068, 0235–0238 (boost), 0505–0507 (idle control) |
| COOLING | ECT | 0115–0119, 0125–0128, 0480–0485 (fans) |
| IGNITION | TIMING_ADV | 0010–0017 (cam adjusters/timing), 0300–0312 (misfire), 0325–0328 (knock), 0335–0349 (crank/cam sensors), 0350–0362 (coils) |
| EXHAUST_CAT | O2_B1S2_V | 0136–0141 (post-cat O2), 0410–0419 (secondary air), 0420–0439 (cat) |
| BATTERY | BATT_V_ADC | 0560–0563 (system voltage) |
| OTHER | — | anything unmapped (incl. all non-P0 codes) |

Subsystem status = worst of: (a) current-session post-hysteresis level of its
live signals, mapped gauge→status as AMBER ⇒ WATCH, RED ⇒ ATTENTION
(OK/NEUTRAL/UNAVAILABLE ⇒ OK — absence of data is never a fault), (b) open
(unacknowledged) warnings targeting it — WATCH for
`BASELINE_DEVIATION`/`DRIFT`, (c) DTCs: any **stored** code ⇒ ATTENTION, any
**pending** code ⇒ WATCH, with the code and its curated title as the stated
reason. Status labels (normative UI text): OK → "Looks good", WATCH →
"Worth watching", ATTENTION → "Needs a look". Overall verdict = worst
subsystem, phrased "All systems look normal" / "{n} system(s) worth watching"
/ "{n} system(s) need a look — {names}". OTHER renders as a card only when
non-empty. IGNITION carries a permanent footnote: "DTC-based — this ECU
exposes no live misfire counters" (scope honesty on screen).

### 5.5 Warnings feed

Warning producers: live severity (post-hysteresis RED, or AMBER sustained ≥
**60 s** [Best estimate]), baseline deviation (§5.2), drift (§5.3), DTC
transitions (a code newly appearing in a report). All go through the
`dedupeKey` upsert (§2). Acknowledging is a UI action setting
`acknowledged = true`; acknowledged warnings stay listed (dimmed) under
"History". Warnings carry `source`; the feed shows REAL warnings (Demo mode
swaps to SIM ones, §8.3).

### 5.6 `HealthTuning`

One pure-Kotlin object holding every tunable named above (session gates 30 s /
60 s / 30 snapshots, hysteresis 3 s / 10 s, context sample floor 30, baseline
20 / 3×MAD / 8 sessions / 14 days, drift 20 / 8 / 14 / 1–26 weeks, AMBER
dwell 60 s, due-soon 500 km / 30 days, re-notify 7 days). Every value carries
a `[Best estimate]` comment. Tests reference the constants, not magic
numbers.

## 6. Maintenance engine (`maintenance/`, pure Kotlin)

### 6.1 Odometer model

`OdometerEstimator`: estimated km = latest manual anchor km + Σ `distanceKm`
of REAL sessions ending after that anchor's `epochMs`. No anchor ⇒ estimate
is NULL ⇒ km-based due math is inactive ("Enter your odometer to activate km
tracking"). The Maintenance screen always shows the derivation: "Last
entered {km} · {date} · +{est} km estimated from drives". With no hardware
yet, the estimate simply equals the last anchor — correct and honest.

### 6.2 Seeded schedule (builtin items)

All intervals are service-conservative defaults for a 22-year-old M271,
editable in-app, each carrying its confidence tag and note verbatim:

| # | Item | km | months | Category | Confidence | Note (shown in UI) |
|---|---|---|---|---|---|---|
| 1 | Engine oil + filter | 10 000 | 12 | ENGINE | Best estimate | Chain-driven engine — fresh oil protects the timing chain and cam adjusters |
| 2 | Breather-hose inspection | 20 000 | 24 | INSPECTION | Best estimate | THE M271 failure: cracked partial-load breather hoses cause lean codes (P0171) |
| 3 | Cam-magnet oil-ingress check | 10 000 | 12 | INSPECTION | Likely | Known M271 issue — oil wicks into the connector/harness; check at each oil change |
| 4 | Spark plugs | 60 000 | 48 | ENGINE | Best estimate | Correct plug: FR6MPP332 or NGK ILFR6A [Confirmed type] |
| 5 | Air filter | 40 000 | 48 | ENGINE | Best estimate | Supercharged intake — a clean filter matters more than on NA engines |
| 6 | Cabin filter | 20 000 | 24 | ENGINE | Best estimate | Comfort item; cheap |
| 7 | Fuel filter | 60 000 | — | ENGINE | Best estimate | Often forgotten on this era of MB |
| 8 | Coolant renewal | 100 000 | 60 | FLUIDS | Best estimate | Use MB 325.0-approved coolant |
| 9 | Brake fluid | — | 24 | BRAKES | Likely | Standard MB two-year interval — hygroscopic fluid |
| 10 | Transmission service (722.6) | 60 000 | 60 | TRANSMISSION | Best estimate | "Lifetime fill" is a myth — fluid + filter + pan gasket |
| 11 | Poly-V belt + tensioner inspection | 60 000 | 48 | INSPECTION | Best estimate | Listen for tensioner rattle; check for cracking |

These are reference-guide-grade defaults, not WIS gospel — the UI's
Maintenance screen carries the same "confirm against WIS" caveat line as
`docs/w203_reference.md`.

### 6.3 `DueCalculator`

Inputs: item, latest `service_log` entry for it (nullable), estimated km
(nullable), `nowMs`. Month math uses calendar months (`LocalDate.plusMonths`),
not day approximations. Output state, in precedence order:

1. `NEVER_LOGGED` — no service log entry: UI shows "When did you last do
   this?" with a log prompt; no reminder fires.
2. `OVERDUE` — any computable dimension negative (km remaining < 0 OR date
   past). Shows "Overdue by {x km|y months}" (the more overdue dimension).
3. `DUE_SOON` — km remaining ≤ 500 OR days remaining ≤ 30.
4. `KM_UNTRACKED` — km-only item whose km side is incomputable (no odometer
   anchor or the last log has no km): "Enter your odometer to activate";
   dual-interval items fall back to their date side and additionally show the
   small km-untracked hint.
5. `OK` — shows the sooner of "{km} km left" / "{n months} left".

Marking done inserts a `service_log` row (date default today, km default
current estimate — both editable in the dialog) and clears
`lastNotifiedAtEpochMs`.

### 6.4 Reminders

`ReminderPolicy` (pure): given item states + `lastNotifiedAtEpochMs` + now ⇒
which items to notify. Rule: notify when state ∈ {DUE_SOON, OVERDUE} AND
(never notified OR last notified ≥ 7 days ago). The WorkManager side is a
thin `MaintenanceCheckWorker`: unique periodic work `kl-maintenance-check`,
every 24 h, no constraints; posts one notification per item (channel
"Maintenance reminders", tap opens MainActivity → Maintenance tab) and stamps
`lastNotifiedAtEpochMs`. Scheduled from `KompressorLinkApp.onCreate`
(idempotent `KEEP`). `POST_NOTIFICATIONS` is already requested at startup
(Phase 4). One UI battery management may delay/kill periodic work
[Likely — PLAN.md §10 risk]; mitigations: the Maintenance tab always shows
true state regardless, and the overflow menu gains a debug "Run reminder
check now" entry (also the acceptance-test hook).

## 7. DTC explanation system

### 7.1 Content schema

`w203_dtc.json` entries are upgraded (breaking change to `DtcEntry`, screen
rewritten same-phase):

```json
{ "code": "P0171", "title": "Engine running too lean (bank 1)",
  "meaning": "The engine is getting more air than the fuel math expects, so the computer keeps adding fuel to compensate.",
  "urgency": "CHECK_SOON",
  "can_i_drive": "Yes, for now — sustained lean running can slowly damage the catalyst.",
  "check_first": ["Partial-load breather hoses (THE M271 weak spot)",
                   "Intake leaks after the MAF", "MAF sensor readings", "Fuel pressure"],
  "note": "On this engine, cracked breather hoses cause this code far more often than a failing MAF.",
  "confidence": "Confirmed" }
```

Urgency enum + normative UI labels/colors: `INFO` → "Info" (neutral),
`CHECK_SOON` → "Check soon" (amber), `URGENT` → "Urgent — drive gently"
(red), `STOP` → "Stop driving" (red, filled). Wording rules (normative for
content authoring): plain language, no scare tactics, no invented
statistics; `check_first` ordered by W203 likelihood (breather hoses stay
first for lean codes); meanings ≤ 2 sentences; every entry confidence-tagged;
existing w203_reference.md interpretations are the seed and may not be
contradicted.

Coverage: all 15 existing codes upgraded + expansion to ≥ **50** curated
codes across the families this engine can actually set over K-line Mode
03/07: MAF/IAT circuits (P0100–0113), ECT circuit (P0115–0118), thermostat
(P0125/0128), TPS (P0120–0124), O2 sensors both positions (P0130–0141), lean/
rich (P0170–0175), injectors (P0201–0204), misfire (P0300–0304), knock
(P0325/0327), crank/cam sensors (P0335/0340/0341), coils (P0350–0354),
secondary air (P0410/0413/0418), cat (P0420), EVAP (P0440/0442/0446/0455),
idle control (P0505/0506/0507), system voltage (P0560–0562), ECU
(P0600/0601/0606), plus the existing cam-adjuster set (P0010–0017). Exact
final list is fixed in the plan; the schema test enforces ≥ 50 entries, no
duplicates, valid urgency values, and non-empty `meaning`/`check_first`.

`generic_dtc.json` (new asset): ~**250** common generic OBD-II codes —
`code`, `title`, `meaning` (1 sentence), `urgency`. [Confirmed content —
standard SAE J2012 meanings.] Schema test: ≥ 200 entries, no overlap
processing needed (curated always wins), valid urgencies.

### 7.2 Structural decoder (pure function)

`decodeDtcFamily(code)`: char 1 → P/C/B/U system; digit 2 → generic (0) vs
manufacturer-specific (1/2/3); digit 3 (P-codes) → family text (0/1 "fuel &
air metering", 2 "injector circuit", 3 "ignition / misfire", 4 "auxiliary
emissions", 5 "speed & idle control", 6 "computer & outputs", 7/8/9
"transmission"). Output example: *"P1863 — manufacturer-specific powertrain
code, transmission group. No detailed entry for this code — have it read with
a MB-capable scanner for the exact meaning."* Urgency: none — the card says
"Unknown urgency". Never fabricates a meaning.

### 7.3 Lookup and UI contract

`DtcRepository.explain(code)`: curated → generic → structural; result carries
`sourceLayer` rendered as a chip: **"W203-specific"** / **"Generic OBD-II"**
/ **"Code family only"** — friendliness never blurs provenance. DTC screen
(§8.4) renders one expandable card per code. DTCs also feed §5.4's subsystem
rollup via the family table.

## 8. UI redesign

New packages: `ui/theme` (Material 3 dark automotive theme — dark by default,
follows system setting; large numerals for gauge values), `ui/components`
(shared: `ConnectionBanner`, `BandBar`, `StatusChip`, `UrgencyChip`,
`SparklineCanvas` — extracted from today's dashboard). Bottom navigation with
four destinations + icons: Dashboard (speed), Health (heart-pulse), DTCs
(warning), Maintenance (wrench). The mockup file is the directional visual
target; structure/states below are normative.

### 8.1 Connection banner (every screen)

States: Connected (device name) · Connecting · Disconnected · Simulated
({scenario}) — driven by `ConnectionState` + `SourceChoice`. The existing
top-bar SIM/BLE switcher and "Pair with device…" menu remain.

### 8.2 Dashboard (rewrite of existing)

Existing tile grid, upgraded: every banded gauge gets a `BandBar` (thin track
spanning `[lo − w, hi + w]` where `w` = band half-width — i.e. exactly the
AMBER envelope — healthy zone highlighted, marker at current value, marker
color = post-hysteresis level); sparklines stay; trims tile stays combined
(both trims get band bars); hints only when non-OK. "Warm idle detected" chip
appears when the context holds, so contextual bands are self-explanatory.
Unavailable signals keep "— · Not provided by ECU". Battery tile caption
names its active context ("charging · 13.8–14.5 V" / "resting · ≥ 12.5 V" /
neutral when RPM unavailable).

### 8.3 Health (new)

Top: verdict card (worst-of, §5.4 wording, colored edge). Grid of subsystem
cards: status dot + label, one-line reason (the worst contributor's text),
drift arrow (↗/↘) when a drift verdict is active, "Collecting baseline · N of
8 drives" when gated. Warnings feed below: unacknowledged first (colored,
"Acknowledge" action), then dimmed history. Footer when drift is globally
gated: "Drift forecasts unlock after 8 real drives across 2 weeks." **Demo
mode:** a toggle chip; ON ⇒ full-width amber ribbon "DEMO DATA — simulated
history" and every query switches to SIM-sourced rows. Not persisted —
defaults off each launch. With zero real sessions (the state the user is in
until hardware exists), the screen explains itself: what will appear, and
that Demo mode previews it.

### 8.4 DTCs (rewrite)

Stored/pending sections; each code an expandable card: header `P0171` +
`UrgencyChip`; title; source chip + confidence chip; expanded body sections
labeled "What's happening", "Can I keep driving?", "Check first" (numbered),
italic note. Collapsed = code + title + urgency + one-line meaning + chevron.
Empty states distinguished: "No DTC data received yet" (no report) vs "No
stored codes — nothing to worry about here" (clean report). Footer unchanged:
"Clearing codes is not available in this version." To prove layer 3 on
screen, `FakeTelemetrySource`'s fault scenario adds **pending `P1570`** (a
manufacturer-specific code in neither JSON) alongside stored `P0171` — the
DTC screen must render it family-decoded, and §5.4 routes it to OTHER.

### 8.5 Maintenance (new)

Odometer card: big estimated km (or "— Enter your odometer"), derivation
line, "Update odometer" → numeric dialog with the monotonic validation.
Items list sorted OVERDUE → DUE_SOON → NEVER_LOGGED → KM_UNTRACKED → OK
(ties: by name); each row: status dot, name, remaining text, last-done line,
note line. Tap → item detail: full service history, edit intervals,
enable/disable (builtin) or delete (custom), "Mark done" (date/km/note
dialog). "+ Log service" FAB → item picker → same dialog. "Add custom item"
in the screen's overflow. WIS caveat line at the bottom.

## 9. Testing & verification ladder

1. **JVM unit tests** (agents, `gradlew.bat testDebugUnitTest`): Welford
   aggregation vs golden values; recorder open/close/persist-gate with a fake
   clock (30 s gap, source switch, < 60 s discard); context accumulation
   (warm-idle/engine-state, unavailable-gating); hysteresis sequences (flicker
   suppressed, 3 s escalate, 10 s de-escalate); distance integration incl.
   gap guard; baseline median/MAD, eligibility, degenerate-MAD, activation
   gate; Theil–Sen goldens (clean drift, outlier session, irregular spacing,
   too-few points, > 26-week slope ⇒ silent); time-to-edge math; subsystem
   rollup (DTC forcing, worst-of, OTHER); warning dedupe/acknowledge;
   **quarantine tests** (SIM sessions never in baseline/drift/odometer/
   reminder queries — a dedicated suite, not incidental coverage);
   `DueCalculator` full state matrix incl. calendar-month edges;
   `OdometerEstimator` anchor+sessions, no-anchor, monotonic validation;
   `ReminderPolicy` (first notify, 7-day re-notify, cleared-on-service);
   DTC 3-layer precedence, decoder table test, both JSON schema tests;
   band-context selection incl. RPM-unavailable ⇒ NEUTRAL.
2. **Robolectric tests** (agents): each DAO against in-memory Room (CRUD,
   cascade deletes, dedupe upsert, seeding-on-create incl. builtin flags).
3. **Existing suites stay green** (agents): all Phase-4 unit tests still pass
   (`FrameCodec` goldens etc.), `assembleDebug` still builds. Firmware and
   pipeline are untouched — their suites are not in this phase's loop, but
   nothing may modify their inputs.
4. **Manual checkpoints on the S23 FE** (user, simulated sources only — no
   hardware): defined per milestone in the plan; the final one is the
   acceptance gate below.

## 10. Acceptance gate (bench form, no hardware)

- All new JVM + Robolectric suites green; all pre-existing Android tests
  green; `assembleDebug` produces an installable APK.
- On the phone, simulated sources only: four tabs navigate; Dashboard shows
  band bars + warm-idle chip, and the fault scenario drives LTFT amber/red
  with the breather-hose hint without color flicker; battery tile shows the
  context-aware caption.
- After ≥ 3 sim sessions (healthy + fault mix), Health in Demo mode shows
  populated subsystem cards, at least one warning in the feed, and the
  baseline-gate message with a live count; with Demo off it honestly shows
  the empty real-history state.
- Maintenance: seeded schedule lists; entering an odometer anchor activates
  km math; marking oil service done resets it; an item forced due (edited
  interval) produces a real Android notification via the debug "Run reminder
  check now" hook; service history shows the logged entry.
- DTCs: sim fault scenario's stored P0171 renders the full friendly card
  (urgency chip, W203-specific chip, check-first list); its pending P1570
  (§8.4) renders the family-decoded card — no bare codes anywhere.
- **Honest carry-forward:** everything real-data — drift on real drives,
  odometer estimate accuracy, reminder reliability with the app force-stopped
  on One UI, and all BLE behavior — validates only after the hardware/car
  phases. Stated, not hidden.

## 11. Risks (carried into the plan, not hidden)

- **Content volume** (≥ 50 curated + ~250 generic entries) is the largest
  authoring surface. Mitigation: schema tests enforce shape/counts; wording
  rules in §7.1 are normative; content tasks get the same two-review
  discipline as code; w203_reference.md stays the source of truth for
  W203-specific claims.
- **New dependencies** (Room/KSP, WorkManager, Robolectric) may interact with
  the Phase-4-pinned AGP/Kotlin versions. Same pattern as Phase 4: first task
  adds deps + a walking-skeleton test, pins exact versions, reports.
- **Room schema evolution:** Phase 5 (rides) will extend the DB;
  `exportSchema = true` from day one keeps future migrations honest.
- **One UI may throttle WorkManager** [Likely]; the debug trigger proves the
  notification path, the tab never lies, and the README documents the
  battery-exemption step (same doc Phase 4 started).
- **Drift/baseline math on sparse, irregular sim data** — gates (≥ 8
  sessions, ≥ 14 days) make verdicts refuse to fire prematurely; sim sessions
  can be generated with spoofed spacing only through the recorder's injected
  clock in tests, never in the shipped app.
- **Battery `engine_off` band upper bound (13.2 V)** is [Best estimate] and
  may mis-flag during surface-charge decay; it's one JSON line to tune, and
  hysteresis dampens transients.
- **No real data this phase, by definition.** The design keeps every real-
  data-dependent claim behind gates and Demo-mode labeling rather than
  pretending sim history is evidence.

---

## Self-review notes

- **Placeholder scan:** no TBD/TODO. Deliberately deferred-to-execution:
  exact dependency versions (mechanism: first-task pin-and-report, §11) and
  the final curated-code list (mechanism: plan fixes it; schema test enforces
  ≥ 50 + shape, §7.1). Both are stated decisions with owners, not omissions.
- **Internal consistency:** source enum spelled identically in §1/§2/§3;
  stats-are-raw vs UI-is-hysteresis stated in §3, §4, §5.5 consistently;
  metric context floors (30 samples) distinct from session persist gate (30
  snapshots) — both defined in §5.6; battery bands identical in §4/§5.1/§8.2;
  urgency enum identical in §7.1/§8.4; subsystem set identical in §5.4/§8.3.
  Fixed during this review: §5.4's DTC ranges had FUELING/EXHAUST_CAT
  overlapping on 0136–0141 (now disjoint), P0010–0017 mapped to no subsystem
  despite being curated (now IGNITION), the gauge-level→health-status mapping
  was implicit (now explicit: AMBER⇒WATCH, RED⇒ATTENTION, no-data⇒OK), and
  the layer-3 on-screen proof was vague (now pinned: fault scenario carries
  pending P1570).
- **Scope check:** one plan's worth — one DB, three pure engines, one content
  expansion, four screens; no firmware, no pipeline, no new transport. Large
  but Phase-4-shaped; the natural split seam (data+engines / content+UI) is
  available to the plan writer if task count demands it.
- **Ambiguity check:** "out of range" quantified (raw level vs hysteresis,
  §3–4); "healthy session" defined (§5.2); drift wording is a fixed template
  (§5.3); due-state precedence is an ordered list (§6.3); lookup precedence
  fixed (§7.3); Demo-mode reach fixed (§8.3: Health-screen queries only).
- **Ground-rule audit:** no self-labeling fault learning claimed (§5.2–5.3
  are envelope + trend only); Untested contract extended, not weakened (§3
  context gating, §8.2 unavailable states); no ECU writes introduced
  (clear-DTC still absent, §8.4); confidence tags on every unverified value
  (§4, §5.6, §6.2, §11); persistence-baseline gate not applicable — no
  forecaster ships here (drift reports slope, it does not forecast a series;
  the `kl trend` gate remains for Phase 6) — stated so the gate isn't
  silently skipped.
