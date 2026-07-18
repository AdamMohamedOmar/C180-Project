#include "wifi_sync.h"

#include <WebServer.h>
#include <WiFi.h>

#include <cstdlib>

#include "wifi_sync_core.h"

namespace {

// WebServer is single-threaded (serviced from loop()); statics keep the
// 4 KiB chunk buffer off the loop task's stack.
WebServer g_server(80);
RideFileStore* g_store = nullptr;
uint32_t* g_last_activity = nullptr;
uint32_t g_now = 0;
uint8_t g_chunk[kSyncMaxChunk];
char g_manifest[2048];

void handle_rides() {
  *g_last_activity = g_now;
  const size_t n = build_manifest_json(*g_store, g_manifest, sizeof(g_manifest));
  if (n == 0) {
    g_server.send(500, "text/plain", "manifest too large");
    return;
  }
  g_server.send(200, "application/json", g_manifest);
}

void handle_data() {
  *g_last_activity = g_now;
  if (!g_server.hasArg("name") || !g_server.hasArg("offset") ||
      !g_server.hasArg("length")) {
    g_server.send(400, "text/plain", "name/offset/length required");
    return;
  }
  const String name = g_server.arg("name");
  const uint32_t offset = strtoul(g_server.arg("offset").c_str(), nullptr, 10);
  const uint32_t length = strtoul(g_server.arg("length").c_str(), nullptr, 10);
  const int32_t n = read_ride_chunk(*g_store, name.c_str(), offset, length,
                                    g_chunk, sizeof(g_chunk));
  if (n < 0) {
    g_server.send(404, "text/plain", "no such ride");
    return;
  }
  g_server.setContentLength(static_cast<size_t>(n));
  g_server.send(200, "application/octet-stream", "");
  if (n > 0) {
    g_server.sendContent(reinterpret_cast<const char*>(g_chunk),
                         static_cast<size_t>(n));
  }
}

}  // namespace

void WifiSync::start(RideFileStore& store, uint32_t now_ms) {
  if (active_) return;
  g_store = &store;
  g_last_activity = &last_activity_ms_;
  WiFi.mode(WIFI_AP);
  WiFi.softAP(kWifiSyncSsid, kWifiSyncPsk);
  g_server.on("/rides", handle_rides);
  g_server.on("/data", handle_data);
  g_server.begin();
  last_activity_ms_ = now_ms;
  active_ = true;
  Serial.print("wifi_sync: AP up, IP ");
  Serial.println(WiFi.softAPIP());
}

void WifiSync::stop() {
  if (!active_) return;
  g_server.stop();
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_OFF);
  active_ = false;
  Serial.println("wifi_sync: AP down");
}

void WifiSync::handle(uint32_t now_ms) {
  if (!active_) return;
  g_now = now_ms;
  g_server.handleClient();
  if (now_ms - last_activity_ms_ >= kWifiIdleOffMs) stop();
}
