#pragma once

#include <cstdint>

#include "dtc_list.h"
#include "latest_values.h"
#include "telemetry_frame.h"

// GATT identity — mirror-verbatim of docs/ble_protocol.md. Fixed forever.
inline constexpr const char* kBleDeviceName = "KompressorLink";
inline constexpr const char* kKlServiceUuid = "c1800001-4b4c-4d27-b946-c180c0deba5e";
inline constexpr const char* kTelemetryCharUuid = "c1800002-4b4c-4d27-b946-c180c0deba5e";
inline constexpr const char* kDtcCharUuid = "c1800003-4b4c-4d27-b946-c180c0deba5e";
inline constexpr const char* kControlCharUuid = "c1800004-4b4c-4d27-b946-c180c0deba5e";

// Thin ESP32-only NimBLE wrapper (spec §3.5): advertise, own the three
// characteristics + Device Information Service, notify from caller-supplied
// data, dispatch control writes. ZERO protocol logic — all bytes come from
// telemetry_frame. Compile-verified only (ble_svc.cpp is excluded from the
// native env); real-BLE behavior is proven in the final hardware session.
class BleSvc {
 public:
  using TimeSyncCallback = void (*)(uint64_t epoch_ms);
  using WifiSyncRequestCallback = void (*)();

  // Init NimBLE, create service/characteristics/DIS, start advertising.
  // on_wifi_sync fires on control opcode 0x03 (defaults to nullptr — main.cpp
  // wiring it up is a later task; nullptr just means the request is ignored
  // rather than crashing, matching the existing g_time_cb-null-check pattern).
  void begin(const char* fw_version, TimeSyncCallback on_time_sync,
             WifiSyncRequestCallback on_wifi_sync = nullptr);

  // Packs lv (caller has already stamped seq/uptime_ms) and notifies if a
  // client is subscribed. Call at ~2 Hz.
  void notify_telemetry(const LatestValues& lv);

  // Packs the DTC report; updates the readable value; notifies only when
  // the packed bytes actually changed since the last call.
  void update_dtc(const DtcList& stored, const DtcList& pending);
};
