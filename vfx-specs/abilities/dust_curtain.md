# Ability: Dust Curtain

| Field | Value |
|-------|-------|
| Mode | Dust (0) |
| Trigger | **F** while mode==0 (`DustService`) |
| Styles | Burst (0) · Zone (1) — radial sub-panel |
| Burst cost | **5** on look-target hit; miss spends 0 |
| Zone cost | **10** to plant a smoke zone |
| Zone | Radius **6**, lifetime **200** ticks (snapshotted) |
| Burst hit | Blindness 4s + Slowness II 3s on the looked-at living entity |
| Damage | None (control) |
| Stand-in | Java `DustService` particles (white ash, cloud, charge dust, sparks, ground ring) |

## Timeline (burst)

| t | Event |
|--:|-------|
| 0.00 | Spend 5 if look-hit |
| 0.00 | White grit / cloud / sparks + ground static ring at target |
| 0–4s | Blindness / slowness window |

## Timeline (zone)

| t | Event |
|--:|-------|
| 0.00 | Spend 10 · plant zone at looked-at living |
| while live | White haze + sparse sparks in radius 6 |
| 10s | Zone expires |

## Emitters (future)

1. **Ground ring** — disc, cool-white charge dust
2. **Volume cloud** — sphere, white ash + cloud
3. **Charge flecks** — electric spark sparse

## Sound

Extinguish + redstone burnout + phantom flap (burst).

## Cleanup

No permanent blocks. Zone map entry removed on expiry / logout.
