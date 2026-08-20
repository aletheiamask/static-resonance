# Static Resonance — Ability Catalog (AI Source of Truth)

> **Purpose:** What this Origin **actually implements right now**.  
> **Update this file** whenever you add/change/remove powers, Java services, or keybind UX.  
> Do **not** invent skills from design docs alone — match this list first.  
> Quoted costs **must** equal `GameplayDefaults` / `SrConfig` defaults, not historical datapack numbers. A world’s `static_resonance-common.toml` still wins at runtime.  
>  
> Thai player-facing summary: [`AI_ABILITIES_CATALOG_TH.md`](./AI_ABILITIES_CATALOG_TH.md)

| Field | Value |
|-------|--------|
| Mod | `static_resonance` |
| MC / Loader | 1.20.1 Forge |
| Depends | Origins (Forge) + Apoli |
| Origin ID | `static_resonance:static_resonance` |
| Catalog version | **2.6.0** |
| Last updated | **2026-08-20** |
| Status | Playable: 5 modes (Dust, Sense, Control, Aegis, Circuit) + always-on climb + Faraday Cage + Control field/target + select-only radial. Cast is **F**. Balance numbers are Forge COMMON config defaults (`GameplayDefaults` → `config/static_resonance-common.toml`; Cloth Config opens them from Mods → Config). |

---

## Control scheme

| Input | Key | Behavior |
|-------|-----|----------|
| Primary cast | **F** (`key.static_resonance.cast` → `CastPacket`) | Cast **current mode** skill only |
| Mode radial | **Hold R** (`key.static_resonance.mode_menu`) | Open 5-wedge wheel |
| Mode cycle | **Tap R** | Cycle mode 0→1→2→3→4→0 |
### Radial UX (authoritative)

| Action | Result |
|--------|--------|
| **LMB** on combat wedge | **Select mode only** (`SetModePacket`) — never spends charge, never casts |
| **LMB** on Faraday / Control / Dust / Sense | Select mode + open **sub-panel** (emergency / target·field / burst·zone / scan filters) |
| **Release R** (no prior LMB commit) | Select hovered combat mode |
| **F** | Cast skill of current mode |
| Grey wedges by cast cost? | **No** — radial is select-only; cost is for F |

Java: `RadialModeMenu`, `RadialModeMenuRenderer`, `RadialWedge`  
Packets: `SetModePacket`, `SetControlStylePacket`, `ToggleFaradayEmergencyPacket`

While radial is open: mouse attack/use cancelled (`InteractionKeyMappingTriggered`).

---

## Mode index

Resource: `static_resonance:systems/mode` · **0–4**, start **0**, HUD hidden

| Index | Mode (lang) | Cast owner | Radial note |
|------:|-------------|------------|-------------|
| 0 | Dust | Java `DustService` | LMB → sub: burst / zone |
| 1 | Sense | Java `ScanOutlineService` | LMB → sub: scan filters |
| 2 | Control | Java `ControlService` | LMB → sub: Single Target / Field |
| 3 | Overcharge / **Aegis** | Java `FaradayService` | LMB → sub: Faraday emergency arm |
| 4 | Circuit | Java `CircuitService` (F look-toggle) | LMB selects immediately; idle Circuit is free |

**Not live as a cast:** Pulse Explosion (design-only in `vfx-specs/`; no power JSON on disk).

---

## Resource: Static Charge

| Field | Value |
|-------|--------|
| Power ID | `static_resonance:systems/charge` |
| Range | **0–100** (never negative) |
| Start | 40 |
| HUD | **yes** (only resource bar intended to show) |

Mode skills are Java, not Origins active powers, so they do not add extra Origins HUD bars. Only `systems/charge` should render.

### Charge tick

| Field | Value |
|-------|--------|
| Owner | Java `MotionHarvestService` (`SrConfig.charge`) |
| Interval | **35** ticks (default) |
| Wet | charge **−3** + bubbles |
| Dry + not full | charge **+2** |

### Motion charge

| Field | Value |
|-------|--------|
| Owner | Java `MotionHarvestService` (`SrConfig.charge`) |
| Interval | 20 ticks (default) |
| Range | `#motion_sources`, ~0.5–5 (default) |
| Reward | +1…+4 charge from nearby moving life |

### Other systems resources

