#include "ble_svc.h"

#include <Arduino.h>
#include <NimBLEDevice.h>

#include <cstring>

// File-scope singletons: NimBLE objects are created once in begin() and
// live for the device's whole uptime (no teardown path — the ESP32 just
// powers off). API names below are [Likely] for NimBLE-Arduino 2.x — adapt
// to the pinned version's headers if a signature differs (Task 7 note).
namespace {
NimBLEServer* g_server = nullptr;
NimBLECharacteristic* g_telemetry_chr = nullptr;
NimBLECharacteristic* g_dtc_chr = nullptr;
BleSvc::TimeSyncCallback g_time_cb = nullptr;
uint8_t g_last_dtc[kDtcFrameMaxLen] = {0};
size_t g_last_dtc_len = 0;

class ControlCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* chr, NimBLEConnInfo& conn_info) override {
    NimBLEAttValue v = chr->getValue();
    ControlCommand cmd;
    if (parse_control(v.data(), v.length(), &cmd)) {
      if (g_time_cb != nullptr) {
        g_time_cb(cmd.epoch_ms);
      }
    } else {
      // Unknown opcode (incl. reserved 0x02 CLEAR_DTC) or malformed frame:
      // ignore, per docs/ble_protocol.md.
      Serial.println("ble_svc: ignored unknown/malformed control write");
    }
  }
};

ControlCallbacks g_control_callbacks;
}  // namespace

void BleSvc::begin(const char* fw_version, TimeSyncCallback on_time_sync) {
  g_time_cb = on_time_sync;

  NimBLEDevice::init(kBleDeviceName);
  g_server = NimBLEDevice::createServer();

  NimBLEService* svc = g_server->createService(kKlServiceUuid);
  g_telemetry_chr = svc->createCharacteristic(kTelemetryCharUuid, NIMBLE_PROPERTY::NOTIFY);
  g_dtc_chr = svc->createCharacteristic(kDtcCharUuid,
                                        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  NimBLECharacteristic* control =
      svc->createCharacteristic(kControlCharUuid, NIMBLE_PROPERTY::WRITE);
  control->setCallbacks(&g_control_callbacks);
  // NOTE: installed NimBLE-Arduino 2.5.0 deprecated NimBLEService::start()
  // (services now auto-start when NimBLEServer::start()/the server begins
  // advertising) -- no explicit start() call needed here; see Task 7 note
  // in ble_svc.h about adapting to the installed headers.

  // Standard Device Information Service: fw revision + manufacturer.
  NimBLEService* dis = g_server->createService("180A");
  dis->createCharacteristic("2A26", NIMBLE_PROPERTY::READ)->setValue(fw_version);
  dis->createCharacteristic("2A29", NIMBLE_PROPERTY::READ)->setValue(kBleDeviceName);

  // NimBLEServer::start() (distinct from the deprecated per-service
  // start() above) registers all services/characteristics with the NimBLE
  // host -- required exactly once, after they're all created, before
  // advertising begins.
  g_server->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(kKlServiceUuid);
  adv->start();
  Serial.println("ble_svc: advertising as KompressorLink");
}

void BleSvc::notify_telemetry(const LatestValues& lv) {
  if (g_telemetry_chr == nullptr) {
    return;
  }
  uint8_t frame[kTelemetryFrameLen];
  pack_telemetry(lv, frame);
  g_telemetry_chr->setValue(frame, kTelemetryFrameLen);
  // Installed NimBLE-Arduino 2.5.0 has no per-characteristic subscribed-
  // count accessor (NimBLECharacteristic::getSubscribedCount() does not
  // exist in this version's headers). NimBLEServer::getConnectedCount()
  // is the library's own documented idiom for gating notify() calls (see
  // examples/NimBLE_Server/NimBLE_Server.ino) -- notify() itself is a
  // per-connection no-op for any peer that hasn't subscribed to this
  // characteristic's CCCD, so this preserves the intent (don't burn
  // airtime/power notifying into a vacuum) at the granularity the
  // installed API actually exposes.
  if (g_server != nullptr && g_server->getConnectedCount() > 0) {
    g_telemetry_chr->notify();
  }
}

void BleSvc::update_dtc(const DtcList& stored, const DtcList& pending) {
  if (g_dtc_chr == nullptr) {
    return;
  }
  uint8_t frame[kDtcFrameMaxLen];
  const size_t n = pack_dtc_report(stored, pending, frame, sizeof(frame));
  if (n == 0) {
    return;  // can't happen while DtcList's kMaxDtcs invariant holds
  }
  const bool changed = (n != g_last_dtc_len) || (memcmp(frame, g_last_dtc, n) != 0);
  if (!changed) {
    return;
  }
  memcpy(g_last_dtc, frame, n);
  g_last_dtc_len = n;
  g_dtc_chr->setValue(frame, n);
  // See notify_telemetry() above for why getConnectedCount() replaces the
  // plan's getSubscribedCount() against the installed NimBLE-Arduino 2.5.0.
  if (g_server != nullptr && g_server->getConnectedCount() > 0) {
    g_dtc_chr->notify();
  }
}
