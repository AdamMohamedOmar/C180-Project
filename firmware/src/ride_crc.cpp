#include "ride_crc.h"

#include <cstdio>
#include <cstdlib>

#include "crc32.h"

namespace {

bool compute_crc(RideFileStore& store, const char* name, uint32_t* out) {
  Crc32 crc;
  uint8_t buf[512];
  uint32_t offset = 0;
  // Trusts store.read()'s documented EOF contract (0 = at/after EOF) to
  // terminate; a backend that violated this would loop until offset wraps.
  for (;;) {
    const int32_t n = store.read(name, offset, buf, sizeof(buf));
    if (n < 0) return false;
    if (n == 0) break;
    crc.update(buf, static_cast<size_t>(n));
    offset += static_cast<uint32_t>(n);
  }
  *out = crc.value();
  return true;
}

}  // namespace

void crc_sidecar_name(const char* ride_name, char* out, size_t cap) {
  snprintf(out, cap, "%s.crc", ride_name);
}

bool write_crc_sidecar(RideFileStore& store, const char* ride_name) {
  uint32_t crc = 0;
  if (!compute_crc(store, ride_name, &crc)) return false;
  char sidecar[kCrcSidecarNameCap];
  crc_sidecar_name(ride_name, sidecar, sizeof(sidecar));
  char text[16];
  snprintf(text, sizeof(text), "%08X", static_cast<unsigned>(crc));
  return store.write_text(sidecar, text);
}

bool crc_for_ride(RideFileStore& store, const RideFileInfo& ride, uint32_t* out) {
  char sidecar[kCrcSidecarNameCap];
  crc_sidecar_name(ride.name, sidecar, sizeof(sidecar));
  char text[16];
  if (store.read_text(sidecar, text, sizeof(text))) {
    *out = static_cast<uint32_t>(strtoul(text, nullptr, 16));
    return true;
  }
  uint32_t crc = 0;
  if (!compute_crc(store, ride.name, &crc)) return false;
  char sidecar_text[16];
  snprintf(sidecar_text, sizeof(sidecar_text), "%08X", static_cast<unsigned>(crc));
  store.write_text(sidecar, sidecar_text);
  *out = crc;
  return true;
}
