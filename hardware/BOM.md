# Bill of Materials — KompressorLink logger (module-based, no custom PCB)

| # | Part | Purpose | Notes |
|---|---|---|---|
| 1 | ESP32-WROOM-32 DevKitC | MCU: TWAI(CAN) + BLE + WiFi + ULP deep sleep | Classic ESP32, NOT S3/C3 — widest lib support, BT Classic fallback |
| 2 | L9637D (SO-8 breakout or DIP adapter) | K-line transceiver (ISO 9141/14230 PHY) | Standard hobby K-line PHY |
| 3 | SN65HVD230 breakout | CAN transceiver, CAN-C listen-only | TX pin left DISCONNECTED via jumper |
| 4 | Buck converter, Vin ≥ 40 V rated (e.g. MP9486A / LM2596HV-based), 12 V→5 V | Power | Automotive transients need headroom; avoid cheap 28 V-max MP1584 boards |
| 5 | TVS diode SMBJ33CA (or 5KP33A for more margin) | Load-dump clamp on 12 V input | |
| 6 | 1 A blade/inline fuse + holder | Input protection | FIRST in line from OBD pin 16 |
| 7 | Schottky diode SS34 (or P-FET) | Reverse-polarity protection | |
| 8 | microSD SPI breakout + 8–32 GB card (A1 class) | Ride logging | |
| 9 | OBD-II male plug, breakout screw terminals (J1962M) | Car connection | Buy the pigtail/breakout type |
| 10 | Voltage divider 100 kΩ / 10 kΩ + 100 nF | Battery-voltage sense into ADC (12 V → ≈1.1 V) | High values = negligible drain |
| 11 | Perfboard, DuPont/JST wire, small ABS enclosure | Assembly | Must fit in driver footwell by OBD socket |
| 12 | (Optional) DS3231 RTC module | Accurate timestamps without phone | ESP32 RTC drifts minutes/day; phone time-sync makes this optional |

Rough cost: ≈£20–30 in parts. Tools assumed: soldering iron, multimeter.
