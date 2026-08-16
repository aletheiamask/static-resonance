# Static Resonance

Minecraft **1.20.1 Forge** content mod that adds the **Static Resonance** origin for **Origins (Forge)**.

**Creator:** Pipothai  
**GitHub:** https://github.com/aletheiamask  
**YouTube:** https://www.youtube.com/@Tuna_Sandwich_TOT  

**License: All Rights Reserved.** You may play this mod. You may not copy, reupload, rebrand, or ship it in another project without written permission from Pipothai. See `LICENSE`.

Gameplay is **data-driven** (Apoli/Origins JSON + mcfunctions). Java is a thin shell so 3D VFX and radial UI can be added later without rewriting the origin.

Design sources (also under `docs/`):

- **ความสามารถที่มีจริงตอนนี้ (Static Resonance)**  
  - EN (AI / dev source of truth) → `docs/AI_ABILITIES_CATALOG.md`  
  - TH (สรุปสกิลอ่านง่าย) → `docs/AI_ABILITIES_CATALOG_TH.md`
- Mode system, G/R philosophy → `docs/Static_Resonance.md`
- Folder architecture → `docs/architecture.md`
- VFX / AI rules → `docs/ai-knowledge-base.md` + `vfx-specs/`

---

## Requirements

| Component | Version |
|-----------|---------|
| Minecraft | 1.20.1 |
| Forge | 47.x |
| Origins (Forge) | 1.10.x (e.g. CurseForge project `origins-forge` 474438) |
| Java (build) | 17 |

---

## Controls

| Action | Key | Behavior |
|--------|-----|----------|
| **Mode radial** | **Hold R** | Open skill wheel → aim → release to select |
| **Skill options** | **RMB** on wheel | Sub-wheel (Faraday emergency / Control style) |
| **Toggle option** | **LMB** in sub | Green = ON · black = OFF · center = back |
| **Cycle mode** | **Tap R** | Dust → Sense → Control → Aegis → Dust |
| **Primary ability** | **G** (Primary Active) | Cast the skill of the current mode |
| **Static Climb** | **Secondary Active** | Pay **10 Charge once** → climb walls until cancel (no drain). Press again to stop (free). Bind in Controls → Origins if unbound. |

| Mode | Power ID | Skill (on wheel) | Cost | Cooldown (ticks) |
|------|----------|------------------|-----:|-----------------:|
| Dust | `mode/dust_curtain` | Dust Curtain | 20 | 25 |
| Sense | `mode/static_scan` | Static Scan (toggle expand) | per ring† | 8 |
| Control | `mode/magnetic_lock` | Magnetic Lock (target / field) | 28* / 40‡ | 30 |
| Aegis | `mode/faraday_cage` | Faraday Cage | 50 / borrow | — |

\* Target lock spends Charge **only if** a target is in range (miss = no cost).  
‡ Field: **40** immediately; expands to **30** blocks; lasts **~60s** while standing still; move = collapse; G again = stop.  
† Static Scan: G start/stop; radius +1 every 5 ticks until stop / Charge empty (soft cap 128); cost 1/2/3/4 by band; colored glow by threat/HP.

**Charge** (0–100): `charge_tick` regen while dry / drain when wet; `motion_charge` gains from nearby moving life (≤5 blocks); wet = +25% damage taken.

Hold-R radial UI: `vfx-specs/ui/radial_mode_menu.md`.

---

## Build

```bat
cd Desktop\static-resonance
gradlew.bat build
```

Output jar: `build/libs/static_resonance-1.0.0.jar`

### Dev run

```bat
gradlew.bat runClient
```

Origins-Forge is pulled via CurseMaven (`curse.maven:origins-forge-474438:5311982`). If resolution fails, download Origins-Forge for 1.20.1 from CurseForge and drop it into `run/mods`.

### Compile-time Apoli API (`libs/`)

Origins jars Apoli/Calio as jar-in-jar. For Java compile (`ModeService`) we use extracted jars under `libs/`:

- `libs/apoli-forge-1.20.1-2.9.0.7.jar`
- `libs/calio-forge-1.20.1-1.11.0.4.jar`

Runtime still comes from Origins. After bumping the Origins file id, re-extract nested jars from the mapped Origins jar (`META-INF/jarjar/`) into `libs/` and align `build.gradle` `compileOnly files(...)`.

---

## Install (play)

1. Install Forge 1.20.1  
2. Install **Origins (Forge)** for 1.20.1  
3. Drop `static_resonance-*.jar` into `mods/`  
4. Create world → choose **Static Resonance**  

---

## Project layout

```
static-resonance/
  src/main/java/...                 thin @Mod entry
  src/main/resources/data/
    static_resonance/
      origins/                      origin definition
      powers/
        systems/                    charge, mode, charge_tick
        mode/                       one power per mode ability
        active/                     switch_mode, no_charge_feedback
        passive/ weakness/
      functions/                    dust, sense, control, overcharge, mode, util
      tags/
  vfx-specs/                        AI-readable 3D/UI designs (no runtime)
  docs/
```

Power IDs: `static_resonance:mode/dust_curtain`, `static_resonance:active/switch_mode`, …

Combat functions tag the caster as `sr_caster` for damage attribution (no `@p`). No teleport CC.

---

## Smoke test

- [ ] Mod + Origins load without errors  
- [ ] Origin appears in chooser  
- [ ] Charge bar visible; regenerates on land; drains in water  
- [ ] Secondary cycles four mode names on actionbar  
- [ ] Primary casts different skill per mode (separate CDs)  
- [ ] Low charge shows “Insufficient Charge”  
- [ ] Magnetic Lock miss does not spend charge  
- [ ] Multiplayer: damage credit stays on caster  

---

## Phase 2 (not implemented)

- 3D meshes / beams / trails (`vfx-specs/abilities/*`)  
- ~~Hold-R radial UI~~ (done)
- Full upgrade tree (Dust Storm, Chain Shock, …)  
- Hostile vs PvP target tags cleanup  
- More origins under the same shared systems

---

© 2026 Pipothai. All Rights Reserved.  
