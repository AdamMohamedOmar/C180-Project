#include <Arduino.h>

#include <sys/time.h>

#include <atomic>

#include "ble_svc.h"
#include "latest_values.h"
#include "littlefs_ride_file_store.h"
#include "version.h"
#include "wifi_sync.h"

#ifdef KL_DEMO_MODE
#include "demo_feed.h"
#else
#include "capture_pipeline.h"
#include "esp32_uart_transport.h"
#include "freertos_capture_queue.h"
#include "kline_kwp.h"
#include "littlefs_storage.h"
#include "pid_scheduler.h"
#include "ride_logger.h"
#include "storage_drain.h"
#endif

namespace {

BleSvc ble;
LatestValues latest;
// loop() packs/notifies while the capture task (or demo_fill) updates --
// copy under a spinlock; both sides hold it only for a struct copy/update.
portMUX_TYPE latest_mux = portMUX_INITIALIZER_UNLOCKED;
uint32_t last_notify_ms = 0;
constexpr uint32_t kNotifyIntervalMs = 500;  // ~2 Hz, per docs/ble_protocol.md

LittleFsRideFileStore file_store;
WifiSync wifi_sync;
// Set from the NimBLE host task (control opcode 0x03); consumed by loop().
std::atomic<bool> wifi_sync_requested{false};

void on_wifi_sync_request() { wifi_sync_requested = true; }

#ifndef KL_DEMO_MODE
FreeRtosCaptureQueue capture_queue;
CapturePipeline pipeline(capture_queue);
std::atomic<bool> kline_up{false};

constexpr uint32_t kTesterPresentIntervalMs = 2000;
// Bench-test-scoped retry cadence (see Phase 4 notes): the firmware keeps
// trying rather than going silent after a failed boot-time attempt.
constexpr uint32_t kSetupRetryIntervalMs = 5000;
constexpr uint32_t kReinitRetryIntervalMs = 3000;
// [Best estimate] DTCs change rarely; one request pair per minute is
// negligible inside the ~10 req/s budget (spec §3.6).
constexpr uint32_t kDtcReadIntervalMs = 60000;

// Reads stored+pending DTCs, pushes them to the BLE cache, and (optionally)
// queues the startup-snapshot-lite header lines. Publish only when BOTH
// reads succeed in the same poll -- the BLE wire format carries no per-list
// freshness marker (Phase 4 decision, unchanged).
void read_and_publish_dtcs(KlineKwp& kwp, bool write_csv_header) {
  DtcList stored, pending;
  const bool got_stored = kwp.read_stored_dtcs(&stored);
  const bool got_pending = kwp.read_pending_dtcs(&pending);
  if (!got_stored || !got_pending) return;
  ble.update_dtc(stored, pending);  // [Likely] NimBLE-safe off-loopTask
  if (!write_csv_header) return;
  char line[96];
  size_t o = snprintf(line, sizeof(line), "dtc_stored=");
  for (uint8_t i = 0; i < stored.count && o + 7 < sizeof(line); ++i) {
    char code[6];
    dtc_code_to_string(stored.codes[i], code);
    o += snprintf(line + o, sizeof(line) - o, "%s%s", i ? "," : "", code);
  }
  pipeline.header_line(line);
  o = snprintf(line, sizeof(line), "dtc_pending=");
  for (uint8_t i = 0; i < pending.count && o + 7 < sizeof(line); ++i) {
    char code[6];
    dtc_code_to_string(pending.codes[i], code);
    o += snprintf(line + o, sizeof(line) - o, "%s%s", i ? "," : "", code);
  }
  pipeline.header_line(line);
}

// [Likely] KNOWN RISK, not fixed by this task: esp32_uart_transport.cpp's
// blocking read (Stream::timedRead(), a pre-existing Phase-1 file outside
// this plan's scope) can busy-poll for up to the K-line response timeout
// with no yielding primitive. Since this task (prio 2) outranks loopTask
// (prio 1, BLE notify + WiFi sync) on the same core, a slow/down K-line
// link can transiently starve BLE/WiFi-sync responsiveness -- expected to
// happen routinely given several PIDs are documented as unsupported on
// this ECU. Confirmed NOT a watchdog-panic risk (CPU1's idle task isn't
// watchdog-monitored on the pinned sdkconfig; loopTaskWDTEnabled defaults
// off and is never enabled here) -- a responsiveness degradation, not a
// crash/data-loss risk. Candidate for a future task: make the transport's
// wait loop yield periodically. Watch for this specifically during the
// hardware bring-up session (BLE notify cadence during a deliberately
// stopped kline_sim.py, and uxTaskGetStackHighWaterMark() readings for
// both new tasks after the first ride-start and a /rides HTTP request).
//
// Capture task: owns ALL K-line I/O. Constructed locals => nothing else can
// touch kwp/scheduler. Storage never blocks this task: readings go through
// the non-blocking queue (drops counted + reported as a header line).
void capture_task(void*) {
  Esp32UartTransport transport(Serial2);
  KlineKwp kwp(transport);
  PidScheduler scheduler(kwp);

  bool ride_active = false;
  uint32_t last_tester_present_ms = 0;
  uint32_t last_setup_retry_ms = 0;
  uint32_t last_reinit_attempt_ms = 0;
  uint32_t last_dtc_read_ms = 0;
  uint32_t last_drop_marker_ms = 0;

  for (;;) {
    const uint32_t now = millis();
    kline_up = ride_active && !kwp.needs_reinit();

    if (!ride_active) {
      if (now - last_setup_retry_ms >= kSetupRetryIntervalMs) {
        last_setup_retry_ms = now;
        if (kwp.start_communication()) {
          Serial.println("StartCommunication OK");
          // Logical StartCommunication only -- physical fast-init/5-baud is
          // Phase 2/3 scope (needs the L9637D). The storage task allocates
          // the filename and opens the file when it drains this event.
          pipeline.ride_start("logical-init");
          read_and_publish_dtcs(kwp, /*write_csv_header=*/true);
          ride_active = true;
        } else {
          Serial.println(
              "StartCommunication failed -- retrying (check wiring / "
              "kline_sim.py)");
        }
      }
      vTaskDelay(pdMS_TO_TICKS(100));
      continue;
    }

    PidScheduler::Reading reading;
    if (scheduler.tick(now, &reading)) {
      pipeline.reading(now, reading);
      portENTER_CRITICAL(&latest_mux);
      latest_values_apply(&latest, reading.signal, reading.value,
                          reading.available);
      portEXIT_CRITICAL(&latest_mux);
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
      vTaskDelay(pdMS_TO_TICKS(1));
    }

    if (now - last_tester_present_ms >= kTesterPresentIntervalMs) {
      kwp.send_tester_present();
      last_tester_present_ms = now;
    }

    if (now - last_dtc_read_ms >= kDtcReadIntervalMs) {
      last_dtc_read_ms = now;
      read_and_publish_dtcs(kwp, /*write_csv_header=*/false);
    }

    if (now - last_drop_marker_ms >= 60000) {
      last_drop_marker_ms = now;
      pipeline.emit_drop_marker();
    }

    if (kwp.needs_reinit() &&
        now - last_reinit_attempt_ms >= kReinitRetryIntervalMs) {
      last_reinit_attempt_ms = now;
      Serial.print("3 consecutive timeouts -- re-running StartCommunication: ");
      Serial.println(kwp.start_communication() ? "OK" : "failed");
    }
  }
}

// Storage task: the ONLY RideLogger/RideStorage writer after the split.
void storage_task(void*) {
  LittleFsStorage storage;
  RideLogger logger(storage);
  StorageDrain drain(logger, file_store, FW_VERSION);
  CaptureEvent e;
  for (;;) {
    if (capture_queue.receive(&e, 500)) drain.handle(e);
    drain.maybe_flush(millis());
  }
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
  // Runs on the NimBLE host task. The queue is the task-safe path into the
  // ride file (the pre-split code wrote to the logger from here -- a race).
  char marker[48];
  snprintf(marker, sizeof(marker), "time_sync=%lu:%llu",
           static_cast<unsigned long>(millis()),
           static_cast<unsigned long long>(epoch_ms));
  pipeline.header_line(marker);
#endif
}

void notify_tick(uint32_t now) {
  if (now - last_notify_ms < kNotifyIntervalMs) {
    return;
  }
  last_notify_ms = now;
  LatestValues snapshot;
  portENTER_CRITICAL(&latest_mux);
  ++latest.seq;
  latest.uptime_ms = now;
#ifndef KL_DEMO_MODE
  latest_values_set_flag(&latest, kFlagKlineConnected, kline_up.load());
#endif
  snapshot = latest;
  portEXIT_CRITICAL(&latest_mux);
  ble.notify_telemetry(snapshot);
}

void service_wifi_sync(uint32_t now) {
  if (wifi_sync_requested.exchange(false)) {
    wifi_sync.start(file_store, now);
  }
  wifi_sync.handle(now);
}

}  // namespace

