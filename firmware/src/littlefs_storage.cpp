#include "littlefs_storage.h"

namespace {
// Mounting is idempotent once successful; only the first call risks the
// formatOnFail=true format-on-corrupt-mount path. Guarding against repeat
// mount attempts means a later open_for_write() in the same boot can never
// re-trigger that format, even if the filesystem were to fail transiently.
bool g_littlefs_mounted = false;
}  // namespace

bool LittleFsStorage::open_for_write(const char* filename) {
  if (!g_littlefs_mounted) {
    if (!LittleFS.begin(/*formatOnFail=*/true)) {
      return false;
    }
    g_littlefs_mounted = true;
  }
  file_ = LittleFS.open(filename, "w");
  return static_cast<bool>(file_);
}

void LittleFsStorage::write_line(const char* line) {
  if (!file_) {
    return;
  }
  file_.println(line);
}

void LittleFsStorage::flush() {
  if (!file_) {
    return;
  }
  file_.flush();
}

void LittleFsStorage::close() {
  if (!file_) {
    return;
  }
  file_.close();
}
