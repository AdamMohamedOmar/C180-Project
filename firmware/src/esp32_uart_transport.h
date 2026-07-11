#pragma once

#include <HardwareSerial.h>

#include "kline_transport.h"

// Real hardware: UART2 on GPIO17 (TX) / GPIO16 (RX) per hardware/wiring.md.
// Only compiled for the esp32dev env (uses Arduino/HardwareSerial) — never
// included from native tests.
class Esp32UartTransport : public KLineTransport {
 public:
  explicit Esp32UartTransport(HardwareSerial& serial);

  void write(const uint8_t* data, size_t len) override;
  size_t read(uint8_t* buf, size_t len, uint32_t timeout_ms) override;
  uint32_t now_ms() override;

 private:
  // Non-owning reference; must outlive this instance.
  HardwareSerial& serial_;
};
