#pragma once

#include <cstddef>
#include <cstdint>

// One manifest/retention listing can hold this many closed rides. On the
// ~1.4 MB LittleFS partition, retention (ride_retention.h)'s free-space
// floor keeps realistic ride counts far below this bound for realistic
// ride durations (tens of KB per ride) -- verified: at ~60 KB/ride, the
// 256 KiB retention floor triggers around ~20 rides, well under this cap.
// [Best estimate] If ride-file count ever exceeds this bound (e.g. many
// very short/failed rides, or a first-boot bootstrap with pre-existing
// untracked files and no persisted seq counter yet), allocate_ride_seq's
// max_seen scan may miss files and risk a seq collision -- and because
// LittleFsStorage opens ride files with truncate-on-open semantics, a
// collision doesn't just confuse the phone's sync bookkeeping, it silently
// OVERWRITES the previous ride's recorded data the moment the colliding
// name is reused. Accepted residual risk for this personal single-car
// project rather than a pagination-safe listing API.
inline constexpr int kMaxRideFiles = 64;

struct RideFileInfo {
  char name[40];  // bare name, e.g. "ride_00042.csv"
  uint32_t size;
};

// Read/list/delete side of ride storage (RideStorage stays the write side).
// Names are BARE (no leading '/'); backends map to their own paths.
class RideFileStore {
 public:
  virtual ~RideFileStore() = default;

  // CLOSED ride CSVs only: files matching ride_*.csv, minus the active
  // ride registered via set_active_ride(). Returns count written (<= cap).
  virtual int list_rides(RideFileInfo* out, int cap) = 0;

  // Reads up to len bytes at offset from any file (rides, sidecars).
  // Returns bytes read (0 at/after EOF), or -1 if the file doesn't exist.
  virtual int32_t read(const char* name, uint32_t offset, uint8_t* buf,
                       uint32_t len) = 0;

  virtual bool remove(const char* name) = 0;
  virtual uint64_t free_bytes() = 0;

  // Whole-small-file text helpers (seq counter, CRC sidecars).
  // Implementations MUST truncate to cap-1 chars + NUL on a longer file,
  // never overflow the caller's buffer.
  virtual bool read_text(const char* name, char* out, size_t cap) = 0;
  virtual bool write_text(const char* name, const char* text) = 0;

  // The ride currently being written; excluded from list_rides(). Pass
  // nullptr on close. Callers pass BARE names.
  virtual void set_active_ride(const char* name_or_null) = 0;
};
