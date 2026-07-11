#include <unity.h>

#include "pid_wire_format.h"

// Unity fixture hooks -- the framework links against these even when unused.
void setUp(void) {}
void tearDown(void) {}

void test_rpm_decodes_correctly(void) {
  const uint8_t data[2] = {0x0C, 0x80};  // (12*256+128)/4 = 800.0 rpm
  TEST_ASSERT_EQUAL_FLOAT(800.0f, decode_formula(FormulaKind::TWO_BYTE_DIV4, data));
}

void test_ect_decodes_correctly(void) {
  const uint8_t data[1] = {0x50};  // 80 - 40 = 40.0 C
  TEST_ASSERT_EQUAL_FLOAT(40.0f, decode_formula(FormulaKind::ONE_BYTE_MINUS40, data));
}

void test_stft_decodes_negative_trim(void) {
  const uint8_t data[1] = {0x00};  // (0-128)*100/128 = -100.0 %
  TEST_ASSERT_EQUAL_FLOAT(-100.0f, decode_formula(FormulaKind::ONE_BYTE_TRIM, data));
}

void test_ctrl_module_v_decodes_per_sae_j1979_div1000(void) {
  // PID 0x42 is (A*256+B)/1000 per the SAE J1979 standard formula, not
  // /100 -- regression test for a formula-table bug caught in code review.
  const uint8_t data[2] = {0x37, 0x78};  // (0x37*256+0x78)/1000 = 14.2 V
  TEST_ASSERT_EQUAL_FLOAT(14.2f, decode_formula(FormulaKind::TWO_BYTE_DIV1000, data));
}

void test_find_wire_format_returns_ctrl_module_v_with_div1000_formula(void) {
  const WireFormat* wf = find_wire_format(Signal::CTRL_MODULE_V);
  TEST_ASSERT_NOT_NULL(wf);
  TEST_ASSERT_EQUAL_UINT8(0x42, wf->mode01_pid);
  TEST_ASSERT_TRUE(FormulaKind::TWO_BYTE_DIV1000 == wf->formula);
}

void test_find_wire_format_returns_rpm_entry(void) {
  const WireFormat* wf = find_wire_format(Signal::RPM);
  TEST_ASSERT_NOT_NULL(wf);
  TEST_ASSERT_EQUAL_UINT8(0x0C, wf->mode01_pid);
  TEST_ASSERT_EQUAL_UINT8(2, wf->byte_count);
}

void test_find_wire_format_returns_null_for_batt_v_adc(void) {
  TEST_ASSERT_NULL(find_wire_format(Signal::BATT_V_ADC));
}

void test_wire_format_table_has_18_entries(void) {
  TEST_ASSERT_EQUAL(18, kWireFormatCount);
}

int main(int argc, char** argv) {
  UNITY_BEGIN();
  RUN_TEST(test_rpm_decodes_correctly);
  RUN_TEST(test_ect_decodes_correctly);
  RUN_TEST(test_stft_decodes_negative_trim);
  RUN_TEST(test_ctrl_module_v_decodes_per_sae_j1979_div1000);
  RUN_TEST(test_find_wire_format_returns_ctrl_module_v_with_div1000_formula);
  RUN_TEST(test_find_wire_format_returns_rpm_entry);
  RUN_TEST(test_find_wire_format_returns_null_for_batt_v_adc);
  RUN_TEST(test_wire_format_table_has_18_entries);
  return UNITY_END();
}
