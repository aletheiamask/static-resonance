# Ability: Magnetic Lock

| Field | Value |
|-------|-------|
| Mode | Control (2) |
| Styles | Target (0) · Field (1) — radial sub-wheel |
| Target cost | **15** Charge on hit only (`GameplayDefaults.CONTROL_TARGET_COST`) |
| Field cost | **40** Charge immediately |
| Target range | look / visible living up to **64** blocks |
| Field max radius | **30** blocks, expands while standing still |
| Field duration | **60s** (1200 ticks) while standing still |
| Field abort | Move · mode change · **F** again · timeout |

## Target (style 0)

| t | Event |
|--:|-------|
| 0.00 | Spend 15 if hit |
| 0.05 | Head ring + spiral suck-down |
| 0–5s | Slowness 255 · mining fatigue II · glowing · Motion pull-down · held weapon frozen |

## Field (style 1)

| t | Event |
|--:|-------|
| 0.00 | Spend 40 · anchor position |
| while active | Radius expands toward 30; lock living in radius |
| move | Instant collapse |
| 60s / F | End field |

## VFX on locked target

1. **Head ring** — purple dust circle above head
2. **Spiral suck** — 3 layers downward
3. **Ground seal** — end_rod + reverse_portal at feet

## Implementation

- Java: `ControlService` (field session + applyLock particles)
- No datapack `functions/control/` — spend/apply is Java only
