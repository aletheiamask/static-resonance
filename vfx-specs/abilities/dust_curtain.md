# Ability: Dust Curtain

| Field | Value |
|-------|-------|
| Mode | Dust (0) |
| Trigger | Primary active while mode==0 |
| Charge cost | 20 |
| Cooldown | Primary shared ~1s (+ ability feel) |
| Duration | ~0.8–1.2s cloud presence; status 3–4s |
| Radius | ~7 blocks |
| Hit | AoE status on `#static_resonance:affectable` |
| Damage | None (control) |
| Phase 1 stand-in | `functions/dust/curtain.mcfunction` particles sand/cloud/crit |

## Timeline

| t | Event |
|--:|-------|
| 0.00 | Spawn low dust ring at feet |
| 0.10 | Expand charged grit sphere |
| 0.25 | Peak density; apply blindness/slowness window |
| 0.60 | Density falls; residual floaters |
| 1.00 | Cleanup emitters |

## Emitters (future)

1. **Ground ring** — disc, brown dust, lifetime 0.6s  
2. **Volume cloud** — sphere r=2→5, noise turbulence  
3. **Charge flecks** — yellow sparkles sparse  

## Sound

- Sand break + dry whoosh (phase 1: sand.break + phantom.flap)

## Cleanup

Despawn all emitters; leave no permanent blocks.
