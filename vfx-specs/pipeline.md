# Effect Pipeline

```
Input (Primary / Secondary key)
  → Conditions (mode, charge, cooldown, wet)
  → Consume resources
  → Play cast animation (future)
  → Spawn VFX
  → Play sound
  → Deal damage / apply status
  → Cleanup / despawn emitters
```

## Rules

1. **Gameplay first** — VFX never decides hit/miss; hit detection stays server-side.
2. **One pipeline per ability** — no duplicated hit logic in client FX.
3. **Budget** — prefer short-lived emitters; cap particles for multiplayer.
4. **Replace, don’t stack** — when 3D ships, disable or reduce vanilla stand-ins.
5. **Mode color** must match HUD identity (see `modes/`).

## Standard timeline slots

| t (s) | Slot |
|------:|------|
| 0.00 | Spawn / cast start |
| 0.05–0.15 | Charge windup |
| 0.15–0.30 | Flash / impact start |
| 0.30–0.60 | Main body (beam, cloud, lock) |
| 0.60–0.90 | Damage window (if any) |
| 0.90–1.20 | Decay / cleanup |

Not every ability uses every slot.
