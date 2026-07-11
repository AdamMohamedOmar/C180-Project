from scenarios import SCENARIOS
from wire_format import ANSWERED_SIGNALS


def test_idle_values_cover_every_answered_signal():
    # kline_sim.py does scenario.idle_values[signal] for every signal in
    # ANSWERED_SIGNALS with no default -- a future edit to either set that
    # drops this invariant would only surface as a live-bench KeyError.
    for scenario in SCENARIOS.values():
        assert ANSWERED_SIGNALS <= set(scenario.idle_values)


def test_healthy_scenario_has_no_dtcs():
    assert SCENARIOS["healthy"].dtcs == []


def test_fault_injected_scenario_has_p0171():
    assert SCENARIOS["fault-injected"].dtcs == ["P0171"]


def test_healthy_scenario_idle_values_within_w203_reference_bands():
    values = SCENARIOS["healthy"].idle_values
    assert 3.0 <= values["MAF_GS"] <= 5.0  # docs/w203_reference.md healthy band
    assert -10.0 <= values["STFT1"] <= 10.0
    assert -10.0 <= values["LTFT1"] <= 10.0


def test_all_four_scenarios_exist():
    assert set(SCENARIOS) == {"healthy", "fault-injected", "slow", "unresponsive"}
