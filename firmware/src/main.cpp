#include <Arduino.h>

#include <sys/time.h>

#include "ble_svc.h"
#include "latest_values.h"
#include "version.h"

#ifdef KL_DEMO_MODE
#include "demo_feed.h"
#else
#include "esp32_uart_transport.h"
#include "kline_kwp.h"
#include "littlefs_storage.h"
#include "pid_scheduler.h"
#include "ride_logger.h"
#endif

namespace {

BleSvc ble;
LatestValues latest;
uint32_t last_notify_ms = 0;
constexpr uint32_t kNotifyIntervalMs = 500;  // ~2 Hz, per docs/ble_protocol.md

#ifndef KL_DEMO_MODE
Esp32UartTransport transport(Serial2);
KlineKwp kwp(transport);
PidScheduler scheduler(kwp);
LittleFsStorage storage;
RideLogger logger(storage);

uint32_t last_tester_present_ms = 0;
uint32_t last_flush_ms = 0;
uint32_t last_setup_retry_ms = 0;
uint32_t last_reinit_attempt_ms = 0;
uint32_t last_dtc_read_ms = 0;
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
// [Best estimate] DTCs change rarely; one extra K-line request pair per
// minute is negligible inside the ~10 req/s budget (spec §3.6).
constexpr uint32_t kDtcReadIntervalMs = 60000;
bool ride_active = false;
DtcList stored_dtcs;
DtcList pending_dtcs;

// Reads stored+pending DTCs, pushes them to the BLE cache, and writes the
// startup-snapshot-lite header lines into the ride CSV (spec §3.6).
void read_and_publish_dtcs(bool write_csv_header) {
  const bool got_stored = kwp.read_stored_dtcs(&stored_dtcs);
  const bool got_pending = kwp.read_pending_dtcs(&pending_dtcs);
  if (!got_stored || !got_pending) {
    // Publish only when BOTH reads succeed in the same poll -- otherwise
    // ble.update_dtc() would combine one fresh list with a stale (or
    // falsely "confirmed empty") one, and the BLE wire format carries no
    // per-list freshness/timestamp to let the app tell the difference.
    // Skipping this cycle just means the next kDtcReadIntervalMs poll (or
    // the next try_start_ride() attempt) retries -- acceptable given DTCs
    // change rarely and this is a personal-project scope, not a protocol
    // change worth a wire-format redesign.
    return;
  }
  ble.update_dtc(stored_dtcs, pending_dtcs);
  if (!write_csv_header) {
    return;
  }
  char line[96];
  size_t o = snprintf(line, sizeof(line), "dtc_stored=");
  for (uint8_t i = 0; i < stored_dtcs.count && o + 7 < sizeof(line); ++i) {
    char code[6];
    dtc_code_to_string(stored_dtcs.codes[i], code);
    o += snprintf(line + o, sizeof(line) - o, "%s%s", i ? "," : "", code);
  }
  logger.write_header_line(line);
  o = snprintf(line, sizeof(line), "dtc_pending=");
  for (uint8_t i = 0; i < pending_dtcs.count && o + 7 < sizeof(line); ++i) {
    char code[6];
    dtc_code_to_string(pending_dtcs.codes[i], code);
    o += snprintf(line + o, sizeof(line) - o, "%s%s", i ? "," : "", code);
  }
  logger.write_header_line(line);
}

// Logical StartCommunication only (see kline_kwp.h) -- the physical
// fast-init/5-baud wake-pulse distinction is Phase 2/3 scope, once the
// L9637D exists. Shared by setup() and loop()'s idle-retry path so a
// failed boot-time attempt isn't a dead end.
bool try_start_ride() {
  if (!kwp.start_communication()) {
    return false;
  }
  Serial.println("StartCommunication OK");
  if (!logger.start_ride("/ride_phase4.csv", FW_VERSION, "logical-init")) {
    Serial.println("Failed to open ride file on LittleFS");
    return false;
  }
  Serial.println("Ride file opened, polling...");
  read_and_publish_dtcs(/*write_csv_header=*/true);  // startup-snapshot-lite
  return true;
}
#endif  // !KL_DEMO_MODE

void on_time_sync(uint64_t epoch_ms) {
  timeval tv;
  tv.tv_sec = static_cast<time_t>(epoch_ms / 1000);
  tv.tv_usec = static_cast<suseconds_t>((epoch_ms % 1000) * 1000);
  settimeofday(&tv, nullptr);
  Serial.print("time sync applied, epoch_s=");
  Serial.println(static_cast<unsigned long>(epoch_ms / 1000));
#ifndef KL_DEMO_MODE
  if (ride_active) {
    char marker[48];
    snprintf(marker, sizeof(marker), "time_sync=%lu:%llu",
             static_cast<unsigned long>(millis()),
             static_cast<unsigned long long>(epoch_ms));
    logger.write_header_line(marker);
  }
#endif
}

void notify_tick(uint32_t now) {
  if (now - last_notify_ms < kNotifyIntervalMs) {
    return;
  }
  last_notify_ms = now;
  ++latest.seq;
  latest.uptime_ms = now;
  ble.notify_telemetry(latest);
}

}  // namespace

#ifdef KL_DEMO_MODE

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.print("KompressorLink DEMO firmware boot, fw=");
  Serial.println(FW_VERSION);
  ble.begin(FW_VERSION, on_time_sync);
  DtcList stored, pending;
  demo_dtcs(&stored, &pending);
  ble.update_dtc(stored, pending);
}

void loop() {
  const uint32_t now = millis();
  demo_fill(now, &latest);
  notify_tick(now);
  delay(10);
}

#else  // real target: K-line polling + BLE together

void setup() {
  Serial.begin(115200);
  Serial2.begin(10400, SERIAL_8N1, /*RX=*/16, /*TX=*/17);
  delay(200);
  Serial.print("KompressorLink firmware boot, fw=");
  Serial.println(FW_VERSION);

  ble.begin(FW_VERSION, on_time_sync);

  ride_active = try_start_ride();
  if (!ride_active) {
    Serial.println("StartCommunication failed -- will keep retrying (check wiring / confirm kline_sim.py is running)");
  }
}

void loop() {
  const uint32_t now = millis();

  // kline_connected reflects live link state on every pass (spec §3.6).
  latest_values_set_flag(&latest, kFlagKlineConnected,
                         ride_active && !kwp.needs_reinit());
  notify_tick(now);

  if (!ride_active) {
    if (now - last_setup_retry_ms >= kSetupRetryIntervalMs) {
      last_setup_retry_ms = now;
      ride_active = try_start_ride();
    }
    delay(100);  // shorter than Phase 1's 1000 ms so BLE notify cadence holds while idle
    return;
  }

  PidScheduler::Reading reading;
  if (scheduler.tick(now, &reading)) {
    logger.log_reading(now, reading);
    latest_values_apply(&latest, reading.signal, reading.value, reading.available);
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

  if (now - last_dtc_read_ms >= kDtcReadIntervalMs) {
    last_dtc_read_ms = now;
    read_and_publish_dtcs(/*write_csv_header=*/false);
  }

  if (kwp.needs_reinit() && now - last_reinit_attempt_ms >= kReinitRetryIntervalMs) {
    last_reinit_attempt_ms = now;
    Serial.print("3 consecutive timeouts -- re-running StartCommunication: ");
    Serial.println(kwp.start_communication() ? "OK" : "failed");
  }
}

#endif  // KL_DEMO_MODE
