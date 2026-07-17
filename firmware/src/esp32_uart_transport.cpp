#include "esp32_uart_transport.h"

#include <Arduino.h>

Esp32UartTransport::Esp32UartTransport(HardwareSerial& serial) : serial_(serial) {}

void Esp32UartTransport::write(const uint8_t* data, size_t len) {
  // Strict request/response protocol: any byte still sitting in RX when a
  // new request starts is stale by definition -- a late or partial response
  // to an exchange kline_kwp already gave up on. Drain it so it can't be
  // mistaken for the reply to THIS request. kline_kwp's frame scan skips
  // echoes and misaddressed frames, but a stale well-addressed frame would
  // look genuine.
  while (serial_.available() > 0) {
    serial_.read();
  }
  serial_.write(data, len);
}

size_t Esp32UartTransport::read(uint8_t* buf, size_t len, uint32_t timeout_ms) {
  serial_.setTimeout(timeout_ms);
  return serial_.readBytes(buf, len);
}

uint32_t Esp32UartTransport::now_ms() {
  return millis();
}
