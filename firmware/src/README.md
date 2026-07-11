# firmware/src/ — module map

Phase 0 only scaffolds the build (`platformio.ini`, `main.cpp`, `version.h`)
and the shared `pid_schema` (signal table + physical-bounds guard — also
mirrored in `pipeline/src/kl/schema.py`; keep both in sync).

Modules below are Phase 1+ (PLAN.md §5.1, §9) — not yet created:

| Module | File(s) | Responsibility |
|---|---|---|
| power_mgr | power_mgr.h/.cpp | Deep sleep, wake sources, parasitic-draw budget |
| kline_kwp | kline_kwp.h/.cpp | ISO 14230 KWP2000 stack: init, tester-present, Mode 01/02/03/07/09 |
| pid_scheduler | pid_scheduler.h/.cpp | Tiered polling against `pid_schema`'s Tier field |
| ride_logger | ride_logger.h/.cpp | Long-format CSV writer, flush+fsync, header block |
| startup_snapshot | startup_snapshot.h/.cpp | DTC/freeze-frame/readiness capture on wake |
| ble_svc | ble_svc.h/.cpp | GATT telemetry/control/ride-index service |
| wifi_sync | wifi_sync.h/.cpp | SoftAP + HTTP bulk ride-file transfer |
| can_sniffer | can_sniffer.h/.cpp | Phase 7, default off. Listen-only TWAI frame census |

`pid_schema.h/.cpp` (Phase 0) is the one piece of shared state these all
depend on — don't duplicate the signal table elsewhere in firmware.

Also Phase 1+ (PLAN.md §5.1): PlatformIO build targets (not modules with their
own `.h/.cpp`) `bench_selftest` and `kline_probe` — bench self-test and the
Phase 3 car-probe firmware, respectively.
