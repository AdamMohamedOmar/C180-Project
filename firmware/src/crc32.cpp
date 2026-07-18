#include "crc32.h"

void Crc32::update(const uint8_t* data, size_t len) {
  uint32_t c = state_;
  for (size_t i = 0; i < len; ++i) {
    c ^= data[i];
    for (int k = 0; k < 8; ++k) {
      c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u)));
    }
  }
  state_ = c;
}
