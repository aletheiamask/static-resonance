# Static Resonance — Ability Catalog (AI Source of Truth)

> **Purpose:** What this Origin **actually implements right now**.  
> **Update this file** whenever you add/change/remove powers, Java services, or keybind UX.  
> Do **not** invent skills from design docs alone — match this list first.  
>  
> Thai player-facing summary: [`AI_ABILITIES_CATALOG_TH.md`](./AI_ABILITIES_CATALOG_TH.md)

| Field | Value |
|-------|--------|
| Mod | `static_resonance` |
| MC / Loader | 1.20.1 Forge |
| Depends | Origins (Forge) + Apoli |
| Origin ID | `static_resonance:static_resonance` |
| Catalog version | **2.2.0** |
| Last updated | **2026-08-16** |
| Status | Playable: 4 modes + Faraday Cage + Control field/target + select-only radial |

---

## Control scheme

| Input | Key | Behavior |
|-------|-----|----------|
| Primary cast | **G** (`key.origins.primary_active`) | Cast **current mode** skill only |
| Mode radial | **Hold R** (`key.static_resonance.mode_menu`) | Open 5-wedge wheel |
| Mode cycle | **Tap R** | Cycle mode 0→1→2→3→0 |
### Radial UX (authoritative)

| Action | Result |
|--------|--------|
| **LMB** on combat wedge | **Select mode only** (`SetModePacket`) — never spends charge, never casts |
| **LMB** on Faraday / Control | Select mode + open **sub-panel** (emergency / target·field) |
| **Release R** (no prior LMB commit) | Select hovered combat mode |
| **G** | Cast skill of current mode |
| Grey wedges by cast cost? | **No** — radial is select-only; cost is for G |

Java: `RadialModeMenu`, `RadialModeMenuRenderer`, `RadialWedge`  
Packets: `SetModePacket`, `SetControlStylePacket`, `ToggleFaradayEmergencyPacket`

While radial is open: mouse attack/use cancelled (`InteractionKeyMappingTriggered`).

---

## Mode index

Resource: `static_resonance:systems/mode` · **0–3**, start **0**, HUD hidden

| Index | Mode (lang) | Primary power | Radial note |
|------:|-------------|---------------|-------------|
| 0 | Dust | `mode/dust_curtain` | LMB selects immediately |
| 1 | Sense | `mode/static_scan` | LMB selects immediately |
| 2 | Control | `mode/magnetic_lock` | LMB → sub: Single Target / Field |
| 3 | Overcharge / **Aegis** | `mode/faraday_cage` | LMB → sub: Faraday emergency arm |

**Not live as mode-3 cast:** `mode/pulse_explosion` (stub empty JSON kept for old refs).

---

## Resource: Static Charge

| Field | Value |
|-------|--------|
| Power ID | `static_resonance:systems/charge` |
| Range | **0–100** (never negative) |
| Start | 40 |
| HUD | **yes** (only resource bar intended to show) |

Mode skill powers use `"hud_render": { "should_render": false }` so Origins bars do not stack.

### Charge tick

| Field | Value |
|-------|--------|
| Power ID | `systems/charge_tick` |
| Interval | 35 ticks |
| Wet | charge **−3** + bubbles |
| Dry + not full | charge **+2** |

### Motion charge

| Field | Value |
|-------|--------|
| Power ID | `systems/motion_charge` |
| Interval | 20 ticks |
| Range | `#motion_sources`, ~0.5–5 |
| Reward | +1 or +2 charge from nearby moving life |

### Other systems resources

| Power ID | Role |
|----------|------|
| `systems/scan_active` / `scan_radius` / `scan_step_*` / `scan_abort` | Expanding Static Scan |
| `systems/control_style` | 0 = target, 1 = field |
| `systems/field_active` | Mirror of Java field session |
| `systems/faraday_emergency` | 0/1 auto Faraday if a hit would leave HP below 2 |

---

## Active abilities (implemented)

### 1. Mode select (radial + tap)

| Field | Value |
|-------|--------|
| Free | **Yes** — changing mode never spends charge |
| Server | `ModeService.setMode` → Apoli mode + `function static_resonance:mode/{dust,sense,control,overcharge}` feedback only |
| Legacy | `active/switch_mode.json` **not on origin** |

