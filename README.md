# Static Resonance

Minecraft **1.20.1 Forge** content mod that adds the **Static Resonance** origin for **Origins (Forge)**.

**Creator:** Pipothai  
**GitHub:** https://github.com/aletheiamask/static-resonance  
**YouTube:** https://www.youtube.com/@Tuna_Sandwich_TOT  

**License: All Rights Reserved.** You may play this mod. You may not copy, reupload, rebrand, or ship it in another project without written permission from Pipothai. See `LICENSE`.

Gameplay is a **hybrid**: Origins/Apoli for mode gates and resource bars, **Java services** for spend/apply so charge cannot desync. The skill wheel and outlines are Java.

เอกสารอยู่ที่ [`docs/README.md`](docs/README.md) — เริ่มจากแคตตาล็อกสกิล ถ้าขัดกับเอกสารออกแบบให้เชื่อแคตตาล็อก  
ก่อนขึ้นเลขเวอร์ชันใช้ [`docs/PLAYTEST.md`](docs/PLAYTEST.md)  
สเปกวงล้อและ VFX อยู่ที่ `vfx-specs/`

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
| **Mode radial** | **Hold R** | Open 5-wedge wheel → aim → release to select |
| **Skill options** | **LMB** on Faraday / Control / Dust / Sense | Open sub-panel (emergency, style, filters) |
| **Toggle option** | **LMB** in sub | Green = ON · black = OFF · center = back |
| **Cycle mode** | **Tap R** | Dust → Sense → Control → Aegis → Circuit → Dust |
| **Use skill** | **F** | Cast the skill of the current mode |
| **Wall climb** | (always on) | Slide on a wall to climb. No charge. |

| Mode | Skill | Cost | Notes |
|------|-------|-----:|-------|
| Dust | Dust Curtain | 5 / 10 | Burst look-hit / smoke zone from the sub-panel |
| Sense | Static Scan | 8 | F start/stop; one 32-block pulse (source default; world toml may differ); no per-tick drain |
| Control | Magnetic Lock | 15* / 40‡ | Target miss spends nothing |
| Aegis | Faraday Cage | 50 | Emergency can raise for free if a hit would drop below the floor |
| Circuit | Circuit Flip | 8 | F flips the looked-at redstone piece for 8s. No world scan. |

\* Target lock spends Charge **only if** a living target is hit.  
‡ Field: **40** immediately; expands to **30** blocks; lasts **~60s** while standing still; move = collapse; F again = stop.

**Charge** (0–100): Java regen while dry / drain when wet; motion harvest from nearby moving life (≤5 blocks default); wet = +25% damage taken. Numbers live in `config/static_resonance-common.toml`. With Cloth Config, Mods → Static Resonance → Config edits them in-game.

Hold-R radial UI: `vfx-specs/ui/radial_mode_menu.md`.

---

## Build

```bat
cd Desktop\static-resonance
gradlew.bat build
```

Output jar: `build/libs/static_resonance-1.0.18.jar`

Formula unit tests (no Minecraft boot):

```bat
gradlew.bat test
```

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
  src/main/java/com/staticresonance/
    mode/     spend/apply authority (Faraday, Control, Scan, Circuit, Climb)
    logic/    pure formulas (tested)
    client/   radial, outlines
    network/  F-cast + mode/style packets
  src/main/resources/data/static_resonance/
    origins/  origin definition
    powers/   resources, passives, weakness, leftover mode JSON
    functions/  one-shot FX
    tags/
  docs/       catalog (source of truth), PLAYTEST, architecture
  vfx-specs/  AI-readable 3D/UI designs (no runtime)
```

Combat functions do not use `@p` for damage credit.

---

## Playtest

Use [`docs/PLAYTEST.md`](docs/PLAYTEST.md) before bumping the version. Short version:

- [ ] Origin chooser + F casts the selected mode  
- [ ] Charge regenerates dry, drains wet  
- [ ] Radial LMB does not spend charge  
- [ ] Faraday farm feel + emergency without a cage up  
- [ ] Scan in a dense area then unload a marked mob  
- [ ] Circuit toggle, walk away, wait for expiry  
- [ ] Wall climb on a dedicated server (no rubber-band)

---

## Phase 2 (not implemented)

- 3D meshes / beams / trails (`vfx-specs/abilities/*`)  
- Full upgrade tree (Dust Storm, Chain Shock, …)  
- Hostile vs PvP target tags cleanup  
- More origins under the same shared systems

---

© 2026 Pipothai. All Rights Reserved.
