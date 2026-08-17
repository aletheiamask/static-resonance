## Executive Summary

### Audit Overview

- **Target System**: Static Resonance (`static_resonance` / `com.staticresonance`) 1.0.15
- **Analysis Date**: 2026-08-17
- **Analysis Scope**: Full-stack Minecraft Forge 1.20.1 content mod (server tick paths, remaining Origins/Apoli datapack powers, client HUD and outline renderers)
- **Technology Stack**: Minecraft 1.20.1, Forge 47.2.0, Origins/Apoli (Forge + Fabric-via-Connector), Java 17, Gradle, scoreboard + Apoli resources as persistence, Forge SimpleChannel protocol `"9"`

This is a re-audit of the tree after the 1.0.15 remediations (motion harvest in Java, packet caps, Circuit section culling, side-split `hasModePower` cache, Scan snapshot, TickGate, radial `try/finally` batch). Prior 1.0.14 items that were verified fixed are not re-filed.

### Performance Assessment Summary

| Performance Level | Count | Percentage |
|-------------------|-------|------------|
| Critical Issues   | 0     | 0%         |
| High Impact       | 4     | 29%        |
| Medium Impact     | 6     | 43%        |
| Low Impact        | 4     | 29%        |
| **Total**         | **14** | **100%** |

### Key Analysis Results

- **Performance Anti-Patterns**: 0 critical patterns remaining after 1.0.15; 4 high-impact patterns still fire on skill-active hot paths
- **Code Optimization Opportunities**: 4 high-impact optimizations (Circuit collect-all, Faraday effect refresh, Scan client rebuild, Faraday global combat tax)
- **Architecture Assessment**: 8/10 performance best practices implemented (early-outs, caps, caches, logout cleanup; Faraday duration and Circuit early-exit still missing)
- **Overall Code Performance Score**: 79/100 (based on static analysis of tick/render paths after 1.0.15 remediations)

---

## Analysis Methodology

### Performance Analysis Approach

- **Static Code Analysis**: Comprehensive source code review for performance anti-patterns
- **Database Query Analysis**: Review of queries, indexing strategies, and data access patterns
- **Resource Utilization Assessment**: Analysis of memory, CPU, and I/O usage patterns
- **Architecture Performance Review**: Examination of caching, scaling, and optimization strategies

### Analysis Coverage

- **Files Analyzed**: 80 source files across 2 trees (41 Java under `src/main/java`, 39 datapack `.json`/`.mcfunction` under `src/main/resources/data`)
- **Database Queries Reviewed**: 20 datapack command/tick paths (16 `.mcfunction` files + 4 `origins:action_over_time` / `execute_command` powers). There is no SQL/ORM layer.
- **API Endpoints Analyzed**: 11 SimpleChannel packet types across 1 channel (`ModNetwork`, protocol `"9"`)
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

No critical issues identified.

The 1.0.14 critical class (unbounded S2C `ArrayList` decode, per-tick NBT `data get entity` harvest, naive Circuit 33³ `getBlockState`) is no longer present. `PacketLimits.readCount` throws on oversized counts, motion harvest is Java, and Circuit sight is section-culled with a 128-mark cap.

### High Performance Impact Findings

#### P-001: Circuit sight still walks the full 33-radius volume before the 128-mark cap

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:338`
**Performance Impact**: 7.8 (High)
**Pattern Detected**: Unbounded collect-then-truncate; early-exit cap applied only after the scan
**Code Context**:

```java
List<CircuitOutlinePacket.Entry> found = new ArrayList<>();
// ... section-culled 33-radius y→z→x walk ...
found.add(new CircuitOutlinePacket.Entry(pos, isWorking(level, pos, state, inf)));
// after the entire volume:
if (found.size() > MAX_MARKS) {
    found.sort((a, b) -> Integer.compare(a.pos().distManhattan(origin), b.pos().distManhattan(origin)));
    found.subList(MAX_MARKS, found.size()).clear();
}
```

**Impact**: Circuit mode still refreshes when the player moves 4 blocks or a nearby block changes (`SCAN_INTERVAL = 20`, `MOVE_SNAP = 4`). Air / non-circuit sections skip, but any section whose palette contains `#static_resonance:circuit` walks its clipped 16³ cells. In a redstone megabase that is thousands of wire blocks inside r=16: tens of thousands of palette reads, one `BlockPos` + `Entry` + optional `InterfereKey` per hit, then a sort of the whole list to keep 128. Spike lands on the server thread and can hitch the 50 ms tick.
**Performance Cost**: ~20–80k section `getBlockState` calls plus thousands of allocations per refresh in a dense factory; 0.5–8 ms typical, 15–40 ms possible on a large computer. Idle / sparse wiring is cheap because of `hasOnlyAir` / `maybeHas`.
**Recommendation**: Walk Manhattan shells from the player outward and stop at `MAX_MARKS` (128). Keep the dense-once warning. Do not change `RADIUS = 16` or client `pick(16)`.
**Fix Priority**: Within 1 week

