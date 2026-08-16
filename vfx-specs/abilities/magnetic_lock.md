# Ability: Magnetic Lock

| Field | Value |
|-------|-------|
| Mode | Control (2) |
| Styles | Target (0) · Field (1) — radial sub-wheel |
| Target cost | 28 Charge on hit only |
| Field cost | **40 Charge immediately** |
| Target range | ~10 blocks, nearest affectable |
| Field max radius | **30** blocks, expands +1 / 5 ticks |
| Field duration | **60s** while standing still |
| Field abort | Move · mode change · G again · timeout |

## Target (style 0)

| t | Event |
|--:|-------|
| 0.00 | Spend 28 if hit |
| 0.05 | Head ring + spiral suck-down |
| 0–5s | Slowness 255 · mining fatigue II · glowing · Motion pull-down |

## Field (style 1)

| t | Event |
|--:|-------|
| 0.00 | Spend 40 · anchor position · sonic boom |
| 0–7.5s | Radius expands 0→30 |
| while active | Lock each `#affectable` in radius once; refresh CC |
| edge | Purple dust / witch ring at current radius |
| move | Instant collapse |
| 60s / G | End field |

## VFX on locked target

1. **Head ring** — purple dust circle above head  
2. **Spiral suck** — 3 layers downward  
3. **Ground seal** — end_rod + reverse_portal at feet  

## Implementation

- Java: `ControlService` (field session + applyLock particles)
- Functions: `control/lock_hit`, `control/lock_apply`, `control/lock_miss`
- Power: `mode/magnetic_lock` branches on `systems/control_style`
