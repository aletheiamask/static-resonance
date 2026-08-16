# Ability: Faraday Cage

| Field | Value |
|-------|-------|
| Mode | Aegis / Overcharge (3) |
| Trigger | Primary G while mode==3 · or emergency auto |
| Charge cost | 50, or borrow shortfall as debt |
| Duration | 90s lattice |
| Shared CD | 120s after cage ends |
| Phase 1 | `functions/faraday/*` + vanilla particles |

## Emergency

- `prevent_death` → activate + sci-fi titles
- HP ≤ 5 after hit → activate
- Toggle from radial sub-panel (resource `systems/faraday_emergency`)

## Timeline

| t | Event |
|--:|-------|
| 0.00 | Flash + spark lattice expand |
| 0–90s | Resistance V, projectile hold ~1.5–2s then drop |
| idle | Geometric cage particles (see emitters) |
| end | Lattice shatter, 120s re-arm CD |

## Emitters (idle lattice)

Phase-1 stand-in is a 3-emitter silhouette (`functions/faraday/particles.mcfunction`):

1. **Ground seal** — low `end_rod` disk  
2. **Body lattice** — `electric_spark` around torso  
3. **Apex** — `glow` at head  

Open/close keep flash + thunder / glass. Future 3D cage can replace the idle trio.

## Sound

Thunder high + beacon activate · emergency adds elder guardian sting

## UI

Emergency arm toggle: radial **sub-wheel** (RMB on Faraday) · green ON / black OFF