#### P-002: Faraday repel refreshes slowness on every living entity every 2 ticks

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:184`
**Performance Impact**: 7.5 (High)
**Pattern Detected**: High-frequency mob-effect write (effect-packet storm) inside a 10-block living-entity scan
**Code Context**:

```java
if (now % 2 == 0) {
    tickRepel(player, now);
    catchNearby(player);
}
// tickRepel:
for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, e ->
        e.isAlive() && e != player)) {
    // ...
    living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 15, 2, true, false, true));
    // or amp 0 in the outer ring
}
```

**Impact**: While a cage is up (`sr_faraday > 0`), every other tick scans living entities in a 20×4×20 AABB and writes slowness (duration 15) to each body in the mid/outer ring. Vanilla re-sends the effect to trackers on each refresh. A 40-mob farm next to an active cage is ~20 effect packets per tick (400/s) plus particles every 5 ticks. A second `getEntities` for projectiles runs on the same cadence (`catchNearby`).
**Performance Cost**: 0.2–2 ms CPU plus 50–400 extra packets/s per active cage in a dense mob area. Single-player wilderness: negligible.
**Recommendation**: Apply slowness with duration 40–60 and refresh only when remaining duration `< 20` (same pattern as `ControlService.needsLockRefresh`). Merge `tickRepel` and `catchNearby` into one entity iteration. Keep the 10 / 7 / 5 radius feel.
**Fix Priority**: Within 1 week

#### P-003: Scan client rebuilds the living-entity cache every client tick

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:104`
**Performance Impact**: 7.2 (High)
**Pattern Detected**: Per-tick wide AABB entity query on the render/client tick thread
**Code Context**:

```java
rebuildCache(level, mc.player);
// rebuildCache:
AABB search = player.getBoundingBox().inflate(ScanOutlineService.SCAN_RADIUS); // 32
for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, search,
        e -> MARKED.contains(e.getUUID()))) {
```

**Impact**: After a scan pulse, the client walks every living entity in a ~66³ box every tick (20 Hz) to resolve up to 128 UUIDs. Farms, raids, and player hubs make this a client FPS hitch even though the server pulse is one-shot. Render then draws two `renderLineBox` calls per cached body every frame; flecks spawn every 4 ticks.
**Performance Cost**: 0.3–4 ms/tick client-side with 100–400 nearby livings; plus 256 line boxes/frame at the 128 cap.
**Recommendation**: Rebuild every 2–4 ticks, or resolve UUIDs via the client entity map when the entity is already tracked and only fall back to the AABB for newly loaded bodies. Keep prune-only-observed-dead and do not evict out-of-range marks.
**Fix Priority**: Within 1 week

#### P-004: Faraday combat listeners tax the whole server while any cage is active

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:200`
**Performance Impact**: 7.0 (High)
**Pattern Detected**: Global event fan-in gated only by “any cage / held shot exists”
**Code Context**:

```java
public static void onProjectileImpact(ProjectileImpactEvent event) {
    if (idleCombat()) {
        return;
    }
    if (shouldBlock(event.getProjectile())) {
        event.setCanceled(true);
    }
}
```

`idleCombat()` is `ACTIVE_CAGES.isEmpty() && HELD.isEmpty()`. Once one player raises a cage, every projectile impact and every `LivingAttackEvent` / `LivingHurtEvent` on the server enters `shouldBlock` → `isInterceptable` → `nearestCage` (iterate all cages, `distanceToSqr`). TACZ / high-arrow PvP makes this a per-bullet tax for 30 seconds.

**Impact**: One Overcharge player paying 50 charge turns on a server-wide intercept filter. Correctness is fine; cost scales with world combat volume, not with nearby threats.
**Performance Cost**: A few microseconds per event × hundreds of bullets/attacks per second ≈ 0.5–3 ms extra server time during gunfights with a cage up.
**Recommendation**: Spatial hash or chunk-bucket of cage positions; skip `shouldBlock` when the event entity is farther than `CATCH_RADIUS` from every cage (cheap AABB / chunk key test before `nearestCage`). Keep `idleCombat()` for the zero-cage case.
**Fix Priority**: Within 1 week

### Medium Performance Impact Findings

#### P-005: Faraday duration, cooldown, and resistance still tick through Brigadier

**Location**: `src/main/resources/data/static_resonance/powers/systems/faraday_tick.json:1`
**Performance Impact**: 6.2 (Medium)
**Pattern Detected**: Synchronous command-function bucket on a 5-tick interval
**Code Context**:

```json
{
  "type": "origins:action_over_time",
  "interval": 5,
  "condition": { "type": "origins:or", "conditions": [
    { "type": "origins:scoreboard", "objective": "sr_faraday", "comparison": ">", "compare_to": 0 },
    { "type": "origins:scoreboard", "objective": "sr_faraday_cd", "comparison": ">", "compare_to": 0 }
  ]},
  "entity_action": {
    "type": "origins:execute_command",
    "command": "function static_resonance:faraday/tick"
  }
}
```

`faraday/tick_live.mcfunction` then `effect give` resistance + fire resistance (6 s) and runs `faraday/particles` (3 `force` particle commands) every 5 ticks while the cage is up.

**Impact**: Origins evaluates two scoreboard conditions for every origin player every 5 ticks. When `sr_faraday` or `sr_faraday_cd` is positive it parses and runs a function on the server thread. Particle `force @a[distance=..24]` is extra bandwidth. Java already owns intercept/repel; this leftover is the duration clock.
**Performance Cost**: ~0.1–0.4 ms per origin player every 5 ticks when cage or CD is live; particle packets to nearby viewers.
**Recommendation**: Move `sr_faraday` / `sr_faraday_cd` decrement, resistance refresh, and silhouette particles into `FaradayService.onServerTick`. Keep `activate` / `deactivate` functions as one-shot FX if desired.
**Fix Priority**: Within 1 month

#### P-006: Circuit neighbor-notify handler allocates a key on every redstone update while any toggle is live

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:235`
**Performance Impact**: 5.8 (Medium)
**Pattern Detected**: Global event listener + per-event allocation
**Code Context**:

