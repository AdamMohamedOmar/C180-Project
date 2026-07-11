from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, auto


# Ordinal values are Python-internal only (auto() starts at 1, unlike the
# C++ mirror's 0-based enum class) — rides are logged by signal *name* in
# CSV (t_ms,signal,value; see PLAN.md §5.1), so no code should depend on
# these ordinals matching firmware/src/pid_schema.h's numeric values.
class Signal(Enum):
    RPM = auto()
    SPEED = auto()
    MAF_GS = auto()
    THROTTLE = auto()
    LOAD = auto()
    STFT1 = auto()
    LTFT1 = auto()
    MAP = auto()
    TIMING_ADV = auto()
    O2_B1S1_V = auto()
    O2_B1S2_V = auto()
    ECT = auto()
    IAT = auto()
    FUEL_STATUS = auto()
    PEDAL_D = auto()
    PEDAL_E = auto()
    CMD_THROTTLE = auto()
    CTRL_MODULE_V = auto()
    BATT_V_ADC = auto()


class Tier(Enum):
    F = auto()
    M = auto()
    S = auto()
    LOCAL = auto()
    LEGACY = auto()


@dataclass(frozen=True)
class SignalBounds:
    signal: Signal
    min: float
    max: float
    tier: Tier


SIGNAL_TABLE: dict[Signal, SignalBounds] = {
    Signal.RPM: SignalBounds(Signal.RPM, 0.0, 8000.0, Tier.F),
    Signal.SPEED: SignalBounds(Signal.SPEED, 0.0, 255.0, Tier.F),
    Signal.MAF_GS: SignalBounds(Signal.MAF_GS, 0.0, 400.0, Tier.F),
    Signal.THROTTLE: SignalBounds(Signal.THROTTLE, 0.0, 100.0, Tier.F),
    Signal.LOAD: SignalBounds(Signal.LOAD, 0.0, 100.0, Tier.M),
    Signal.STFT1: SignalBounds(Signal.STFT1, -100.0, 99.2, Tier.M),
    Signal.LTFT1: SignalBounds(Signal.LTFT1, -100.0, 99.2, Tier.M),
    Signal.MAP: SignalBounds(Signal.MAP, 0.0, 250.0, Tier.M),
    Signal.TIMING_ADV: SignalBounds(Signal.TIMING_ADV, -64.0, 63.5, Tier.M),
    Signal.O2_B1S1_V: SignalBounds(Signal.O2_B1S1_V, 0.0, 1.275, Tier.M),
    Signal.O2_B1S2_V: SignalBounds(Signal.O2_B1S2_V, 0.0, 1.275, Tier.M),
    Signal.ECT: SignalBounds(Signal.ECT, -40.0, 130.0, Tier.S),
    Signal.IAT: SignalBounds(Signal.IAT, -40.0, 80.0, Tier.S),
    Signal.FUEL_STATUS: SignalBounds(Signal.FUEL_STATUS, 1.0, 8.0, Tier.S),
    Signal.PEDAL_D: SignalBounds(Signal.PEDAL_D, 0.0, 100.0, Tier.LEGACY),
    Signal.PEDAL_E: SignalBounds(Signal.PEDAL_E, 0.0, 100.0, Tier.LEGACY),
    Signal.CMD_THROTTLE: SignalBounds(Signal.CMD_THROTTLE, 0.0, 100.0, Tier.LEGACY),
    Signal.CTRL_MODULE_V: SignalBounds(Signal.CTRL_MODULE_V, 0.0, 20.0, Tier.LEGACY),
    Signal.BATT_V_ADC: SignalBounds(Signal.BATT_V_ADC, 0.0, 18.0, Tier.LOCAL),
}


def in_bounds(signal: Signal, value: float) -> bool:
    bounds = SIGNAL_TABLE[signal]
    return bounds.min <= value <= bounds.max
