#include "ride_retention.h"

#include "ride_crc.h"
#include "ride_naming.h"

int enforce_retention(RideFileStore& store, uint64_t min_free_bytes) {
  int deleted = 0;
  // Trusts free_bytes() to reflect reality immediately after remove()
  // succeeds -- true for LittleFS's copy-on-write semantics.
  while (store.free_bytes() < min_free_bytes) {
    RideFileInfo rides[kMaxRideFiles];
    const int n = store.list_rides(rides, kMaxRideFiles);
    uint32_t oldest_seq = 0;
    int oldest = -1;
    for (int i = 0; i < n; ++i) {
      const uint32_t seq = parse_ride_seq(rides[i].name);
      if (seq == 0) continue;  // unparseable names are never auto-deleted
      if (oldest < 0 || seq < oldest_seq) {
        oldest_seq = seq;
        oldest = i;
      }
    }
    if (oldest < 0) break;  // nothing deletable -- bail rather than spin
    if (!store.remove(rides[oldest].name)) break;
    char sidecar[kCrcSidecarNameCap];
    crc_sidecar_name(rides[oldest].name, sidecar, sizeof(sidecar));
    store.remove(sidecar);  // best-effort; may not exist
    ++deleted;
  }
  return deleted;
}
