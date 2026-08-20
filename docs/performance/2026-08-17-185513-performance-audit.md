## Executive Summary

### Audit Overview

- **Target System**: Static Resonance (`static_resonance` / `com.staticresonance`) 1.0.15
- **Analysis Date**: 2026-08-17
- **Analysis Scope**: Full-stack Minecraft Forge 1.20.1 Origins content mod (server tick paths, datapack powers/functions, client HUD and outline renderers, SimpleChannel packets)
- **Technology Stack**: Minecraft 1.20.1, Forge 47.2.0, Origins/Apoli (Forge + Fabric-via-Connector), Java 17, Gradle, scoreboard + Apoli resources as persistence, Forge SimpleChannel protocol `"9"`

This is a re-audit of the live tree after the 1.0.15 remediations and the later same-day patches (Circuit section-sort + early-exit, Faraday slowness duration gate + merged aura, Scan rebuild every other tick + single line box, LookTargets cone cap, `hasModePower` inclusive TTL, Faraday clock moved into Java and dropped from the origin grant list). Prior findings that are verified fixed are not re-filed.

### Performance Assessment Summary

| Performance Level | Count | Percentage |
|-------------------|-------|------------|
| Critical Issues   | 0     | 0%         |
| High Impact       | 4     | 22%        |
| Medium Impact     | 8     | 44%        |
| Low Impact        | 6     | 33%        |
| **Total**         | **18** | **100%**   |

### Key Analysis Results

- **Performance Anti-Patterns**: 0 critical patterns remaining; 4 high-impact patterns still fire on skill-active hot paths (Circuit volume walk, Scan client AABB, Faraday combat fan-in, Faraday aura tracker spam)
- **Code Optimization Opportunities**: 4 high-impact optimizations plus 8 medium-impact session-gated or always-on client costs
- **Architecture Assessment**: 8.5/10 performance best practices implemented (empty-map early-outs, packet caps, TTL caches, logout cleanup, section-culled Circuit, duration-gated Faraday slowness; Circuit cell walk, Scan UUID resolve, and Faraday spatial gating still missing)
- **Overall Code Performance Score**: 81/100 (based on static analysis of tick/render paths after the 1.0.15 remediations)

---

## Analysis Methodology

### Performance Analysis Approach

- **Static Code Analysis**: Comprehensive source code review for performance anti-patterns
- **Database Query Analysis**: Review of queries, indexing strategies, and data access patterns
- **Resource Utilization Assessment**: Analysis of memory, CPU, and I/O usage patterns
- **Architecture Performance Review**: Examination of caching, scaling, and optimization strategies

### Analysis Coverage

- **Files Analyzed**: 80 source files across 2 trees (41 Java under `src/main/java`, 39 datapack `.json`/`.mcfunction` under `src/main/resources/data`)
- **Database Queries Reviewed**: 18 datapack command/tick paths (16 `.mcfunction` files + 2 granted `origins:action_over_time` powers: `charge_tick`, `scan_abort`). `systems/faraday_tick.json` exists on disk but is **not** granted by `static_resonance.json`. There is no SQL/ORM layer.
- **API Endpoints Analyzed**: 10 SimpleChannel packet types across 1 channel (`ModNetwork`, protocol `"9"`)
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

The 1.0.14 critical class (unbounded S2C `ArrayList` decode, per-tick NBT `data get entity` harvest, naive Circuit 33³ `getBlockState`) is no longer present. `PacketLimits.readCount` throws on oversized counts, motion harvest is Java, Circuit sight is section-culled with a 128-mark cap and an in-scan early exit, Faraday slowness is duration-gated, and the Faraday duration clock now lives in `FaradayService.tickCageClock` (the leftover `faraday_tick` power is not granted).

### High Performance Impact Findings

#### P-001: Circuit sight still walks every cell of every circuit-tagged section

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:409`
**Performance Impact**: 7.4 (High)
**Pattern Detected**: Section-sorted collect with a mark cap, but no early-exit on *cells visited* inside a 16³ section
**Code Context**:

```java
sections.sort(Comparator.comparingInt(s -> s[3]));
boolean dense = false;
scan:
for (int[] sect : sections) {
    if (found.size() > MAX_MARKS) {
        dense = true;
        break;
    }
    // ...
    for (int y = y0; y <= y1; y++) {
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                if (dx * dx + dy * dy + dz * dz > rSq) {
                    continue;
                }
                BlockState state = section.getBlockState(x & 15, ly, lz);
                if (!state.is(CIRCUIT_TAG)) {
                    continue;
                }
                found.add(new CircuitOutlinePacket.Entry(pos, isWorking(level, pos, state, inf)));
                if (found.size() > MAX_MARKS) {
                    dense = true;
                    break scan;
                }
            }
        }
    }
}
```

**Impact**: Circuit mode refreshes when the player moves 4 blocks or a nearby block changes (`SCAN_INTERVAL = 20`, `MOVE_SNAP = 4`). Air / non-circuit sections skip via `hasOnlyAir` / `maybeHas`, and a *dense* section now stops at 129 marks. A *sparse* redstone megabase is the remaining cliff: every section whose palette contains `#static_resonance:circuit` still walks its clipped 16³ cells (up to 4096 `getBlockState` each). A 33-block cube is ~3–5 chunks × several section Ys; tens of thousands of palette reads still land on the server thread before 128 wires are found.
**Performance Cost**: ~5–40k section `getBlockState` calls per refresh in a factory with circuit-tagged sections everywhere and few hits per section; 0.4–6 ms typical, 10–25 ms possible on a large computer. Idle / wilderness Circuit is cheap.
**Recommendation**: Walk Manhattan shells from the player outward (or walk section cells in origin-relative order) and stop at `MAX_MARKS` (128). Keep the dense-once warning. Do not change `RADIUS = 16` or client `pick(16)`.
**Fix Priority**: Within 1 week