```java
public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
    if (INTERFERENCES.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) {
        return;
    }
    Interference inf = INTERFERENCES.get(InterfereKey.of(level.dimension(), event.getPos()));
```

**Impact**: `INTERFERENCES.isEmpty()` is a good idle gate. While even one wire is forced for 160 ticks, every neighbor notify on the server (redstone clocks, pistons, observers) allocates `InterfereKey` and hits the map. A 20 Hz clock tower next to an unrelated Circuit toggle pays this tax for 8 seconds.
**Performance Cost**: One record + CHM get per notify; thousands/tick on a large computer ≈ 0.2–1.5 ms extra while any interference exists.
**Recommendation**: Reuse a thread-local / packed-long lookup (`dim` + `pos.asLong()`) without allocating, or skip the event unless the notify pos is in a small set of live interference positions.
**Fix Priority**: Within 1 month

#### P-007: Control Field sweeps a growing 30-block cylinder every 4 ticks

**Location**: `src/main/java/com/staticresonance/mode/ControlService.java:460`
**Performance Impact**: 5.2 (Medium)
**Pattern Detected**: Session-gated but wide living-entity scan + effect writes
**Code Context**:

```java
if (now % SWEEP_INTERVAL == 0) { // 4
    double r = Math.max(0.5, session.radius); // grows to 30
    AABB box = new AABB(pos.x - r, pos.y - 2, pos.z - r, pos.x + r, pos.y + 3, pos.z + r);
    for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, ...)) {
        if (!isUnderLock(living)) {
            applyLock(...); // 3 addEffect + optional hand capture
        } else if (needsLockRefresh(living)) {
            refreshLockEffects(...);
        }
    }
}
```

**Impact**: One field session (40 charge, 60 s, stand still) is gated well. At full radius the AABB is 60×5×60. A village / raid / farm inside that cylinder gets a living scan 5 times per second and three effects per newly locked body. Refresh is already duration-gated (`REFRESH_WHEN_REMAINING = 20`).
**Performance Cost**: 0.2–1.5 ms per sweep at r=30 in a populated area. Zero when no session.
**Recommendation**: Keep the feel. Optionally scan only the newly expanded ring when `radius` grows, and reuse the existing lock-refresh gate (already good).
**Fix Priority**: Within 1 month

#### P-008: Scan outline draws two see-through line boxes per marked entity every frame

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:131`
**Performance Impact**: 5.0 (Medium)
**Pattern Detected**: Per-frame GPU line-box duplication at the 128 cap
**Code Context**:

```java
for (LivingEntity living : CACHED) {
    AABB box = living.getBoundingBox().inflate(0.06);
    LevelRenderer.renderLineBox(pose, lines, box, rgb[0], rgb[1], rgb[2], 0.95f);
    LevelRenderer.renderLineBox(pose, lines, box.inflate(0.05), rgb[0], rgb[1], rgb[2], 0.35f);
}
```

**Impact**: At 128 marks this is 256 `renderLineBox` submissions through a no-depth `RenderType` (`SeeThroughRender`, 256 KiB buffer). Combined with P-003 this is the Scan FPS cliff. Circuit outlines (one box per mark, precomputed AABB) are cheaper.
**Performance Cost**: 0.3–2 ms GPU/CPU per frame at the cap; less with a handful of marks.
**Recommendation**: One box per entity, or drop the outer halo below 60 marks. Keep see-through; that is the feature.
**Fix Priority**: Within 1 month

#### P-009: Circuit `applyForce` runs every server tick for every live interference

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:284`
**Performance Impact**: 4.8 (Medium)
**Pattern Detected**: Per-tick re-application with a cheap already-forced guard
**Code Context**:

