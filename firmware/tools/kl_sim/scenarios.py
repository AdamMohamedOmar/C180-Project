from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Scenario:
    name: str
    idle_values: dict[str, float]
    dtcs: list[str] = field(default_factory=list)
    response_delay_ms: float = 0.0
    drop_first_n_requests: int = 0


_HEALTHY_IDLE = {
    "RPM": 850.0,
    "SPEED": 0.0,
    "MAF_GS": 4.0,       # docs/w203_reference.md: warm idle 3-5 g/s
    "THROTTLE": 0.0,
    "LOAD": 20.0,
    "STFT1": 2.0,        # within +-10% healthy band
    "LTFT1": 3.0,
    "MAP": 100.0,
    "TIMING_ADV": 10.0,
    "O2_B1S1_V": 0.45,
    "O2_B1S2_V": 0.60,
    "ECT": 90.0,         # near thermostat opening (87 C)
    "IAT": 25.0,
    "FUEL_STATUS": 2.0,  # closed loop
}

SCENARIOS: dict[str, Scenario] = {
    "healthy": Scenario(name="healthy", idle_values=_HEALTHY_IDLE),
    "fault-injected": Scenario(
        name="fault-injected", idle_values=_HEALTHY_IDLE, dtcs=["P0171"]
    ),
    "slow": Scenario(name="slow", idle_values=_HEALTHY_IDLE, response_delay_ms=60.0),
    "unresponsive": Scenario(
        name="unresponsive", idle_values=_HEALTHY_IDLE, drop_first_n_requests=3
    ),
}