---

### 2. Dust Curtain (mode 0)

| Field | Value |
|-------|--------|
| Power ID | `mode/dust_curtain` |
| Cost | **20** on cast |
| Cooldown | 25 ticks |
| Function | `dust/curtain` |
| Targets | `#static_resonance:affectable` ~7 blocks |
| Effects | Blindness 4s, Slowness II 3s |

---

### 3. Static Scan (mode 1) — one-shot toggle

| Field | Value |
|-------|--------|
| Power ID | `mode/static_scan` (cast via Java `ScanOutlineService.toggle`) |
| Cost | **8** once on start (Java). No per-tick drain |
| Toggle | F start / F stop; abort on leaving Sense |
| Radius | **32** — one server AABB pulse |
| Expand | Client-only reveal 0→32 over 1s |
| Reveal | Private see-through boxes: **green** safe, **white** players, **red** hostiles |

---

### 4. Magnetic Lock (mode 2) — Java authority

| Field | Value |
|-------|--------|
| Power ID | `mode/magnetic_lock` |
| Key | primary |
| Styles | **Target** (`control_style` 0) · **Field** (`control_style` 1) — set from radial sub |
| Cooldown | 30 ticks |

#### Target style

| Field | Value |
|-------|--------|
| Command | `sr_internal lock_target` only (no Origins `change_resource`) |
| Authority | `ControlService.lockNearestTarget` |
| Cost | **28** spent in Java **only on hit** |
| Miss / no living | **0** charge + miss feedback |
| Range | look / visible `LivingEntity` up to **64** blocks (ray hit, else view-cone + line-of-sight) |
| Effects | Slowness amp **255** ~5s, Mining Fatigue II, Glowing; soft pull-down; **held weapon frozen** (no hotbar snap loop) |
| “Already locked” | `ControlService.isUnderLock` = has slowness amp ≥255 (**no permanent `sr_locked` tag**) |
| VFX | Head ring + sparks on paid cast |

#### Field style

| Field | Value |
|-------|--------|
| Start / stop | `sr_internal field_start` / `field_stop` |
| Authority | `ControlService.tryStartField` / `stopField` |
| Cost | **40** atomic in Java on start |
| Behavior | Expanding radius (up to 30), stand still or abort; locks living in radius; duration ~60s |
| Sessions | In-memory `SESSIONS` map + `field_active` resource mirror |

---

### 5. Faraday Cage (mode 3 / Aegis)

| Field | Value |
|-------|--------|
| Power ID | `mode/faraday_cage` |
| Command | `sr_internal faraday_manual` |
| Authority | `FaradayService.tryActivate(MANUAL)` |
| Cost | **50** (requires charge ≥50; **never goes negative**) |
| Duration | score `sr_faraday` ~90s (datapack tick down) |
| Effects | Resistance / fire resist refresh; catch projectiles (datapack); **repel** living in ~**3** blocks (`REPEL_RADIUS`) |
| Particles | Soft **aura** around body (`faraday/particles`) — not hex cage lattice |
| Emergency | Radial sub toggle → `ToggleFaradayEmergencyPacket`. Java `LivingHurtEvent`/`LivingDamageEvent` **HIGHEST** predicts remaining HP+absorption; if **< 2.0** raises cage (no spend, **ignores cage cooldown**), caps this hit, intercepts catchable shots. Origins `prevent_death` is a last-ditch net. |
| Active set | `FaradayService.ACTIVE_CAGES` — tick repel only for active UUIDs (not full player list when empty) |

---

### 6. Redstone Sight (mode 4 / Circuit)

| Field | Value |
|-------|--------|
| Select | Entering Circuit (radial / tap R) starts free local outlines of `#circuit` in **16** |
| Cost | **0** to see · **8** only when F flips the aimed piece |
| F | Force on/off that piece for 8s. Miss (no look target) spends nothing and does **not** close sight |
| Leave mode | Outlines clear |
| Client | See-through line boxes (`SeeThroughRender`); aimed piece highlighted |

---

### 7. No-charge feedback