#ifdef KL_DEMO_MODE

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.print("KompressorLink DEMO firmware boot, fw=");
  Serial.println(FW_VERSION);
  ble.begin(FW_VERSION, on_time_sync, on_wifi_sync_request);
  DtcList stored, pending;
  demo_dtcs(&stored, &pending);
  ble.update_dtc(stored, pending);
}

void loop() {
  const uint32_t now = millis();
  portENTER_CRITICAL(&latest_mux);
  demo_fill(now, &latest);
  portEXIT_CRITICAL(&latest_mux);
  notify_tick(now);
  service_wifi_sync(now);
  delay(10);
}

#else  // real target: capture/storage tasks + BLE + wifi sync

void setup() {
  Serial.begin(115200);
  Serial2.begin(10400, SERIAL_8N1, /*RX=*/16, /*TX=*/17);
  delay(200);
  Serial.print("KompressorLink firmware boot, fw=");
  Serial.println(FW_VERSION);

  ble.begin(FW_VERSION, on_time_sync, on_wifi_sync_request);

  // Stack sizes in BYTES on ESP32 (ESP-IDF semantics). [Best estimate]
  // 8 KiB each: both tasks stick to small fixed buffers.
  // Capture on core 1 above loopTask; storage on core 0 beside the radio
  // stack -- storage latency can never stall K-line timing from there.
  xTaskCreatePinnedToCore(capture_task, "kl_capture", 8192, nullptr,
                          /*prio=*/2, nullptr, /*core=*/1);
  xTaskCreatePinnedToCore(storage_task, "kl_storage", 8192, nullptr,
                          /*prio=*/1, nullptr, /*core=*/0);
}

void loop() {
  const uint32_t now = millis();
  notify_tick(now);
  service_wifi_sync(now);
  delay(10);
}

#endif  // KL_DEMO_MODE