| Power ID | Role |
|----------|------|
| `systems/scan_active` / `scan_radius` / `scan_filter` / `scan_abort` | Static Scan toggle + radius snapshot + filter + abort net |
| `systems/control_style` | 0 = target, 1 = field |
| `systems/field_active` | Mirror of Java field session |
| `systems/faraday_emergency` | 0/1 auto Faraday if a hit would leave HP below 2 |

---

## Active abilities (implemented)

### 1. Mode select (radial + tap)

| Field | Value |
|-------|--------|
| Free | **Yes** — changing mode never spends charge |
| Server | `ModeService.setMode` → Apoli mode + `function static_resonance:mode/{dust,sense,control,overcharge,circuit}` feedback only |

---

### 2. Dust Curtain (mode 0)

| Field | Value |
|-------|--------|
| Authority | Java `DustService` (`systems/dust_style` 0 = burst, 1 = zone) |
| Burst cost | **5** (`GameplayDefaults.DUST_BURST_COST`) on a look-target hit; miss spends **0** |
| Zone cost | **10** (`GameplayDefaults.DUST_ZONE_COST`) to plant a smoke zone |
| Zone | Radius **6**, lifetime **200** ticks (snapshotted on place) |
| Burst effects | Blindness 4s, Slowness II 3s on the looked-at living entity |

---

### 3. Static Scan (mode 1) — one-shot toggle

| Field | Value |
|-------|--------|
| Authority | Java `ScanOutlineService.toggle` |
| Cost | **8** once on start (Java). No per-tick drain |
| Toggle | **F** start / F stop; abort on leaving Sense |
| Radius | **32** default (`GameplayDefaults.SCAN_RADIUS`, config 1–128) — one server AABB pulse. A world’s toml wins if it differs. |
| Cap | **128** marks |
| Expand | Client-only reveal 0→radius over 1s |
| Reveal | Private colored boxes: **green** safe, **white** players, **red** hostiles. See-through walls default **off** (`visuals.seeThroughWalls`; server COMMON file is opt-in) |

---

### 4. Magnetic Lock (mode 2) — Java authority

| Field | Value |
|-------|--------|
| Authority | Java `ControlService` (`systems/control_style`) |
| Styles | **Target** (`control_style` 0) · **Field** (`control_style` 1) — set from radial sub |

#### Target style

| Field | Value |
|-------|--------|
| Command | `sr_internal lock_target` only (no Origins `change_resource`) |
| Authority | `ControlService.lockLookTarget` |
| Cost | **15** spent in Java **only on hit** (`GameplayDefaults.CONTROL_TARGET_COST`) |
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
| Command | `sr_internal faraday_manual` |
| Authority | `FaradayService.tryActivate(MANUAL)` |
| Cost | **50** (requires charge ≥50; **never goes negative**) |
| Duration | `CAGE_EXPIRE_AT` **600** ticks / 30s default (`SrConfig.faraday.durationTicks`; cooldown **2400** / 120s). Leftover `sr_faraday` / `sr_faraday_cd` are HUD/datapack mirrors (arm / expiry / 0.5 s), not the clock. |
| Effects | Resistance / fire resist refresh; catch projectiles; **repel** living in **10** blocks default (`SrConfig.faraday.repelRadius`) |
| Particles | Soft **aura** from Java (`tickCageFx` / `tickAura`) — not a hex lattice |
| Emergency | Radial sub toggle → `ToggleFaradayEmergencyPacket`. Java `LivingDamageEvent` **HIGHEST** (not idle-gated) predicts remaining HP; if below config floor raises cage (no spend, **ignores cage cooldown**), caps this hit, intercepts catchable shots. Origins `prevent_death` is a last-ditch net. |
| Active set | `FaradayService.ACTIVE_CAGES` — tick repel only for active UUIDs (not full player list when empty) |

---

### 6. Circuit Flip (mode 4 / Circuit)

| Field | Value |
|-------|--------|
| Select | Entering Circuit does **nothing** on the server (no scan, no packets) |
| Cost | **8** when F flips the aimed `#circuit` piece |
| F | One `player.pick` (`lookRange` default **16**). Force on/off that piece for 8s. Neighbor graph pulses once; later vanilla notifies on that pos are canceled. Miss spends nothing |
| Leave mode / logout | Owned forced pieces restore immediately |
| Client | No Circuit outlines. Sense still uses `SeeThroughRender` |

