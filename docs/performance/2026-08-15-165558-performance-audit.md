## Executive Summary

### Audit Overview

- **Target System**: Static Resonance (`static_resonance` / `com.staticresonance`)
- **Analysis Date**: 2026-08-15
- **Analysis Scope**: Full Stack Minecraft Forge 1.20.1 content mod (server tick paths, datapack functions, Origins/Apoli powers, client HUD renderer)
- **Technology Stack**: Minecraft 1.20.1, Forge 47.2.0, Origins/Apoli (Forge + Fabric-via-Connector), Java 17, Gradle, scoreboard/NBT as persistence, SimpleChannel packets

### Performance Assessment Summary

| Performance Level | Count | Percentage |
|-------------------|-------|------------|
| Critical Issues   | 1     | 5%         |
| High Impact       | 6     | 30%        |
| Medium Impact     | 7     | 35%        |
| Low Impact        | 6     | 30%        |
| **Total**         | **20** | **100%**  |

### Key Analysis Results

- **Performance Anti-Patterns**: 1 critical pattern identified requiring immediate attention (unbounded Static Scan particle + entity recast)
- **Code Optimization Opportunities**: 6 high-impact optimizations discovered
- **Architecture Assessment**: 7/10 performance best practices implemented (session maps and early-outs are solid; particle/entity query budget is not)
- **Overall Code Performance Score**: 58/100 (based on static analysis and architectural patterns)

---

## Analysis Methodology

### Performance Analysis Approach

- **Static Code Analysis**: Comprehensive source code review for performance anti-patterns
- **Database Query Analysis**: Review of queries, indexing strategies, and data access patterns (scoreboard, entity selectors, NBT `data get` / `data modify`)
- **Resource Utilization Assessment**: Analysis of memory, CPU, and I/O usage patterns
- **Architecture Performance Review**: Examination of caching, scaling, and optimization strategies

### Analysis Coverage

- **Files Analyzed**: 79 source files across `src/main/java` (17), `*.mcfunction` (30), and datapack JSON (32)
- **Database Queries Reviewed**: 18 entity-selector / NBT / `getEntitiesOfClass` access patterns
- **API Endpoints Analyzed**: 12 handlers (3 SimpleChannel packets, 5 `sr_internal` commands, 4 Origins `active_self` keys) plus 8 `action_over_time` tick powers
- **Performance Patterns Checked**: N+1 queries, memory leaks, CPU bottlenecks, I/O blocking, concurrency issues

### Analysis Capabilities

- **Pattern Detection**: N+1 queries, inefficient loops, memory leaks, blocking operations, race conditions
- **Database Analysis**: Missing indexes, expensive queries, connection pool management
- **Resource Analysis**: Memory allocation patterns, CPU-intensive operations, I/O bottlenecks
- **Architecture Assessment**: Caching strategies, async patterns, connection pooling, concurrency safety
- **Frontend Analysis**: Core Web Vitals impact, bundle size, rendering performance, hydration cost

---

## Performance Findings

### Critical Performance Issues

#### P-001: Static Scan emits an unbounded particle + entity-recast storm as radius grows to 128

**Location**: `src/main/resources/data/static_resonance/functions/sense/scan_pulse.mcfunction:7-27`
**Performance Impact**: 9.3 (Critical)
**Pattern Detected**: Unbounded per-tick work that scales with radius and entity count; particles forced to every online player
**Code Context**:

```mcfunction
execute if score @s sr_scan matches 65..128 run particle minecraft:dust 0.2 0.75 1.0 1.2 ~ ~0.25 ~ 64 0.05 64 0 140 force @a
# ...
execute if score @s sr_scan matches 65..128 as @e[type=#static_resonance:affectable,distance=0.5..128] run function static_resonance:sense/color_entity
```

**Impact**: Scan grows every 5 ticks (`scan_step_1`–`scan_step_4`) until charge runs out. At band 65–128 the server emits 140 dust particles with a 128×128 spread **and** re-runs `color_entity` on every affectable entity inside 128 blocks. `color_entity` then emits another 18 `force @a` particles **per entity** and applies Glowing. On a busy SMP (50–200 mobs in range, 20+ players) this is hundreds of particle packets every 5 ticks plus a 128-block entity sweep. `force` bypasses the client particle slider; `@a` ignores view distance.
**Performance Cost**: Estimated 5–40+ ms server tick spikes and multi-megabit particle bursts; client FPS collapse for every online player, not just nearby ones. Can cascade into tick-skip / watchdog risk on populated worlds.
**Recommendation**: Cap visual radius (16–24), emit a thin ring instead of a filled volume, use `normal @a[distance=..32]` (not `force @a`), and color **only the new shell** (`distance=r-1..r`) or entities not yet tagged `sr_scanned`.
**Fix Priority**: Immediate

### High Performance Impact Findings

#### P-002: Almost every VFX command uses `force @a`, broadcasting particles to the entire server

**Location**: `src/main/resources/data/static_resonance/functions/faraday/particles.mcfunction:3-29`
**Performance Impact**: 8.5 (High)
**Pattern Detected**: Unbounded fan-out of particle packets (no distance filter, `force` overrides particle settings)
**Code Context**:

```mcfunction
particle minecraft:end_rod ~0.9 ~0.08 ~0.3 0.05 0.02 0.05 0 1 force @a
particle minecraft:dust 0.55 0.85 1.0 0.9 ~ ~0.0 ~0.7 ~0.0 0.7 0.25 0.7 0 6 force @a
```

