import pytest

from protocol import (
    TARGET_ADDRESS,
    TESTER_ADDRESS,
    build_frame,
    build_dtc_response,
    build_mode01_response,
    build_start_communication_response,
    build_tester_present_response,
    checksum,
    encode_dtc,
    parse_frame,
)


def test_checksum_matches_hand_computed_value():
    # FMT=0x82, TGT=0x33, SRC=0xF1, DATA=[0x01, 0x0C] -> sum & 0xFF
    assert checksum(bytes([0x82, 0x33, 0xF1, 0x01, 0x0C])) == (
        0x82 + 0x33 + 0xF1 + 0x01 + 0x0C
    ) & 0xFF


def test_build_frame_round_trips_through_parse_frame():
    frame = build_frame(TARGET_ADDRESS, TESTER_ADDRESS, bytes([0x41, 0x0C, 0x03, 0x20]))
    target, source, data = parse_frame(frame)
    assert target == TARGET_ADDRESS
    assert source == TESTER_ADDRESS
    assert data == bytes([0x41, 0x0C, 0x03, 0x20])


def test_parse_frame_rejects_bad_checksum():
    frame = bytearray(build_frame(TARGET_ADDRESS, TESTER_ADDRESS, bytes([0x01])))
    frame[-1] ^= 0xFF  # corrupt the checksum byte
    assert parse_frame(bytes(frame)) is None


def test_start_communication_response_uses_positive_sid():
    _, _, data = parse_frame(build_start_communication_response())
    assert data[0] == 0xC1


def test_tester_present_response_uses_positive_sid():
    _, _, data = parse_frame(build_tester_present_response())
    assert data[0] == 0x7E


def test_mode01_response_rpm_encodes_correctly():
    _, _, data = parse_frame(build_mode01_response(pid=0x0C, value=800.0))
    assert data == bytes([0x41, 0x0C, 0x0C, 0x80])  # 800*4=3200=0x0C80


def test_encode_dtc_p0171_matches_sae_j2012():
    assert encode_dtc("P0171") == bytes([0x01, 0x71])


def test_build_dtc_response_stored_dtcs():
    _, _, data = parse_frame(build_dtc_response(sid_positive=0x43, dtcs=["P0171"]))
    assert data == bytes([0x43, 0x01, 0x01, 0x71])


def test_encode_dtc_rejects_first_digit_above_3():
    # First digit shares byte1 with the 2-bit letter field -- a digit >3
    # would silently bleed into the letter bits if unvalidated.
    with pytest.raises(ValueError):
        encode_dtc("P4171")


def test_encode_dtc_rejects_wrong_length():
    with pytest.raises(ValueError):
        encode_dtc("P017")


def test_encode_dtc_rejects_unknown_letter():
    with pytest.raises(ValueError):
        encode_dtc("X0171")


def test_mode01_response_unknown_pid_raises_clear_error():
    with pytest.raises(ValueError, match="0x99"):
        build_mode01_response(pid=0x99, value=0.0)
