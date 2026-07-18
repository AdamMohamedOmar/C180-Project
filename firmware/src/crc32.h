#pragma once

#include <cstddef>
#include <cstdint>

// CRC-32/ISO-HDLC (zlib polynomial 0xEDB88320, reflected, init 0xFFFFFFFF,
// final XOR 0xFFFFFFFF). Deliberately identical to Kotlin's
// java.util.zip.CRC32 so the Android sync client verifies downloads without
// a custom implementation. value() does not consume state — update() may
// continue afterwards.
class Crc32 {
 public:
  void update(const uint8_t* data, size_t len);
  uint32_t value() const { return state_ ^ 0xFFFFFFFFu; }

 private:
  uint32_t state_ = 0xFFFFFFFFu;
};