| Power ID | Role |
|----------|------|
| `active/no_charge_feedback` | Primary when can’t cast mode skill |

---

## Passives & weakness

| Power | Effect |
|-------|--------|
| `passive/charged_stability` | Charge ≥50 → knockback resistance |
| `passive/faraday_emergency` | Armed flag + `prevent_death` backup. Predict/cap lives in `FaradayService` |
| `weakness/water_vulnerability` | Wet: +25% damage taken + charge leak via charge_tick |

---

## Full power list on origin

Order in `origins/static_resonance.json` (approximate):

1. `systems/charge`, `mode`, `charge_tick`
2. Scan stack: `scan_active`, `scan_radius`, `scan_step_1..4`, `scan_abort`
3. `systems/motion_charge`
4. Mode skills: `dust_curtain`, `static_scan`, `magnetic_lock`, `faraday_cage`
5. `systems/control_style`, `field_active`, `faraday_emergency`
6. `systems/faraday_tick`, `passive/faraday_emergency`
7. `active/no_charge_feedback`
8. `passive/charged_stability`, `weakness/water_vulnerability`

---

## Internal commands (`sr_internal`, permission 2)

| Subcommand | Service |
|------------|---------|
| `faraday_manual` / `faraday_emergency` | `FaradayService` |
| `field_start` / `field_stop` | `ControlService` |
| `lock_target` | `ControlService.lockNearestTarget` (spend + apply) |

---

## Targeting

| System | Who is affected |
|--------|-----------------|
| Magnetic lock / Control field | **Any `LivingEntity`** except caster |
| Dust / Scan color | `#static_resonance:affectable` (whitelist) |
| Motion charge | `#static_resonance:motion_sources` |
| Faraday repel | Living in radius (except caster) |
| Projectiles (cage) | `#static_resonance:projectiles` (datapack) |

---

## Java package map

```
com.staticresonance
  client/ui/     RadialModeMenu, RadialModeMenuRenderer, RadialWedge
  mode/          ModeService, ControlService, FaradayService,
                 ApoliResources, ResonanceMode, SrScoreboards
  network/       SetModePacket, SetControlStylePacket,
                 ToggleFaradayEmergencyPacket
  command/       ModCommands (sr_internal)
```

---

## Not implemented / design-only

- Pulse Explosion as live mode-3 cast (JSON stub only)
- Full progression trees (Dust Wall, Treasure Scan, Dash, etc.)
- Custom electric damage type
- Full 3D mesh VFX (vanilla particles stand-in)
- Static Climb / spider wall climb (removed)

---

## File map (runtime, high level)

```
data/static_resonance/
  origins/static_resonance.json
  powers/
    systems/{charge,mode,charge_tick,scan_*,motion_charge,
             control_style,field_active,faraday_*}
    mode/{dust_curtain,static_scan,magnetic_lock,faraday_cage,pulse_explosion}
    active/{no_charge_feedback,switch_mode}
    passive/{charged_stability,faraday_emergency}
    weakness/water_vulnerability.json
  functions/
    faraday/{activate,deactivate,particles,tick_*,...}
    control/{lock_hit,lock_miss,lock_apply}
    dust/, sense/, mode/, systems/, util/
  tags/entity_types/{affectable,motion_sources,projectiles}
```

---

## AI update protocol

1. Change code / datapack first  
2. Update **this file** + **TH catalog** (costs, UX, authority)  
3. VFX changes → `vfx-specs/`  
4. Catalog version + Last updated  

### Checklist

```
- [ ] Mode index / primary power IDs
- [ ] Charge min/max (0–100)
- [ ] Who spends charge (Java vs Origins) — single authority
- [ ] Radial = select only; G/Secondary = pay
- [ ] Faraday / Control dual styles
- [ ] Catalog version / date
```

---

## Quick summary

**Static Resonance** = charge **0–100**, 4 modes.  
**Hold R** = select skill/mode (free) · **G** = cast.  
**Live casts:** Dust Curtain, Static Scan, Magnetic Lock (target 28 / field 40, Java spend), Faraday Cage (50 + aura + repel).  
**Not live:** Pulse Explosion as mode skill, climb, upgrade trees, 3D VFX.
