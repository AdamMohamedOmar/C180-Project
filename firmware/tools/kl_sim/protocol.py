from __future__ import annotations

from wire_format import WIRE_FORMAT_TABLE, encode_formula

TARGET_ADDRESS = 0x33  # [Likely] placeholder ECU address — confirm in Phase 3
TESTER_ADDRESS = 0xF1  # [Confirmed] standard ISO 14230 tester address

SID_START_COMMUNICATION = 0x81
SID_START_COMMUNICATION_POSITIVE = 0xC1
SID_TESTER_PRESENT = 0x3E
SID_TESTER_PRESENT_POSITIVE = 0x7E
SID_SHOW_CURRENT_DATA = 0x01
SID_SHOW_CURRENT_DATA_POSITIVE = 0x41
SID_SHOW_FREEZE_FRAME = 0x02
SID_SHOW_FREEZE_FRAME_POSITIVE = 0x42
SID_STORED_DTCS = 0x03
SID_STORED_DTCS_POSITIVE = 0x43
SID_PENDING_DTCS = 0x07
SID_PENDING_DTCS_POSITIVE = 0x47
SID_VEHICLE_INFO = 0x09
SID_VEHICLE_INFO_POSITIVE = 0x49

# [Best estimate] KWP2000 default timing (ms) — negotiated values from a
# real ECU's StartCommunication response supersede these; unused by the
# simulator itself, kept here so firmware and simulator agree on the
# defaults if either side needs them.
P2_MIN_MS = 25
P2_MAX_MS = 50
P3_MIN_MS = 55

_PID_TO_WIRE_FORMAT = {wf.mode01_pid: wf for wf in WIRE_FORMAT_TABLE.values()}


def checksum(data: bytes) -> int:
    return sum(data) & 0xFF


def build_frame(target: int, source: int, data: bytes) -> bytes:
    if len(data) > 63:
        raise ValueError("Phase 1 only supports payloads <= 63 bytes")
    fmt = 0x80 | len(data)
    body = bytes([fmt, target, source]) + data
    return body + bytes([checksum(body)])


def parse_frame(frame: bytes) -> tuple[int, int, bytes] | None:
    if len(frame) < 4:
        return None
    fmt, target, source = frame[0], frame[1], frame[2]
    length = fmt & 0x3F
    if len(frame) != 3 + length + 1:
        return None
    data = frame[3 : 3 + length]
    received_cs = frame[3 + length]
    if checksum(frame[: 3 + length]) != received_cs:
        return None
    return target, source, data


def build_start_communication_response() -> bytes:
    # [Best estimate] key bytes — not yet verified against a real SIM4LKE.
    return build_frame(TESTER_ADDRESS, TARGET_ADDRESS, bytes([SID_START_COMMUNICATION_POSITIVE, 0x8F, 0xE9]))


def build_tester_present_response() -> bytes:
    return build_frame(TESTER_ADDRESS, TARGET_ADDRESS, bytes([SID_TESTER_PRESENT_POSITIVE]))


def build_mode01_response(pid: int, value: float) -> bytes:
    wf = _PID_TO_WIRE_FORMAT.get(pid)
    if wf is None:
        raise ValueError(f"no WireFormat entry for Mode 01 PID {pid:#04x}")
    payload = bytes([SID_SHOW_CURRENT_DATA_POSITIVE, pid]) + encode_formula(wf.formula, value)
    return build_frame(TESTER_ADDRESS, TARGET_ADDRESS, payload)


def encode_dtc(code: str) -> bytes:
    # SAE J2012 2-byte DTC encoding. code like "P0171". First digit is
    # always 0-3 by construction (it shares byte1 with the 2-bit letter
    # field) -- validate rather than let an out-of-range digit silently
    # bleed into the letter bits.
    if len(code) != 5 or code[0] not in "PCBU" or not code[1:].isdigit():
        raise ValueError(f"malformed DTC code: {code!r}")
    letter_bits = {"P": 0b00, "C": 0b01, "B": 0b10, "U": 0b11}[code[0]]
    digits = [int(c) for c in code[1:]]
    if not (0 <= digits[0] <= 3):
        raise ValueError(f"DTC first digit must be 0-3: {code!r}")
    byte1 = (letter_bits << 6) | (digits[0] << 4) | digits[1]
    byte2 = (digits[2] << 4) | digits[3]
    return bytes([byte1, byte2])


def build_dtc_response(sid_positive: int, dtcs: list[str]) -> bytes:
    payload = bytes([sid_positive, len(dtcs)])
    for code in dtcs:
        payload += encode_dtc(code)
    return build_frame(TESTER_ADDRESS, TARGET_ADDRESS, payload)
