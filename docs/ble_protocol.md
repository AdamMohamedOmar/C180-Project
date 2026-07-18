# KompressorLink BLE Protocol v1

Normative contract between `firmware/src/telemetry_frame.h/.cpp` (packer)
and `android/.../telemetry/ble/FrameCodec.kt` (parser). Both mirror this
file verbatim — change here first, then both mirrors, then the golden-vector
tests on both sides (they embed the vectors below as constants).

All multi-byte fields are **little-endian**. Floats are IEEE 754 float32.

## GATT layout

Device name: `KompressorLink`. Advertises the service UUID below whenever
powered (no sleep gating until `power_mgr` exists). Standard Device
Information Service (0x180A) also served: Firmware Revision String (0x2A26)
= `FW_VERSION`, Manufacturer Name (0x2A29) = `KompressorLink`.

| UUID | Role | Properties |
|---|---|---|
| `c1800001-4b4c-4d27-b946-c180c0deba5e` | KompressorLink service | — |
| `c1800002-4b4c-4d27-b946-c180c0deba5e` | Telemetry | notify |
| `c1800003-4b4c-4d27-b946-c180c0deba5e` | DTC report | read + notify |
| `c1800004-4b4c-4d27-b946-c180c0deba5e` | Control | write (with response) |

No pairing/bonding in v1 (accepted risk: read-only telemetry, personal tool).

## Telemetry frame — 88 bytes, notified every 500 ms

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `protocol_version` = 0x01 |
| 1 | 1 | `flags`: bit0 demo_mode, bit1 kline_connected, bits 2–7 reserved 0 |
| 2 | 2 | `seq` — uint16, +1 per notification, wraps |
| 4 | 4 | `uptime_ms` — uint32, firmware millis(), wraps ~49.7 days |
| 8 | 4 | `avail_mask` — bit i = Signal enum ordinal i available; bits 19–31 = 0 |
| 12 | 76 | 19 × float32, Signal enum declaration order (see below) |

Signal order (mirror-verbatim of `firmware/src/pid_schema.h`'s enum — index
= mask bit = value slot): RPM(0), SPEED(1), MAF_GS(2), THROTTLE(3), LOAD(4),
STFT1(5), LTFT1(6), MAP(7), TIMING_ADV(8), O2_B1S1_V(9), O2_B1S2_V(10),
ECT(11), IAT(12), FUEL_STATUS(13), PEDAL_D(14), PEDAL_E(15),
CMD_THROTTLE(16), CTRL_MODULE_V(17), BATT_V_ADC(18).

A signal with mask bit clear is transmitted as 0.0f and MUST be ignored by
the receiver. Receivers MUST reject frames whose length ≠ 88 or whose
version byte ≠ 0x01 (surface a protocol-mismatch state, never mis-parse).
Requires ATT MTU ≥ 91; the client requests 517 after connecting and treats
a negotiated MTU < 91 as a failed connection (disconnect + retry). [Likely]
real hardware negotiates well above this floor without issue — the ≥ 91
check is a correctness guard, not an expected failure mode.

## DTC report frame — read anytime, notified on change

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `protocol_version` = 0x01 |
| 1 | 1 | `stored_count` N (≤ 8) |
| 2 | 1 | `pending_count` M (≤ 8) |
| 3 | 2·N | stored DTCs, 2 bytes each |
| 3+2·N | 2·M | pending DTCs, 2 bytes each |

Receivers MUST clamp or reject a `stored_count`/`pending_count` byte that
exceeds 8, or that would read past the frame's actual received length,
rather than trusting it and reading past the buffer (same never-mis-parse
posture as the Telemetry frame).

DTC 2-byte encoding is SAE J2012 [Confirmed], exactly what
`firmware/tools/kl_sim/protocol.py`'s `encode_dtc` produces: byte1 =
letter(2 bits: P=00 C=01 B=10 U=11) | first-digit(2 bits) | second-digit
(4 bits); byte2 = third-digit(4) | fourth-digit(4). The two DTC bytes keep
their J2012 order (byte1 first) — they are NOT little-endian swapped.

## Control frame — 9 bytes, written by the client

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `opcode`: 0x01 = TIME_SYNC. 0x02 = CLEAR_DTC reserved, UNIMPLEMENTED (deferred by design). 0x03 = START_WIFI_SYNC (epoch_ms ignored, send zeros) |
| 1 | 8 | `epoch_ms` — uint64 LE, Unix epoch milliseconds |

Firmware ignores unknown opcodes (logs to Serial); a write whose length ≠ 9
bytes MUST be treated the same way — logged to Serial and discarded, never
parsed. On TIME_SYNC it sets the system clock and, if a ride is active,
writes `#time_sync=<t_ms>:<epoch_ms>` into the ride CSV. On START_WIFI_SYNC
the firmware raises its WiFi SoftAP + HTTP sync server (see
`docs/wifi_sync_protocol.md`); it auto-drops the AP after 5 min idle.
Version byte is unchanged (this is an additive opcode, not a layout break).

