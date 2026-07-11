#include <Arduino.h>

#include "esp32_uart_transport.h"
#include "kline_kwp.h"
#include "littlefs_storage.h"
#include "pid_scheduler.h"
#include "ride_logger.h"
#include "version.h"

namespace {
Esp32UartTransport transport(Serial2);
KlineKwp kwp(transport);
PidScheduler scheduler(kwp);
LittleFsStorage storage;
RideLogger logger(storage);

uint32_t last_tester_present_ms = 0;
uint32_t last_flush_ms = 0;
uint32_t last_setup_retry_ms = 0;
uint32_t last_reinit_attempt_ms = 0;
constexpr uint32_t kTesterPresentIntervalMs = 2000;
constexpr uint32_t kFlushIntervalMs = 5000;
// How often to retry the whole StartCommunication+start_ride sequence
// while idle, and how often to retry a stuck link once active. Bench-test
// scoped: a human is expected to be watching Serial and can restart
// kline_sim.py or power-cycle if this drags on -- these intervals just
// mean the firmware itself keeps trying rather than going silent forever
// after one failed attempt at boot.
constexpr uint32_t kSetupRetryIntervalMs = 5000;
constexpr uint32_t kReinitRetryIntervalMs = 3000;
bool ride_active = false;

// Logical StartCommunication only (see kline_kwp.h) -- the physical
// fast-init/5-baud wake-pulse distinction is Phase 2/3 scope, once the
// L9637D exists. Shared by setup() and loop()'s idle-retry path so a
// failed boot-time attempt isn't a dead end -- see main.cpp's Task 10
// review notes on why silently giving up after one failed attempt was a
// real gap for the Phase 1 bench-test workflow.
bool try_start_ride() {
  if (!kwp.start_communication()) {
    return false;
  }
  Serial.println("StartCommunication OK");
  if (!logger.start_ride("/ride_phase1.csv", FW_VERSION, "logical-init")) {
    Serial.println("Failed to open ride file on LittleFS");
    return false;
  }
  Serial.println("Ride file opened, polling...");
  return true;
}
}  // namespace

void setup() {
  Serial.begin(115200);
  Serial2.begin(10400, SERIAL_8N1, /*RX=*/16, /*TX=*/17);
  delay(200);
  Serial.print("KompressorLink firmware boot, fw=");
  Serial.println(FW_VERSION);

  ride_active = try_start_ride();
  if (!ride_active) {
    Serial.println("StartCommunication failed -- will keep retrying (check wiring / confirm kline_sim.py is running)");
  }
}

void loop() {
  const uint32_t now = millis();

  if (!ride_active) {
    if (now - last_setup_retry_ms >= kSetupRetryIntervalMs) {
      last_setup_retry_ms = now;
      ride_active = try_start_ride();
    }
    delay(1000);
    return;
  }

  PidScheduler::Reading reading;
  if (scheduler.tick(now, &reading)) {
    logger.log_reading(now, reading);
    Serial.print(now);
    Serial.print(",");
    Serial.print(signal_name(reading.signal));
    Serial.print(",");
    if (reading.available) {
      Serial.println(reading.value, 3);
    } else {
      Serial.println("(unavailable)");
    }
  } else {
    // Nothing due this iteration -- yield instead of busy-spinning at
    // full CPU until the next signal comes due.
    delay(1);
  }

  if (now - last_tester_present_ms >= kTesterPresentIntervalMs) {
    kwp.send_tester_present();
    last_tester_present_ms = now;
  }

  if (now - last_flush_ms >= kFlushIntervalMs) {
    logger.flush();
    last_flush_ms = now;
  }

  if (kwp.needs_reinit() && now - last_reinit_attempt_ms >= kReinitRetryIntervalMs) {
    last_reinit_attempt_ms = now;
    Serial.print("3 consecutive timeouts -- re-running StartCommunication: ");
    Serial.println(kwp.start_communication() ? "OK" : "failed");
  }
}
