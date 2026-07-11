# Wiring — OBD-II socket and ESP32 GPIO map

## OBD-II socket (J1962, [Confirmed] for W203)

| OBD pin | Signal | Connects to |
|---|---|---|
| 16 | +12 V permanent (Terminal 30) | Fuse → reverse diode (SS34) → TVS → buck Vin; also divider → ESP32 ADC (GPIO34) |
| 4, 5 | Chassis / signal ground | Common ground |
| 7 | K-line | L9637D K pin; L9637D TX/RX → ESP32 UART2 |
| 6, 14 | CAN-C High / Low (500 kbps, MB-proprietary) | SN65HVD230 H/L; RX → ESP32 TWAI RX; **TX jumper OPEN, always** |

## ESP32 GPIO map

| GPIO | Function | Source |
|---|---|---|
| 34 (ADC1, input-only) | Battery voltage sense (through 100k/10k divider) | Correct pin choice — GPIO34 is ADC1-capable and input-only |
| 17 | UART2 TX → L9637D TX | K-line |
| 16 | UART2 RX ← L9637D RX | K-line |
| 4 | TWAI (CAN) RX ← SN65HVD230 RX; also the RTC-capable wake pin for CAN-activity wake | GPIO4 is RTC-IO-capable on classic ESP32 — consistent with the wake design in PLAN.md §5.1 |
| — | TWAI TX | **Not wired.** Jumper physically open — safety requirement, not just a firmware setting |
| 5 | SD card SPI CS | |
| 18 | SD card SPI SCK | |
| 19 | SD card SPI MISO | |
| 23 | SD card SPI MOSI | |

## Notes

- 100 nF ceramic cap from the ADC34 divider node to ground, close to the pin,
  to tame injected noise from the buck converter.
- Fuse is the *first* component in the 12 V chain from OBD pin 16 — nothing
  upstream of it.