**Impact**: `force @a` appears across Faraday aura (every 5 ticks while cage is up), activate (235 particles), deactivate (141), Dust Curtain (160), mode-switch feedback, charge-tick bubbles, and scan coloring. Minecraft's `particle` command with `@a` sends a packet to every player on the server. `force` also makes clients render them even when particles are set to Minimal. Faraday aura alone is ~20 particle commands ≈ 40+ particles × player count, 4 times per second.
**Performance Cost**: Linear in (particle count × online players). On a 30-player server, one Faraday cage is ~4.8k particle packets/sec before other skills. Network and client GPU both pay.
**Recommendation**: Replace `force @a` with `normal @a[distance=..24]` (or `force @a[distance=..24]` only for cast bursts). Sounds already use `distance=..16` in several files — particles should match that pattern.
**Fix Priority**: Immediate

#### P-003: Scan recolors the entire filled disk every pulse instead of the new ring

**Location**: `src/main/resources/data/static_resonance/functions/sense/scan_pulse.mcfunction:22-27`
**Performance Impact**: 8.0 (High)
**Pattern Detected**: Repeated full-set scan (N+1 style recast); no incremental shell
**Code Context**:

```mcfunction
execute if score @s sr_scan matches 17..32 as @e[type=#static_resonance:affectable,distance=0.5..32] run function static_resonance:sense/color_entity
execute if score @s sr_scan matches 33..64 as @e[type=#static_resonance:affectable,distance=0.5..64] run function static_resonance:sense/color_entity
```

**Impact**: `color_entity` is a function call per entity: Glowing effect, `data get entity Health` for players, 18 particles, and a ping check. Entities already scanned last pulse are fully reprocessed. At r=64 this is O(entities in 128×128 cylinder) every 5 ticks for the lifetime of the scan (charge-limited, but still tens of seconds).
**Performance Cost**: 0.5–8 ms per pulse in typical combat; 10–30+ ms in mob farms / raids / spawners inside the disk.
**Recommendation**: Select only the new band (`distance=$(r-1)..$r`) or `tag=!sr_scanned`, and skip particle/effect work for already tagged entities. Refresh Glowing on a slower interval (40 ticks) if needed.
**Fix Priority**: Immediate

#### P-004: Faraday projectile hold does a world-wide `@e` scan and writes NBT every 5 ticks

**Location**: `src/main/resources/data/static_resonance/functions/faraday/hold_projectiles.mcfunction:1-3`
**Performance Impact**: 7.8 (High)
**Pattern Detected**: Unbounded entity query (no distance on the hold tick) + per-entity NBT write (`data modify`)
**Code Context**:

```mcfunction
execute at @s as @e[type=#static_resonance:projectiles,distance=..4.8,tag=!sr_faraday_held] run function static_resonance:faraday/catch_projectile
execute as @e[type=#static_resonance:projectiles,tag=sr_faraday_held] at @s if entity @a[tag=sr_faraday,distance=..10] run function static_resonance:faraday/tick_held_projectile
```

**Impact**: The catch line is correctly radius-limited. The hold line is **global**: every Faraday-active player iterates all loaded arrows/fireballs/tridents/etc. with `sr_faraday_held`. `tick_held_projectile` then does two `data modify entity` writes (Motion + NoGravity) which dirty the entity and sync to trackers. 14 projectile types are in the tag. On a PvP / skeleton farm world this walks every loaded projectile.
**Performance Cost**: 0.3–5 ms per Faraday player per 5 ticks, worse with many loaded arrows; extra entity-tracker packets from Motion writes.
**Recommendation**: Scope the hold tick: `execute at @s as @e[type=#static_resonance:projectiles,tag=sr_faraday_held,distance=..10]`. Freeze motion in Java (`setDeltaMovement` + `setNoGravity`) on a UUID set, not via NBT commands.
**Fix Priority**: Within 1 week

#### P-005: Control Field queries every living entity in the expanding AABB every server tick

**Location**: `src/main/java/com/staticresonance/mode/ControlService.java:394-408`
**Performance Impact**: 7.6 (High)
**Pattern Detected**: Per-tick `getEntitiesOfClass` with no interval; work grows with `FIELD_MAX_RADIUS` (30)
**Code Context**:

```java
AABB box = new AABB(
        pos.x - r, pos.y - 2, pos.z - r,
        pos.x + r, pos.y + 3, pos.z + r
);
for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, e ->
        e.isAlive()
                && e != player
                && e.distanceToSqr(pos) <= r * r)) {
    if (!isUnderLock(living)) {
        applyLock(living, level, true, true);
    } else if (now % REFRESH_INTERVAL == 0) {
        refreshLockEffects(living);
    }
}
```

**Impact**: `tickField` runs on `TickEvent.ServerTickEvent` END every tick while a session exists. Radius expands to 30 (`elapsed / 5`). The AABB is 60×5×60 at full size. First-time `applyLock(..., withVfx=true)` also calls `spawnHeadRing` (P-007). `LivingEntity.class` includes villagers, animals, armor stands with living type — not just hostiles. Refresh still walks the full set 2 times per second.
**Performance Cost**: ~0.2–2 ms/tick idle field; spikes when many living entities enter (village, farm, raid). Two concurrent fields double it.
**Recommendation**: Run the entity sweep every 2–5 ticks; cache locked UUIDs; restrict to a tag/`#static_resonance:affectable` equivalent; skip VFX after the first N locks per expand step.
**Fix Priority**: Within 1 week

#### P-006: Single-target lock scans a 64-block cube and raytraces every candidate

**Location**: `src/main/java/com/staticresonance/mode/ControlService.java:198-247`
**Performance Impact**: 7.3 (High)
**Pattern Detected**: Wide AABB + per-entity `hasLineOfSight` (block raycast) after the precise ray already missed
**Code Context**:

