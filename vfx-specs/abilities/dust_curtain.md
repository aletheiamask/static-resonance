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
| Stand-in | Java `DustService` particles (sand block dust, cloud, crit) |

## Timeline (burst)

| t | Event |
|--:|-------|
| 0.00 | Spend 5 if look-hit |
| 0.00 | Sand/cloud/crit at target |
| 0–4s | Blindness / slowness window |

## Timeline (zone)

| t | Event |
|--:|-------|
| 0.00 | Spend 10 · plant zone at looked-at living |
| while live | Smoke in radius 6 |
| 10s | Zone expires |

## Emitters (future)

1. **Ground ring** — disc, brown dust, lifetime 0.6s
2. **Volume cloud** — sphere, noise turbulence
3. **Charge flecks** — yellow sparkles sparse

## Sound

Sand break + phantom flap (burst).

## Cleanup

No permanent blocks. Zone map entry removed on expiry / logout.
