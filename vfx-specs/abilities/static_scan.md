# Ability: Static Scan

| Field | Value |
|-------|-------|
| Mode | Sense (1) |
| Trigger | **F** toggle while mode==1 (`ScanOutlineService.toggle`) |
| Charge cost | **8** once on start (`GameplayDefaults.SCAN_COST`). No per-tick drain |
| Radius | One server AABB pulse, default **32** (`GameplayDefaults.SCAN_RADIUS`, config 1–128). A world’s toml wins if it differs |
| Cap | **128** marks |
| Hit | Private see-through boxes for the scanner: green safe, white players, red hostiles |
| Damage | None |
| Stand-in | Java outlines + `functions/sense/scan_start` / `scan_stop` FX |

## Timeline (one-shot)

| t | Event |
|--:|-------|
| 0.00 | Spend 8 · pulse AABB · S2C marks |
| 0–1s | Client-only reveal 0→radius |
| Hold | Boxes stay until F again or leave Sense |
| Stop | `scan_stop` FX · clear marks |

## Emitters (future)

1. **Scan torus** — cyan rim at pulse radius, additive
2. **Entity mark** — brief ring over each hit entity

## Sound

Beacon activate on start; beacon deactivate on stop.

## Cleanup

Client outlines clear on stop / logout / leave Sense.