```java
public static final double TARGET_RANGE = 64.0;
AABB around = player.getBoundingBox().inflate(TARGET_RANGE);
for (LivingEntity living : player.serverLevel().getEntitiesOfClass(LivingEntity.class, around, e ->
        isLockable(player, e)
                && e.distanceToSqr(player) <= rangeSq
                && player.hasLineOfSight(e))) {
```

**Impact**: Predicate runs `hasLineOfSight` (clip from eye to target) for every living entity in a 128³ cube **before** the cone test. In a crowded spawn or mob farm that is tens to hundreds of block raycasts on one G-press. Not a per-tick path, but a hitch on the server thread when the player misses the exact hitbox.
**Performance Cost**: 1–15+ ms hitch per miss-then-cone lock in dense areas.
**Recommendation**: Inflate by a tighter box (24–32), test cone/dot **before** LOS, and/or limit candidates (closest 16 by distance). Keep the precise `ProjectileUtil` ray as the fast path (already present).
**Fix Priority**: Within 1 week

#### P-007: `spawnHeadRing` fires ~80+ `sendParticles` calls per first lock

**Location**: `src/main/java/com/staticresonance/mode/ControlService.java:286-316`
**Performance Impact**: 7.1 (High)
**Pattern Detected**: Particle packet flood; amplified when Control Field first-locks many entities
**Code Context**:

```java
int n = 16;
for (int i = 0; i < n; i++) {
    level.sendParticles(RING_DUST, x, cy, z, 2, 0.02, 0.02, 0.02, 0);
    level.sendParticles(ParticleTypes.WITCH, x, cy, z, 1, 0, 0, 0, 0);
    level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, cy, z, 1, 0.02, 0.02, 0.02, 0.01);
}
for (int layer = 0; layer < 4; layer++) {
    for (int i = 0; i < 8; i++) {
        level.sendParticles(ParticleTypes.ENCHANTED_HIT, ...);
    }
}
```

**Impact**: 16×3 + 4×8 + 4 burst calls ≈ 84 `ServerLevel.sendParticles` invocations per newly locked entity. Each call is its own network packet to tracking players. Field mode first-locks every living entity as the ring expands — a village walk-in can emit thousands of particle packets in a few seconds.
**Performance Cost**: ~84 packets × entity count; noticeable MSPT and client stutter on field start in populated areas.
**Recommendation**: Collapse to 2–3 `sendParticles` with a count/spread (one ring dust, one spark burst, one flash). Cap first-lock VFX per tick (e.g. 3 rings/tick).
**Fix Priority**: Within 1 week

### Medium Performance Impact Findings

#### P-008: Faraday activate/deactivate bursts 235 / 141 `force @a` particles

**Location**: `src/main/resources/data/static_resonance/functions/faraday/activate.mcfunction:9-12`
**Performance Impact**: 6.6 (Medium)
**Pattern Detected**: One-shot particle burst oversized for multiplayer
**Code Context**:

```mcfunction
particle minecraft:electric_spark ~ ~1 ~ 1.8 1.0 1.8 0.4 160 force @a
particle minecraft:end_rod ~ ~1 ~ 1.4 0.9 1.4 0.05 50 force @a
particle minecraft:soul_fire_flame ~ ~1 ~ 1.2 0.8 1.2 0.02 24 force @a
```

**Impact**: Activate is 1+160+50+24 = 235 particles to every player. Deactivate is 1+100+40 = 141 (`faraday/deactivate.mcfunction:13-15`). Cast-time only, but combined with P-002 it spikes bandwidth whenever the cage toggles (including emergency).
**Performance Cost**: Short 50–200 ms client hitch for distant players who should not see the effect.
**Recommendation**: Drop spark count to ~40, `normal @a[distance=..28]` (sounds already use 18–28).
**Fix Priority**: Within 1 month

#### P-009: Faraday aura function runs ~20 particle commands every 5 ticks for the full 90s cage

**Location**: `src/main/resources/data/static_resonance/functions/faraday/tick_live.mcfunction:1-10`
**Performance Impact**: 6.3 (Medium)
**Pattern Detected**: Periodic command + particle cost with no LOD / player-count budget
**Code Context**:

```mcfunction
effect give @s minecraft:resistance 6 4 true
effect give @s minecraft:fire_resistance 6 0 true
function static_resonance:faraday/hold_projectiles
function static_resonance:faraday/particles
```

**Impact**: Cage lasts 1800 ticks (`activate.mcfunction:3`); tick interval is 5 (`faraday_tick.json`). That is 360 aura updates, each applying two effects, an actionbar title, hold_projectiles (P-004), and `particles.mcfunction`. Java `tickRepel` **also** queries living entities every server tick and emits sparks every 5 ticks (`FaradayService.java:157-188`). Two independent tick paths for one ability.
**Performance Cost**: Sustained 0.3–2 ms/tick per active cage plus client GPU for the aura.
**Recommendation**: Move aura to Java `sendParticles` with a budget; refresh potion effects every 40–80 ticks (duration is already 6s); keep Java as the single tick authority.
**Fix Priority**: Within 1 month

#### P-010: Radial HUD tessellates many immediate-mode strips and allocates `ItemStack`s every frame

**Location**: `src/main/java/com/staticresonance/client/ui/RadialModeMenuRenderer.java:154-168`
**Performance Impact**: 5.8 (Medium)
**Pattern Detected**: Per-frame allocation + many `tesselator.end()` calls on the render thread
**Code Context**:

```java
private static final int SEGMENTS_PER_WEDGE = 24;
ItemStack stack = new ItemStack(wedge.iconItem());
graphics.renderItem(stack, ix, iy);
```

**Impact**: Menu-open only (good). While open, each wedge can call `fillAnnularSector` multiple times (fill + hover rim + active edge). Each sector is 25-iteration TRIANGLE_STRIP + immediate `tesselator.end()`. Center disc/rings add 360° strips. Three full-screen `graphics.fill` scrims. Four new `ItemStack`s per frame. Not a server issue; can dip FPS on integrated GPUs during the hold-R gesture.
**Performance Cost**: Estimated 0.3–2 ms GPU/CPU per HUD frame; young-gen churn from stacks/vertex begins.
**Recommendation**: Cache static `ItemStack`s per wedge; batch all sectors into one buffer; reduce `SEGMENTS_PER_WEDGE` to 12; single scrim.
**Fix Priority**: Within 1 month

#### P-011: Faraday datapack tick has no Origins condition, so every origin player pays command overhead

**Location**: `src/main/resources/data/static_resonance/powers/systems/faraday_tick.json:1-8`
**Performance Impact**: 5.4 (Medium)
**Pattern Detected**: Unconditional `action_over_time` → command parse/dispatch
**Code Context**:

```json
{
  "type": "origins:action_over_time",
  "interval": 5,
  "entity_action": {
    "type": "origins:execute_command",
    "command": "function static_resonance:faraday/tick"
  }
}
```

**Impact**: Every player with the origin runs `function static_resonance:faraday/tick` 4 times/sec even with cage off. The function early-outs via scoreboard, but Apoli still dispatches a command and the function still evaluates two score checks. Multiply by origin player count.
**Performance Cost**: Small per player (~0.05–0.2 ms × players) but always-on.
**Recommendation**: Add a condition (`sr_faraday > 0 OR sr_faraday_cd > 0`) or drive cooldown entirely from Java `ACTIVE_CAGES` / a cooldown map (Java already ticks only active UUIDs).
**Fix Priority**: Within 1 month

#### P-012: Four overlapping scan-step powers plus abort evaluate the same resources every 5 ticks

**Location**: `src/main/resources/data/static_resonance/powers/systems/scan_step_1.json:1-58`
**Performance Impact**: 5.1 (Medium)
**Pattern Detected**: Redundant condition evaluation (4 bands + abort, all interval 5)
**Code Context**:

```json
"interval": 5,
"condition": {
  "type": "origins:and",
  "conditions": [
    { "type": "origins:resource", "resource": "static_resonance:systems/scan_active", "comparison": "==", "compare_to": 1 },
    { "type": "origins:resource", "resource": "static_resonance:systems/mode", "comparison": "==", "compare_to": 1 }
  ]
}
```

**Impact**: `scan_step_1`–`4` and `scan_abort.json` each poll `scan_active`, `mode`, `scan_radius`, and `charge` every 5 ticks. Only one step can fire, but all five powers are evaluated. Resource reads go through Apoli containers (Forge direct or Fabric reflection).
**Performance Cost**: Modest CPU; grows with origin player count. Complexity also makes a single Java/scan service harder to budget.
**Recommendation**: One `action_over_time` with banded cost in Java, or one power that reads radius and subtracts cost via a lookup.
**Fix Priority**: Within 2 months

#### P-013: Motion harvest reads NBT Motion triples for a large entity-type set every second

**Location**: `src/main/resources/data/static_resonance/functions/systems/motion_try.mcfunction:4-6`
**Performance Impact**: 4.8 (Medium)
**Pattern Detected**: Per-entity NBT `data get` in a wide type tag; extra `origins:command` score checks
**Code Context**:

```mcfunction
execute store result score #mx sr_tmp run data get entity @s Motion[0] 100
execute store result score #my sr_tmp run data get entity @s Motion[1] 100
execute store result score #mz sr_tmp run data get entity @s Motion[2] 100
```

**Impact**: `motion_charge.json` runs every 20 ticks when charge < 100. `motion_harvest` selects `#static_resonance:motion_sources` (all affectable hostiles **plus** ~35 passive types: villagers, livestock, fish, bees, …) in r=5, then `motion_try` does 3 NBT reads each. Apoli then re-checks `execute if score @s sr_mc` via `origins:command` (another command parse). Villages/farms make this a dense NBT walk every second per origin player.
**Performance Cost**: 0.1–1.5 ms/s per player in animal-dense areas.
**Recommendation**: Narrow the tag to hostiles + players; read motion from Java (`getDeltaMovement`) if this stays; drop `origins:command` and use a scoreboard condition or Java charge add.
**Fix Priority**: Within 2 months

#### P-014: Scan stop sweeps **all loaded entities** by tag, twice

**Location**: `src/main/resources/data/static_resonance/functions/sense/scan_stop.mcfunction:3-5`
**Performance Impact**: 4.6 (Medium)
**Pattern Detected**: Unbounded `@e[tag=…]` world scan
**Code Context**:

```mcfunction
execute as @e[tag=sr_scanned] run effect clear @s minecraft:glowing
tag @e[tag=sr_scanned] remove sr_scanned
tag @e[tag=sr_scan_pinged] remove sr_scan_pinged
```

**Impact**: Three global entity iterations over every loaded entity that matches the tag (or, for the selector engine, a scan of loaded entities to find tags). Fine for a handful of mobs; expensive after a 128-block scan that tagged a raid + farm. Also clears Glowing that **other** scanners may still want (correctness + extra work).
**Performance Cost**: 0.2–3 ms hitch on scan cancel after a large scan.
**Recommendation**: `execute at @s as @e[tag=sr_scanned,distance=..128]`; keep a scanned-UUID list in Java; do not clear tags belonging to another player's scan.
**Fix Priority**: Within 1 month