```java
applyForce(level, inf.pos, inf.saved, inf.forceOn, false);
```

`alreadyForced` usually returns true after the first pulse, so later ticks are `getBlockState` + property compares. `TOGGLE_TICKS = 160`, so each F-toggle pays 160 of those. Multiple players stacking toggles scale linearly.

**Impact**: Not a setBlock storm when the guard works. Still unnecessary work versus “apply on start, on neighbor change, and on expire.”
**Performance Cost**: ~5–20 µs per interference per tick; tens of interferences stay under 0.5 ms.
**Recommendation**: Apply on create + `NeighborNotifyEvent` for that pos + expire. Leave the 160-tick duration.
**Fix Priority**: Within 1 month

#### P-010: Look-cone fallback scans a 32-block inflate and runs line-of-sight per candidate

**Location**: `src/main/java/com/staticresonance/mode/LookTargets.java:54`
**Performance Impact**: 4.5 (Medium)
**Pattern Detected**: Wide entity scan + `hasLineOfSight` only on the ray-miss path
**Code Context**:

```java
double fallback = Math.min(range, 32.0);
AABB around = player.getBoundingBox().inflate(fallback);
for (LivingEntity living : player.level().getEntitiesOfClass(LivingEntity.class, around, ...)) {
    // ...
    if (dot < minDot || !player.hasLineOfSight(living)) {
        continue;
    }
}
```

**Impact**: Control lock, Dust burst/zone, and radial ally snapshot use this. It is cast-gated (or once on radial open), not per-tick. A miss in a 32-block mob farm still walks every living body and clips a ray per candidate. Do not shrink the 64-block precise ray or 40° cone — that is play-feel.
**Performance Cost**: 0.2–2 ms on a dense miss; ~0 on a direct ray hit.
**Recommendation**: Cap cone candidates (e.g. 32 nearest) before LOS. Keep `DEFAULT_RANGE = 64` and `DEFAULT_CONE_DEG = 40`.
**Fix Priority**: Within 2 months

### Low Performance Impact Findings

#### P-011: Scan abort still polls two Apoli resources every 5 ticks

**Location**: `src/main/resources/data/static_resonance/powers/systems/scan_abort.json:1`
**Performance Impact**: 3.2 (Low)
**Pattern Detected**: Redundant timed poll after Java already stops scan on mode change
**Code Context**:

```json
"type": "origins:action_over_time",
"interval": 5,
"condition": { "type": "origins:and", "conditions": [
  { "type": "origins:resource", "resource": "static_resonance:systems/scan_active", "comparison": "==", "compare_to": 1 },
  { "type": "origins:resource", "resource": "static_resonance:systems/mode", "comparison": "!=", "compare_to": 1 }
]}
```

`ModeService.setMode` already calls `ScanOutlineService.stop` when leaving Sense. The power is a safety net that still wakes every origin player every 5 ticks for two resource reads.

**Impact**: Cheap Apoli reads; `execute_command scan_stop` only if the Java path was skipped.
**Performance Cost**: Microseconds per origin player per 5 ticks.
**Recommendation**: Keep as a rare safety net, or fold the check into an existing Java tick that already runs for scanners.
**Fix Priority**: Within 2 months

#### P-012: `hasModePower` TTL is exclusive, so 20-tick work always misses the cache

**Location**: `src/main/java/com/staticresonance/mode/ModeService.java:42`
**Performance Impact**: 3.0 (Low)
**Pattern Detected**: Off-by-one cache window aligned with harvest interval
**Code Context**:

```java
if (cached != null && now - cached.at < POWER_CACHE_TTL) { // TTL = 20
    return cached.has;
}
```

`MotionHarvestService` runs when `gameTime % 20 == 0`. A value stored at tick 0 expires at tick 20 (`20 < 20` is false). Harvest therefore always does a live `ApoliResources.hasPower`. Climb and F still hit the cache on the other 19 ticks.

