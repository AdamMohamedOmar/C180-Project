#include "dtc_list.h"

void dtc_code_to_string(uint16_t code, char out[6]) {
  static const char kLetters[4] = {'P', 'C', 'B', 'U'};
  static const char kHex[] = "0123456789ABCDEF";
  out[0] = kLetters[(code >> 14) & 0x3];
  out[1] = kHex[(code >> 12) & 0x3];
  out[2] = kHex[(code >> 8) & 0xF];
  out[3] = kHex[(code >> 4) & 0xF];
  out[4] = kHex[code & 0xF];
  out[5] = '\0';
}
