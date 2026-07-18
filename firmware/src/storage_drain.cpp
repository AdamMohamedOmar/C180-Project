#include "storage_drain.h"

#include "ride_crc.h"
#include "ride_naming.h"
#include "ride_retention.h"

void StorageDrain::handle(const CaptureEvent& e) {
  switch (e.type) {
    case CaptureEvent::kRideStart: {
      if (ride_open_) return;  // duplicate start -- keep the open ride
      enforce_retention(store_, kMinFreeRideBytes);
      const uint32_t seq = allocate_ride_seq(store_);
      format_ride_name(ride_name_, sizeof(ride_name_), seq);
      if (!logger_.start_ride(ride_name_, fw_version_, e.text2)) {
        ride_name_[0] = '\0';
        return;  // storage failure: swallow events until the next start
      }
      store_.set_active_ride(ride_name_);
      ride_open_ = true;
      break;
    }
    case CaptureEvent::kHeaderLine:
      if (ride_open_) logger_.write_header_line(e.text);
      break;
    case CaptureEvent::kReading:
      if (ride_open_) {
        PidScheduler::Reading r;
        r.signal = e.signal;
        r.value = e.value;
        r.available = e.available;
        logger_.log_reading(e.t_ms, r);
      }
      break;
    case CaptureEvent::kRideClose:
      if (!ride_open_) return;
      logger_.flush();
      logger_.close_ride();
      ride_open_ = false;
      store_.set_active_ride(nullptr);
      write_crc_sidecar(store_, ride_name_);
      break;
  }
}

void StorageDrain::maybe_flush(uint32_t now_ms) {
  if (!ride_open_) return;
  if (now_ms - last_flush_ms_ < kDrainFlushIntervalMs) return;
  last_flush_ms_ = now_ms;
  logger_.flush();
}