#### P-002: Scan client still rebuilds marks via a 32-block living-entity AABB

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:104`
**Performance Impact**: 7.1 (High)
**Pattern Detected**: Periodic wide AABB entity query on the client tick thread instead of UUID map lookup
**Code Context**:

```java
if ((clientTick & 1) == 0 || CACHED.isEmpty()) {
    rebuildCache(level, mc.player);
}
// rebuildCache:
AABB search = player.getBoundingBox().inflate(ScanOutlineService.SCAN_RADIUS); // 32
for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, search,
        e -> MARKED.contains(e.getUUID()))) {
```

**Impact**: After a scan pulse the client walks every living entity in a ~66³ box every other tick (10 Hz) to resolve at most 128 UUIDs. Farms, raids, and player hubs make this a client FPS hitch even though the server pulse is one-shot. Render then draws one `renderLineBox` per cached body every frame (the second halo box is gone); flecks still spawn every 4 ticks.
**Performance Cost**: 0.2–3 ms/tick client-side with 100–400 nearby livings; plus 128 line boxes/frame at the cap.
**Recommendation**: Resolve UUIDs via `ClientLevel.getEntity(uuid)` (or the client entity map) for the ≤128 marked ids and only fall back to an AABB for newly loaded bodies. Rebuild every 2–4 ticks is fine once the query is O(marks). Keep prune-only-observed-dead and do not evict out-of-range marks.
**Fix Priority**: Within 1 week

#### P-003: Faraday combat listeners tax the whole server while any cage is active

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:210`
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
// shouldBlock → axisNearAnyCage → nearestCage (iterate ACTIVE_CAGES)
```

`idleCombat()` is `ACTIVE_CAGES.isEmpty() && HELD.isEmpty()`. The idle gate is correct. Once one player raises a cage, every projectile impact and every `LivingAttackEvent` / `LivingHurtEvent` on the server enters `shouldBlock`. A single TACZ/vanilla hit can fire all three listeners. Each call then walks `ACTIVE_CAGES` twice: `axisNearAnyCage` then `nearestCage` (both `getPlayer` + `isCageActive`). TACZ / high-arrow PvP makes this a per-bullet tax for the full cage duration (`sr_faraday` starts at 600 ticks).

**Impact**: One Overcharge player paying 50 charge turns on a server-wide intercept filter. Correctness is fine; cost scales with world combat volume, not with nearby threats. `GUNSHOT_TYPES` caching and the axis pre-filter are real improvements over earlier audits.
**Performance Cost**: A few microseconds per event × hundreds of bullets/attacks per second ≈ 0.4–3 ms extra server time during gunfights with a cage up. In-cage gunfights pay 2–3× `shouldBlock` per bullet.
**Recommendation**: Return the cage player from one `nearestCage` walk. Handle intercept on `ProjectileImpactEvent` only; let `onAttack` / `onHurt` check `HELD.containsKey` only. Optionally chunk-bucket cages. Keep `idleCombat()` for the zero-cage case.
**Fix Priority**: Within 1 week

#### P-004: Faraday aura still scans a 20×4×20 box and dirties trackers every 2 ticks

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:195`
**Performance Impact**: 7.0 (High)
**Pattern Detected**: High-frequency untyped entity query + `hurtMarked` / `setPos` inside the active-cage loop
**Code Context**:

```java
if (now % 2 == 0) {
    tickAura(player, now);
}
// tickAura:
for (Entity entity : level.getEntities(player, box, e -> e != player)) {
    // interceptables → catchEntity
    // hard ring → setPos + setDeltaMovement + hurtMarked
    // mid ring → refreshRepelSlow + hurtMarked
}
```

**Impact**: Slowness is now duration-gated (`REPEL_SLOW_TICKS = 50`, refresh only when remaining `< 20`) and catch/repel share one `getEntities` — those morning findings are fixed. Remaining cost is the query itself plus vanilla tracker packets: every mid-ring living body is `hurtMarked` every other tick, and the hard ring also `setPos`s. A 40-mob farm next to an active cage is ~20 velocity packets per tick (400/s) plus sparks every 5 ticks.
**Performance Cost**: 0.2–2 ms CPU plus 50–400 extra packets/s per active cage in a dense mob area. Single-player wilderness: negligible.
**Recommendation**: Keep the 10 / 7 / 5 radius feel. Apply knockback only when the mob is actually walking inward (dot of motion vs. outward normal < 0) and skip `hurtMarked` when velocity did not change. Optionally query `LivingEntity` + `Projectile` in two tight AABBs instead of every entity type.
**Fix Priority**: Within 1 week

### Medium Performance Impact Findings

#### P-005: Control Field sweeps a growing 30-block cylinder every 4 ticks

**Location**: `src/main/java/com/staticresonance/mode/ControlService.java:460`
**Performance Impact**: 5.3 (Medium)
**Pattern Detected**: Session-gated but wide living-entity scan + effect writes
**Code Context**:

```java
if (now % SWEEP_INTERVAL == 0) {
    if (session.radius > 0) {
        spawnFieldEdge(level, pos, session.radius, now);
    }
    double r = Math.max(0.5, session.radius);
    AABB box = new AABB(
            pos.x - r, pos.y - 2, pos.z - r,
            pos.x + r, pos.y + 3, pos.z + r
    );
    for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, e ->
            e.isAlive()
                    && e != player
                    && !AllyService.isProtected(player, e)
                    && e.distanceToSqr(pos) <= r * r)) {
        if (!isUnderLock(living)) {
            applyLock(player, living, level, true, true);
        } else if (needsLockRefresh(living)) {
            refreshLockEffects(player, living);
        }
    }
}
```

**Impact**: One field session (40 charge, 60 s, stand still) is gated well. At full radius the AABB is 60×5×60. A village / raid / farm inside that cylinder gets a living scan 5 times per second and three effects per newly locked body. Refresh is already duration-gated (`REFRESH_WHEN_REMAINING = 20`).
**Performance Cost**: 0.2–1.5 ms per sweep at r=30 in a populated area. Zero when no session.
**Recommendation**: Keep the feel. Optionally scan only the newly expanded ring when `radius` grows, and reuse the existing lock-refresh gate (already good).
**Fix Priority**: Within 1 month

#### P-006: Dust zone rewrites blindness every 4 ticks with no remaining-duration check

**Location**: `src/main/java/com/staticresonance/mode/DustService.java:191`
**Performance Impact**: 5.0 (Medium)
**Pattern Detected**: High-frequency mob-effect write inside a 6-block living scan
**Code Context**:

```java
if (now % ZONE_TICK_INTERVAL != 0) { // 4
    continue;
}
// tickZone:
living.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLIND_REFRESH_TICKS, 0, false, true, true));
```

**Impact**: `BLIND_REFRESH_TICKS = 40` but the write happens every 4 ticks for every non-ally living body in the zone. Vanilla re-sends the effect to trackers on each `addEffect`. A 10-second zone (`ZONE_DURATION_TICKS = 200`) in a mob farm is ~50 refresh waves. Faraday slowness and Control lock already use the “refresh when remaining < N” pattern; Dust does not.
**Performance Cost**: 0.1–0.8 ms plus 10–200 extra effect packets/s in a dense zone. One or two mobs: noise.
**Recommendation**: Mirror `FaradayService.refreshRepelSlow` / `ControlService.needsLockRefresh` — apply duration 40–60 and skip when remaining ≥ 20. Keep `ZONE_RADIUS = 6` and the sand/cloud VFX.
**Fix Priority**: Within 1 month

#### P-007: Faraday cage resistance is rewritten every 5 ticks

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:632`
**Performance Impact**: 4.8 (Medium)
**Pattern Detected**: Unconditional `addEffect` on a short clock despite a long duration
**Code Context**:

```java
setScore(player, OBJ_FARADAY, next);
refreshCageEffects(player);
spawnCageSilhouette(player);
// refreshCageEffects:
player.addEffect(new MobEffectInstance(
        MobEffects.DAMAGE_RESISTANCE, CAGE_RESIST_TICKS, CAGE_RESIST_AMP, true, false, true));
player.addEffect(new MobEffectInstance(
        MobEffects.FIRE_RESISTANCE, CAGE_RESIST_TICKS, 0, true, false, true));
```

**Impact**: Duration is 120 ticks (6 s) and the clock is 5 ticks, so the same two effects are rewritten 4 times per second for the whole cage. Trackers receive effect packets on each write. The Java clock correctly replaced the granted `faraday_tick` power; this leftover write cadence is the remaining cost.
**Performance Cost**: Two effect packets + 11 silhouette particles every 5 ticks per caged player (~8 effect packets/s, ~44 particles/s).
**Recommendation**: Refresh resistance/fire resistance only when remaining duration `< 40`. Keep `CAGE_RESIST_TICKS = 120` and the silhouette VFX interval.
**Fix Priority**: Within 1 month

#### P-008: Circuit neighbor-notify still runs on every redstone update while any toggle is live

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:237`
**Performance Impact**: 4.6 (Medium)
**Pattern Detected**: Global event listener keyed by packed long (no allocation) but not spatially filtered
**Code Context**:

```java
public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
    if (INTERFERENCES.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) {
        return;
    }
    Interference inf = getInterference(level.dimension(), event.getPos());
    if (inf == null) {
        return;
    }
```

**Impact**: `INTERFERENCES.isEmpty()` is a good idle gate, and the lookup is now `dim + pos.asLong()` (the allocated `InterfereKey` from the morning audit is gone). While even one wire is forced for 160 ticks, every neighbor notify on the server (redstone clocks, pistons, observers) still hits the map. A 20 Hz clock tower next to an unrelated Circuit toggle pays this tax for 8 seconds.
**Performance Cost**: One CHM get per notify; thousands/tick on a large computer ≈ 0.1–0.8 ms extra while any interference exists.
**Recommendation**: Skip the event unless the notify pos is in a small set of live interference positions (or a chunk-long set). Leave the 160-tick duration.
**Fix Priority**: Within 1 month

#### P-009: Scan and Circuit still submit up to 128 see-through line boxes every frame

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:133`
**Performance Impact**: 4.5 (Medium)
**Pattern Detected**: Per-frame GPU line-box submission at the 128 cap through a no-depth `RenderType`
**Code Context**:

```java
for (LivingEntity living : CACHED) {
    // ...
    AABB box = living.getBoundingBox().inflate(0.06);
    LevelRenderer.renderLineBox(pose, lines, box, rgb[0], rgb[1], rgb[2], 0.95f);
}
```

`CircuitOutlineClient` does the same for up to 128 precomputed AABBs (`CircuitOutlineClient.java:80`). `SeeThroughRender` uses a 256 KiB line buffer and `NO_DEPTH_TEST`.

**Impact**: The second Scan halo box from the morning audit is gone (one box per mark). At the 128 cap this is still 128 `renderLineBox` submissions (24 edges each) through a see-through type. Combined with P-002 this is the Scan FPS cliff. Circuit is cheaper because AABBs are precomputed and only one outline type is typically live (mode change stops scan).
**Performance Cost**: 0.2–1.2 ms GPU/CPU per frame at the cap; less with a handful of marks.
**Recommendation**: One box per entity is already correct. Optionally drop marks that fail a frustum / distance test before `renderLineBox`. Keep see-through; that is the feature.
**Fix Priority**: Within 1 month

#### P-010: Wall climb dirties the player tracker every tick

**Location**: `src/main/java/com/staticresonance/mode/ClimbService.java:40`
**Performance Impact**: 4.2 (Medium)
**Pattern Detected**: Unconditional `hurtMarked = true` on the climb hot path
**Code Context**:

```java
if (m.y < CLIMB_SPEED) {
    player.setDeltaMovement(m.x, CLIMB_SPEED, m.z);
}
player.fallDistance = 0.0f;
player.hurtMarked = true;
```

**Impact**: `PlayerTickEvent` is already well gated (`hasModePower` cache + `horizontalCollision`). While climbing, `hurtMarked` forces a velocity packet to every tracker every tick (20 Hz) even when `m.y` was already `CLIMB_SPEED` and nothing changed. Faraday’s `freeze()` already skips `hurtMarked` when motion did not drift; climb does not.
**Performance Cost**: One extra entity-tracker packet/tick per climbing origin player, both sides.
**Recommendation**: Set `hurtMarked` only when vertical velocity was actually written. Keep `CLIMB_SPEED = 0.18` and the 8-tick spark.
**Fix Priority**: Within 1 month

#### P-016: Circuit client raycasts 16 blocks every tick even when idle

**Location**: `src/main/java/com/staticresonance/client/CircuitOutlineClient.java:54`
**Performance Impact**: 5.2 (Medium)
**Pattern Detected**: Always-on client clip with no `MARKS` / origin gate
**Code Context**:

```java
public static void onClientTick(TickEvent.ClientTickEvent event) {
    if (event.phase != TickEvent.Phase.END) {
        return;
    }
    looked = lookCircuit(Minecraft.getInstance().player);
}
```

`lookCircuit` always calls `player.pick(CircuitService.RADIUS, 1.0f, false)` — a 16-block block clip. This runs for **every client with the mod**, including players who never chose the origin and when `MARKS` is empty.

**Impact**: This is the only always-on world query the mod still does on the client. Small per tick, but it never idle-outs.
**Performance Cost**: ~10–50 µs/tick × all clients with the jar loaded.
**Recommendation**: Return immediately when `MARKS.isEmpty()`. Keep `pick(16)` while Circuit sight is live.
**Fix Priority**: Within 1 month

#### P-017: Scan flecks spawn two particles per cached entity every 4 ticks

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:207`
**Performance Impact**: 4.5 (Medium)
**Pattern Detected**: Client particle flood at the 128-mark cap
**Code Context**:

```java
private static void spawnLocalFlecks(ClientLevel level) {
    for (LivingEntity entity : CACHED) {
        if (!revealed(entity)) {
            continue;
        }
        level.addParticle(dustFor(entity), ...);
        level.addParticle(ParticleTypes.ELECTRIC_SPARK, ...);
    }
}
```

**Impact**: At 128 marks this is 256 `addParticle` calls every 4 ticks (~64 particles/tick), client-only. Combined with P-002 / P-009 this is the remaining Scan FPS cliff after the second line box was removed.
**Performance Cost**: Extra particle-system time on the client at the cap; wilderness Scan with a handful of marks is fine.
**Recommendation**: Spawn flecks for at most 16–24 nearest / on-screen marks, or every 8 ticks below 32 marks. Keep the colored dust + spark look.
**Fix Priority**: Within 1 month

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

#### P-012: Ungranted Faraday datapack clock still ships in the jar

**Location**: `src/main/resources/data/static_resonance/powers/systems/faraday_tick.json:1`
**Performance Impact**: 2.8 (Low)
**Pattern Detected**: Dead tick authority that is one origin-list edit away from doubling the Java clock
**Code Context**:

`static_resonance.json` no longer lists `systems/faraday_tick`. Java `tickCageClock` now owns duration, cooldown, resistance, and silhouette particles. The power JSON and `faraday/tick` → `tick_active` → `tick_live` → `particles` still decrement `sr_faraday` by 5 and `effect give` every 5 ticks *if granted*.

**Impact**: Not live on current origin players. If a datapack or future origin grant re-adds the power, cage duration halves (Java and function both subtract 5 per 5 ticks from a 600 starting score) and particles/effects double.
**Performance Cost**: Zero at runtime today; latent double-clock if re-granted.
**Recommendation**: Delete `faraday_tick.json` and the unused tick functions, or leave `activate`/`deactivate` as one-shot FX only and add a comment in the origin file that the clock is Java.
**Fix Priority**: Within 2 months

#### P-013: Origins prevent-death still dispatches a Brigadier command

**Location**: `src/main/resources/data/static_resonance/powers/passive/faraday_emergency.json:12`
**Performance Impact**: 2.6 (Low)
**Pattern Detected**: One-shot `origins:execute_command` next to a Java `LivingDeathEvent` / `LivingDamageEvent` path that already raises the cage
**Code Context**:

```json
"entity_action": {
  "type": "origins:execute_command",
  "command": "sr_internal faraday_emergency"
}
```

**Impact**: Emergency is rare (lethal hit). Java `onDeath` / `capIncoming` already call `tryActivate(..., EMERGENCY)`. The command path is a second parse of `sr_internal` plus another `tryActivate` that no-ops if the cage is already up.
**Performance Cost**: One command parse on a near-death hit; not a tick tax.
**Recommendation**: Point the Origins action at a no-op / particle-only function, or drop `prevent_death` once Java is the sole authority. Do not remove the Java handlers.
**Fix Priority**: Within 2 months

#### P-014: Circuit `isWorking` falls back to `hasNeighborSignal` per unmarked block

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:543`
**Performance Impact**: 2.3 (Low)
**Pattern Detected**: Extra neighbor-power query on the Circuit scan hot path
**Code Context**:

```java
if (state.is(Blocks.REDSTONE_BLOCK)) {
    return true;
}
return level.hasNeighborSignal(pos);
```

**Impact**: Most `#static_resonance:circuit` blocks hit an earlier property (`POWER`, `POWERED`, `LIT`, …). Only odd tagged blocks reach the fallback, and each call walks neighbors. At 128 marks this is bounded; it is extra work only for the tail of the tag.
**Performance Cost**: Microseconds per fallback hit per refresh.
**Recommendation**: Treat unknown tagged blocks as idle (`return false`) unless a specific block needs the neighbor check.
**Fix Priority**: Within 2 months

#### P-015: Uncached Apoli `getValue` still uses Fabric reflection on Connector

**Location**: `src/main/java/com/staticresonance/mode/ApoliResources.java:244`
**Performance Impact**: 2.2 (Low)
**Pattern Detected**: Per-call reflection on Fabric Apoli; Forge backend is direct
**Code Context**:

```java
public OptionalInt getValue(Player player, ResourceLocation powerId) {
    Object power = resolveVariableInt(player, powerId);
    // keyMaybeGet + holderHasPower + holderGetPower + getValue.invoke
}
```

`hasModePower` is TTL-cached (`<= POWER_CACHE_TTL`, 20 ticks) and that morning off-by-one is fixed. Charge / style / filter / emergency reads on the radial HUD and harvest path are not cached.

**Impact**: Forge users pay a few virtual calls. Connector users pay several `Method.invoke`s per HUD frame while the radial is open (`liveCharge`, `liveMode`, styles) and once per second per origin player in `MotionHarvestService`.
**Performance Cost**: Tens of microseconds per read on Connector; noise on Forge.
**Recommendation**: Cache last `getValue` per `(player, powerId)` for 1–5 ticks if Connector profiles show HUD hitch. Do not cache writes.
**Fix Priority**: Within 2 months

#### P-018: Circuit tick reads live Apoli mode every server tick

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:183`
**Performance Impact**: 3.1 (Low)
**Pattern Detected**: Uncached `getMode` on the Circuit sight loop
**Code Context**:

```java
for (UUID id : SIGHTS.keySet()) {
    ServerPlayer player = server.getPlayerList().getPlayer(id);
    if (player == null || ModeService.getMode(player) != ResonanceMode.CIRCUIT) {
```

`getMode` → live `ApoliResources.getValue` (not the 20-tick `hasModePower` cache), 20 times/s per Circuit player. Control field does the same (`ControlService.java:434`).

**Impact**: Extra Apoli container walk every tick while Circuit (or Control Field) is live. Connector users pay reflection on that path (see P-015).
**Performance Cost**: Tens of microseconds per Circuit/Control player per tick.
**Recommendation**: Check mode only on `SCAN_INTERVAL` / `SWEEP_INTERVAL`, or cache the index for 5–10 ticks.
**Fix Priority**: Within 2 months

---

## Code Pattern Performance Analysis

### Performance Anti-Pattern Detection

- **N+1 Query Patterns**: 0 instances detected across 0 database files (no ORM/SQL)
- **Blocking Operations**: 2 remaining granted `origins:execute_command` paths (`scan_abort`, Faraday emergency `sr_internal`) plus one-shot mode-feedback / scan-start / Faraday activate-deactivate functions. `faraday_tick` is no longer granted.
- **Memory Pressure Points**: 3 locations with excessive allocation (Circuit `found` / section lists in dense wiring, Scan client per-rebuild entity list, Faraday aura `getEntities` result lists)
- **Inefficient Data Access**: 3 queries with suboptimal patterns (Circuit full-section cell walk, Faraday untyped aura AABB, Scan client 32-inflate every other tick)
- **Concurrency Issues**: 0 shared-state bugs found; maps are `ConcurrentHashMap` and logout/login/dim handlers clear player keys. Faraday `GUNSHOT_TYPES` and `MODE_POWER_CACHE` are bounded by entity-type count / online players.

### Database Access Pattern Analysis

- **ORM/Query Usage**: 0 SQL queries. Persistence is vanilla scoreboard (`sr_faraday`, `sr_faraday_cd`, `sr_mc`) and Apoli resource powers. 18 datapack command/tick paths reviewed.
- **Missing Async Patterns**: 0 — Minecraft 1.20.1 game logic must run on the server thread. Offloading Circuit scan to a worker would desync block state.
- **Query Complexity**: Remaining expensive function trees are one-shot (`faraday/activate`, `faraday/deactivate`, mode feedback). The 5-tick Faraday function tree is ungranted.
- **Connection Management**: Not applicable (no connection pool). Analog: Forge `SimpleChannel` is a single protocol-`"9"` channel with bounded list codecs (`PacketLimits.MAX_OUTLINE_MARKS = 128`, `MAX_ALLIES = 256`).

### Resource Management Pattern Analysis

- **Memory Allocation Patterns**: Circuit `refreshSight` still allocates `BlockPos` + `Entry` per hit (capped at 129). Scan client clears/refills `CACHED` at 10 Hz. Dust `SAND_DUST` is now a static `BlockParticleOption` (morning P-014 fixed).
- **Garbage Collection Pressure**: Young-gen churn on Circuit refresh and Faraday aura in farms. No unbounded cache growth observed.
- **Thread/Event Loop Usage**: All game logic is main-thread (correct for MC). No worker offload. Faraday combat handlers add work to every combat event while a cage exists.
- **Caching Opportunities**: `hasModePower` (20-tick TTL, inclusive), ally UUID set, gunshot `EntityType` boolean, Fabric `PowerType` map. Missing: Scan UUID→entity, Faraday cage chunk index, Apoli `getValue` short TTL.
- **Resource Disposal**: Logout/login/dim handlers clear Circuit sights, Faraday cages, Control sessions/hands, Dust zones, Scan marks, TickGates, ally cache. `TickGate` / `MODE_POWER_CACHE` evict on logout only (acceptable for online-player cardinality).

---

## Architecture Performance Assessment

### Data Access Layer Analysis

- No SQL. Scoreboard objectives are created once (`SrScoreboards.ensureAll`) and then used as O(1) dummy scores. Apoli resource powers are the source of truth for mode, charge, styles, and scan filter.
- Data-layer caching is present for `hasModePower` and allies. Charge harvest still does a live `getResourceValue` once per second, which is acceptable.
- Read/write pattern is cache-aside for allies and TTL for origin membership. Faraday duration is now a Java-owned score decrement (`CLOCK_INTERVAL = 5`, `CAGE_TICK_STEP = 5`) instead of a granted Origins function.

### Application Layer Analysis

- Async/await is not applicable; Forge events run on the server/client thread. Empty-map / empty-set early-outs are consistent (`SESSIONS`, `ZONES`, `SIGHTS`, `ACTIVE_CAGES`, `MARKED`, `INTERFERENCES`).
- Memory lifecycle is good: player-keyed maps clear on logout. `GUNSHOT_TYPES` lives for the process and is bounded by the entity registry.
- CPU cost concentrates in Circuit `refreshSight`, Faraday `tickAura`, Control `tickField`, and Scan client `rebuildCache`.
- External I/O is SimpleChannel + vanilla particle/sound packets. No HTTP, no disk in the tick path.

### Frontend Analysis (if applicable)

- Core Web Vitals do not apply (Minecraft client, not a browser). Analog: frame time while Circuit/Scan outlines are visible, and INP analog is radial open/close.
- There is no JS bundle. Client cost is outline line boxes + radial tessellation (batched `TRIANGLES` with `try/finally flushSectors`, only while R is held).
- Rendering: see-through line `RenderType` (256 KiB buffer, no depth test). Scan is one box per mark; Circuit uses precomputed idle/aim AABBs. Radial item icons are static `EnumMap` `ItemStack`s.
- Asset optimization: GUI textures are small; no 4K atlases in this mod. Particle counts on Faraday activate/deactivate are one-shot and acceptable.

### Infrastructure Analysis

- No CDN. Multiplayer cost is vanilla entity-tracker + SimpleChannel. Missing compression is vanilla’s problem, not this mod’s.
- Transfer: outline packets are capped at 128 entries. Scan sends the full UUID set on pulse only (not per tick). Circuit sends on dirty/move, not every scan interval if marks are unchanged (`next.equals(sight.marks)`).
- Observability: no tick timers, no Spark/sparkplug hooks, no packet counters. Failures would show up as server `tick` overruns in `latest.log` or Spark profiles only.

---

## Performance Bottleneck Analysis

### Top Performance Bottlenecks

| Rank | Component | Issue | Impact Score | Estimated Cost |
|------|-----------|-------|--------------|----------------|
| 1 | `CircuitService.refreshSight` | Full 16³ walk of every circuit-tagged section | 7.4 | 0.4–25 ms per refresh in a factory |
| 2 | `ScanOutlineClient.rebuildCache` | 32-block living AABB every other client tick | 7.1 | 0.2–3 ms/tick with 100–400 livings |
| 3 | `FaradayService` combat events | Global intercept while any cage exists | 7.0 | 0.4–3 ms during gunfights |
| 4 | `FaradayService.tickAura` | Untyped 20×4×20 scan + `hurtMarked` @ 10 Hz | 7.0 | 0.2–2 ms + 50–400 packets/s in farms |
| 5 | `ControlService.tickField` | Growing r=30 living sweep @ 5 Hz | 5.3 | 0.2–1.5 ms per sweep |
| 6 | `DustService.tickZone` | Blindness rewrite every 4 ticks | 5.0 | 10–200 effect packets/s in a farm |
| 7 | `FaradayService.refreshCageEffects` | Two `addEffect` every 5 ticks | 4.8 | ~8 effect packets/s per cage |
| 8 | `CircuitService.onNeighborNotify` | Map probe on every redstone notify | 4.6 | 0.1–0.8 ms on a clock tower |
| 9 | Scan/Circuit outline render | 128 see-through line boxes / frame | 4.5 | 0.2–1.2 ms/frame at the cap |
| 10 | `ScanOutlineClient.spawnLocalFlecks` | 2 particles × N / 4 ticks | 4.5 | ~64 particles/tick at the 128 cap |
| 11 | `ClimbService` | `hurtMarked` every climb tick | 4.2 | 1 tracker packet/tick per climber |
| 12 | `CircuitOutlineClient.onClientTick` | `pick(16)` even when `MARKS` empty | 5.2 | 10–50 µs/tick on every client |

---

## Technical Recommendations

### Immediate Performance Fixes

1. Walk Circuit marks in origin-relative order and stop at 128 *cells*, not after finishing a section (P-001).
2. Resolve Scan marks with `level.getEntity(uuid)` instead of a 32-block living query (P-002).
3. Bucket Faraday cages by chunk and skip combat events whose entity chunk is outside `CATCH_RADIUS` (P-003).
4. Skip Faraday `hurtMarked` / `setPos` when the mob is not moving inward (P-004).

### Performance Enhancements

1. Duration-gate Dust blindness and Faraday resistance the same way Control lock and Faraday slowness already work (P-006, P-007).
2. Scan only the newly expanded Control Field ring when radius grows (P-005).
3. Frustum-cull outline boxes before `renderLineBox` (P-009).
4. Set climb `hurtMarked` only when vertical velocity changed (P-010).

### Architecture Improvements

1. Delete or quarantine the ungranted `faraday_tick` power so it cannot double the Java clock (P-012).
2. Make Java the sole Faraday emergency authority and drop `sr_internal faraday_emergency` from Origins (P-013).
3. Add a 1-second moving Spark/jfr checklist for Circuit refresh, Faraday aura, and Scan client rebuild so the next audit is measured, not estimated.

---

## Code Optimization Examples

### Walk Circuit shells and stop at 128 (references P-001)

**Before (Inefficient)**:

```java
// CircuitService.refreshSight — walks every cell of every maybeHas section
for (int y = y0; y <= y1; y++) {
    for (int z = z0; z <= z1; z++) {
        for (int x = x0; x <= x1; x++) {
            BlockState state = section.getBlockState(x & 15, ly, lz);
            if (!state.is(CIRCUIT_TAG)) {
                continue;
            }
            found.add(new CircuitOutlinePacket.Entry(pos, isWorking(level, pos, state, inf)));
            if (found.size() > MAX_MARKS) {
                dense = true;
                break scan;
            }
        }
    }
}
```

**After (Optimized)**:

```java
// Expand Manhattan shells from origin; stop at MAX_MARKS.
// Still skip air / non-circuit sections via maybeHas.
for (int d = 0; d <= RADIUS && found.size() < MAX_MARKS; d++) {
    for (BlockPos pos : manhattanShell(origin, d, minY, maxY)) {
        if (!level.hasChunkAt(pos)) {
            continue;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.is(CIRCUIT_TAG)) {
            continue;
        }
        found.add(new CircuitOutlinePacket.Entry(pos.immutable(),
                isWorking(level, pos, state, probeInterfere ? getInterference(dim, pos.asLong()) : null)));
        if (found.size() >= MAX_MARKS) {
            dense = true;
            break;
        }
    }
}
```

**Expected Improvement**: Dense factories stay at ~128 hits (already true). Sparse megabases drop from tens of thousands of `getBlockState` calls to a few hundred, typically 5–20× less server time on Circuit refresh.

### Resolve Scan marks by UUID (references P-002)

**Before (Inefficient)**:

```java
AABB search = player.getBoundingBox().inflate(ScanOutlineService.SCAN_RADIUS);
for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, search,
        e -> MARKED.contains(e.getUUID()))) {
    if (!living.isAlive()) {
        MARKED.remove(living.getUUID());
        continue;
    }
    CACHED.add(living);
}
```

**After (Optimized)**:

```java
for (UUID id : MARKED) {
    Entity entity = level.getEntity(id);
    if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
        if (entity != null) {
            MARKED.remove(id);
        }
        continue;
    }
    CACHED.add(living);
}
```

**Expected Improvement**: Client cost becomes O(marks) ≤ 128 map lookups instead of walking every living body in a 66³ box. Farm/hub hitch should fall from milliseconds to tens of microseconds.

### Duration-gate Dust blindness (references P-006)

**Before (Inefficient)**:

```java
living.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLIND_REFRESH_TICKS, 0, false, true, true));
```

**After (Optimized)**:

```java
var blindness = living.getEffect(MobEffects.BLINDNESS);
if (blindness == null || blindness.getDuration() < 20) {
    living.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLIND_REFRESH_TICKS, 0, false, true, true));
}
```

**Expected Improvement**: Effect packets per mob drop from 5/s to ~1/s (duration 40, refresh at < 20). Same blindness feel.

### Skip climb tracker writes when velocity is already set (references P-010)

**Before (Inefficient)**:

```java
if (m.y < CLIMB_SPEED) {
    player.setDeltaMovement(m.x, CLIMB_SPEED, m.z);
}
player.fallDistance = 0.0f;
player.hurtMarked = true;
```

**After (Optimized)**:

```java
if (m.y < CLIMB_SPEED) {
    player.setDeltaMovement(m.x, CLIMB_SPEED, m.z);
    player.hurtMarked = true;
}
player.fallDistance = 0.0f;
```

**Expected Improvement**: Removes 20 tracker packets/s per climbing player after the first tick of a climb.

---

## Performance Optimization Priorities

### Phase 1: Critical Performance Fixes

- [ ] No critical items. Do not change `RADIUS`, scan range, or Faraday catch radius as a “perf” shortcut.

### Phase 2: High Impact Optimizations

- [ ] P-001: Manhattan-shell / origin-ordered Circuit walk with stop at 128
- [ ] P-002: Scan client UUID resolve via `getEntity`
- [ ] P-003: Chunk-bucket Faraday cages for combat events
- [ ] P-004: Conditional Faraday `hurtMarked` / inward-only knockback

### Phase 3: Medium Impact Improvements

- [ ] P-005: Control Field ring-only sweep on radius growth
- [ ] P-006: Duration-gate Dust blindness
- [ ] P-007: Duration-gate Faraday resistance / fire resistance
- [ ] P-008: Interference-pos set before Circuit neighbor notify
- [ ] P-009: Frustum / distance cull outline boxes
- [ ] P-010: Climb `hurtMarked` only on velocity write
- [ ] P-016: Skip Circuit `pick(16)` when `MARKS` is empty
- [ ] P-017: Cap Scan flecks to nearest / on-screen marks

### Phase 4: Performance Monitoring and Fine-tuning

- [ ] P-011: Fold `scan_abort` into Java or accept as a cheap safety net
- [ ] P-012: Delete ungranted `faraday_tick` power/functions
- [ ] P-013: Drop Origins `sr_internal faraday_emergency` once Java is sole authority
- [ ] P-018: Check Circuit/Control mode on the existing interval, not every tick
- [ ] Add Spark/jfr sampling on a factory world (Circuit) and a mob farm (Faraday + Scan)
- [ ] Log Circuit `refreshSight` duration when `dense` is true (one-line debug, not per tick)

---

## Estimated Performance Improvement Impact

### Performance Gains by Priority

| Priority Level | Expected Improvement | Implementation Complexity |
|----------------|---------------------|--------------------------|
| Critical Fixes | None remaining | n/a |
| High Impact | 40–80% less Circuit refresh time in factories; Scan farm hitch down an order of magnitude; Faraday gunfight/farm packet rate down 50–90% | Low–medium (localized walks and gates, no gameplay retune) |
| Medium Impact | 30–70% fewer effect/tracker packets on Dust, Faraday resistance, climb, and Control Field | Low (copy existing duration-gate pattern) |
| Low Impact | Cleanup only; no player-visible FPS change | Low |

### Resource Utilization Improvements

- **Database Load**: Not applicable (no SQL). Scoreboard/Apoli read volume stays the same.
- **Memory Usage**: Small drop in young-gen churn on Circuit refresh and Scan rebuild (fewer `BlockPos` / entity-list walks).
- **CPU Utilization**: Largest win is Circuit `refreshSight` on redstone computers and Scan client rebuild in farms.
- **Concurrency/Throughput**: Faraday combat handlers stop scaling with world-wide projectile volume; server TPS headroom returns during TACZ fights with a cage up.

---

## Performance Monitoring Setup Recommendations

### Observability Recommendations

- **Metrics**: Spark (or Minecraft’s `/debug` + Spark Forge) on the dedicated server. Custom counters are optional; a 30-second Spark sampler during Circuit-in-factory and Faraday-in-farm is enough.
- **Logging**: Structured one-liners only on rare paths: Circuit `dense` refresh duration, Faraday activate/deactivate, Scan pulse mark count. Do not log per tick.
- **Distributed Tracing**: Not applicable (single process). Use Spark’s tick / event-bus flame graph instead.

### Recommended Performance Tracking

- Server `tick` ms (alert if 50 ms exceeded for >5 s) while any origin player is in Circuit or Faraday
- Entity-tracker packet rate (`debug` pie / Spark packet view) during Faraday aura near farms
- Client frame time with Scan marks at the 128 cap
- Circuit `found.size()` and sections visited per refresh (debug)

### Performance Testing Recommendations

- Load: 4 origin players, one in Circuit inside a 32×32 redstone floor, one Faraday cage next to a 100-mob farm, one Control Field at r=30 in a village, one Scan pulse in that farm
- Benchmark paths: `CircuitService.refreshSight`, `FaradayService.tickAura`, `ScanOutlineClient.rebuildCache`, `ControlService.tickField`
- Tools: Spark 1.10+ for Forge 1.20.1; client F3 + Spark client sampler for outline FPS. No JMH — the hot paths are world-state dependent.

---

## Summary

This performance analysis identified **0 critical**, **4 high**, **8 medium**, and **6 low** performance issues across the application stack. The analysis focused on code patterns, datapack tick paths, resource utilization, and architectural performance.

**Key Strengths Identified**:

- Packet codecs are hard-capped (`PacketLimits.readCount` throws on oversized lists); the 1.0.14 unbounded decode class is gone
- Every service early-outs on empty session maps; Faraday `idleCombat()` keeps combat listeners cheap when no cage exists
- Circuit is section-culled with `maybeHas` / `hasOnlyAir`, sorted by section Manhattan, and stops after 129 marks
- Faraday slowness, Control lock refresh, LookTargets cone (32-candidate cap), Scan single line box, Dust static `SAND_DUST`, and inclusive `hasModePower` TTL are already in place
- Faraday duration/cooldown/silhouette now live in Java; `systems/faraday_tick` is no longer granted
- Motion harvest is Java (`getEntities` + tag + 8-cap) instead of per-tick NBT `data get`
- Radial tessellation is batched and `try/finally` flushed; hover `ItemStack`s are static

**Critical Areas Requiring Immediate Attention**:

- Circuit still walks entire 16³ sections in sparse megabases (P-001)
- Scan client still queries every nearby living entity instead of 128 UUIDs (P-002)
- One Faraday cage turns on a server-wide combat filter (P-003)
- Faraday aura still `hurtMarked`s every mid-ring mob every other tick (P-004)
- Dust and Faraday resistance still rewrite effects on a short clock (P-006, P-007)
- Circuit client still `pick(16)`s every tick even when idle (P-016)

**Expected Overall Performance Improvement**: Fixing the four high items should remove the remaining factory/farm hitches (roughly half to an order of magnitude on those three skills) without changing ranges, caps, or feel. Medium items are packet-rate cleanup using patterns the codebase already trusts. No rewrite is required.
