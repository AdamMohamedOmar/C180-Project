from wire_format import FormulaKind, WIRE_FORMAT_TABLE, encode_formula


def test_rpm_encodes_to_two_bytes():
    assert encode_formula(FormulaKind.TWO_BYTE_DIV4, 800.0) == bytes([0x0C, 0x80])


def test_ect_encodes_to_one_byte():
    assert encode_formula(FormulaKind.ONE_BYTE_MINUS40, 40.0) == bytes([0x50])


def test_stft_encodes_negative_trim():
    assert encode_formula(FormulaKind.ONE_BYTE_TRIM, -100.0) == bytes([0x00])


def test_table_has_18_entries():
    assert len(WIRE_FORMAT_TABLE) == 18


def test_rpm_pid_byte_matches_claude_md():
    assert WIRE_FORMAT_TABLE["RPM"].mode01_pid == 0x0C


def test_ctrl_module_v_encodes_per_sae_j1979_div1000():
    # PID 0x42 is (A*256+B)/1000 per SAE J1979, not /100 -- regression test
    # for a formula-table bug caught in code review.
    assert encode_formula(FormulaKind.TWO_BYTE_DIV1000, 14.2) == bytes([0x37, 0x78])


def test_ctrl_module_v_table_entry_uses_div1000_formula():
    assert WIRE_FORMAT_TABLE["CTRL_MODULE_V"].formula == FormulaKind.TWO_BYTE_DIV1000
