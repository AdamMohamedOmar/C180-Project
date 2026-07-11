#pragma once

#include <cstring>
#include <vector>

#include "kline_transport.h"

// Test double: queue bytes for read() to hand back, and capture what
// write() sends.
//
// read() is immediate and does NOT model elapsed time: it returns whatever
// is currently queued right away, regardless of `timeout_ms`, and never
// consults now_ms()/fake_time_ms_. A queued response always "arrives";
// nothing queued always looks like an instant timeout (0 bytes read). This
// is sufficient for testing "did the ECU respond or not" branches, but NOT
// for testing behavior that depends on a response arriving only after a
// specific amount of simulated time has passed -- there is no such
// scenario in this project's tests today. now_ms()/advance_ms() are wired
// for callers that need a controllable clock for their OWN bookkeeping
// (e.g. code that timestamps events via now_ms()); they don't gate read().
class FakeTransport : public KLineTransport {
 public:
  void write(const uint8_t* data, size_t len) override {
    written_.insert(written_.end(), data, data + len);
  }

  size_t read(uint8_t* buf, size_t len, uint32_t timeout_ms) override {
    (void)timeout_ms;  // See class comment: read() is immediate, not time-gated.
    const size_t available = queued_.size() - read_pos_;
    const size_t n = available < len ? available : len;
    std::memcpy(buf, queued_.data() + read_pos_, n);
    read_pos_ += n;
    return n;
  }

  uint32_t now_ms() override { return fake_time_ms_; }

  void queue_response(const std::vector<uint8_t>& bytes) {
    queued_ = bytes;
    read_pos_ = 0;
  }

  void advance_ms(uint32_t delta) { fake_time_ms_ += delta; }

  std::vector<uint8_t> written_;

 private:
  std::vector<uint8_t> queued_;
  size_t read_pos_ = 0;
  uint32_t fake_time_ms_ = 0;
};
