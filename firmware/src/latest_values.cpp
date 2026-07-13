#include "latest_values.h"

void latest_values_apply(LatestValues* lv, Signal signal, float value, bool available) {
  const size_t i = static_cast<size_t>(signal);
  if (i >= kSignalCount) {
    return;
  }
  if (available) {
    lv->values[i] = value;
    lv->avail_mask |= (1u << i);
  } else {
    lv->values[i] = 0.0f;
    lv->avail_mask &= ~(1u << i);
  }
}

void latest_values_set_flag(LatestValues* lv, uint8_t flag, bool on) {
  if (on) {
    lv->flags |= flag;
  } else {
    lv->flags &= static_cast<uint8_t>(~flag);
  }
}
