from kl.schema import SIGNAL_TABLE, Signal, in_bounds


def test_rpm_within_bounds():
    assert in_bounds(Signal.RPM, 850.0) is True


def test_rpm_at_min_boundary_is_in_bounds():
    assert in_bounds(Signal.RPM, 0.0) is True


def test_rpm_at_max_boundary_is_in_bounds():
    assert in_bounds(Signal.RPM, 8000.0) is True


def test_rpm_rejects_above_max():
    assert in_bounds(Signal.RPM, 9000.0) is False


def test_ect_rejects_below_min():
    assert in_bounds(Signal.ECT, -50.0) is False


def test_signal_table_covers_all_signals():
    assert len(Signal) == 19
    assert set(SIGNAL_TABLE) == set(Signal)


def test_signal_table_entries_are_internally_consistent():
    for signal, bounds in SIGNAL_TABLE.items():
        assert bounds.signal == signal
        assert bounds.min <= bounds.max
