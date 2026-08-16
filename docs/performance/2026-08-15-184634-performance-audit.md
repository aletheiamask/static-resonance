## Executive Summary

### Audit Overview

- **Target System**: Static Resonance (`static_resonance` / `com.staticresonance`)
- **Analysis Date**: 2026-08-15
- **Analysis Scope**: Full Stack Minecraft Forge 1.20.1 content mod (server tick paths, datapack functions, Origins/Apoli powers, client HUD and scan-outline renderer)
- **Technology Stack**: Minecraft 1.20.1, Forge 47.2.0, Origins/Apoli (Forge + Fabric-via-Connector), Java 17, Gradle, scoreboard/NBT as persistence, SimpleChannel packets (protocol `"5"`)

### Performance Assessment Summary

| Performance Level | Count | Percentage |
|-------------------|-------|------------|
| Critical Issues   | 0     | 0%         |
| High Impact       | 3     | 17%        |
| Medium Impact     | 8     | 44%        |
| Low Impact        | 7     | 39%        |
| **Total**         | **18** | **100%**  |

### Key Analysis Results

- **Performance Anti-Patterns**: 0 critical patterns remaining. The previous scan-volume + `force @a` storm is no longer in source. Remaining highs are bounded tick queries (Faraday hold, Control Field sweep, Magnetic Lock cone).
- **Code Optimization Opportunities**: 3 high-impact optimizations still live on server tick / ability-press paths
- **Architecture Assessment**: 8/10 performance best practices implemented (session maps, logout cleanup, distance-limited VFX, client-only scan outline; dual Faraday tick authority and unbounded projectile hold remain)
- **Overall Code Performance Score**: 74/100 (based on static analysis and architectural patterns; previous audit of this tree scored 58/100 before VFX/scan work)

---

## Analysis Methodology

### Performance Analysis Approach

- **Static Code Analysis**: Comprehensive source code review for performance anti-patterns
- **Database Query Analysis**: Review of queries, indexing strategies, and data access patterns (scoreboard, entity selectors, NBT `data get` / `data modify`, `getEntitiesOfClass`)
- **Resource Utilization Assessment**: Analysis of memory, CPU, and I/O usage patterns
- **Architecture Performance Review**: Examination of caching, scaling, and optimization strategies

### Analysis Coverage

- **Files Analyzed**: 89 source files across `src/main/java` (20), `*.mcfunction` (37), and datapack JSON (32)
- **Database Queries Reviewed**: 20 entity-selector / NBT / `getEntitiesOfClass` access patterns
- **API Endpoints Analyzed**: 16 handlers (4 SimpleChannel packets, 7 `sr_internal` commands, 4 Origins `active_self` keys) plus 8 `action_over_time` tick powers
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

No Critical issues identified.

The prior Critical finding (filled 128-block dust + per-entity recast + server-wide `force @a`) is not present in current source. Scan now emits an origin heartbeat plus an 8-point rim, colors only `tag=!sr_scanned` entities, and draws outline via a scanner-only S2C packet.

### High Performance Impact Findings

#### P-001: Faraday projectile hold walks every loaded held projectile, then writes NBT

**Location**: `src/main/resources/data/static_resonance/functions/faraday/hold_projectiles.mcfunction:3`
**Performance Impact**: 7.8 (High)
**Pattern Detected**: Unbounded entity query (no distance on the hold tick) + per-entity NBT write
**Code Context**:

```mcfunction
execute at @s as @e[type=#static_resonance:projectiles,distance=..4.8,tag=!sr_faraday_held] run function static_resonance:faraday/catch_projectile
execute as @e[type=#static_resonance:projectiles,tag=sr_faraday_held] at @s if entity @a[tag=sr_faraday,distance=..10] run function static_resonance:faraday/tick_held_projectile
```

**Impact**: Catch is correctly radius-limited at 4.8. The hold line is global: every Faraday-active player iterates all loaded arrows / fireballs / tridents / etc. tagged `sr_faraday_held` (14 types in `tags/entity_types/projectiles.json`). `tick_held_projectile` then does two `data modify entity` writes (Motion + NoGravity) which dirty the entity and sync to trackers, plus 2 forced sparks to nearby viewers. `NoGravity` only needs to be set on catch; rewriting it every 5 ticks is pure sync cost.
**Performance Cost**: 0.3–5 ms per Faraday player per 5 ticks on projectile-dense worlds; extra tracker packets from Motion writes. Cost scales with loaded held projectiles, not with cage radius.
**Recommendation**: Scope the hold tick to the cage: `execute at @s as @e[type=#static_resonance:projectiles,tag=sr_faraday_held,distance=..10]`. Leave `NoGravity` to `catch_projectile`. Prefer Java `setDeltaMovement` + `setNoGravity` on a UUID set if this is migrated later. Do not change the 4.8 catch radius or the 90-second cage feel.
**Fix Priority**: Within 1 week

#### P-002: Control Field queries every living entity in the expanding AABB every server tick

