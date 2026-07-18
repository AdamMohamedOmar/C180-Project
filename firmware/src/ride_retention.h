#pragma once

#include <cstdint>

#include "ride_file_store.h"

// Free-space floor enforced at every ride start. [Best estimate] 256 KiB ≈
// several bench-hours of long-format CSV headroom on the ~1.4 MB LittleFS
// partition; revisit when the SD backend lands (Phase 2 hardware).
inline constexpr uint64_t kMinFreeRideBytes = 256 * 1024;

// Deletes oldest closed rides (lowest ride seq, sidecars too) until
// free_bytes() >= min_free_bytes or nothing deletable remains. Returns the
// number of ride files deleted. Never touches the active ride (list_rides
// excludes it) or non-ride files.
int enforce_retention(RideFileStore& store, uint64_t min_free_bytes);
