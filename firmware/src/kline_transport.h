#pragma once

#include <cstddef>
#include <cstdint>

// Abstracts the K-line byte stream so protocol logic (kline_kwp) compiles
// and tests identically under PlatformIO's native env (no hardware) and the
// esp32dev env (real UART2). See docs/superpowers/specs/2026-07-09-phase1-
// firmware-core-design.md §1.
class KLineTransport {
 public:
  virtual ~KLineTransport() = default;

  virtual void write(const uint8_t* data, size_t len) = 0;

  // Reads up to `len` bytes, blocking until `timeout_ms` elapses. Returns
  // the number of bytes actually read (may be less than `len` on timeout).
  virtual size_t read(uint8_t* buf, size_t len, uint32_t timeout_ms) = 0;

  virtual uint32_t now_ms() = 0;
};