## Golden test vectors (embed VERBATIM in both codebases' tests)

Each telemetry vector is given as one 24-hex-char header line + nineteen
8-hex-char value lines (one per signal, in enum order). The canonical
vector is the **concatenation of the lines in order** — 176 hex chars = 88
bytes. Tests embed the vector by concatenating these exact line strings
(C++ adjacent string literals / Kotlin `+`), one per line, so each line is
independently checkable against this table and there is no single
unwrappable 176-char string to mistype.

**T1 — telemetry, fully populated.** version=1, flags=0x02
(kline_connected), seq=0x1234, uptime_ms=100000, mask=0x0007FFFF (all 19),
values: RPM=750, SPEED=0, MAF_GS=4, THROTTLE=12.5, LOAD=25, STFT1=-2.5,
LTFT1=5, MAP=98, TIMING_ADV=10, O2_B1S1_V=0.5, O2_B1S2_V=0.75, ECT=90,
IAT=35, FUEL_STATUS=2, PEDAL_D=15, PEDAL_E=15, CMD_THROTTLE=12,
CTRL_MODULE_V=14, BATT_V_ADC=14.25 (all exactly representable in float32 —
deliberate, so equality asserts are exact):

```
01023412a0860100ffff0700   header: ver=01 flags=02 seq=3412 uptime=a0860100 mask=ffff0700
00803b44   RPM          = 750.0
00000000   SPEED        = 0.0
00008040   MAF_GS       = 4.0
00004841   THROTTLE     = 12.5
0000c841   LOAD         = 25.0
000020c0   STFT1        = -2.5
0000a040   LTFT1        = 5.0
0000c442   MAP          = 98.0
00002041   TIMING_ADV   = 10.0
0000003f   O2_B1S1_V    = 0.5
0000403f   O2_B1S2_V    = 0.75
0000b442   ECT          = 90.0
00000c42   IAT          = 35.0
00000040   FUEL_STATUS  = 2.0
00007041   PEDAL_D      = 15.0
00007041   PEDAL_E      = 15.0
00004041   CMD_THROTTLE = 12.0
00006041   CTRL_MODULE_V= 14.0
00006441   BATT_V_ADC   = 14.25
```

**T2 — telemetry, sparse.** version=1, flags=0x01 (demo_mode), seq=0xFFFF,
uptime_ms=0, mask=0x00000801 (only RPM bit 0 + ECT bit 11), RPM=800,
ECT=87, every other value slot zero:

```
0101ffff0000000001080000   header: ver=01 flags=01 seq=ffff uptime=00000000 mask=01080000
00004844   RPM          = 800.0
00000000   SPEED        (masked out)
00000000   MAF_GS       (masked out)
00000000   THROTTLE     (masked out)
00000000   LOAD         (masked out)
00000000   STFT1        (masked out)
00000000   LTFT1        (masked out)
00000000   MAP          (masked out)
00000000   TIMING_ADV   (masked out)
00000000   O2_B1S1_V    (masked out)
00000000   O2_B1S2_V    (masked out)
0000ae42   ECT          = 87.0
00000000   IAT          (masked out)
00000000   FUEL_STATUS  (masked out)
00000000   PEDAL_D      (masked out)
00000000   PEDAL_E      (masked out)
00000000   CMD_THROTTLE (masked out)
00000000   CTRL_MODULE_V(masked out)
00000000   BATT_V_ADC   (masked out)
```

**D1 — DTC report: stored=[P0171], pending=[]:** `0101000171`
**D2 — DTC report: both empty:** `010000`
**C1 — control TIME_SYNC, epoch_ms=0x0123456789ABCDEF:** `01efcdab8967452301`
**C2 — control START_WIFI_SYNC, epoch_ms=0xFEDCBA9876543210 (payload
present but ignored by the firmware for this opcode — this vector proves
parse_control decodes it correctly regardless):** `031032547698badcfe`

## Version policy

Breaking layout change ⇒ bump `protocol_version`, update this doc + both
mirrors + vectors. The app rejects unknown versions with a visible
protocol-mismatch state.

## Confidence

Frame layouts, UUIDs, encodings above: design-fixed here, [Confirmed] as
the contract by the golden tests. The DTC **count-byte framing on the
K-line side** (how the ECU reports DTCs to firmware) is handled
defensively: `kline_kwp` accepts both possible layouts, discriminated by
the response frame's data-length parity ([Best estimate]/[Likely] — see
the `read_stored_dtcs`/`read_pending_dtcs` contract comment in
`firmware/src/kline_kwp.h`, and spec §2.3). That uncertainty is isolated
in `kline_kwp` and does NOT affect this BLE frame, which is our own
format end-to-end.