**Impact**: One extra Apoli `hasPower` per origin player per second.
**Performance Cost**: Tens of microseconds per player per second.
**Recommendation**: Use `<= POWER_CACHE_TTL` or TTL 21. Keep the `(uuid, isClientSide)` key.
**Fix Priority**: Within 2 months

#### P-013: Wet charge drain still fires a Brigadier particle command

**Location**: `src/main/resources/data/static_resonance/powers/systems/charge_tick.json:33`
**Performance Impact**: 2.5 (Low)
**Pattern Detected**: `origins:execute_command` on a 35-tick interval when wet
**Code Context**:

```json
"type": "origins:execute_command",
"command": "particle minecraft:bubble ~ ~1 ~ 0.3 0.4 0.3 0.02 4 force @a[distance=..24]"
```

The `+2` / `-3` charge math is native `change_resource` (fine). Only the bubble line pays Brigadier, and only while wet/rained.

**Impact**: Rare compared to Faraday’s 5-tick function.
**Performance Cost**: One command parse every 1.75 s per wet origin player.
**Recommendation**: `ServerLevel.sendParticles` from a Java water-drain tick if Faraday’s clock is moved anyway.
**Fix Priority**: Within 2 months

#### P-014: Dust zone allocates a `Vec3` and two particle option objects every 4 ticks

**Location**: `src/main/java/com/staticresonance/mode/DustService.java:180`
**Performance Impact**: 2.2 (Low)
**Pattern Detected**: Per-interval allocation of immutable value objects
**Code Context**:

```java
Vec3 pos = new Vec3(zone.x, zone.y, zone.z);
level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState()), ...);
```

**Impact**: One zone, 10 seconds, 50 ticks of work. Allocation is tiny versus the entity scan.
**Performance Cost**: A handful of young-gen objects per 4 ticks per zone.
**Recommendation**: Store a static `BlockParticleOption` (as Control already does for `RING_DUST`) and pass raw coordinates to `sendParticles`.
**Fix Priority**: Within 2 months

---

## Code Pattern Performance Analysis

### Performance Anti-Pattern Detection

- **N+1 Query Patterns**: 0 instances detected across 0 database files (no ORM/SQL)
- **Blocking Operations**: 4 remaining `origins:execute_command` paths (`faraday_tick`, `charge_tick` particle, `scan_abort`, Faraday emergency `sr_internal`) plus one-shot mode-feedback / scan-start functions
- **Memory Pressure Points**: 4 locations with excessive allocation or missing disposal (Circuit `found` list in dense wiring, Faraday `InterfereKey` per notify, Scan client per-tick entity list, Dust per-interval particle options)
- **Inefficient Data Access**: 3 queries with suboptimal patterns (Circuit full-volume collect, Faraday dual entity scans, Scan client 32-inflate every tick)
- **Concurrency Issues**: 0 shared-state bugs found; maps are `ConcurrentHashMap` and logout/login/dim handlers clear player keys. Faraday `GUNSHOT_TYPES` and `MODE_POWER_CACHE` are bounded by entity-type count / online players.

### Database Access Pattern Analysis

- **ORM/Query Usage**: 0 SQL queries. Persistence is vanilla scoreboard (`sr_faraday`, `sr_faraday_cd`, `sr_mc`) and Apoli resource powers. 20 datapack command/tick paths reviewed.
- **Missing Async Patterns**: 0 — Minecraft 1.20.1 game logic must run on the server thread. Offloading Circuit scan to a worker would desync block state.
- **Query Complexity**: 1 remaining expensive command tree (`function static_resonance:faraday/tick` → `tick_active` → `tick_live` → `particles`)
- **Connection Management**: Not applicable (no connection pool). Analog: Forge `SimpleChannel` is a single protocol-`"9"` channel with bounded list codecs.

### Resource Management Pattern Analysis

- **Memory Allocation Patterns**: Circuit dense-scan `ArrayList<Entry>` is the largest spike. Scan/Circuit outline maps are capped at 128. Ally list capped at 256 on the wire.
- **Garbage Collection Pressure**: Faraday `addEffect` every 2 ticks (P-002) and Circuit `InterfereKey` per notify (P-006) are the young-gen chatter sources.
- **Thread/Event Loop Usage**: All hot work is on the server thread or the client render/tick thread. No worker offload, no blocking I/O in tick handlers.
- **Caching Opportunities**: `hasModePower` (20-tick, side-split), Fabric Apoli `powerTypes`, Faraday `GUNSHOT_TYPES`, Ally `CACHE`. Charge / mode index are still live Apoli reads (correct — they change often).
- **Resource Disposal**: Logout clears TickGate, mode cache, Circuit sight, Faraday cage set, Scan marks, Dust zones, Control sessions/hands, Ally cache. Faraday `HELD` entries drop when the entity dies or hold expires.

---

