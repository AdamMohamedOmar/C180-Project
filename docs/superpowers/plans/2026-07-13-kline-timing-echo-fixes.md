# K-line Echo/Timing Fixes + Dashboard Allocation Cleanup — Implementation Plan

> **For agentic workers:** Implement task-by-task, in order. Steps use checkbox
> (`- [ ]`) syntax for tracking. Follow CLAUDE.md workflow rules: emit **full
> replacement files** for every file you change, and **never run `git commit`**
> — leave a clean, reviewable working tree. The user runs `pio test -e native`
> locally; your job is that all listed tests exist and are expected to pass.

**Goal:** Make `KlineKwp` survive real half-duplex K-line physics (self-echo,
inter-byte gaps, no fixed-size responses) instead of only the Phase 1
full-duplex bench link, and remove a redundant per-emission list copy in the
Android dashboard — without breaking the 42-test native suite or the
`FakeTransport` contract.

**Origin:** A four-item external bug report, validated against the repo on
2026-07-13. Validation verdicts (read these before implementing — two of the
four reported fixes must NOT be applied as originally directed):

| # | Report claim | Verdict | Action |
|---|---|---|---|
| 1 | Echo classified as comm failure in `KlineKwp::send_request_and_get_response` | **CONFIRMED** (`kline_kwp.cpp:32-39`) | Task 1 |
| 2 | Single coarse timeout / `readBytes(32)` violates KWP2000 inter-byte timing | **CONFIRMED in substance**, mechanism corrected below | Task 1 |
| 3 | `PidScheduler::tick` timestamp-before-read causes cumulative drift | **REJECTED — not a bug**; the proposed fix is a numeric no-op | Task 3 (comment only) |
| 4 | `histories.getValue(signal).toList()` per combine = GC pressure | **Valid observation, severity LOW**, proposed fix is **harmful** — do the safe variant instead | Task 4 |

## Validation detail (why the plan is shaped this way)

**Bug 1 — confirmed.** `kline_kwp.cpp:32-39` detects a mis-addressed frame
(the code comment itself names self-echo as the expected real-hardware case)
but then increments `consecutive_timeouts_` and returns `false`. On real
half-duplex K-line every request echoes, so every exchange fails, `needs_reinit()`
trips after 3, and the device livelocks in reinit. Worse: the genuine ECU
response arrives *after* the early return and sits in the UART RX buffer,
desyncing the next exchange.

