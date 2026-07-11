# /// script
# requires-python = ">=3.11"
# dependencies = ["pyserial>=3.5"]
# ///
"""PC-side KWP2000 ECU simulator for KompressorLink firmware bench tests.

Bench topology (Phase 1, no L9637D yet): bare ESP32 UART2 (GPIO17/16) wired
directly to a USB-TTL adapter's RX/TX (crossed) + common GND. See
docs/superpowers/specs/2026-07-09-phase1-firmware-core-design.md.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "kl_sim"))

import serial  # noqa: E402

from protocol import (  # noqa: E402
    SID_PENDING_DTCS,
    SID_PENDING_DTCS_POSITIVE,
    SID_SHOW_CURRENT_DATA,
    SID_START_COMMUNICATION,
    SID_STORED_DTCS,
    SID_STORED_DTCS_POSITIVE,
    SID_TESTER_PRESENT,
    build_dtc_response,
    build_mode01_response,
    build_start_communication_response,
    build_tester_present_response,
    parse_frame,
)
from scenarios import SCENARIOS
from wire_format import ANSWERED_SIGNALS, WIRE_FORMAT_TABLE


def _read_frame(ser: serial.Serial) -> bytes | None:
    """Reads exactly one [FMT][TGT][SRC][DATA...][CS] frame, length-aware.

    `ser.read(64)` (a fixed-size read) would block for the port's full
    `timeout` trying to fill 64 bytes that a short ~7-byte frame will never
    provide -- pyserial's `timeout` bounds the whole read() call, not an
    inter-byte idle gap, so a naive fixed-size read makes every round trip
    take up to the full timeout instead of returning as soon as the frame
    actually arrives. It would also silently coalesce two frames queued
    back-to-back into one read(), which `parse_frame`'s exact-length check
    then rejects outright -- exactly the scenarios (`slow`/`unresponsive`)
    this tool exists to test. Reading the format byte first and deriving
    the real frame length from it (matching `protocol.parse_frame`'s own
    `fmt & 0x3F` convention) avoids both problems.
    """
    fmt_byte = ser.read(1)
    if not fmt_byte:
        return None  # nothing arrived within the port's read timeout
    length = fmt_byte[0] & 0x3F
    rest = ser.read(2 + length + 1)  # target + source + data + checksum
    if len(rest) != 2 + length + 1:
        return None  # timed out mid-frame
    return fmt_byte + rest


def run(port: str, baud: int, scenario_name: str) -> None:
    scenario = SCENARIOS[scenario_name]
    pid_to_signal = {wf.mode01_pid: name for name, wf in WIRE_FORMAT_TABLE.items()}
    request_count = 0

    with serial.Serial(port, baud, timeout=1.0) as ser:
        print(f"kline_sim: listening on {port} @ {baud} baud, scenario={scenario_name}")
        while True:
            frame = _read_frame(ser)
            if frame is None:
                continue
            parsed = parse_frame(frame)
            if parsed is None:
                print(f"  dropped frame (bad checksum/length): {frame.hex()}")
                continue
            _target, _source, data = parsed

            request_count += 1
            if request_count <= scenario.drop_first_n_requests:
                print(f"  dropping request #{request_count} (unresponsive scenario)")
                continue
            if scenario.response_delay_ms:
                time.sleep(scenario.response_delay_ms / 1000.0)

            sid = data[0]
            response = None
            if sid == SID_START_COMMUNICATION:
                response = build_start_communication_response()
            elif sid == SID_TESTER_PRESENT:
                response = build_tester_present_response()
            elif sid == SID_SHOW_CURRENT_DATA and len(data) >= 2:
                pid = data[1]
                signal = pid_to_signal.get(pid)
                if signal in ANSWERED_SIGNALS:
                    response = build_mode01_response(pid, scenario.idle_values[signal])
                # else: no response — simulates an unsupported PID, matching
                # PLAN.md §2's "expect them empty" for a 2004 K-line ECU.
            elif sid == SID_STORED_DTCS:
                response = build_dtc_response(SID_STORED_DTCS_POSITIVE, scenario.dtcs)
            elif sid == SID_PENDING_DTCS:
                response = build_dtc_response(SID_PENDING_DTCS_POSITIVE, [])

            if response is not None:
                ser.write(response)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=sorted(SCENARIOS), default="healthy", nargs="?")
    parser.add_argument("--port", required=True, help="e.g. COM5")
    parser.add_argument("--baud", type=int, default=10400, help="ISO 14230 standard K-line baud rate")
    args = parser.parse_args()
    try:
        run(args.port, args.baud, args.scenario)
    except serial.SerialException as exc:
        print(f"kline_sim: couldn't open {args.port}: {exc}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