## Architecture Performance Assessment

### Data Access Layer Analysis

- There is no database. Authority is Java maps + Apoli resources + a handful of dummy scoreboard objectives created once in `SrScoreboards.ensureAll`.
- 1.0.15 removed the worst data-access pattern (`data get entity` motion harvest and four Brigadier charge buckets). `MotionHarvestService` now uses `getEntities` + axis thresholds and writes `sr_mc` only if the objective exists.
- Faraday duration/CD is the last scoreboard clock that still needs a command function to decrement (P-005). `isCageActive` is a read-only `hasPlayerScore` path and does not create scores.

### Application Layer Analysis

- Tick services early-out when their maps are empty (`CircuitService` sights, `FaradayService` cages, `ControlService` sessions, `DustService` zones, `ScanOutlineService` marks). This is the right architecture for a 20 TPS budget.
- Remaining cost is skill-active, not idle: Circuit sight, Faraday cage, Scan outlines, Control Field. That is acceptable if those paths stay bounded (P-001 still is not bounded in CPU, only in packet size).
- Apoli access is centralized in `ApoliResources` with a memoized Fabric backend and reload invalidation. `hasModePower` is the only hot cache; `getValue` for charge/mode is live and cheap enough.
- I/O is particles, sounds, and SimpleChannel. No disk in the tick path.

### Frontend Analysis (if applicable)

- Not a web app. Client “frontend” is the radial HUD plus Circuit/Scan world overlays.
- Radial renderer is menu-gated, batches TRIANGLES, and flushes in `try/finally` before text/items (1.0.15 review-fix). No finding.
- Scan overlay is the client hotspot (P-003, P-008). Circuit overlay uses precomputed AABBs and one box per mark; `pick(16)` is once per client tick (play-feel lock — keep it).
- No JS bundle, no LCP/INP/CLS, no hydration.

### Infrastructure Analysis

- Deployment is a Forge dedicated or integrated server. No CDN, no HTTP compression, no horizontal scale-out.
- Observability is vanilla logs + optional Spark/Plan profiler. The mod does not emit tick-time metrics.
- Packet protocol `"9"` is unchanged; list codecs are now bounded (`PacketLimits.MAX_OUTLINE_MARKS = 128`, `MAX_ALLIES = 256`).

---

## Performance Bottleneck Analysis

### Top Performance Bottlenecks

| Rank | Component | Issue | Impact Score | Estimated Cost |
|------|-----------|-------|--------------|----------------|
| 1 | `CircuitService.refreshSight` | Full 33-radius collect then truncate to 128 | 7.8 | 0.5–40 ms per refresh in dense wiring |
| 2 | `FaradayService.tickRepel` | `addEffect` every 2 ticks × nearby livings | 7.5 | 50–400 effect packets/s in farms |
| 3 | `ScanOutlineClient.rebuildCache` | 32-block living query every client tick | 7.2 | 0.3–4 ms/tick client in hubs |
| 4 | Faraday combat events | Server-wide intercept while any cage is up | 7.0 | 0.5–3 ms during gunfights |
| 5 | `faraday_tick` datapack | Brigadier duration/CD/FX every 5 ticks | 6.2 | 0.1–0.4 ms per caged player / 5 ticks |
| 6 | Circuit `NeighborNotifyEvent` | Alloc + map get on every redstone update | 5.8 | 0.2–1.5 ms on clocks while toggled |
| 7 | `ControlService.tickField` | r=30 living sweep every 4 ticks | 5.2 | 0.2–1.5 ms per sweep |
| 8 | Scan line-box draw | 2 boxes × up to 128 entities / frame | 5.0 | 0.3–2 ms/frame at cap |

---

## Technical Recommendations

### Immediate Performance Fixes

1. Stop Circuit sight at 128 nearest marks (Manhattan shells) instead of collecting the whole volume (P-001).
2. Lengthen Faraday slowness to 40–60 ticks and refresh only when remaining duration is low (P-002).
3. Rebuild Scan client cache every 2–4 ticks, not every tick (P-003).
4. Distance-gate Faraday combat events so far-away bullets skip `nearestCage` (P-004).

### Performance Enhancements

1. Move Faraday duration, cooldown, resistance, and silhouette particles into `FaradayService` (P-005).
2. Avoid allocating `InterfereKey` on every neighbor notify (P-006).
3. Draw one Scan outline box per entity (P-008).
4. Apply Circuit force on start / notify / expire, not every tick (P-009).

### Architecture Improvements

1. Keep Java as the tick authority; treat remaining `origins:action_over_time` powers as one-shot FX or rare safety nets only.
2. Add a tiny Spark-friendly debug (optional, off by default) that records last Circuit refresh micros, Faraday cage count, and Scan mark count.
3. Do not introduce worker threads for block/entity scans — block state and entity lists are not thread-safe in 1.20.1.