**Location**: `src/main/java/com/staticresonance/mode/ControlService.java:381`
**Performance Impact**: 7.6 (High)
**Pattern Detected**: Per-tick `getEntitiesOfClass` with no sweep interval; work grows with `FIELD_MAX_RADIUS` (30)
**Code Context**:

```java
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

**Impact**: `tickField` runs on `TickEvent.ServerTickEvent` END every tick while a session exists. Radius expands to 30 (`elapsed / 5`). The AABB is 60×5×60 at full size. First-time `applyLock(..., withVfx=true)` still calls `spawnHeadRing` (now 4 packets, previously ~84). `LivingEntity.class` includes villagers, animals, and other living types — not just hostiles. Refresh still walks the full set twice per second (`REFRESH_INTERVAL = 10`) and `refreshLockEffects` re-applies slowness, mining fatigue, and **public** glowing (3 synced effects per locked entity).
**Performance Cost**: ~0.2–2 ms/tick idle field; spikes when many living entities enter (village, farm, raid). Two concurrent fields double it. First-lock VFX is no longer the dominant cost; effect-map sync every 10 ticks can be, in a crowded field.
**Recommendation**: Run the entity sweep every 4–5 ticks; refresh potions only when remaining duration is under ~20 ticks. Keep expand/status/edge VFX on their existing cadences so the ring still grows smoothly. Optionally restrict to `#static_resonance:affectable` equivalents. Do not shrink `FIELD_MAX_RADIUS`. Leave Magnetic Lock public glowing on the single-target path.
**Fix Priority**: Within 1 week

#### P-003: Single-target lock scans a 64-block cube and raytraces every candidate

**Location**: `src/main/java/com/staticresonance/mode/ControlService.java:226`
**Performance Impact**: 7.3 (High)
**Pattern Detected**: Wide AABB + per-entity `hasLineOfSight` (block raycast) after the precise ray already missed
**Code Context**:

```java
AABB around = player.getBoundingBox().inflate(TARGET_RANGE);
for (LivingEntity living : player.serverLevel().getEntitiesOfClass(LivingEntity.class, around, e ->
        isLockable(player, e)
                && e.distanceToSqr(player) <= rangeSq
                && player.hasLineOfSight(e))) {
```

**Impact**: Predicate runs `hasLineOfSight` (clip from eye to target) for every living entity in a 128³ cube **before** the 40° cone test. In a crowded spawn or mob farm that is tens to hundreds of block raycasts on one G-press. Not a per-tick path, but a hitch on the server thread when the player misses the exact hitbox. The precise `ProjectileUtil` ray (lines 204–218) is already the fast path.
**Performance Cost**: 1–15+ ms hitch per miss-then-cone lock in dense areas.
**Recommendation**: Test cone/dot **before** LOS, inflate a tighter box (24–32) or keep 64 but cap candidates (closest 16 by distance). Keep `TARGET_RANGE = 64` if that is the intended lock fantasy.
**Fix Priority**: Within 1 week

### Medium Performance Impact Findings

#### P-004: Static Scan still selects the filled disk every pulse

**Location**: `src/main/resources/data/static_resonance/functions/sense/scan_pulse.mcfunction:24`
**Performance Impact**: 6.2 (Medium)
**Pattern Detected**: Wide entity selector every 5 ticks; work is now query-bound, not VFX-bound
**Code Context**:

```mcfunction
execute if score @s sr_scan matches 65..128 as @e[type=#static_resonance:affectable,tag=!sr_scanned,distance=0.5..128] run function static_resonance:sense/color_entity
```

**Impact**: Visual work is a 10-particle heartbeat plus 8 rim points — no longer a filled volume. `color_entity` no longer emits HP dust or a public glowing potion; it tags, runs `sr_internal scan_mark` (full command parse per new entity), walks the dimension player list for `sr_scanning`, sends one S2C outline packet, and maybe pings a player. `tag=!sr_scanned` skips already-marked entities, but the selector still walks a 128-block cylinder every 5 ticks to find untagged affectables. The expensive spike is the **first** pulse over a farm (tens to hundreds of command hops in one tick). Later pulses are query-only until a new mob walks in. New arrivals inside the disk are still discovered (intentional gameplay).
**Performance Cost**: 0.2–3 ms per later pulse in dense disks; first-contact pulse can hitch harder because each new entity is a Brigadier command, not just a tag. Packet cost is one outline packet per newly seen entity, not 18 particles per entity.
**Recommendation**: Keep radius 128 and keep filled-disk discovery. Batch outline marks from Java (scanner UUID + UUID list) so a farm pulse is one query + one packet, not N `sr_internal` dispatches. Do not switch to shell-only `r-1..r` without approving the gameplay change (mobs walking into the interior later would be missed).
**Fix Priority**: Within 1 month

