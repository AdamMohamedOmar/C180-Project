#pragma once

#include <cstddef>
#include <cstdint>

#include "ride_file_store.h"

// Max bytes per /data response. 4 KiB fits one static buffer in the HTTP
// glue and keeps chunk latency low over SoftAP. [Best estimate]
inline constexpr uint32_t kSyncMaxChunk = 4096;

// {"rides":[{"name":"...","size":N,"crc32":"XXXXXXXX"},...]} — closed rides
// only (the store excludes the active ride). A ride whose CRC can't be
// computed (crc_for_ride fails) is OMITTED from the manifest rather than
// reported with a fabricated checksum. Returns bytes written (excl. NUL),
// or 0 if `cap` is too small (caller answers HTTP 500).
size_t build_manifest_json(RideFileStore& store, char* out, size_t cap);

// Copies min(length, kSyncMaxChunk, cap, bytes-remaining) bytes at `offset`
// into out. 0 = at/after EOF (client interprets as done); -1 = no such file.
int32_t read_ride_chunk(RideFileStore& store, const char* name,
                        uint32_t offset, uint32_t length, uint8_t* out,
                        uint32_t cap);