**Bug 2 — confirmed, but the mechanism in the report is wrong and matters for
the fix.** `Esp32UartTransport::read` calls `serial_.readBytes(buf, 32)` with a
100 ms timeout. Every real KWP frame is shorter than 32 bytes, so the call
**never fills the buffer and always blocks the full 100 ms even on a fast,
complete response**. That alone caps the whole system at <10 req/s (the
schedule's ideal demand is ~11.9 req/s), independent of any ECU slowness. It is
also not frame-aware: once echo exists (bug 1), one `read()` can return
echo+response concatenated, or a frame split across calls — both unparseable.
The inter-byte (ISO 14230 P1) compliance point stands. **Fix location differs
from the report's directive:** the framed byte-by-byte reader goes in
`KlineKwp` (portable, exercised by the native test suite via `FakeTransport`),
NOT in `Esp32UartTransport` — putting frame knowledge in the ESP32-only class
would leave it untested in the native env and duplicate frame-format knowledge.
The `KLineTransport` interface does not change.

**Bug 3 — rejected.** `next_due_ms_[i] = now_ms + interval` uses the
tick-start timestamp, so the blocking `read_pid` duration never enters the
deadline math — that is precisely the drift-free anchoring the report asks
for. The report's directive ("move the same assignment, same `now_ms`, after
`read_pid`") computes the identical value and changes nothing. The real
phenomenon behind the symptom — tiers slipping under overload because demand
(~11.9 req/s) exceeds K-line supply — is (a) intentional EDF back-pressure,
already documented in the function, and (b) mostly caused by bug 2's 100 ms
dead tail, which Task 1 removes. No logic change; add a comment so this isn't
re-reported.

**Bug 4 — downgraded to LOW; original directive must not be applied.** The
allocation is real (10 signals × ~2 Hz × ≤120 boxed Floats ≈ tens of KB/s) but
trivial for Android's GC — not "HIGH". The report's fix (put the live
`ArrayDeque` into `GaugeUiState`) introduces genuine bugs: the deque is mutated
in place, so (a) the reference in UI state never changes and Compose/StateFlow
equality can stop sparkline updates, and (b) composition would read a
collection while `onEach` mutates it — `ConcurrentModificationException` risk.
The safe optimization: snapshot each history to an immutable list **once per
actual mutation** (inside `onEach`), and have `combine` reuse the stored
snapshots — no copies on connectionState-only recombines, stable references
for unchanged histories, no mutable state in UI.

**Tech stack:** C++17 firmware (PlatformIO native + esp32dev), Unity native
tests under `firmware/test/test_<module>/`, Kotlin + Compose for Task 4.

---

## Task 1: Framed, echo-tolerant response reader in `KlineKwp`

**Files:**
- Modify: `firmware/src/kline_kwp.h`
- Modify: `firmware/src/kline_kwp.cpp`
- `firmware/src/kline_transport.h`, `firmware/src/esp32_uart_transport.*`: **unchanged**

- [ ] **Step 1: Timing constants in `kline_kwp.h`**

Keep `kResponseTimeoutMs = 100` but re-document it as the P2-style overall
budget: deadline from end-of-request to *completion of the response frame*
(covers echo + ECU think time + frame transmission at 10.4 kbps; a max frame
is ~26 bytes ≈ 25 ms on the wire). Add:

```cpp
// [Best estimate] max gap between consecutive bytes WITHIN one frame.
// ISO 14230-2 default P1 max is 20 ms; +5 ms margin for USB-TTL adapter
// chunking on the Phase 1 bench link. A gap longer than this means the
// frame died mid-transmission. Real P1 is settled at Phase 3 with the car.
inline constexpr uint32_t kInterByteTimeoutMs = 25;
```

- [ ] **Step 2: Private framed reader `read_frame` in `kline_kwp.cpp`**

Add `bool read_frame(ParsedFrame* out, uint32_t deadline_budget_ms);`
(private, declared in the header). Algorithm:

1. `start = transport_.now_ms()`.
2. Loop (each iteration attempts to receive ONE complete frame):
   a. `elapsed = transport_.now_ms() - start`; if `elapsed >= deadline_budget_ms`, return false.
   b. Read 1 byte (FMT) with timeout `deadline_budget_ms - elapsed`. 0 bytes ⇒ return false. (The `KLineTransport` contract says `read` blocks until the timeout elapses, so a 0-byte result IS the deadline expiring — no spin. This also keeps `FakeTransport`, whose clock never advances, loop-safe: its immediate 0-byte return is terminal.)
   c. `data_len = fmt & 0x3F`. If `data_len == 0` (KWP escape-length form, unsupported here), return false.
   d. Read the remaining `2 + data_len + 1` bytes (TGT, SRC, DATA, CS) **one at a time**, each with timeout `min(kInterByteTimeoutMs, remaining_deadline)`; any 0-byte read ⇒ incomplete frame ⇒ return false.
   e. Assemble into a local buffer and run `kline_parse_frame`. Parse/checksum failure ⇒ return false (a corrupt frame means possible desync; don't keep reading garbage this exchange — the caller's timeout counter and, eventually, `needs_reinit()` handle persistent corruption).
   f. If `out->target != kTesterAddress || out->source != kTargetAddress`: this is our own echo (or foreign bus traffic) — **discard silently and `continue` the loop** (back to 2a). Do NOT touch `consecutive_timeouts_`.
   g. Correctly addressed, checksum-valid frame ⇒ return true.

- [ ] **Step 3: Rewire `send_request_and_get_response`**

Replace the single `transport_.read(response, 32, kResponseTimeoutMs)` +
parse + address-check block (lines 26-39) with one call to
`read_frame(out, kResponseTimeoutMs)`; on false, `++consecutive_timeouts_`
and return false. The `frame_len == 0` builder-bug branch and the
`consecutive_timeouts_ = 0` reset on success stay exactly as they are.
Deliberately NO pre-write RX drain: `FakeTransport` tests pre-queue responses
before the request is written (a drain would eat them), and the discard loop +
content checks in `read_pid`/`read_dtcs` already self-recover from a stale
frame within one poll cycle — note this in a comment.

- [ ] **Step 4: Preserve every existing `test_kline_kwp` / `test_kwp_dtc` behavior**

`FakeTransport::read` already serves 1-byte reads from its queue, so existing
tests that queue exactly one well-formed response keep passing unmodified. If
any existing test queues a mis-addressed frame and asserts failure-with-
timeout-increment, it still passes (queue exhausts ⇒ deadline path ⇒ increment).

## Task 2: Native tests for echo tolerance and framing

**Files:**
- Modify: `firmware/test/test_kline_kwp/test_kline_kwp.cpp` (add cases)

- [ ] Echo-then-response: queue `[echo frame (TGT=0x33,SRC=0xF1)] + [real response (TGT=0xF1,SRC=0x33)]` concatenated in one `queue_response`; assert `read_pid` succeeds, value decodes, `consecutive_timeouts() == 0`.
- [ ] Echo-only (ECU never answers): queue only the echo; assert `read_pid` fails and `consecutive_timeouts() == 1`.
- [ ] Two junk frames then response: queue two mis-addressed frames + real response; assert success (loop discards more than one).
- [ ] Truncated frame (header promises `data_len` bytes, queue ends early): assert failure + timeout increment (inter-byte timeout path).
- [ ] Corrupt checksum on a correctly-sized frame: assert failure + timeout increment.
- [ ] `fmt & 0x3F == 0`: assert failure (escape-length rejected).

## Task 3: Scheduler — documentation comment only (no logic change)

**Files:**
- Modify: `firmware/src/pid_scheduler.cpp`

- [ ] Above `next_due_ms_[due_index] = now_ms + tier_interval_ms(tier);` add a
short comment: the deadline is anchored to the tick-start timestamp, so the
duration of the blocking `read_pid` below does not accumulate into future
deadlines (drift-free); under overload a late-served signal's period stretches
from its serve time, which is intentional back-pressure given the K-line
budget — do not "fix" by moving this line after the read (same value) or by
`+= interval` anchoring (unbounded backlog under overload).

## Task 4: Dashboard history snapshots (LOW priority, safe variant only)

**Files:**
- Modify: `android/app/src/main/java/com/kompressorlink/app/dashboard/DashboardViewModel.kt`

- [ ] Add `private val historySnapshots = mutableMapOf<Signal, List<Float>>()`.
In the existing `onEach` block — the single place histories mutate, once per
genuine telemetry emission — after appending a sample to a signal's deque, set
`historySnapshots[signal] = history.toList()`. In the `combine` lambda, replace
`histories.getValue(signal).toList()` with
`historySnapshots[signal] ?: emptyList()`.
- [ ] Do NOT put the `ArrayDeque` (or any mutable collection) into
`GaugeUiState` — see validation detail above (Compose equality/skip breakage +
concurrent mutation). `GaugeUiState.history: List<Float>` and the `Sparkline`
composable stay as they are.
- [ ] Behavior notes to preserve: signals with no new sample keep their previous
snapshot (reference-stable ⇒ cheap structural equality); connectionState-only
recombines now allocate zero lists; the existing onEach-vs-combine
double-append guarantee (documented in the file's comment) is untouched.

## Acceptance

- `pio test -e native` passes: all pre-existing suites plus Task 2's new cases.
- `pio run -e esp32dev` compiles (transport interface unchanged, so this is
  low-risk; verify anyway).
- Android module compiles; dashboard behavior unchanged except allocation
  pattern (`./gradlew :app:compileDebugKotlin` or the user's local build).
- Working tree clean and reviewable; **no `git commit` executed by the agent.**
