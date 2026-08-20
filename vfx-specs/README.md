# VFX Specs — Static Resonance (AI handoff)

This folder is **design-only** for a future 3D / advanced effect pass.

- **Do not** mix VFX implementation into Origins JSON gameplay logic.
- Phase 1 uses **vanilla particles + sounds** as stand-ins.
- When implementing 3D, **replace** the stand-ins listed under each ability’s “Phase 1 stand-in” section.

## Stack roles

| Layer | Owns |
|-------|------|
| Origins / Apoli | When ability fires, costs, conditions |
| Minecraft | Damage, effects, entities |
| VFX (this folder → future code) | Mesh, beam, trail, light, camera, custom particles |

## Files

| Path | Purpose |
|------|---------|
| `pipeline.md` | Shared effect pipeline + timeline rules |
| `coordinate-system.md` | Vectors, attach points |
| `modes/*.md` | Palette and identity per mode |
| `abilities/*.md` | One ability = full timeline + emitters |
| `ui/radial_mode_menu.md` | Hold-R radial skill wheel (implemented) |

## Implementation order (later)

1. Mode switch feedback (short, readable)
2. Dust Curtain volume
3. Static Scan pulse ring
4. Magnetic Lock chain / tether
5. Pulse Explosion shockwave + spark burst (design-only — not a live cast)
6. Radial UI
