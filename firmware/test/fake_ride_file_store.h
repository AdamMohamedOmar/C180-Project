#pragma once

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <map>
#include <set>
#include <string>

#include "ride_file_store.h"

class FakeRideFileStore : public RideFileStore {
 public:
  int list_rides(RideFileInfo* out, int cap) override {
    int n = 0;
    for (const auto& [name, data] : files) {
      if (name.rfind("ride_", 0) != 0) continue;
      if (name.size() < 4 || name.compare(name.size() - 4, 4, ".csv") != 0) continue;
      if (name == active) continue;
      if (n >= cap) break;
      snprintf(out[n].name, sizeof(out[n].name), "%s", name.c_str());
      out[n].size = static_cast<uint32_t>(data.size());
      ++n;
    }
    return n;
  }

  int32_t read(const char* name, uint32_t offset, uint8_t* buf,
               uint32_t len) override {
    if (fail_read.count(name)) return -1;
    auto it = files.find(name);
    if (it == files.end()) return -1;
    if (offset >= it->second.size()) return 0;
    const uint32_t n =
        std::min<uint32_t>(len, static_cast<uint32_t>(it->second.size()) - offset);
    memcpy(buf, it->second.data() + offset, n);
    return static_cast<int32_t>(n);
  }

  // Names in fail_remove report removal failure without actually being
  // removed -- lets tests exercise a backend remove() failure (e.g. a
  // real filesystem write error) without needing one to actually happen.
  // Empty by default: existing callers see unchanged behavior.
  bool remove(const char* name) override {
    if (fail_remove.count(name)) return false;
    return files.erase(name) > 0;
  }

  uint64_t free_bytes() override {
    uint64_t used = 0;
    for (const auto& [_, data] : files) used += data.size();
    return used >= capacity_bytes ? 0 : capacity_bytes - used;
  }

  bool read_text(const char* name, char* out, size_t cap) override {
    auto it = files.find(name);
    if (it == files.end()) return false;
    snprintf(out, cap, "%s", it->second.c_str());
    return true;
  }

  bool write_text(const char* name, const char* text) override {
    files[name] = text;
    return true;
  }

  void set_active_ride(const char* name_or_null) override {
    active = name_or_null ? name_or_null : "";
  }

  void add_ride(const std::string& name, size_t size, char fill = 'x') {
    files[name] = std::string(size, fill);
  }

  // NOTE: real LittleFS directory order is arbitrary -- production code
  // must not rely on any particular order; this fake's std::map ordering
  // is a test convenience only, not a guarantee callers may depend on.
  std::map<std::string, std::string> files;  // ordered => deterministic listing
  std::string active;
  uint64_t capacity_bytes = 1400 * 1024;
  std::set<std::string> fail_remove;
  // Names in fail_read report a read() failure (returns -1) without
  // affecting read_text()/write_text() (sidecar/seq-counter lookups stay
  // unaffected) -- lets tests simulate an unreadable ride file that's
  // still present in list_rides()'s output. Empty by default: existing
  // callers see unchanged behavior.
  std::set<std::string> fail_read;
};
