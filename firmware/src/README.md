# firmware/src/ — module map

Phase 0 only scaffolds the build (`platformio.ini`, `main.cpp`, `version.h`)
and the shared `pid_schema` (signal table + physical-bounds guard — also
mirrored in `pipeline/src/kl/schema.py`; keep both in sync).

Modules below are Phase 1+ (PLAN.md §5.1, §9). Most now exist; `power_mgr`,
`startup_snapshot`, and `can_sniffer` are still unbuilt:

| Module | File(s) | Responsibility |
|---|---|---|
| power_mgr | power_mgr.h/.cpp | Deep sleep, wake sources, parasitic-draw budget |
| kline_kwp | kline_kwp.h/.cpp | ISO 14230 KWP2000 stack: init, tester-present, Mode 01/02/03/07/09 |
| pid_scheduler | pid_scheduler.h/.cpp | Tiered polling against `pid_schema`'s Tier field |
| ride_logger | ride_logger.h/.cpp | Long-format CSV writer, flush+fsync, header block |
| startup_snapshot | startup_snapshot.h/.cpp | DTC/freeze-frame/readiness capture on wake |
| ble_svc | ble_svc.h/.cpp | GATT telemetry/control/ride-index service |
| wifi_sync | wifi_sync.h/.cpp | SoftAP + HTTP glue: SoftAP lifecycle + WebServer around `wifi_sync_core` |
| can_sniffer | can_sniffer.h/.cpp | Phase 7, default off. Listen-only TWAI frame census |
| crc32 | crc32.h/.cpp | CRC-32/ISO-HDLC checksum |
| ride_file_store | ride_file_store.h + littlefs_ride_file_store.h/.cpp | Read/list/delete-side ride storage abstraction (interface) + LittleFS impl |
| ride_naming | ride_naming.h/.cpp | Crash-safe monotonic ride filename allocation |
| ride_crc | ride_crc.h/.cpp | CRC sidecar caching (compute-once, persist, prefer-cached-over-recompute) |
| ride_retention | ride_retention.h/.cpp | FIFO delete-oldest-ride-under-floor free-space enforcement |
| capture_event / capture_queue / capture_pipeline | capture_event.h + capture_queue.h + capture_pipeline.h/.cpp | Producer-side: fixed-size queue event type + queue interface + the pipeline helper that shapes readings/headers/lifecycle into events |
| storage_drain | storage_drain.h/.cpp | Consumer-side: the sole `RideLogger`/`RideStorage` owner (see Task model below) |
| wifi_sync_core | wifi_sync_core.h/.cpp | Pure manifest/chunk logic behind `wifi_sync` — no ESP32/WiFi dependency, natively testable |
| freertos_capture_queue | freertos_capture_queue.h | FreeRTOS-backed `CaptureQueue` impl (ESP32-only; tests use an in-memory fake) |

`pid_schema.h/.cpp` (Phase 0) is the one piece of shared state these all
depend on — don't duplicate the signal table elsewhere in firmware.

Also Phase 1+ (PLAN.md §5.1): PlatformIO build targets (not modules with their
own `.h/.cpp`) `bench_selftest` and `kline_probe` — bench self-test and the
Phase 3 car-probe firmware, respectively.

## Task model

`main.cpp` runs three FreeRTOS tasks instead of one Arduino loop: the
**capture task** (core 1, prio 2) owns all K-line I/O — Serial2/KWP,
`PidScheduler`, tester-present, reinit-on-timeout, and the periodic DTC
poll; the **storage task** (core 0, prio 1) is the only task that touches
`RideLogger`/`RideStorage`, draining the `CaptureQueue` via `StorageDrain`
(ride naming, retention, open/close, CRC sidecar); and **loopTask**
(Arduino `loop()`, core 1, prio 1) handles BLE telemetry notify (~2 Hz) and
WiFi sync servicing. Single-writer rule: nothing but the storage task ever
calls into `RideLogger`/`RideStorage` — every other producer (capture-task
readings/headers, the NimBLE time-sync callback) goes through
`CapturePipeline`/`CaptureQueue` instead of touching the logger directly.
