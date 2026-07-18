#pragma once

#include <cstddef>
#include <cstdint>

#include "ride_file_store.h"

inline constexpr const char* kRideSeqFile = "ride_next.txt";

// "ride_%05u.csv". Buffer must hold >= 15 chars + NUL.
void format_ride_name(char* out, size_t cap, uint32_t seq);

// Returns the NNNNN of a "ride_NNNNN.csv" name, or 0 if the name doesn't
// match exactly (0 is never a valid ride seq).
uint32_t parse_ride_seq(const char* name);

// Crash-safe monotonic allocation: seq = max(persisted next, highest
// existing + 1), and "next = seq + 1" is persisted BEFORE the caller ever
// opens the file. Worst case after a power cut is a skipped number — a
// reused number would corrupt the phone's name-keyed sync state.
uint32_t allocate_ride_seq(RideFileStore& store);
