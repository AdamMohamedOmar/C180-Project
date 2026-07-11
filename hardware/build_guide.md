# Build guide — bench to car bring-up

Human tasks. Reference `hardware/BOM.md` for parts and `hardware/wiring.md` for
pin assignments. Firmware targets referenced below (`bench_selftest`,
`kline_probe`) are built in later phases (PLAN.md §9) — this guide is written
now so it's ready when they land.

## Stage A — bench power (no car)

- [ ] 1. Solder the power chain on perfboard: input screw terminals → fuse →
      SS34 → TVS across rails → buck module → 5 V to ESP32 VIN.
- [ ] 2. Feed 12 V from a bench supply (or battery charger). Verify
      5.0–5.2 V at ESP32 VIN with a multimeter **before** connecting the ESP32.
- [ ] 3. Add the 100k/10k divider from the protected 12 V node to GPIO34, and
      100 nF to ground. Verify divider output ≈1.09 V at 12.0 V input.

## Stage B — peripherals

- [ ] 4. Wire microSD (SPI: CS=GPIO5, SCK=18, MISO=19, MOSI=23). Flash the
      `bench_selftest` firmware target once it exists (Phase 1/2) — it must
      report SD write/read OK and print the ADC-derived input voltage within
      ±0.2 V of the multimeter reading (calibrate the constant).
- [ ] 5. Wire L9637D: VS=12 V protected rail, GND, K→screw terminal (later
      OBD pin 7), TX→GPIO17, RX→GPIO16.
- [ ] 6. Wire SN65HVD230: 3V3, GND, CANH/CANL→terminals (later pins 6/14),
      RX→GPIO4 (TWAI RX **and** wired to the RTC-capable wake path),
      TX→header pin with **no jumper fitted**.

## Stage C — bench protocol test (still no car)

- [ ] 7. Run `firmware/tools/kline_sim.py` (Phase 1 deliverable) on the PC
      with a USB-UART adapter wired to the L9637D K-line through a 1 kΩ
      pull-up to 12 V — it emulates a KWP2000 ECU (fast-init + 5-baud init,
      Mode 01/03/02 responses with canned M271-plausible data). The firmware
      must complete init, poll the tier schedule, and write a valid ride CSV
      to SD. **This simulator is the single most important de-risking
      artifact in the project.**

## Stage D — car bring-up

- [ ] 8. With everything verified on bench: connect the OBD breakout pigtail
      per `hardware/wiring.md`.
- [ ] 9. First plug-in, engine OFF, ignition ON: firmware probe (`kline_probe`
      target, Phase 3) logs which init succeeded (fast vs 5-baud), the
      supported-PID bitmask (Mode 01 PIDs 0x00/0x20/0x40), attempts Mode 09
      VIN, then idles.
- [ ] 10. Parasitic check per the owner's guide DD.3 procedure: after 35 min
      locked, clamp/multimeter the device feed — must be **< 5 mA**. Fix
      before leaving it plugged in overnight.
