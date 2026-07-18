#include "ride_naming.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>

void format_ride_name(char* out, size_t cap, uint32_t seq) {
  snprintf(out, cap, "ride_%05u.csv", static_cast<unsigned>(seq));
}

uint32_t parse_ride_seq(const char* name) {
  // Exact shape: "ride_" + 5 digits + ".csv" (14 chars for seq <= 99999;
  // longer digit runs still parse so growth past 99999 keeps working).
  if (strncmp(name, "ride_", 5) != 0) return 0;
  const char* p = name + 5;
  const char* d = p;
  while (*d >= '0' && *d <= '9') ++d;
  if (d == p) return 0;               // no digits
  if (strcmp(d, ".csv") != 0) return 0;  // trailing junk / sidecar / txt
  return static_cast<uint32_t>(strtoul(p, nullptr, 10));
}

uint32_t allocate_ride_seq(RideFileStore& store) {
  char buf[16] = {0};
  uint32_t persisted = 0;
  if (store.read_text(kRideSeqFile, buf, sizeof(buf))) {
    persisted = static_cast<uint32_t>(strtoul(buf, nullptr, 10));
  }
  // See kMaxRideFiles' doc comment: this scan can miss files if ride count
  // ever exceeds kMaxRideFiles. Accepted residual risk (see ride_file_store.h).
  RideFileInfo rides[kMaxRideFiles];
  const int n = store.list_rides(rides, kMaxRideFiles);
  uint32_t max_seen = 0;
  for (int i = 0; i < n; ++i) {
    const uint32_t s = parse_ride_seq(rides[i].name);
    if (s > max_seen) max_seen = s;
  }
  uint32_t seq = persisted > max_seen + 1 ? persisted : max_seen + 1;
  if (seq == 0) seq = 1;
  char next[16];
  snprintf(next, sizeof(next), "%u", static_cast<unsigned>(seq + 1));
  store.write_text(kRideSeqFile, next);  // persist BEFORE use (crash-safe)
  return seq;
}
