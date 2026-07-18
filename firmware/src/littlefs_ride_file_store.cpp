#include "littlefs_ride_file_store.h"

#include <LittleFS.h>

#include <cstdio>
#include <cstring>

namespace {
void to_path(const char* name, char* out, size_t cap) {
  snprintf(out, cap, "/%s", name);
}

class MutexGuard {
 public:
  explicit MutexGuard(SemaphoreHandle_t m) : m_(m) {
    xSemaphoreTake(m_, portMAX_DELAY);
  }
  ~MutexGuard() { xSemaphoreGive(m_); }

 private:
  SemaphoreHandle_t m_;
};
}  // namespace

LittleFsRideFileStore::LittleFsRideFileStore() : mux_(xSemaphoreCreateMutex()) {}

bool LittleFsRideFileStore::ensure_mounted() {
  // Same formatOnFail rationale as littlefs_storage.cpp; begin() after a
  // successful mount is a cheap no-op, so no static guard duplication.
  return LittleFS.begin(/*formatOnFail=*/true);
}

int LittleFsRideFileStore::list_rides(RideFileInfo* out, int cap) {
  MutexGuard guard(mux_);
  if (!ensure_mounted()) return 0;
  File root = LittleFS.open("/");
  if (!root) return 0;
  int n = 0;
  for (File f = root.openNextFile(); f && n < cap; f = root.openNextFile()) {
    const char* raw = f.name();
    if (raw == nullptr) continue;
    // Task 8 verification (docs/superpowers/plans/2026-07-17-firmware-
    // nonblocking-sync.md): checked the ACTUALLY-installed
    // framework-arduinoespressif32 package (3.20017.241212+sha.dcc1105b =
    // arduino-esp32 core 2.0.17, per cores/esp32/core_version.h). In that
    // core, LittleFS.open()/File::openNextFile() return VFSFileImpl-backed
    // File objects (libraries/FS/src/vfs_api.cpp), where:
    //   VFSFileImpl::path() returns the full "/"-prefixed path
    //   VFSFileImpl::name() returns pathToFileName(path())
    // and pathToFileName() (cores/esp32/esp32-hal-misc.c) returns the
    // pointer just past the LAST '/' or '\' -- i.e. name() already yields
    // a bare basename ("ride_00001.csv"), never "/"-prefixed, on this
    // installed core. So the strncmp/strcmp filters below would work even
    // without the strip below. Still defensively strip a leading '/' here
    // so this stays correct if a future core bump ever changes name() to
    // return path()-style names -- cheap, and this is exactly the kind of
    // core-version-dependent assumption that must not be silently guessed.
    const char* base = (raw[0] == '/') ? raw + 1 : raw;
    const size_t len = strlen(base);
    if (strncmp(base, "ride_", 5) != 0) continue;
    if (len < 4 || strcmp(base + len - 4, ".csv") != 0) continue;
    if (active_[0] != '\0' && strcmp(base, active_) == 0) continue;
    snprintf(out[n].name, sizeof(out[n].name), "%s", base);
    out[n].size = static_cast<uint32_t>(f.size());
    ++n;
  }
  return n;
}

int32_t LittleFsRideFileStore::read(const char* name, uint32_t offset,
                                    uint8_t* buf, uint32_t len) {
  MutexGuard guard(mux_);
  if (!ensure_mounted()) return -1;
  char path[48];
  to_path(name, path, sizeof(path));
  File f = LittleFS.open(path, "r");
  if (!f) return -1;
  if (offset >= static_cast<uint32_t>(f.size())) {
    f.close();
    return 0;
  }
  if (!f.seek(offset)) {
    f.close();
    return -1;
  }
  const size_t n = f.read(buf, len);
  f.close();
  return static_cast<int32_t>(n);
}

bool LittleFsRideFileStore::remove(const char* name) {
  MutexGuard guard(mux_);
  if (!ensure_mounted()) return false;
  char path[48];
  to_path(name, path, sizeof(path));
  return LittleFS.remove(path);
}

uint64_t LittleFsRideFileStore::free_bytes() {
  MutexGuard guard(mux_);
  if (!ensure_mounted()) return 0;
  const uint64_t total = LittleFS.totalBytes();
  const uint64_t used = LittleFS.usedBytes();
  return used >= total ? 0 : total - used;
}

bool LittleFsRideFileStore::read_text(const char* name, char* out,
                                      size_t cap) {
  MutexGuard guard(mux_);
  if (!ensure_mounted()) return false;
  char path[48];
  to_path(name, path, sizeof(path));
  File f = LittleFS.open(path, "r");
  if (!f) return false;
  if (cap == 0) {
    // Never write to a buffer the caller declared zero capacity for --
    // matches FakeRideFileStore::read_text()'s (snprintf-based) behavior
    // for this same edge case, which this real implementation must not
    // silently diverge from (ride_file_store.h's "never overflow the
    // caller's buffer" contract, tightened during Task 3's review).
    f.close();
    return false;
  }
  const size_t n = f.read(reinterpret_cast<uint8_t*>(out), cap - 1);
  out[n] = '\0';
  f.close();
  return true;
}

bool LittleFsRideFileStore::write_text(const char* name, const char* text) {
  MutexGuard guard(mux_);
  if (!ensure_mounted()) return false;
  char path[48];
  to_path(name, path, sizeof(path));
  File f = LittleFS.open(path, "w");
  if (!f) return false;
  f.print(text);
  f.close();
  return true;
}

void LittleFsRideFileStore::set_active_ride(const char* name_or_null) {
  MutexGuard guard(mux_);
  if (name_or_null == nullptr) {
    active_[0] = '\0';
  } else {
    snprintf(active_, sizeof(active_), "%s", name_or_null);
  }
}
