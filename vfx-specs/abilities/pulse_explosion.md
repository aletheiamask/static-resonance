# Ability: Pulse Explosion

**Not shipped.** Design-only. Overcharge / Aegis live cast is Faraday Cage. There is no `powers/mode/pulse_explosion.json` and no `functions/overcharge/pulse.mcfunction`.

| Field | Value |
|-------|-------|
| Mode | Was Overcharge (3) — **not a live cast** |
| Trigger | — |
| Charge cost | 45 (design only) |
| Radius | ~5.5 blocks |
| Hit | Magic damage ~8 + knockback-ish + slowness |
| Self risk | Weakness + hunger brief |

## Timeline

| t | Event |
|--:|-------|
| 0.00 | White core flash at chest |
| 0.08 | Shockwave sphere expands |
| 0.15 | Spark storm peak |
| 0.20 | Damage window |
| 0.45 | Residual arcs |
| 0.80 | Cleanup; dim aura |

## Emitters (future)

1. **Core flash** — 1 frame billboard  
2. **Shockwave mesh** — expanding sphere, electric rim  
3. **Radial sparks** — outward velocity  
4. **Optional** light point 0.3s  

## Sound

Thunder + soft explode (already phase 1)

## Cleanup

No lingering fire; no blocks broken.
