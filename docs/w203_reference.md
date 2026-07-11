# W203 C180 Kompressor (2004, M271.946) — Reference Data

Source: owner's workshop guide for this specific car (see PLAN.md §8). Confirm
any safety-critical value against WIS for the VIN before acting on it.

## Healthy bands

| Signal | Healthy band | Confidence |
|---|---|---|
| MAF (warm idle) | 3–5 g/s | Confirmed |
| Battery, resting | ≥ 12.5 V | Confirmed |
| Battery, charging | 13.8–14.5 V | Confirmed |
| Battery, cranking | ≥ 9.6 V | Confirmed |
| Thermostat opening | 87 °C | Confirmed |
| Boost, peak | ≈ 0.4–0.55 bar | Best estimate |
| Fuel trims (STFT/LTFT) | ±10 % | Confirmed |
| Fuel rail pressure | ≈ 3.8 bar | Best estimate (not OBD-visible on this ECU — reference only) |
| Ignition coil primary resistance | 0.4–1.0 Ω | Best estimate |
| Injector resistance | 12–16 Ω | Best estimate |

## DTC interpretation layer (W203-specific likely causes)

| DTC family | Likely cause, in order | Notes |
|---|---|---|
| P0170 / P0171 | Partial-load breather hoses (**the** M271 fault) → MAF → fuel pressure | Check breather hoses first, every time |
| P0016 / P0017 | Timing chain stretch / cam-magnet | |
| P0010–P0014 | Cam adjuster magnet, oil ingress | |
| P0300 family | Coils / plugs | Correct plug: FR6MPP332 or NGK ILFR6A |
| P0420 | Verify with the in-app cat-health snapshot test before condemning the catalyst | See PLAN.md §6.2 guided test |
| Cooling-system codes | Split diagnosis: fan-side vs. flow-side | |

## Caveat

These are reference values from the owner's guide project for this specific
VIN/spec, not a substitute for the factory workshop manual. Confirm
safety-critical values (torque specs, clearances, electrical limits) against
WIS before acting on them.