### Low Performance Impact Findings

#### P-015: Dust Curtain emits 160 forced particles and scans the same selector twice

**Location**: `src/main/resources/data/static_resonance/functions/dust/curtain.mcfunction:3-8`
**Performance Impact**: 3.8 (Low)
**Pattern Detected**: Duplicate entity selector; oversized one-shot VFX
**Code Context**:

```mcfunction
particle minecraft:block sand ~ ~1 ~ 2.2 0.8 2.2 0.05 90 force @a
particle minecraft:cloud ~ ~1 ~ 1.8 0.6 1.8 0.02 50 force @a
execute as @e[type=#static_resonance:affectable,distance=0.5..7] run effect give @s minecraft:blindness 4 0 true
execute as @e[type=#static_resonance:affectable,distance=0.5..7] run effect give @s minecraft:slowness 3 1 true
```

**Impact**: Cast-only (cooldown 25). Two identical `@e` sweeps. 160 `force @a` particles (P-002).
**Performance Cost**: Brief hitch on cast; selector cost is small at r=7.
**Recommendation**: One `execute as @e[...] run function` that applies both effects; cut particles ~50%; distance-limit viewers.
**Fix Priority**: Within 2 months

#### P-016: Mode/Faraday feedback goes through command parse (`performPrefixedCommand`)

**Location**: `src/main/java/com/staticresonance/mode/ModeService.java:94-103`
**Performance Impact**: 3.4 (Low)
**Pattern Detected**: String command parse on a hot-ish path instead of direct function invocation
**Code Context**:

```java
server.getCommands().performPrefixedCommand(source, "function " + mode.feedbackFunction());
```

**Impact**: Each mode switch and Faraday activate/emergency parses a command string, builds a source stack, and dispatches. Same pattern in `FaradayService.runFunction` (`FaradayService.java:190-197`). Not per-tick, but unnecessary allocations and parse cost. Mode-switch functions themselves also emit `force @a` particles (`mode/*.mcfunction`).
**Performance Cost**: Sub-millisecond per switch; dominated by the particle functions they call.
**Recommendation**: `server.getFunctions().execute(...)` with a cached `ResourceLocation`; keep particles distance-limited.
**Fix Priority**: Within 2 months

#### P-017: Client tick and HUD read Apoli resources every tick (Fabric path is reflection)

**Location**: `src/main/java/com/staticresonance/client/ui/RadialModeMenu.java:125-151`
**Performance Impact**: 3.1 (Low)
**Pattern Detected**: Per-tick component lookup; Fabric backend uses `Method.invoke` per field
**Code Context**:

```java
if (!ModeService.hasModePower(player)) {
    reset(mc);
    wasDown = false;
    return;
}
```

**Impact**: Every client tick calls `hasModePower` → `ApoliResources.hasPower`. Forge backend is a few direct calls. Fabric/Connector backend (`ApoliResources.FabricBackend`) does `maybeGet` + registry + `hasPower` via reflection. HUD open additionally reads mode, charge, Faraday emergency, and control style (more reflection). Acceptable on Forge; noisier under Connector.
**Performance Cost**: Tens of microseconds (Forge) to low hundreds (Fabric) per tick.
**Recommendation**: Cache `hasModePower` for 5–10 ticks; cache HUD resource reads for 2–3 ticks.
**Fix Priority**: Within 2 months

#### P-018: Faraday live tick re-applies 6-second effects and rewrites the actionbar every 5 ticks

**Location**: `src/main/resources/data/static_resonance/functions/faraday/tick_live.mcfunction:1-10`
**Performance Impact**: 2.8 (Low)
**Pattern Detected**: Redundant effect/UI refresh far more often than duration requires
**Code Context**:

```mcfunction
effect give @s minecraft:resistance 6 4 true
effect give @s minecraft:fire_resistance 6 0 true
title @s actionbar [{"text":"◆ AEGIS GRID ","color":"#4FC3F7"},{"score":{"name":"#sec","objective":"sr_tmp"}}]
```

**Impact**: Effects last 6 seconds but are reapplied 4 times per second. Actionbar is rewritten at the same rate (Java Control Field already updates status every 20 ticks — a better cadence).
**Performance Cost**: Minor packet/effect-map churn.
**Recommendation**: Refresh effects every 40–80 ticks; actionbar every 20 ticks.
**Fix Priority**: Within 2 months