---

## Passives & weakness

| Power | Effect |
|-------|--------|
| `passive/charged_stability` | Charge ≥50 → knockback resistance |
| `passive/static_footing` | Always-on knockback resistance |
| `passive/faraday_emergency` | Armed flag + `prevent_death` backup. Predict/cap lives in `FaradayService` |
| `weakness/water_vulnerability` | Wet: +25% damage taken + charge leak via Java regen |
| Wall climb (Java) | `ClimbService` — no Origins power; no charge |

---

## Full power list on origin

Order in `origins/static_resonance.json`:

1. `systems/charge`, `mode`
2. Scan: `scan_active`, `scan_radius`, `scan_abort`
3. `systems/control_style`, `dust_style`, `scan_filter`, `field_active`, `faraday_emergency`
4. `passive/faraday_emergency`, `charged_stability`, `static_footing`
5. `weakness/water_vulnerability`

Mode skills and climb are **Java** (`CastService` / `ClimbService`), not Origins active powers. Regen is Java (`MotionHarvestService`); there is no granted `charge_tick` power.

---

## Internal commands (`sr_internal`, permission 2)

| Subcommand | Service |
|------------|---------|
| `faraday_manual` / `faraday_emergency` | `FaradayService` |
| `field_start` / `field_stop` | `ControlService` |
| `lock_target` | `ControlService.lockLookTarget` (spend + apply) |

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
  mode/          ModeService, CastService, ControlService, FaradayService,
                 CircuitService, ClimbService, ScanOutlineService,
                 ApoliResources, ResonanceMode, SrScoreboards
  logic/         ChargeBuckets, ExpireClock, MarkPrune, GameplayDefaults (no Minecraft)
  network/       CastPacket, SetModePacket, SetControlStylePacket,
                 ToggleFaradayEmergencyPacket, ScanOutlinePacket
                 (no CircuitOutlinePacket — Circuit is look-toggle only)
  command/       ModCommands (sr_internal)
```

---

## Not implemented / design-only

- Pulse Explosion as a live cast (VFX design-only; not shipped)
- Full progression trees (Dust Wall, Treasure Scan, Dash, etc.)
- Custom electric damage type
- Full 3D mesh VFX (vanilla particles stand-in)

**Live, not a mode:** `ClimbService` — always-on wall climb while an origin player is sliding on a wall. No charge. Client + server (prediction).

---

## File map (runtime, high level)

```
data/static_resonance/
  origins/static_resonance.json
  powers/
    systems/{charge,mode,scan_active,scan_radius,scan_abort,
             scan_filter,control_style,dust_style,field_active,faraday_emergency}
    passive/{charged_stability,faraday_emergency,static_footing}
    weakness/water_vulnerability.json
  functions/
    faraday/{activate,deactivate,emergency_fx,release_projectile}
    sense/{scan_start,scan_stop}
    mode/{dust,sense,control,overcharge,circuit}
    init.mcfunction
  tags/entity_types/{affectable,motion_sources,projectiles}
  tags/blocks/circuit.json
```

---

## AI update protocol

1. Change code / datapack first  
2. If a default number changes, change `GameplayDefaults` first, then `SrConfig`, then **this file** + **TH catalog**  
3. VFX changes → `vfx-specs/`  
4. Catalog version + Last updated  

Quoted costs must equal `GameplayDefaults` / `SrConfig` defaults, not historical datapack numbers.

### Checklist

```
- [ ] Mode index / cast owners
- [ ] Charge min/max (0–100)
- [ ] Costs equal GameplayDefaults
- [ ] Who spends charge (Java vs Origins) — single authority
- [ ] Radial = select only; F = pay
- [ ] Faraday / Control dual styles
- [ ] Catalog version / date
```

---

## Quick summary

**Static Resonance** = charge **0–100**, 5 modes + always-on climb.  
**Hold R** = select skill/mode (free) · **F** = cast.  
**Live casts:** Dust Curtain (burst 5 / zone 10), Static Scan (8, r=32 default), Magnetic Lock (target 15 / field 40, Java spend), Faraday Cage (50 + aura + repel), Circuit Flip (8, one looked-at piece).  
**Not live:** Pulse Explosion as a cast, upgrade trees, 3D VFX.