#### P-005: Faraday has two tick authorities for one 90-second cage

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:157`
**Performance Impact**: 6.1 (Medium)
**Pattern Detected**: Periodic command + Java query on overlapping cadences
**Code Context**:

```java
private static void tickRepel(ServerPlayer player, long now) {
    // REPEL_RADIUS = 3.0; runs every server tick per UUID in ACTIVE_CAGES
    for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, e ->
            e.isAlive() && e != player)) {
```

Datapack path (`faraday/tick_live.mcfunction:1-10`) still reapplies 6-second resistance/fire resistance, runs `hold_projectiles`, emits 3 aura particle commands, and rewrites the actionbar every 5 ticks for up to 1800 ticks.
**Impact**: Java correctly ticks only `ACTIVE_CAGES` (good). Datapack `faraday_tick` still runs for every origin player (see P-007). Repel AABB is small (r=3) so the Java query itself is cheap; the waste is duplicated authority and the global hold inside the datapack tick (P-001).
**Performance Cost**: Sustained 0.2–1.5 ms per active cage plus datapack command dispatch; client GPU for the thin aura is modest.
**Recommendation**: Incremental, not a rewrite: add an Origins condition first (P-007), distance-limit hold (P-001), then consider moving hold/aura into the existing Java tick. Keep the 90-second cage, open/idle/close silhouette, and emergency path.
**Fix Priority**: Within 1 month

#### P-006: Control Field edge VFX is one `sendParticles` call per rim point

**Location**: `src/main/java/com/staticresonance/mode/ControlService.java:399`
**Performance Impact**: 5.6 (Medium)
**Pattern Detected**: Particle packet loop on a persistent aura
**Code Context**:

```java
int points = Math.min(24, 8 + radius / 2);
for (int i = 0; i < points; i++) {
    level.sendParticles(RING_DUST, x, center.y + 0.2, z, 1, 0, 0, 0, 0);
    if (i % 3 == 0) {
        level.sendParticles(ParticleTypes.WITCH, x, center.y + 0.25, z, 1, 0, 0, 0, 0);
    }
}
```

**Impact**: Runs every 4 ticks while the field is up (`now % 4 == 0`). At r=30 this is 24 RING_DUST + 8 WITCH ≈ 32 packets every 4 ticks (~8 packets/tick) to tracking players for up to 60 seconds. The ring is the player-facing “field is expanding” cue — do not delete it.
**Performance Cost**: Modest MSPT; visible network/GPU cost with several concurrent fields.
**Recommendation**: Batch into 1–2 `sendParticles` with count/spread along the rim, or cap points at 12. Keep the 4-tick cadence so growth still reads.
**Fix Priority**: Within 1 month

#### P-007: Faraday datapack tick has no Origins condition

**Location**: `src/main/resources/data/static_resonance/powers/systems/faraday_tick.json:1`
**Performance Impact**: 5.2 (Medium)
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

**Impact**: Every player with the origin runs `function static_resonance:faraday/tick` 4 times/sec even with the cage off. The function early-outs via two score checks (`faraday/tick.mcfunction:4-8`), but Apoli still dispatches a command. Multiply by origin player count.
**Performance Cost**: Small per player (~0.05–0.2 ms × players) but always-on.
**Recommendation**: Add a condition (`sr_faraday > 0 OR sr_faraday_cd > 0`) via scoreboard/resource, or drive cooldown entirely from Java `ACTIVE_CAGES`.
**Fix Priority**: Within 1 month

#### P-008: Four overlapping scan-step powers plus abort evaluate the same resources every 5 ticks

**Location**: `src/main/resources/data/static_resonance/powers/systems/scan_step_1.json:1`
**Performance Impact**: 5.0 (Medium)
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
**Recommendation**: One `action_over_time` with banded cost, or one Java scan service with a cost table. Do not collapse this in the same change as P-004.
**Fix Priority**: Within 2 months

#### P-009: Motion harvest reads NBT Motion triples for a large entity-type set every second

**Location**: `src/main/resources/data/static_resonance/functions/systems/motion_try.mcfunction:4`
**Performance Impact**: 4.8 (Medium)
**Pattern Detected**: Per-entity NBT `data get` in a wide type tag; extra `origins:command` score checks
**Code Context**:

```mcfunction
execute store result score #mx sr_tmp run data get entity @s Motion[0] 100
execute store result score #my sr_tmp run data get entity @s Motion[1] 100
execute store result score #mz sr_tmp run data get entity @s Motion[2] 100
```

**Impact**: `motion_charge.json` runs every 20 ticks when charge < 100. `motion_harvest` selects `#static_resonance:motion_sources` (all affectable hostiles **plus** ~35 passive types) in r=5, then `motion_try` does 3 NBT reads each. Apoli then re-checks `execute if score @s sr_mc` via `origins:command`. Villages/farms make this a dense NBT walk every second per origin player.
**Performance Cost**: 0.1–1.5 ms/s per player in animal-dense areas.
**Recommendation**: Narrow the tag to hostiles + players; read motion from Java (`getDeltaMovement`) if this stays; drop `origins:command` and use a scoreboard condition or Java charge add.
**Fix Priority**: Within 2 months

#### P-010: Radial HUD tessellates many immediate-mode strips and allocates `ItemStack`s every frame

**Location**: `src/main/java/com/staticresonance/client/ui/RadialModeMenuRenderer.java:160`
**Performance Impact**: 4.6 (Medium)
**Pattern Detected**: Per-frame allocation + many `tesselator.end()` calls on the render thread
**Code Context**:

```java
private static final int SEGMENTS_PER_WEDGE = 24;
ItemStack stack = new ItemStack(wedge.iconItem());
graphics.renderItem(stack, ix, iy);
```

**Impact**: Menu-open only (good). While open, each wedge can call `fillAnnularSector` multiple times (fill + hover rim + active edge). Each sector is a 25-iteration TRIANGLE_STRIP + immediate `tesselator.end()`. Center disc/rings add 360° strips. Four new `ItemStack`s per frame.
**Performance Cost**: Estimated 0.3–2 ms GPU/CPU per HUD frame; young-gen churn from stacks/vertex begins. Integrated GPUs during hold-R are the risk.
**Recommendation**: Cache static `ItemStack`s per wedge; reduce `SEGMENTS_PER_WEDGE` to 12; batch sectors if the menu is profiled as a hitch.
**Fix Priority**: Within 2 months

#### P-011: Scan stop still sweeps a 128-block cylinder by shared tags

**Location**: `src/main/resources/data/static_resonance/functions/sense/scan_stop.mcfunction:4`
**Performance Impact**: 4.4 (Medium)
**Pattern Detected**: Wide `@e[tag=…]` plus shared-tag correctness cost
**Code Context**:

```mcfunction
tag @e[tag=sr_scanned,distance=..128] remove sr_scanned
tag @e[tag=sr_scan_pinged,distance=..128] remove sr_scan_pinged
sr_internal scan_clear
```

**Impact**: No longer a world-wide `@e` and no longer `effect clear glowing` (public lock glow is safe). The 128-block sweep is the scan soft-cap, which is acceptable. The remaining cost is shared `sr_scanned`: if two players scan overlapping areas, the first tag owner skips the second player's `color_entity`, and one player's stop un-tags the other's finds so the next pulse re-marks them (extra packets + missed private outlines).
**Performance Cost**: 0.1–1 ms hitch on stop; multiplayer recast/missed-outline is the larger issue.
**Recommendation**: Keep the 128 bound. Own scanned UUIDs per scanner in Java so stop/clear is O(marked) and two scanners do not steal marks. `ScanOutlineClient` already stores outlines per local client.
**Fix Priority**: Within 1 month

### Low Performance Impact Findings

#### P-012: Scan outline client walks every rendered entity each tick while any outline is live

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:53`
**Performance Impact**: 3.6 (Low)
**Pattern Detected**: Full render-list scan to apply a small UUID map
**Code Context**:

```java
for (Entity entity : level.entitiesForRendering()) {
    Integer expire = EXPIRE_TICK.get(entity.getUUID());
    if (expire == null) {
        continue;
    }
    entity.setGlowingTag(clientTick < expire);
}
```

**Impact**: Early-outs when the map is empty (good). While a scan is active the client iterates the entire `entitiesForRendering()` list every tick to touch a handful of UUIDs. Entities that are unloaded at expiry may keep `setGlowingTag(true)` because the flag is never cleared.
**Performance Cost**: Tens to low hundreds of microseconds per tick in busy scenes; stuck-outline is a correctness edge, not MSPT.
**Recommendation**: Iterate `EXPIRE_TICK` keys and resolve entities (or refresh the flag only every 5–10 ticks). On expiry, attempt a UUID lookup before dropping the entry.
**Fix Priority**: Within 2 months

#### P-013: Mode/Faraday feedback goes through command parse (`performPrefixedCommand`)

**Location**: `src/main/java/com/staticresonance/mode/ModeService.java:102`
**Performance Impact**: 3.4 (Low)
**Pattern Detected**: String command parse on a hot-ish path instead of direct function invocation
**Code Context**:

```java
server.getCommands().performPrefixedCommand(source, "function " + mode.feedbackFunction());
```

**Impact**: Each mode switch and Faraday activate/emergency (`FaradayService.runFunction` at line 195) parses a command string, builds a source stack, and dispatches. Not per-tick. Mode-switch functions emit distance-limited `force` particles.
**Performance Cost**: Sub-millisecond per switch; dominated by the particle functions they call.
**Recommendation**: `server.getFunctions().execute(...)` with a cached `ResourceLocation`.
**Fix Priority**: Within 2 months

#### P-014: Client tick and HUD read Apoli resources every tick (Fabric path is reflection)

**Location**: `src/main/java/com/staticresonance/client/ui/RadialModeMenu.java:136`
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

**Impact**: Every client tick calls `hasModePower` → `ApoliResources.hasPower`. Forge backend is a few direct calls. Fabric/Connector backend (`ApoliResources.FabricBackend`) does `maybeGet` + registry + `hasPower` via reflection. HUD open additionally reads mode, charge, Faraday emergency, and control style.
**Performance Cost**: Tens of microseconds (Forge) to low hundreds (Fabric) per tick.
**Recommendation**: Cache `hasModePower` for 5–10 ticks; cache HUD resource reads for 2–3 ticks.
**Fix Priority**: Within 2 months

#### P-015: Faraday live tick re-applies 6-second effects and rewrites the actionbar every 5 ticks

**Location**: `src/main/resources/data/static_resonance/functions/faraday/tick_live.mcfunction:1`
**Performance Impact**: 2.8 (Low)
**Pattern Detected**: Redundant effect/UI refresh far more often than duration requires
**Code Context**:

```mcfunction
effect give @s minecraft:resistance 6 4 true
effect give @s minecraft:fire_resistance 6 0 true
title @s actionbar [{"text":"◆ AEGIS GRID ","color":"#4FC3F7"},{"score":{"name":"#sec","objective":"sr_tmp"}}]
```

**Impact**: Effects last 6 seconds but are reapplied 4 times per second. Actionbar is rewritten at the same rate (Java Control Field already updates status every 20 ticks).
**Performance Cost**: Minor packet/effect-map churn.
**Recommendation**: Refresh effects every 40–80 ticks; actionbar every 20 ticks. Do not shorten the 90-second cage.
**Fix Priority**: Within 2 months

#### P-016: Faraday score reads allocate via `getOrCreatePlayerScore` every tick

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:210`
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

#### P-017: Unused datapack still parsed at load

**Location**: `src/main/resources/data/static_resonance/origins/static_resonance.json:3`
**Performance Impact**: 2.2 (Low)
**Pattern Detected**: Load-time parse of dead resources
**Code Context**:

`powers/active/switch_mode.json` is a full `active_self` power but is not in the origin power list (lang already calls it unused). `powers/mode/pulse_explosion.json`, empty `functions/control/lock_apply.mcfunction`, empty `functions/overcharge/pulse.mcfunction`, and `tags/blocks/conductive.json` have no runtime callers found in this pass.
**Impact**: Origins/datapack reload still parses and registers these. No per-tick cost after load.
**Performance Cost**: One-time parse/memory at world load; maintenance noise.
**Recommendation**: Delete or move to an archive folder only after a full caller search across Java, JSON, and functions.
**Fix Priority**: Within 2 months

#### P-018: Scan outline mark walks the dimension player list

**Location**: `src/main/java/com/staticresonance/mode/ScanOutlineService.java:39`
**Performance Impact**: 2.4 (Low)
**Pattern Detected**: Linear player scan per newly marked entity; also a multiplayer correctness risk
**Code Context**:

```java
for (ServerPlayer player : serverLevel.players()) {
    if (player.getTags().contains(SCANNING_TAG)) {
        return player;
    }
}
```

**Impact**: Called once per newly scanned entity. Player-list walks are cheap at typical SMP counts. If two scanners pulse in overlapping ways, the first `sr_scanning` player in the iterator receives the packet.
**Performance Cost**: Negligible CPU; wrong-recipient outline is the real risk.
**Recommendation**: Pass the scanner from the command source / pulse owner instead of searching by tag. Same change as P-011.
**Fix Priority**: Within 1 month

---

## Code Pattern Performance Analysis

### Performance Anti-Pattern Detection

- **N+1 Query Patterns**: 3 instances detected across 3 files (`scan_pulse` filled-disk selector, Dust is now a single `afflict` call, Faraday hold global `@e` + per-projectile NBT)
- **Blocking Operations**: 2 async-convertible operations identified (command-string function dispatch; Fabric reflection on HUD reads). Minecraft world state must stay on the server thread — these are parse/reflection costs, not candidates for worker threads.
- **Memory Pressure Points**: 2 locations with excessive allocation or missing disposal (per-frame `ItemStack` in radial HUD; `ArrayList` copy of session/cage UUID sets each tick)
- **Inefficient Data Access**: 5 queries with suboptimal patterns (global held projectiles, 64-block lock AABB + LOS, field AABB every tick, motion NBT triples, scan 128-block untagged selector)
- **Concurrency Issues**: 0 shared state or synchronization concerns (server tick is single-threaded; `ConcurrentHashMap` is conservative but safe)

### Database Access Pattern Analysis

- **ORM/Query Usage**: 20 selectors / NBT / `getEntitiesOfClass` operations analyzed for efficiency patterns
- **Missing Async Patterns**: 0 database operations identified for async conversion (Minecraft server tick cannot offload entity queries)
- **Query Complexity**: 4 complex queries requiring optimization review (64-block lock + LOS, 128-block scan selector, global held projectiles, field r=30 every tick)
- **Connection Management**: No SQL connection pool; scoreboard objectives are created once (`SrScoreboards`) — healthy

### Resource Management Pattern Analysis

- **Memory Allocation Patterns**: Session maps (`SESSIONS`, `ACTIVE_CAGES`) are bounded by player count and cleaned on logout — good. Scan outline state lives on the receiving client only. HUD `ItemStack` + tessellator begins are the main client churn.
- **Garbage Collection Pressure**: Radial open frames and field-edge particle packet objects are the remaining hot allocators. Scan no longer allocates per-entity particle storms.
- **Thread/Event Loop Usage**: All gameplay work is on the server thread (correct for MC). No event-loop blocking in the Node sense; tick overruns are the equivalent risk.
- **Caching Opportunities**: Apoli resource reads (mode/charge/style) and `hasModePower` are re-fetched constantly with no TTL
- **Resource Disposal**: Logout hooks remove sessions, cages, debounce entries, and client outlines. No leaked server maps found. Client glowing flag can stick if the entity is unloaded at expiry (P-012).

---

## Architecture Performance Assessment

### Data Access Layer Analysis

- Persistence is scoreboard + Apoli resources + entity tags/NBT. Objectives are ensured once at server start and login (`SrScoreboards`) — no per-tick `addObjective` in the hot path except Faraday `ensureObjectives` on activate/query.
- Entity “queries” are unindexed world scans. Distance-limited selectors are used for catch (4.8), Dust (7), motion (5), scan stop (128), and all remaining particle viewers. Faraday hold tick and the lock cone are the two selectors that are still not similarly budgeted.
- Scan outline no longer uses a synced potion as a query/VFX side channel. The remaining shared tag `sr_scanned` is a correctness index, not a particle amplifier.
- No caching layer in front of Apoli resource reads.

### Application Layer Analysis

- Java correctly owns charge spend for Control/Faraday (atomic, no double-spend). Session maps avoid scanning the whole player list (`FaradayService` comment + `ACTIVE_CAGES`; `ControlService.SESSIONS`).
- Dual tick authorities (Origins `action_over_time` **and** Forge `ServerTickEvent`) still duplicate Faraday work (datapack tick + Java repel).
- Functions invoked via `performPrefixedCommand` add parse overhead versus the function manager.
- Debounce on mode change (4 ticks) is a good anti-spam control.
- Network protocol `"5"` adds one scanner-only S2C packet. Dedicated-server safety depends on `DistExecutor` / client-only handler (present). Both sides must use the same jar.

### Frontend Analysis (if applicable)

- Not a web app; client surface is the radial mode HUD (`RadialModeMenu` + `RadialModeMenuRenderer`) and `ScanOutlineClient`.
- Core Web Vitals do not apply. Equivalent risks: HUD frame time (many immediate draws), particle overdraw (`force` + counts), and leftover local glowing flags.
- No extra JS bundle. Texture use is modest (GUI textures only).
- `ItemStack` icons per frame are the main HUD cost. Menu is hold-to-open and early-returns when closed — good.
- Scan outline uses vanilla `setGlowingTag` locally so other clients do not draw it. That is the correct client/server split.

### Infrastructure Analysis

- No CDN, HTTP compression, or connection pool. Multiplayer cost is Minecraft particle/entity packets.
- Particle commands now use `force @a[distance=..N]` rather than bare `force @a`. `force` still bypasses the client particle slider inside that radius (intentional so Minimal clients see signature casts).
- Gradle uses `org.gradle.daemon=false` and `-Xmx3G` (`gradle.properties`) — slower builds, not runtime.
- No APM, Spark/Timings hooks, or particle budget config. Observability is logs only.

---

## Performance Bottleneck Analysis

### Top Performance Bottlenecks

| Rank | Component | Issue | Impact Score | Estimated Cost |
|------|-----------|-------|--------------|----------------|
| 1 | Faraday hold projectiles | Global `@e` + NBT Motion writes | 7.8 | 0.3–5 ms / 5 ticks / cage |
| 2 | Control Field tick | `getEntitiesOfClass` every tick to r=30 | 7.6 | 0.2–2 ms/tick per field |
| 3 | Magnetic Lock cone | 64-block AABB + LOS per living entity | 7.3 | 1–15 ms hitch on miss |
| 4 | Static Scan selector | 128-block untagged disk every 5 ticks | 6.2 | 0.2–3 ms/pulse in dense disks |
| 5 | Faraday dual tick | Datapack aura/hold + Java repel | 6.1 | 0.2–1.5 ms sustained / cage |
| 6 | Field edge VFX | Up to 32 `sendParticles` / 4 ticks | 5.6 | ~8 packets/tick / field |
| 7 | Faraday AOT | Unconditional command every 5 ticks | 5.2 | 0.05–0.2 ms × origin players |
| 8 | Scan-step powers | Five overlapping Apoli polls | 5.0 | modest CPU × origin players |

---

## Technical Recommendations

### Immediate Performance Fixes

1. Distance-limit Faraday hold to `distance=..10` from the cage owner (P-001). One-line datapack change; preserves catch radius and 90-second feel.
2. Interval the Control Field living sweep to every 4–5 ticks; leave expand, status, and edge VFX on their current cadence (P-002).
3. Reorder Magnetic Lock cone: distance + cone/dot before `hasLineOfSight`; optional candidate cap (P-003). Keep 64-block lock range unless gameplay is reopened.

### Performance Enhancements

1. Add an Origins condition to `faraday_tick` so idle origin players do not dispatch a function (P-007). Refresh Faraday potions/actionbar less often (P-015).
2. Batch `spawnFieldEdge` into fewer `sendParticles` calls (P-006).
3. Own scan marks per scanner in Java so overlapping scans do not skip or steal outlines (P-011, P-018). Do not cut scan radius below 128.

### Architecture Improvements

1. Treat Java as Faraday's single tick authority only after P-001 and P-007 land (incremental, with a rollback point).
2. Collapse four scan-step powers + abort into one cost table once scan ownership is per-player (P-008).
3. Cache Apoli resource reads on the client (P-014); invoke functions via the function manager (P-013).

---

## Code Optimization Examples

### Scope Faraday projectile hold (references P-001)

**Before (Inefficient)**:

```mcfunction
execute as @e[type=#static_resonance:projectiles,tag=sr_faraday_held] at @s if entity @a[tag=sr_faraday,distance=..10] run function static_resonance:faraday/tick_held_projectile
```

**After (Optimized)**:

```mcfunction
execute at @s as @e[type=#static_resonance:projectiles,tag=sr_faraday_held,distance=..10] run function static_resonance:faraday/tick_held_projectile
```

**Expected Improvement**: Query cost drops from all loaded held projectiles to a 10-block neighborhood (~70–95% in combat worlds). Cage catch radius and hold feel stay the same.

### Interval Control Field entity sweeps (references P-002)

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
if (now % 4 == 0) {
    for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, e ->
            e.isAlive() && e != player && e.distanceToSqr(pos) <= r * r)) {
        if (!isUnderLock(living)) {
            applyLock(living, level, true, true);
        } else if (now % REFRESH_INTERVAL == 0) {
            refreshLockEffects(living);
        }
    }
}
```

**Expected Improvement**: ~75% fewer living-entity scans per field while the ring still expands every 5 ticks and edge VFX still draws every 4 ticks. Worst-case lock apply delay is 4 ticks (~200 ms).

### Test lock cone before line-of-sight (references P-003)

**Before (Inefficient)**:

```java
for (LivingEntity living : player.serverLevel().getEntitiesOfClass(LivingEntity.class, around, e ->
        isLockable(player, e)
                && e.distanceToSqr(player) <= rangeSq
                && player.hasLineOfSight(e))) {
    // cone test happens after LOS
}
```

**After (Optimized)**:

```java
for (LivingEntity living : player.serverLevel().getEntitiesOfClass(LivingEntity.class, around, e ->
        isLockable(player, e) && e.distanceToSqr(player) <= rangeSq)) {
    Vec3 toCenter = living.getEyePosition().subtract(eye);
    double dist = toCenter.length();
    if (dist < 0.25) continue;
    double dot = look.dot(toCenter.scale(1.0 / dist));
    if (dot < minDot) continue;
    if (!player.hasLineOfSight(living)) continue;
    // score and keep best
}
```

**Expected Improvement**: LOS raycasts drop from “every living in 128³” to “entities already inside the 40° cone”. Typical dense-area hitch falls from multi-ms to sub-ms; 64-block lock range is unchanged.

### Apply scan outlines from the UUID map (references P-012)

**Before (Inefficient)**:

```java
for (Entity entity : level.entitiesForRendering()) {
    Integer expire = EXPIRE_TICK.get(entity.getUUID());
    if (expire == null) continue;
    entity.setGlowingTag(clientTick < expire);
}
```

**After (Optimized)**:

```java
EXPIRE_TICK.entrySet().removeIf(e -> {
    if (clientTick >= e.getValue()) {
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.getUUID().equals(e.getKey())) {
                entity.setGlowingTag(false);
                break;
            }
        }
        return true;
    }
    return false;
});
```

Prefer a UUID→entity lookup if a public API is available on the client level; the point is to iterate the small map, not the render list, on the common path.

**Expected Improvement**: Client work becomes O(outlined entities) instead of O(rendered entities) while any scan is live.

---

## Performance Optimization Priorities

### Phase 1: Critical Performance Fixes

- [ ] No critical findings in current source. Do not re-apply the previous scan-radius cap or `force` → `normal` sweep.

### Phase 2: High Impact Optimizations

- [ ] Distance-limit Faraday hold tick (P-001)
- [ ] Interval Control Field living sweep; do not change max radius (P-002)
- [ ] Cone-before-LOS (and optional candidate cap) on Magnetic Lock (P-003)

### Phase 3: Medium Impact Improvements

- [ ] Per-scanner scan mark ownership; keep 128 radius and filled-disk discovery (P-004, P-011, P-018)
- [ ] Faraday AOT condition + slower effect/actionbar refresh (P-007, P-015)
- [ ] Batch field-edge particles (P-006)
- [ ] Narrow motion-source tag / drop `origins:command` recheck (P-009)
- [ ] Cache radial `ItemStack`s (P-010)

### Phase 4: Performance Monitoring and Fine-tuning

- [ ] Capture Spark/Timings while Scan, Faraday, and Control Field run in a village and at a skeleton farm
- [ ] Track MSPT, TPS, particle packets/sec, and entity-selector counts for 1 / 4 / 8 origin players
- [ ] Add a particle/query budget comment or config only after those measurements exist
- [ ] Invoke datapack functions via the function manager (P-013); read-only Faraday scores (P-016)
- [ ] Cache client Apoli reads (P-014); iterate outline map not render list (P-012)
- [ ] Archive unused powers/functions only after a full caller search (P-017)

---

## Estimated Performance Improvement Impact

### Performance Gains by Priority

| Priority Level | Expected Improvement | Implementation Complexity |
|----------------|---------------------|--------------------------|
| Critical Fixes | None remaining; prior VFX storm already removed | n/a |
| High Impact | 50–80% less Faraday hold query work; ~75% fewer field living scans; lock-miss hitch down from multi-ms to sub-ms in crowds | Low–medium (localized edits) |
| Medium Impact | Lower always-on Apoli dispatch; fewer field-edge packets; no cross-scanner recast | Medium (ownership + conditions) |
| Low Impact | Smaller HUD/client tick noise; cleaner load set | Low |

### Resource Utilization Improvements

- **Database Load**: Not applicable (no SQL). Entity-selector load is the analog — P-001/P-002/P-003 cut the three remaining unbounded or per-tick scans.
- **Memory Usage**: Small drop from cached HUD stacks and read-only score lookups; session maps are already bounded.
- **CPU Utilization**: Main gain is server-thread MSPT during Faraday + Field in populated areas, not idle TPS.
- **Concurrency/Throughput**: More players can run cages/fields before tick-skip; VFX no longer scales with the entire online player list.

---

## Performance Monitoring Setup Recommendations

### Observability Recommendations

- **Metrics**: Spark (Forge) or vanilla Timings for MSPT/TPS; count `getEntitiesOfClass` and `@e` in the Faraday hold, Field sweep, and Scan pulse. No Prometheus/Datadog in this stack.
- **Logging**: Keep ability activate/fail logs. Do not add per-tick logs. Include player count, scan radius, field radius, and held-projectile count on any future debug command.
- **Distributed Tracing**: Not applicable. Treat one server tick as the trace: datapack AOT → Java `ServerTickEvent` → particle/effect packets.

### Recommended Performance Tracking

- MSPT / TPS with no abilities, then Scan at r=128, Faraday for 90s, Control Field at r=30
- Approximate particle packets per second and recipient count (distance filters vs all online)
- Entity-selector hits per 5-tick window
- Client FPS for a nearby viewer and a 64-block-distant viewer
- Alert if MSPT stays above 50 (TPS < 20) while a single origin player uses Faraday + Field in a village

### Performance Testing Recommendations

- Load: open field, village, skeleton spawner / projectile farm, 4–8 origin players using Scan + Field + Faraday together
- Lifecycle: cancel ability, logout, dimension change, `/reload`
- Tools: Spark sampler on the dedicated server; F3 + `pie` / entity counts on the client; record particle slider Minimal vs All
- Do not treat this static report as a substitute for those numbers

---

## Summary

This performance analysis identified **0 critical**, **3 high**, **8 medium**, and **7 low** performance issues across the application stack. The analysis focused on code patterns, entity/NBT “queries”, resource utilization, and architectural performance.

**Key Strengths Identified**:

- Session maps (`SESSIONS`, `ACTIVE_CAGES`) tick only active players and clean up on logout
- Scan VFX is a rim + heartbeat, not a filled 128-block dust volume; HP-color dust and public scan glowing are gone
- No remaining bare `force @a`; viewers are distance-limited while `force` still reaches nearby Minimal clients
- `spawnHeadRing` is 4 batched `sendParticles` calls, not an 80+ positioned loop
- Dust Curtain uses one selector and a thinner burst; Faraday open/idle/close counts are reduced
- Client-only scan outline (protocol `"5"`) keeps blood-read information off other players' screens
- Charge spend for Control/Faraday is Java-owned and atomic

**Critical Areas Requiring Immediate Attention**:

- Faraday hold still iterates every loaded `sr_faraday_held` projectile (P-001)
- Control Field still `getEntitiesOfClass` every tick out to r=30 (P-002)
- Magnetic Lock miss path still LOS-tests a 64-block cube before the view cone (P-003)
- Overlapping scans still share `sr_scanned` and resolve the scanner by world tag (P-011, P-018)
- Faraday still has two tick authorities and an unconditional Origins interval (P-005, P-007)

**Expected Overall Performance Improvement**: Applying the three high-impact tick/query fixes should remove most remaining populated-world MSPT spikes without changing scan radius 128, Faraday's 90-second cage, Dust r=7, or signature `force` VFX. Combined with the VFX work already in tree, the hot path is now query-shaped rather than particle-storm-shaped. Confirm with Spark before calling the remaining items done.
