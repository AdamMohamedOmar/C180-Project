# WiFi ride-sync protocol v1

Phase-5 scope (PLAN.md §5.1 `wifi_sync`): BLE is too slow for ride files, so
bulk transfer runs over a temporary SoftAP raised on demand.

## Session lifecycle
1. Phone writes BLE control opcode `0x03` (START_WIFI_SYNC).
2. Firmware raises SoftAP `KompressorLink` / WPA2 PSK `kompressor-link`
   (static — personal single-user tool [Best estimate: adequate]), IP
   `192.168.4.1`, HTTP on port 80. BLE stays up (same 2.4 GHz radio;
   [Likely] fine for a parked sync session — not designed for while-driving
   use).
3. Phone joins the AP (Android `WifiNetworkSpecifier`) and syncs.
4. Firmware drops the AP after **5 min without any HTTP request**
   (`kWifiIdleOffMs`), or on the next boot.

## Endpoints
### `GET /rides`
`200 application/json`:
`{"rides":[{"name":"ride_00042.csv","size":123456,"crc32":"89ABCDEF"}]}`
- CLOSED rides only — the ride currently being written never appears.
- `crc32` = CRC-32/ISO-HDLC of the full file, 8 uppercase hex chars
  (matches `java.util.zip.CRC32`).
- A ride whose CRC can't be computed is OMITTED from the manifest rather
  than reported with a fabricated checksum (firmware/src/wifi_sync_core.cpp).

### `GET /data?name=<file>&offset=<n>&length=<n>`
`200 application/octet-stream`, body = up to `min(length, 4096)` bytes of
the file starting at `offset`.
- Empty body = at/after EOF.
- `404` = unknown file. `400` = missing/invalid params.

## Client resume rules (implemented app-side)
- Persist per-file `downloadedBytes` after every chunk append; resume from
  it after any disconnection.
- A file is complete when `downloadedBytes == size`; then verify CRC-32 of
  the local bytes against the manifest before ingesting. Mismatch: delete
  local bytes and re-download once; a second mismatch marks the file FAILED.
- Ride names are NEVER reused by the firmware (monotonic counter), so
  `name` is a stable identity key; `size`+`crc32` changing for a known name
  means a firmware-side anomaly — treat as a fresh file.
