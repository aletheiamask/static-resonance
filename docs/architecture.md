# Origins Architecture Guide (2026)

## Goals

- Scalable
- Reusable
- Modular
- Git-friendly
- DRY
- Separation of Concerns

## Static Resonance — live implementation notes (2026-08-09)

This mod is a **hybrid**: Origins/Apoli datapack for mode gates + some AoE, **Java services** for authority on spend/apply where dual costs would desync.

| Concern | Canonical owner |
|---------|-----------------|
| Mode index | Apoli `systems/mode` via `ModeService` |
| Charge 0–100 | Apoli `systems/charge` (min **0**, never negative) |
| Mode select UX | Client `RadialModeMenu` — **select only**, free |
| Cast / spend | G (Origins) or Java (`ControlService`, `FaradayService`) |
| Magnetic lock spend+hit | **`ControlService.lockNearestTarget` only** |
| Control field session | **`ControlService` SESSIONS map** |
| Faraday repel | **`FaradayService.ACTIVE_CAGES`** + score `sr_faraday` |

**Do not** spend charge in Origins JSON and again in Java for the same skill.

Full ability truth: [`AI_ABILITIES_CATALOG.md`](./AI_ABILITIES_CATALOG.md)

### Package layout (this mod)

```
com.staticresonance
  client/ui/     Radial (select-only wheel + sub-panels)
  mode/          ModeService, ControlService, FaradayService, ApoliResources
  network/       C2S packets (mode, style, faraday emergency)
  command/       sr_internal (permission 2 for Apoli execute_command)
```

### Datapack layout (this mod)

```
data/static_resonance/
  origins/
  powers/   systems/ · mode/ · active/ · passive/ · weakness/
  functions/  faraday/ · control/ · dust/ · sense/ · mode/ · systems/
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
