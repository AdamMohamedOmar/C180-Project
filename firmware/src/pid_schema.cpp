#include "pid_schema.h"

bool pid_schema_in_bounds(Signal signal, float value) {
  const size_t index = static_cast<size_t>(signal);
  if (index >= kSignalCount) {
    return false;
  }
  const SignalBounds &b = kSignalTable[index];
  return value >= b.min && value <= b.max;
}
