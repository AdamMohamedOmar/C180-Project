#include "wifi_sync_core.h"

#include <cstdio>
#include <cstring>

#include "ride_crc.h"

size_t build_manifest_json(RideFileStore& store, char* out, size_t cap) {
  RideFileInfo rides[kMaxRideFiles];
  const int n = store.list_rides(rides, kMaxRideFiles);
  size_t o = 0;
  const auto append = [&](const char* fmt, auto... args) -> bool {
    const int w = snprintf(out + o, cap - o, fmt, args...);
    if (w < 0 || o + static_cast<size_t>(w) >= cap) return false;
    o += static_cast<size_t>(w);
    return true;
  };
  if (!append("{\"rides\":[")) return 0;
  bool wrote_any = false;
  for (int i = 0; i < n; ++i) {
    uint32_t crc = 0;
    // A ride crc_for_ride can't read (should be effectively unreachable —
    // list_rides just returned this name, so the backend should be able to
    // read it too — but treat it as defense in depth) is silently omitted
    // from the manifest rather than reported with a fabricated crc32. This
    // is the direct payoff of crc_for_ride's Task-3 signature fix: the old
    // uint32_t-returning version would have reported "00000000" here,
    // indistinguishable from a real empty-file CRC.
    if (!crc_for_ride(store, rides[i], &crc)) continue;
    if (!append("%s{\"name\":\"%s\",\"size\":%lu,\"crc32\":\"%08X\"}",
                wrote_any ? "," : "", rides[i].name,
                static_cast<unsigned long>(rides[i].size),
                static_cast<unsigned>(crc))) {
      return 0;
    }
    wrote_any = true;
  }
  if (!append("]}")) return 0;
  return o;
}

int32_t read_ride_chunk(RideFileStore& store, const char* name,
                        uint32_t offset, uint32_t length, uint8_t* out,
                        uint32_t cap) {
  uint32_t want = length;
  if (want > kSyncMaxChunk) want = kSyncMaxChunk;
  if (want > cap) want = cap;
  return store.read(name, offset, out, want);
}
