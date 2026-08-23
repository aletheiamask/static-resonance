# Origins Architecture Guide (2026)

## Goals

- Scalable
- Reusable
- Modular
- Git-friendly
- DRY
- Separation of Concerns

## Static Resonance — live implementation notes (2026-08-23)

This mod is a **hybrid**: Origins/Apoli datapack for mode gates + some AoE, **Java services** for authority on spend/apply where dual costs would desync.

**One clock per concern.** Mirrors exist for HUD / datapack / relog. Do not tick a mirror every server tick.

| Concern | Canonical owner | Mirror (not the clock) |
|---------|-----------------|------------------------|
| Mode index | Apoli `systems/mode` via `ModeService` | — |
| Charge 0–100 | Apoli `systems/charge` (min **0**, never negative) | `sr_mc` motion-source count |
| Mode select UX | Client `RadialModeMenu` — **select only**, free | — |
| Cast / spend | **F** → `CastPacket` → `CastService` | — |
| Magnetic lock spend+hit | **`ControlService.lockLookTarget` only** | — |
| Control field session | **`ControlService.SESSIONS`** | `systems/field_active` |
| Faraday cage | `ACTIVE_CAGES` + `CAGE_EXPIRE_AT` | leftover `sr_faraday` |
| Faraday cooldown | `COOLDOWNS` + `CD_EXPIRE_AT` | leftover `sr_faraday_cd` |
| Scan marks | `ScanOutlineService.MARKED` | `systems/scan_active` |
| Circuit force-toggle | `INTERFERENCES.expireGameTime` | — (F raycast + one pulse; later notifies canceled) |
| Wall climb | `ClimbService` (always-on, dual-side) | — |

**Do not** spend charge in Origins JSON and again in Java for the same skill. Write leftover scores on arm / expiry / HUD interval only.

Full ability truth: [`AI_ABILITIES_CATALOG.md`](./AI_ABILITIES_CATALOG.md)

### Package layout (this mod)

```
com.staticresonance
  client/        Scan overlay (ScanOutlineClient, ScanBoxRenderer, ShaderPacks)
  client/ui/     Radial (select-only wheel + sub-panels)
  mode/          ModeService, CastService, ControlService, FaradayService, ApoliResources
  logic/         Pure formulas (charge buckets, expire clock, scan prune, gameplay defaults)
  network/       C2S/S2C (cast F, mode, style, outlines)
  command/       sr_internal (permission 2 for Apoli execute_command)
```

### Datapack layout (this mod)

```
data/static_resonance/
  origins/
  powers/   systems/ · passive/ · weakness/
  functions/  faraday/ · sense/ · mode/
  tags/
```

---

## Layer Architecture (generic Origins)

1. origins/ — Origin definitions only (name, icon, power list)

2. powers/ — Business logic by feature:
   - passive/ · active/ · combat/ · movement/ · defense/ · weakness/

3. systems/ — Shared gameplay systems (charge, cooldown, etc.)

4. actions/ · conditions/ · predicates/ · resources/ · damage_types/

5. functions/ — Long mcfunctions

6. tags/ · assets/

## Dependency

Minecraft → Shared Library → Core Systems → Feature Modules → Origins

## Naming Convention

`feature/category/name`  
Examples: `static/active/discharge` · Avoid: `power1`, `newpower`

## Best Practices

- Origins only reference powers.
- One power = one responsibility.
- Reuse systems; prefer **one authority** for spend/apply (Java or JSON, not both).
- Use tags instead of hardcoding entity lists when the list is shared.
- Keep long particle/timeline logic in mcfunctions or dedicated Java VFX helpers.
- Radial/menu UX must not silently cast costly skills (select ≠ activate).
