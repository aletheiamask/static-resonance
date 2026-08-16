# Ability: Static Scan

| Field | Value |
|-------|-------|
| Mode | Sense (1) |
| Trigger | Primary toggle while mode==1 (G start / G stop) |
| Charge cost | Per ring: 1 / 2 / 3 / 4 by band (band 4 continues past r=12) |
| Radius | Expands 1 block / 5 ticks from player until **G stop**, **mode change**, or **Charge empty** (resource soft cap 128) |
| Hit | Client-only white outline on new shell (scanner’s game only); no team hijack; no HP dust |
| Damage | None |
| Phase 1 stand-in | `functions/sense/scan_start\|step\|pulse\|stop\|color_entity` |

## Timeline (expanding)

| t | Event |
|--:|-------|
| 0.00 | Start ping · scan_active |
| +0.25s each | Radius +1 · cyan rim torus · shell color-mark (new entities only) |
| Cancel | G again / out of charge / leave Sense mode |

## Emitters (future)

1. **Scan torus** — expands with radius, cyan, additive  
2. **Entity mark** — brief ring over each hit entity  
3. **Conductive sparkle** — only on `#static_resonance:conductive` blocks in range  

## Sound

Beacon activate + soft tick

**Player contact ping** (once per target per scan session):  
`note_block.bell` + `amethyst_block.chime` played **at the target’s position** for the scanner only → directional stereo (left/right by facing). Tag `sr_scan_pinged` prevents spam; cleared on `scan_stop`.

## Cleanup

Remove client outlines if custom; glowing status expires naturally.
