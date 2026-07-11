#include "esp32_uart_transport.h"

#include <Arduino.h>

Esp32UartTransport::Esp32UartTransport(HardwareSerial& serial) : serial_(serial) {}

void Esp32UartTransport::write(const uint8_t* data, size_t len) {
  serial_.write(data, len);
}

size_t Esp32UartTransport::read(uint8_t* buf, size_t len, uint32_t timeout_ms) {
  serial_.setTimeout(timeout_ms);
  return serial_.readBytes(buf, len);
}

uint32_t Esp32UartTransport::now_ms() {
  return millis();
}
