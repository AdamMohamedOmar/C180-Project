#pragma once

#include <cstddef>
#include <cstdint>

#include "ride_file_store.h"

// ride name (<= sizeof(RideFileInfo::name), NUL included) + ".crc" + NUL.
// Tied to RideFileInfo::name's size so this can't silently drift out of
// sync if that struct ever changes.
inline constexpr size_t kCrcSidecarNameCap = sizeof(RideFileInfo::name) + 4;

// "<ride>.crc" sidecar: 8 uppercase hex chars, the file's CRC-32/ISO-HDLC.
// Cache written at ride close; manifest backfills lazily for older files.
void crc_sidecar_name(const char* ride_name, char* out, size_t cap);

// Streams the whole ride through Crc32 (512 B chunks) and writes the
// sidecar. Returns false if the ride can't be read.
bool write_crc_sidecar(RideFileStore& store, const char* ride_name);

// Sidecar value if present, else compute + backfill. A stale sidecar is
// trusted by design: rides are append-once and never modified after close.
// Returns false (leaving *out unchanged) if the ride can't be read.
bool crc_for_ride(RideFileStore& store, const RideFileInfo& ride, uint32_t* out);