---

## Code Optimization Examples

### Early-exit Circuit sight at 128 marks (references P-001)

**Before (Inefficient)**:

```java
List<CircuitOutlinePacket.Entry> found = new ArrayList<>();
// walk every circuit block in r=16, then:
if (found.size() > MAX_MARKS) {
    found.sort((a, b) -> Integer.compare(a.pos().distManhattan(origin), b.pos().distManhattan(origin)));
    found.subList(MAX_MARKS, found.size()).clear();
}
```

**After (Optimized)**:

```java
List<CircuitOutlinePacket.Entry> found = new ArrayList<>(MAX_MARKS);
int filled = 0;
for (int manh = 0; manh <= RADIUS && filled < MAX_MARKS; manh++) {
    // iterate only positions with |dx|+|dy|+|dz| == manh inside loaded sections
    // that maybeHave CIRCUIT_TAG; add hits until filled == MAX_MARKS
}
if (moreRemainBeyondCap && !sight.denseWarned) {
    ActionBars.show(player, Component.translatable("ui.static_resonance.circuit_dense"));
    sight.denseWarned = true;
}
```

**Expected Improvement**: Dense-factory refresh drops from a full-volume spike (often 10–40 ms) to a bounded ~128-hit walk (typically < 2 ms). Sparse wiring unchanged.

### Faraday slowness refresh gate (references P-002)

**Before (Inefficient)**:

```java
living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 15, 2, true, false, true));
```

**After (Optimized)**:

```java
var slowness = living.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
if (slowness == null || slowness.getAmplifier() < 2 || slowness.getDuration() < 20) {
    living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 2, true, false, true));
}
```

**Expected Improvement**: Effect packets fall from every 2 ticks to about every 30 ticks (~15× fewer) with the same 10/7/5 push feel.

### Scan client cache interval (references P-003)

**Before (Inefficient)**:

```java
rebuildCache(level, mc.player);
```

**After (Optimized)**:

```java
if (clientTick % 2 == 0 || CACHED.isEmpty()) {
    rebuildCache(level, mc.player);
}
```

**Expected Improvement**: Halves the 66³ living query rate. Combined with UUID lookup for already-tracked entities, hub FPS hit should drop ~40–70%.

### Faraday combat distance gate (references P-004)

**Before (Inefficient)**:

```java
if (idleCombat()) {
    return;
}
if (shouldBlock(event.getProjectile())) {
    event.setCanceled(true);
}
```

**After (Optimized)**:

```java
if (idleCombat()) {
    return;
}
Entity proj = event.getProjectile();
if (proj == null || !nearAnyCage(proj.blockPosition())) {
    return;
}
if (shouldBlock(proj)) {
    event.setCanceled(true);
}
```

**Expected Improvement**: Distant gunfights no longer pay `nearestCage` while one player has a cage elsewhere. Expect most of the 0.5–3 ms gunfight tax to disappear unless the fight is inside 5 blocks of a cage.

---

## Performance Optimization Priorities

### Phase 1: Critical Performance Fixes

- [ ] No critical findings — skip this phase

### Phase 2: High Impact Optimizations

- [ ] P-001: Circuit sight early-exit at 128 nearest marks
- [ ] P-002: Faraday slowness duration/refresh gate; merge repel + catch scans
- [ ] P-003: Scan client cache every 2–4 ticks (keep out-of-range marks)
- [ ] P-004: Spatial-gate Faraday combat events to nearby cages only

### Phase 3: Medium Impact Improvements

- [ ] P-005: Move Faraday duration/CD/resistance/particles to Java
- [ ] P-006: Allocation-free interference lookup on neighbor notify
- [ ] P-007: Optional Control Field ring-only sweep on radius growth
- [ ] P-008: Single Scan line box per entity
- [ ] P-009: Circuit `applyForce` on start/notify/expire only
- [ ] P-010: Cap LookTargets cone candidates before LOS

### Phase 4: Performance Monitoring and Fine-tuning

- [ ] P-011 / P-013: Decide whether leftover `action_over_time` polls stay as safety nets
- [ ] P-012: Inclusive `hasModePower` TTL so harvest hits the cache
- [ ] P-014: Static Dust `BlockParticleOption`
- [ ] Add an optional debug overlay or Spark sampler labels for Circuit refresh, Faraday cage count, Scan mark count
- [ ] Playtest checklist: Circuit 16-block aim, F vs offhand, motion per-axis 0.03, Scan corpses drop / out-of-range stay, Faraday feel, radial no flicker

---

## Estimated Performance Improvement Impact

### Performance Gains by Priority

