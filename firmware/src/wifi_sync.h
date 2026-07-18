#pragma once

#include <cstdint>

#include "ride_file_store.h"

inline constexpr const char* kWifiSyncSsid = "KompressorLink";
// Static WPA2 PSK for a personal single-user device; documented in
// docs/wifi_sync_protocol.md. [Best estimate: adequate threat model]
inline constexpr const char* kWifiSyncPsk = "kompressor-link";
inline constexpr uint32_t kWifiIdleOffMs = 5 * 60 * 1000;

// ESP32-only SoftAP + WebServer glue around wifi_sync_core. start() is
// idempotent; handle() must be called from loop(); auto-stops after
// kWifiIdleOffMs without a request.
//
// Task affinity: start()/stop()/handle() must all be called from the SAME
// task (the Arduino loop task) -- active_/last_activity_ms_ and the
// WebServer/buffer statics in wifi_sync.cpp have no synchronization. The
// BLE control-write callback (NimBLE host task, opcode 0x03) that
// eventually triggers a sync must NOT call start() directly -- hand off
// via an atomic flag the loop task polls (see main.cpp's
// wifi_sync_requested), the same pattern this project already uses
// elsewhere for cross-task requests.
//
// Single-instance: unlike BleSvc (which has zero member variables and is a
// pure facade over file-scope NimBLE globals), WifiSync carries real
// per-object state (active_, last_activity_ms_) alongside wifi_sync.cpp's
// file-scope WebServer/buffer statics. Exactly ONE WifiSync must ever
// exist/be active -- a second instance's start() would silently repoint
// the shared statics at its own data while the first instance's active_
// stays true, corrupting both. main.cpp owns the single global instance.
class WifiSync {
 public:
  void start(RideFileStore& store, uint32_t now_ms);
  void stop();
  void handle(uint32_t now_ms);
  bool active() const { return active_; }

 private:
  bool active_ = false;
  uint32_t last_activity_ms_ = 0;
};