#### P-019: Faraday score reads allocate via `getOrCreatePlayerScore` every tick

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:205-211`
**Performance Impact**: 3.0 (Low)
**Pattern Detected**: Write-on-read; scoreboard entry created just to read a value
**Code Context**:

```java
return board.getOrCreatePlayerScore(player.getScoreboardName(), obj).getScore();
```

**Impact**: `isCageActive` runs every server tick for each UUID in `ACTIVE_CAGES` (`FaradayService.java:135`). `getOrCreatePlayerScore` creates a `Score` if missing. Harmless when the player already has `sr_faraday`, but it is a write-capable API on a read path.
**Performance Cost**: Small allocation / scoreboard map churn if the score is ever missing.
**Recommendation**: Read-only lookup (`hasPlayerScore` then `getValue`); only create scores in `activate`.
**Fix Priority**: Within 2 months

#### P-020: Unused datapack still parsed at load

**Location**: `src/main/resources/data/static_resonance/origins/static_resonance.json:3-26`
**Performance Impact**: 2.2 (Low)
**Pattern Detected**: Load-time parse of dead resources
**Code Context**:

`switch_mode.json` is a full `active_self` power but is not in the origin power list (lang already calls it unused). `powers/mode/pulse_explosion.json`, empty `functions/control/lock_apply.mcfunction`, empty `functions/overcharge/pulse.mcfunction`, and `tags/blocks/conductive.json` have no runtime callers.

**Impact**: Origins/datapack reload still parses and registers these. No per-tick cost after load.
**Performance Cost**: One-time parse/memory at world load; maintenance noise.
**Recommendation**: Delete or move to an archive folder so reload stays lean.
**Fix Priority**: Within 2 months

---

## Code Pattern Performance Analysis

### Performance Anti-Pattern Detection

- **N+1 Query Patterns**: 4 instances detected across 3 files (`scan_pulse` recast-all, `color_entity` per-entity particles, Dust Curtain double selector, scan_stop triple `@e`)
- **Blocking Operations**: 2 async-convertible operations identified (command-string function dispatch; Fabric reflection on HUD reads)
- **Memory Pressure Points**: 2 locations with excessive allocation or missing disposal (per-frame `ItemStack` in radial HUD; `ArrayList` copy of session/cage UUID sets each tick)
- **Inefficient Data Access**: 5 queries with suboptimal patterns (global projectile `@e`, 64-block lock AABB, field AABB every tick, motion NBT triples, scan_stop global tags)
- **Concurrency Issues**: 0 shared state or synchronization concerns (server tick is single-threaded; `ConcurrentHashMap` is conservative but safe)

### Database Access Pattern Analysis

- **ORM/Query Usage**: 18 selectors / NBT / `getEntitiesOfClass` operations analyzed for efficiency patterns
- **Missing Async Patterns**: 0 database operations identified for async conversion (Minecraft server tick cannot offload entity queries)
- **Query Complexity**: 5 complex queries requiring optimization review (64-block lock + LOS, 128-block scan, global held projectiles, field r=30 every tick, motion_sources farm tag)
- **Connection Management**: No SQL connection pool; scoreboard objectives are created once (`SrScoreboards`) — healthy

### Resource Management Pattern Analysis

- **Memory Allocation Patterns**: Session maps (`SESSIONS`, `ACTIVE_CAGES`) are bounded by player count and cleaned on logout — good. HUD `ItemStack` + tessellator begins are the main client churn.
- **Garbage Collection Pressure**: Radial open frames and particle packet objects during scan/field are the hot allocators
- **Thread/Event Loop Usage**: All gameplay work is on the server thread (correct for MC). No event-loop blocking in the Node sense; tick overruns are the equivalent risk
- **Caching Opportunities**: Apoli resource reads (mode/charge/style) and `hasModePower` are re-fetched constantly with no TTL
- **Resource Disposal**: Logout hooks remove sessions, cages, and debounce entries. No leaked maps found

---

## Architecture Performance Assessment

### Data Access Layer Analysis

- Persistence is scoreboard + Apoli resources + entity tags/NBT. Objectives are ensured once at server start and login (`SrScoreboards`) — no per-tick `addObjective` in the hot path except Faraday `ensureObjectives` on activate/query.
- Entity “queries” are unindexed world scans. Distance-limited selectors are used in several places (catch radius 4.8, Dust 7, motion 5) but scan, lock cone, field, hold-tick, and scan-stop are not similarly budgeted.
- No caching layer in front of Apoli resource reads.

### Application Layer Analysis

- Java correctly owns charge spend for Control/Faraday (atomic, no double-spend). Session maps avoid scanning the whole player list (`FaradayService` comment + `ACTIVE_CAGES`; `ControlService.SESSIONS`).
- Dual tick authorities (Origins `action_over_time` **and** Forge `ServerTickEvent`) duplicate work for Faraday (datapack tick + Java repel).
- Functions invoked via `performPrefixedCommand` add parse overhead versus the function manager.
- Debounce on mode change (4 ticks) is a good anti-spam control.

### Frontend Analysis (if applicable)

- Not a web app; client surface is the radial mode HUD (`RadialModeMenu` + `RadialModeMenuRenderer`).
- Core Web Vitals do not apply. Equivalent risks: HUD frame time (many immediate draws), particle overdraw (`force` + high counts), and layout-stable HUD (no CLS analog).
- No extra JS bundle. Texture use is modest (GUI textures only).
- `ItemStack` icons per frame and triple scrim are the main HUD costs. Menu is hold-to-open and early-returns when closed — good.

### Infrastructure Analysis

- No CDN, HTTP compression, or connection pool. Multiplayer cost is Minecraft particle/entity packets.
- Gradle uses `org.gradle.daemon=false` and `-Xmx3G` (`gradle.properties`) — slower builds, not runtime.
- No APM, Spark/Timings hooks, or particle budget config. Observability is logs only.

---

## Performance Bottleneck Analysis

### Top Performance Bottlenecks

| Rank | Component | Issue | Impact Score | Estimated Cost |
|------|-----------|-------|--------------|----------------|
| 1 | Static Scan pulse | 128-block recast + 140 `force @a` dust | 9.3 | 5–40+ ms/pulse; server-wide particle flood |
| 2 | Particle `force @a` convention | Every VFX ignores distance and client settings | 8.5 | packets ∝ particles × online players |
| 3 | Scan `color_entity` | Full-disk recolor + 18 particles/entity | 8.0 | O(mobs in r) every 5 ticks |
| 4 | Faraday hold projectiles | Global `@e` + NBT Motion writes | 7.8 | 0.3–5 ms / 5 ticks / cage |
| 5 | Control Field tick | `getEntitiesOfClass` every tick to r=30 | 7.6 | 0.2–2 ms/tick per field |
| 6 | Magnetic Lock cone | 64-block AABB + LOS per living entity | 7.3 | 1–15 ms hitch on miss |
| 7 | `spawnHeadRing` | ~84 sendParticles per first lock | 7.1 | packet burst × new locks |
| 8 | Faraday aura + Java repel | Two tick paths for 90s | 6.3 | sustained 0.3–2 ms + GPU |

---

## Technical Recommendations

### Immediate Performance Fixes

1. Rewrite `scan_pulse` to a thin ring, cap visual/select radius (24), color only new/untagged entities, and stop using `force @a` (P-001, P-003).
2. Global replace `force @a` → `normal @a[distance=..24]` (or 16–32 by ability) across functions (P-002, P-008, P-015).
3. Collapse `spawnHeadRing` to 2–3 batched `sendParticles` and cap first-lock VFX per tick (P-007).

### Performance Enhancements

1. Distance-limit Faraday hold tick; move freeze to Java UUID set (P-004).
2. Interval the Control Field entity sweep (2–5 ticks) and restrict entity class/tag (P-005).
3. Tighten lock cone: range 32, cone before LOS, candidate cap (P-006).
4. Single Faraday tick authority (Java) for aura, effects, and projectiles (P-009, P-011, P-018).

### Architecture Improvements

1. Introduce a small VFX budget helper (`sr_particles` function or Java) so counts and viewer selectors stay consistent.
2. Collapse four scan-step powers + abort into one Java/scan service with a cost table (P-012).
3. Cache Apoli resource reads on client (P-017); invoke functions via the function manager (P-016).

---

## Code Optimization Examples

### Limit scan work to a ring and nearby viewers (references P-001, P-002, P-003)

**Before (Inefficient)**:

```mcfunction
execute if score @s sr_scan matches 65..128 run particle minecraft:dust 0.2 0.75 1.0 1.2 ~ ~0.25 ~ 64 0.05 64 0 140 force @a
execute if score @s sr_scan matches 65..128 as @e[type=#static_resonance:affectable,distance=0.5..128] run function static_resonance:sense/color_entity
```

**After (Optimized)**:

```mcfunction
# Visual cap + nearby viewers only; thin ring via a few positioned particles
execute if score @s sr_scan matches 17..24 run particle minecraft:dust 0.2 0.75 1.0 1.2 ~ ~0.25 ~ 12 0.05 12 0 24 normal @a[distance=..40]
# Only the new shell, skip already tagged
execute if score @s sr_scan matches 17..24 as @e[type=#static_resonance:affectable,tag=!sr_scanned,distance=16..24] run function static_resonance:sense/color_entity
```

**Expected Improvement**: ~80–95% fewer particle packets at large radius; entity work becomes O(shell) instead of O(disk).

### Scope Faraday projectile hold (references P-004)

**Before (Inefficient)**:

```mcfunction
execute as @e[type=#static_resonance:projectiles,tag=sr_faraday_held] at @s if entity @a[tag=sr_faraday,distance=..10] run function static_resonance:faraday/tick_held_projectile
```

**After (Optimized)**:

```mcfunction
execute at @s as @e[type=#static_resonance:projectiles,tag=sr_faraday_held,distance=..10] run function static_resonance:faraday/tick_held_projectile
```

**Expected Improvement**: Query cost drops from all loaded projectiles to a 10-block neighborhood (~70–95% in combat worlds).

### Interval Control Field entity sweeps (references P-005)

**Before (Inefficient)**:

```java
for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, e ->
        e.isAlive() && e != player && e.distanceToSqr(pos) <= r * r)) {
    if (!isUnderLock(living)) {
        applyLock(living, level, true, true);
    } else if (now % REFRESH_INTERVAL == 0) {
        refreshLockEffects(living);
    }
}
```

**After (Optimized)**:

```java
if (now % 4 != 0) {
    return; // expand + move-abort already handled above
}
int locked = 0;
for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, e ->
        e.isAlive() && e != player && e.distanceToSqr(pos) <= r * r)) {
    boolean first = !isUnderLock(living);
    applyLock(living, level, first, first && locked < 3);
    if (first) {
        locked++;
    }
}
```

**Expected Improvement**: ~75% fewer world queries while the field is idle; VFX spikes capped when many mobs enter at once.

### Batch lock VFX (references P-007)

**Before (Inefficient)**:

```java
for (int i = 0; i < 16; i++) {
    level.sendParticles(RING_DUST, x, cy, z, 2, 0.02, 0.02, 0.02, 0);
    level.sendParticles(ParticleTypes.WITCH, x, cy, z, 1, 0, 0, 0, 0);
    level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, cy, z, 1, 0.02, 0.02, 0.02, 0.01);
}
```

**After (Optimized)**:

```java
level.sendParticles(RING_DUST, cx, cy, cz, 12, r, 0.02, r, 0);
level.sendParticles(ParticleTypes.ELECTRIC_SPARK, cx, target.getY() + bodyH * 0.5, cz, 16, 0.3, bodyH * 0.25, 0.3, 0.05);
level.sendParticles(ParticleTypes.FLASH, cx, target.getY() + bodyH * 0.6, cz, 1, 0, 0, 0, 0);
```

**Expected Improvement**: ~84 packets → 3 packets per lock (~95% less particle network traffic).

---

## Performance Optimization Priorities

### Phase 1: Critical Performance Fixes

- [ ] Cap Static Scan visual/select radius and emit a ring, not a filled 128-block volume (P-001)
- [ ] Recolor only the new shell / untagged entities (P-003)
- [ ] Replace `force @a` with distance-limited viewers across datapack VFX (P-002)

### Phase 2: High Impact Optimizations

- [ ] Distance-limit Faraday hold tick; avoid NBT Motion writes (P-004)
- [ ] Interval Control Field entity query; restrict types; cap first-lock VFX (P-005, P-007)
- [ ] Tighten Magnetic Lock cone: smaller AABB, cone before LOS, candidate cap (P-006)
- [ ] Batch `spawnHeadRing` to 2–3 `sendParticles` (P-007)

### Phase 3: Medium Impact Improvements

- [ ] Shrink Faraday activate/deactivate bursts (P-008)
- [ ] Unify Faraday tick in Java; condition or remove unconditional AOT (P-009, P-011, P-018)
- [ ] Cache radial `ItemStack`s; fewer tessellator passes (P-010)
- [ ] Merge scan-step powers; bound scan_stop selectors (P-012, P-014)
- [ ] Narrow `motion_sources` and drop NBT Motion reads (P-013)

### Phase 4: Performance Monitoring and Fine-tuning

- [ ] Add a Spark/Timings profile preset for scan, field, and Faraday
- [ ] Config values: max scan radius, particle viewer distance, field query interval
- [ ] Cache client Apoli reads (P-017); invoke functions without string parse (P-016)
- [ ] Combine Dust Curtain selectors and cut cast particles (P-015)
- [ ] Read Faraday scores without `getOrCreate` (P-019)
- [ ] Remove unused `switch_mode`, `pulse_explosion`, empty lock/pulse functions, unused `conductive` tag (P-020)

---

## Estimated Performance Improvement Impact

### Performance Gains by Priority

| Priority Level | Expected Improvement | Implementation Complexity |
|----------------|---------------------|--------------------------|
| Critical Fixes | 70–90% less particle/entity work during a full scan; removes server-wide FPS hits | Low–Medium (datapack edits) |
| High Impact | 50–80% less MSPT during Faraday + Control Field; lock click hitch mostly gone | Medium (Java + functions) |
| Medium Impact | 10–30% less always-on origin overhead; smoother radial HUD | Low–Medium |
| Low Impact | Small packet/GC wins on mode switch and client tick | Low |

### Resource Utilization Improvements

- **Database Load**: Entity-selector “query” volume down ~60–90% on scan/field/Faraday after radius + interval + tag bounds
- **Memory Usage**: Lower young-gen from HUD stacks and particle packet objects; session maps already bounded
- **CPU Utilization**: Server thread spikes during scan/field should fall below 5 ms in typical combat if Phase 1–2 land
- **Concurrency/Throughput**: More origin players can use skills concurrently without tick-skip; no thread-pool changes required

---

## Performance Monitoring Setup Recommendations

### Observability Recommendations

- **Metrics**: Spark (Fabric/Forge) or vanilla `/debug` + Spark `profiler start --timeout 60`. Track MSPT, entity counts, packet rates. Optional: a `sr_perf` scoreboard incremented around scan/field ticks.
- **Logging**: Keep existing SLF4J/Log4j setup. Avoid per-tick logs. Log scan stop with final radius + entity colored count when a debug flag is on.
- **Distributed Tracing**: Not applicable (single MC server process). Use Spark tick sampler instead of OpenTelemetry.

### Recommended Performance Tracking

- MSPT while a player holds Static Scan past radius 16
- Entity selector time (`minecraft:entities` in Spark) during Faraday + Control Field
- Client FPS / packet rate when 8+ players are online and one Faraday cage is active
- Alert if MSPT exceeds 40 for >2 seconds during ability use (20 TPS budget is 50 ms)

### Performance Testing Recommendations

- Load: 4 origin players using Scan, Field, Faraday, Dust at once in a village + skeleton spawner
- Benchmark `lockLookTarget` with 100 animals in a 64-block pen (current cone path)
- Tools: Spark, Carpet `tick health`, Forge `DebugScreen` pie chart, Wireshark/Spark packet overlay for `ClientboundLevelParticlesPacket`

---

## Summary

This performance analysis identified **1 critical**, **6 high**, **7 medium**, and **6 low** performance issues across the application stack. The analysis focused on code patterns, entity/NBT queries, resource utilization, and architectural performance.

**Key Strengths Identified**:

- Faraday and Control tick handlers **return immediately** when `ACTIVE_CAGES` / `SESSIONS` are empty and iterate UUID sets instead of the whole player list
- Charge spend for Faraday/Control is atomic in Java (no double-spend race with Origins)
- Mode-change debounce, logout cleanup, and orphan field-mirror heal on login
- Several sounds already use `distance=..N` (good pattern to copy for particles)
- Radial HUD does no work when closed; packets are small (`writeByte`) and handled on the main thread correctly

**Critical Areas Requiring Immediate Attention**:

- Static Scan radius 65–128 filled-volume particles + full-disk `color_entity` recast (`P-001`, `P-003`)
- Global `force @a` particle convention (`P-002`)
- Faraday world-wide projectile hold + NBT writes (`P-004`)
- Control Field per-tick living-entity sweep and lock VFX burst (`P-005`, `P-007`)
- 64-block Magnetic Lock cone with per-entity LOS (`P-006`)

**Expected Overall Performance Improvement**: Implementing Phase 1–2 should cut ability-time MSPT by roughly half to an order of magnitude in multiplayer, and stop distant players from receiving every VFX packet. Typical combat should stay well under the 50 ms tick budget; large scans would no longer be a server-wide stutter.

The dedicated `ai-performance:performance-auditor` subagent type is not registered in this Grok environment. This report is from a full static analysis of the repository sources listed above.