| Priority Level | Expected Improvement | Implementation Complexity |
|----------------|---------------------|--------------------------|
| Critical Fixes | None remaining (0%) | N/A |
| High Impact | 40–80% less tick/frame time on Circuit-dense, Faraday-farm, and Scan-hub paths | Low–medium (local loop changes, no protocol bump) |
| Medium Impact | 10–25% less leftover command/notify/field cost when those skills are active | Medium (Faraday clock move needs scoreboard parity testing) |
| Low Impact | < 5% idle-origin overhead | Low |

### Resource Utilization Improvements

- **Database Load**: Not applicable. Analog — datapack command rate: moving Faraday’s clock to Java (P-005) removes the last 5-tick `execute_command` on the hot path.
- **Memory Usage**: Circuit dense-scan allocation spike gone (P-001); fewer `InterfereKey` and `MobEffectInstance` objects (P-002, P-006). Expect a smaller young-gen sawtooth while those skills are up.
- **CPU Utilization**: Server thread: Circuit megabase and Faraday farms are the remaining cliffs. Client thread: Scan hub query + double line boxes.
- **Concurrency/Throughput**: No thread-pool change. Better 20 TPS headroom for other mods when Circuit / Faraday / Scan are in use. Idle TPS already close to vanilla-plus-Origins.

---

## Performance Monitoring Setup Recommendations

### Observability Recommendations

- **Metrics**: Spark (server + client) is the native tool for this stack. Sample `CircuitService.refreshSight`, `FaradayService.tickRepel`, `ScanOutlineClient.rebuildCache`, and Origins `action_over_time` if Faraday clock stays in datapack.
- **Logging**: Keep production logs quiet. A debug-only `StaticResonanceMod` logger line (last Circuit found count, Faraday cage size, Scan mark size) is enough. Do not log per tick.
- **Distributed Tracing**: Not applicable (single JVM, no microservice hop). Spark tick flame graphs replace traces.

### Recommended Performance Tracking

- Server MSPT with 1 / 8 / 20 origin players, Circuit on in a redstone room, Faraday up in a mob farm, Scan on in a village
- Client FPS / frame time with Scan at 8, 64, and 128 marks
- Packet rate (`/debug` or Spark) while Faraday slowness is refreshing
- Alerting: MSPT > 40 ms for > 5 s while only this mod’s skills are the new variable

### Performance Testing Recommendations

- Load: 10 origin players, 4 in Circuit next to a 5k-dust computer, 2 Faraday cages in a 50-mob farm, 2 Scan users in a raid
- Benchmark paths: `refreshSight`, `tickRepel`+`catchNearby`, `rebuildCache`, Faraday `onProjectileImpact` with TACZ if present
- Tools: Spark 1.20.1, Forge integrated server, optional Carpet tick health. There is no JMeter/k6 surface.

---

## Summary

This performance analysis identified **0 critical**, **4 high**, **6 medium**, and **4 low** performance issues across the application stack. The analysis focused on code patterns, remaining datapack command ticks, resource utilization, and architectural performance after the 1.0.15 remediations.

**Key Strengths Identified**:

- Motion harvest is Java (`MotionHarvestService`) — the 1.0.14 NBT/`origins:command` cliff is gone
- S2C lists are bounded (`PacketLimits` 128 / 256) and decoders throw on bad counts
- Circuit sight is section-culled (`hasOnlyAir` / `maybeHas`), interference probe skips when the map is empty, dense warning latches once
- `hasModePower` is cached 20 ticks and keyed by `(uuid, isClientSide)`; login/logout/dim invalidate
- Scan is a replace snapshot; prune only observed-dead; maps clear on logout
- Tick maps are iterated in place (no per-tick `ConcurrentHashMap` copies); Faraday `idleCombat()` skips combat events when nothing is up
- Radial geometry is one TRIANGLES batch with `try/finally` flush before text
- `static_footing` is a static attribute; Climb checks power before collision work

**Critical Areas Requiring Immediate Attention**:

- Circuit still scans the whole 16-radius volume before applying the 128 cap (P-001)
- Faraday slowness is rewritten every 2 ticks for every nearby living entity (P-002)
- Scan client resolves a 32-block living AABB every tick (P-003)
- One Faraday cage turns on a server-wide projectile/attack filter (P-004)

**Expected Overall Performance Improvement**: Fixing the four High items should remove the remaining skill-active cliffs (roughly half to most of Circuit/Faraday/Scan extra time in dense scenes) without changing play-feel locks (per-axis 0.03 harvest, 6-tick cast CD, Circuit `pick(16)`, F-after-power, Scan keep-unloaded). Idle servers are already in good shape; do not spend a rewrite on Faraday AOT or LookTargets AABB size.
