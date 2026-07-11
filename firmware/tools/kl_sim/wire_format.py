from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, auto


class FormulaKind(Enum):
    TWO_BYTE_DIV4 = auto()          # RPM
    ONE_BYTE_RAW = auto()           # SPEED, MAP
    TWO_BYTE_DIV100 = auto()        # MAF_GS
    ONE_BYTE_PCT255 = auto()        # THROTTLE, LOAD
    ONE_BYTE_TRIM = auto()          # STFT1, LTFT1
    ONE_BYTE_HALF_MINUS64 = auto()  # TIMING_ADV
    TWO_BYTE_VOLT_DIV200 = auto()   # O2_B1S1_V, O2_B1S2_V
    ONE_BYTE_MINUS40 = auto()       # ECT, IAT
    ONE_BYTE_RAW_ENUM = auto()      # FUEL_STATUS (simplified, bank 1 only)
    TWO_BYTE_DIV1000 = auto()       # CTRL_MODULE_V


@dataclass(frozen=True)
class WireFormat:
    signal: str
    mode01_pid: int
    formula: FormulaKind
    byte_count: int


WIRE_FORMAT_TABLE: dict[str, WireFormat] = {
    "RPM": WireFormat("RPM", 0x0C, FormulaKind.TWO_BYTE_DIV4, 2),
    "SPEED": WireFormat("SPEED", 0x0D, FormulaKind.ONE_BYTE_RAW, 1),
    "MAF_GS": WireFormat("MAF_GS", 0x10, FormulaKind.TWO_BYTE_DIV100, 2),
    "THROTTLE": WireFormat("THROTTLE", 0x11, FormulaKind.ONE_BYTE_PCT255, 1),
    "LOAD": WireFormat("LOAD", 0x04, FormulaKind.ONE_BYTE_PCT255, 1),
    "STFT1": WireFormat("STFT1", 0x06, FormulaKind.ONE_BYTE_TRIM, 1),
    "LTFT1": WireFormat("LTFT1", 0x07, FormulaKind.ONE_BYTE_TRIM, 1),
    "MAP": WireFormat("MAP", 0x0B, FormulaKind.ONE_BYTE_RAW, 1),
    "TIMING_ADV": WireFormat("TIMING_ADV", 0x0E, FormulaKind.ONE_BYTE_HALF_MINUS64, 1),
    "O2_B1S1_V": WireFormat("O2_B1S1_V", 0x14, FormulaKind.TWO_BYTE_VOLT_DIV200, 2),
    "O2_B1S2_V": WireFormat("O2_B1S2_V", 0x15, FormulaKind.TWO_BYTE_VOLT_DIV200, 2),
    "ECT": WireFormat("ECT", 0x05, FormulaKind.ONE_BYTE_MINUS40, 1),
    "IAT": WireFormat("IAT", 0x0F, FormulaKind.ONE_BYTE_MINUS40, 1),
    "FUEL_STATUS": WireFormat("FUEL_STATUS", 0x03, FormulaKind.ONE_BYTE_RAW_ENUM, 1),
    "PEDAL_D": WireFormat("PEDAL_D", 0x49, FormulaKind.ONE_BYTE_PCT255, 1),
    "PEDAL_E": WireFormat("PEDAL_E", 0x4A, FormulaKind.ONE_BYTE_PCT255, 1),
    "CMD_THROTTLE": WireFormat("CMD_THROTTLE", 0x4C, FormulaKind.ONE_BYTE_PCT255, 1),
    "CTRL_MODULE_V": WireFormat("CTRL_MODULE_V", 0x42, FormulaKind.TWO_BYTE_DIV1000, 2),
}

# Signals kline_sim.py answers by default (the "healthy" scenario). The 4
# PEDAL_D/PEDAL_E/CMD_THROTTLE/CTRL_MODULE_V entries stay in the table above
# (so a request for them can still be recognized/logged) but are excluded
# here — omitting them from this set is what makes the simulator correctly
# NOT respond, per PLAN.md §2's "expect them empty" on a 2004 K-line ECU.
ANSWERED_SIGNALS = {
    "RPM", "SPEED", "MAF_GS", "THROTTLE", "LOAD", "STFT1", "LTFT1", "MAP",
    "TIMING_ADV", "O2_B1S1_V", "O2_B1S2_V", "ECT", "IAT", "FUEL_STATUS",
}


def encode_formula(formula: FormulaKind, value: float) -> bytes:
    if formula is FormulaKind.TWO_BYTE_DIV4:
        raw = round(value * 4)
        return bytes([(raw >> 8) & 0xFF, raw & 0xFF])
    if formula is FormulaKind.ONE_BYTE_RAW:
        return bytes([round(value) & 0xFF])
    if formula is FormulaKind.TWO_BYTE_DIV100:
        raw = round(value * 100)
        return bytes([(raw >> 8) & 0xFF, raw & 0xFF])
    if formula is FormulaKind.ONE_BYTE_PCT255:
        return bytes([round(value * 255 / 100) & 0xFF])
    if formula is FormulaKind.ONE_BYTE_TRIM:
        return bytes([round(value * 128 / 100 + 128) & 0xFF])
    if formula is FormulaKind.ONE_BYTE_HALF_MINUS64:
        return bytes([round((value + 64) * 2) & 0xFF])
    if formula is FormulaKind.TWO_BYTE_VOLT_DIV200:
        return bytes([round(value * 200) & 0xFF, 0xFF])
    if formula is FormulaKind.ONE_BYTE_MINUS40:
        return bytes([round(value + 40) & 0xFF])
    if formula is FormulaKind.ONE_BYTE_RAW_ENUM:
        return bytes([round(value) & 0xFF])
    if formula is FormulaKind.TWO_BYTE_DIV1000:
        raw = round(value * 1000)
        return bytes([(raw >> 8) & 0xFF, raw & 0xFF])
    raise ValueError(f"unhandled formula kind: {formula}")
