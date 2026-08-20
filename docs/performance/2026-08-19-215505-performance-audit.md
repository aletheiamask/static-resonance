## Executive Summary

### Audit Overview

- **Target System**: Static Resonance (`static_resonance` / `com.staticresonance`) 1.0.15
- **Analysis Date**: 2026-08-19
- **Analysis Scope**: Full-stack Minecraft Forge 1.20.1 Origins content mod — server tick loops, Apoli/scoreboard persistence, SimpleChannel packets, client outline/HUD renderers, and granted datapack powers/functions
- **Technology Stack**: Minecraft 1.20.1, Forge 47.2.0, Origins/Apoli (Forge + Fabric-via-Connector), Java 17, Gradle, ForgeConfigSpec COMMON, scoreboard + Apoli resources as persistence, Forge SimpleChannel protocol `"9"`

This is a live-tree static audit of the current `src/main` tree after the in-game config work and the review-fix pass (Faraday expire timestamps, scan `RADIUS_POWER` snapshot, unused `ORIGIN_PLAYERS` / `LAST_CHARGE_SYNC` / `INTERFERE_CHUNKS` caches removed, Faraday mid-ring push restored, Circuit `hasNeighborSignal` restored). Prior remediations still hold: `PacketLimits`, section-culled Circuit with distance-ordered early-exit at 128, incremental Scan client cache, Faraday `idleCombat()` gate, effect duration gates, deleted Faraday tick datapack, `scan_stop` / Faraday deactivate no longer run leftover `@e` selectors.

### Performance Assessment Summary

| Performance Level | Count | Percentage |
|-------------------|-------|------------|
| Critical Issues   | 0     | 0%         |
| High Impact       | 3     | 17%        |
| Medium Impact     | 9     | 50%        |
| Low Impact        | 6     | 33%        |
| **Total**         | **18** | **100%**   |

### Key Analysis Results

- **Performance Anti-Patterns**: 0 critical patterns; 3 high-impact patterns still fire on Faraday-active hot paths (per-tick scoreboard writes, dual-AABB aura + tracker spam, global combat fan-in)
- **Code Optimization Opportunities**: 3 high-impact Faraday fixes plus 9 medium session-gated or always-on costs
- **Architecture Assessment**: 8.2/10 performance best practices implemented (empty-map early-outs, packet caps, TTL `hasModePower` cache, logout cleanup, section-culled Circuit with mark cap, duration-gated effects, Faraday section prefilter, HeldShot entity cache, on-demand scan pulse; Faraday score clock, aura tracker spam, combat spatial gating, and Circuit unloaded-chunk expiry still missing)
- **Overall Code Performance Score**: 81/100 (based on static analysis of tick/render paths after the 1.0.15 remediations and the 2026-08-19 review-fix tree)

---

## Analysis Methodology

### Performance Analysis Approach

- **Static Code Analysis**: Comprehensive source code review for performance anti-patterns
- **Database Query Analysis**: Review of queries, indexing strategies, and data access patterns
- **Resource Utilization Assessment**: Analysis of memory, CPU, and I/O usage patterns
- **Architecture Performance Review**: Examination of caching, scaling, and optimization strategies

### Analysis Coverage

- **Files Analyzed**: 74 source files across 2 trees (42 Java under `src/main/java`, 32 datapack `.json`/`.mcfunction` under `src/main/resources/data`)
- **Database Queries Reviewed**: 22 data-access operations (Apoli `hasPower`/`getValue`/`setValue`/`sync`, scoreboard `sr_faraday`/`sr_faraday_cd`/`sr_mc`, player persistent-NBT allies, Circuit `getBlockState` / `hasNeighborSignal` walks, 1 granted `origins:action_over_time` power, 1 `origins:conditioned_attribute` `tick_rate` 20). There is no SQL/ORM layer.
- **API Endpoints Analyzed**: 10 SimpleChannel packet types across 1 channel (`ModNetwork`, protocol `"9"`)
- **Performance Patterns Checked**: N+1 queries, memory leaks, CPU bottlenecks, I/O blocking, concurrency issues

### Analysis Capabilities

- **Pattern Detected**: N+1 queries, inefficient loops, memory leaks, blocking operations, race conditions
- **Database Analysis**: Missing indexes, expensive queries, connection pool management
- **Resource Analysis**: Memory allocation patterns, CPU-intensive operations, I/O bottlenecks
- **Architecture Assessment**: Caching strategies, async patterns, connection pooling, concurrency safety
- **Frontend Analysis**: Core Web Vitals impact, bundle size, rendering performance, hydration cost

---

## Performance Findings

### Critical Performance Issues

No critical issues identified.

The historical critical class (unbounded S2C `ArrayList` decode, per-tick NBT `data get entity` harvest, naive Circuit 33³ `level.getBlockState`, Faraday same-tick `CAGE_TICK_STEP=5` killing `duration<=5`) is no longer present. `PacketLimits.readCount` throws on oversized counts, motion harvest is Java, Circuit sight is section-culled and stops at 128 marks after walking nearest tagged sections, Faraday duration/CD use expire timestamps checked every tick, and Faraday tick datapack functions are gone. Remaining issues degrade MSPT/FPS under skill-active load; they do not cause outages or data loss under normal play.

### High Performance Impact Findings

#### P-001: Faraday remaining scores are written every server tick

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:638`
**Performance Impact**: 7.3 (High)
**Pattern Detected**: Per-tick scoreboard mutation on a dummy objective that only needs second-resolution HUD and a `> 0` active test
**Code Context**:

```java
private static void tickCageExpiry(MinecraftServer server, long now) {
    if (!COOLDOWNS.isEmpty()) {
        for (UUID id : COOLDOWNS) {
            // ...
            int remaining = remainingTicks(CD_EXPIRE_AT, id, player, OBJ_FARADAY_CD, now);
            if (remaining <= 0) {
                setScore(player, OBJ_FARADAY_CD, 0);
                // ...
            } else {
                setScore(player, OBJ_FARADAY_CD, remaining);
            }
        }
    }
    // same pattern for ACTIVE_CAGES → OBJ_FARADAY
}

private static void setScore(ServerPlayer player, String objective, int value) {
    // board.getOrCreatePlayerScore(...).setScore(value);
}
```

**Impact**: The expire-timestamp rewrite is correct (same-tick arm no longer subtracts 5). The leftover score is now a derived HUD/datapack mirror, but `tickCageExpiry` runs every server tick and writes a new remaining value every tick. Default cage duration is 600 ticks; default cooldown is 2400 ticks. That is 600 writes during a cage plus 2400 writes during CD — 5× the old `CLOCK_INTERVAL = 5` clock. `ServerScoreboard.onScoreChanged` calls `setDirty()` on every write, so the world scoreboard stays dirty for the whole cage + CD window and is rewritten on every autosave. If a datapack later displays `sr_faraday` / `sr_faraday_cd`, each write also becomes a `ClientboundSetScorePacket`. HUD only reads the score every `HUD_INTERVAL = 10` ticks and shows seconds (`ticks / 20`). `isCageActive` / `canActivateCage` only need `> 0`.
**Performance Cost**: 20 scoreboard mutations/s per caged player + 20/s per player on CD. Two minutes of dirty-scoreboard I/O after every cage drop. Network cost is 0 unless the objective is displayed; disk/save cost is real on a dedicated server.
**Recommendation**: Keep expire maps as authority. Write the leftover score on arm/deactivate, on expiry (0), and at `HUD_INTERVAL` (or once per second). Reconstruct expire-from-leftover on login already tolerates up to one HUD interval of skew. Change `isCageActive(player)` to `ACTIVE_CAGES.contains(player.getUUID())` so aura, `nearestCage`, and `ActionBars` stop paying a scoreboard read on the combat path.
**Fix Priority**: Within 1 week

#### P-002: Faraday aura runs two AABB queries and dirties trackers at 10 Hz

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:545`
**Performance Impact**: 7.2 (High)
**Pattern Detected**: High-frequency entity queries + `setPos` / `hurtMarked` inside the active-cage loop
**Code Context**:

```java
if (now % 2 == 0) {
    tickAura(player, now);
}
// tickAura:
for (Entity entity : level.getEntities(player, catchBox, FaradayService::isInterceptable)) {
    if (entity.distanceToSqr(pos) <= catchSq) {
        catchEntity(level, entity);
    }
}
for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, repelBox, e ->
        e.isAlive() && e != player)) {
    // hard ring (≤ hardRadius, default 5): setPos + setDeltaMovement + hurtMarked every 2 ticks
    // mid ring (≤ midRadius, default 7): MID_PUSH + hurtMarked for the entire ring
}
```

**Impact**: Slowness is duration-gated (`REPEL_SLOW_TICKS = 50`, refresh only when remaining `< 20`). The review-fix restored mid-ring push for the entire mid ring, not only inbound walkers, so mid-ring `hurtMarked` is back to 10 Hz for every living body between hard and mid radius. Hard ring still `setPos`s every living body inside `hardRadius` at 10 Hz. Catch box (`CATCH_RADIUS = 4.8`) and repel box (config `repelRadius`, default 10, max 32) are two overlapping entity queries every 2 ticks per active cage. A 40-mob farm next to a cage is ~20 teleport+velocity packets per tick plus sparks every `REPEL_VFX_INTERVAL = 5`.
**Performance Cost**: 0.2–2.5 ms CPU plus 50–400 extra packets/s per active cage in a dense mob area. Single-player wilderness: negligible. Config `repelRadius` 32 roughly quadruples the living AABB versus default 10.
**Recommendation**: Skip `setPos` when `|dist - hardR|` is already within a small epsilon and the entity is outside the wall. Skip `hurtMarked` when velocity did not change. Optionally keep the two typed AABBs. Do not drop the restored mid-ring push feel — only skip no-op tracker dirtying.
**Fix Priority**: Within 1 week

#### P-003: Faraday combat listeners tax the whole server while any cage is active

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:245`
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
public static void onAttack(LivingAttackEvent event) { /* same idleCombat + shouldBlock */ }
public static void onHurt(LivingHurtEvent event) { /* same */ }
// shouldBlock → HELD.containsKey or nearestCage
// nearestCage iterates ACTIVE_CAGES with a ±2 section prefilter
```

`idleCombat()` is `ACTIVE_CAGES.isEmpty() && HELD.isEmpty()`. The idle gate is correct. Once one player raises a cage, every projectile impact and every `LivingAttackEvent` / `LivingHurtEvent` on the server enters `shouldBlock`. A single TACZ/vanilla hit can fire all three listeners. Each uncached call walks `ACTIVE_CAGES` (`getPlayer` + `isCageActive` scoreboard read + section prefilter + `distanceToSqr`). The one-slot `blockCacheId` / `blockCacheTick` only helps the same entity hitting multiple listeners in the same tick — a spray of N bullets is N `nearestCage` walks. Default cage lifetime is 600 ticks.
**Impact**: One Overcharge player paying 50 charge turns on a server-wide intercept filter. Cost scales with world combat volume, not with nearby threats. `GUNSHOT_TYPES` caching and the ±2 section prefilter are real improvements.
**Performance Cost**: A few microseconds per event × hundreds of bullets/attacks per second ≈ 0.4–3 ms extra server time during gunfights with a cage up. In-cage gunfights pay 2–3× `shouldBlock` per bullet.
**Recommendation**: Return the cage player from one `nearestCage` walk. Handle intercept on `ProjectileImpactEvent` only; let `onAttack` / `onHurt` check `HELD.containsKey` only. Keep `idleCombat()` for the zero-cage case.
**Fix Priority**: Within 1 week

### Medium Performance Impact Findings

#### P-004: Circuit sight still walks every cell of every nearby circuit-tagged section

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:367`
**Performance Impact**: 5.8 (Medium)
**Pattern Detected**: Section-culled 16-block (config up to 64) sphere walk; sections are now distance-sorted and the walk breaks at 128 marks
**Code Context**:

```java
ordered.sort(Comparator.comparingDouble(e -> { /* section center dist to player */ }));
scan:
for (Map.Entry<Long, LevelChunkSection> taggedSection : ordered) {
    // triple loop over clipped section cells
    BlockState state = section.getBlockState(lx, ly, z & 15);
    if (!state.is(CIRCUIT_TAG)) {
        continue;
    }
    found.add(new CircuitOutlinePacket.Entry(pos, isWorking(level, pos, state, inf)));
    if (found.size() >= MAX_MARKS) {
        break scan;
    }
}
```

**Impact**: This is better than the previous collect-all-then-sort: nearest tagged sections are visited first and the walk stops at `MAX_MARKS = 128`. Air / non-circuit sections still skip via `hasOnlyAir` / `maybeHas`. Remaining cost is visiting every cell of each tagged section until 128 circuit blocks are found. `#static_resonance:circuit` is a wide tag (hoppers, iron doors, buttons, plates, rails, dust). Default `sightRadius` is 16 (`SCAN_INTERVAL = 20`, `MOVE_SNAP = 4`); config max is 64, which expands the section cube from ~3×3×3 to ~8×8×8. Switching into Circuit calls `startSight` immediately.
**Performance Cost**: ~2–15k `getBlockState` calls per refresh in a redstone-dense area at default radius (0.3–4 ms typical). At `sightRadius` 64 in a megabase, 10–30 ms is still possible if the nearest sections are tagged but sparse (many cells walked before 128 hits). Wilderness Circuit is cheap.
**Recommendation**: Walk Manhattan shells from the player so a dense cluster next to the caster stops after one or two sections. Keep the 128 cap and the section `maybeHas` cull. Treat `sightRadius` 64 as a pack-author footgun in the config comment.
**Fix Priority**: Within 1 month

#### P-005: Control Field sweeps a growing 30-block cylinder every 4 ticks

**Location**: `src/main/java/com/staticresonance/mode/ControlService.java:470`
**Performance Impact**: 5.4 (Medium)
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
            e.isAlive() && e != player && !AllyService.isProtected(player, e)
                    && e.distanceToSqr(pos) <= rSq)) {
        if (!isUnderLock(living)) {
            applyLock(player, living, level, true, true, session.lockEffectTicks);
        } else if (needsLockRefresh(living)) {
            refreshLockEffects(living, session.lockEffectTicks);
        }
    }
}
```

**Impact**: One field session (40 charge, 60 s, stand still) is gated well (`SESSIONS.isEmpty()` early-out). Mode-abort now runs every tick before radius expand (review-fix). At full radius the AABB is 60×5×60 (`fieldMaxRadius` default 30, config max 64). A village / raid / farm inside that cylinder gets a living scan 5 times per second and three effects plus first-lock VFX per newly locked body. Refresh is already duration-gated (`REFRESH_WHEN_REMAINING = 20`).
**Performance Cost**: 0.2–1.5 ms per sweep at r=30 in a populated area. Zero when no session.
**Recommendation**: Keep the feel. Optionally scan only the newly expanded ring when `radius` grows, and reuse the existing lock-refresh gate (already good).
**Fix Priority**: Within 1 month

#### P-006: Motion harvest walks every online player and fully syncs Apoli on every charge change

**Location**: `src/main/java/com/staticresonance/mode/MotionHarvestService.java:60`
**Performance Impact**: 5.2 (Medium)
**Pattern Detected**: Unfiltered player-list scan + full power-container sync on regen and harvest
**Code Context**:

```java
private static void forOriginPlayers(MinecraftServer server, Consumer<ServerPlayer> action) {
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
        action.accept(player);
    }
}
// regenTick / harvest:
if (!ModeService.hasModePower(player)) {
    return;
}
if (next != charge && ApoliResources.setValue(player, ModeService.CHARGE_POWER, next)) {
    ApoliResources.sync(player);
}
```

**Impact**: Review-fix deleted `ORIGIN_PLAYERS` and `LAST_CHARGE_SYNC`. Every `regenIntervalTicks` (default 35) and `motionIntervalTicks` (default 20) the server iterates **all** online players. `hasModePower` is TTL-cached (20 ticks), so the miss path is cheap. Origin players below 100 charge then run a 10-block AABB of `#static_resonance:motion_sources` (default `motionMaxDist = 5`, config max 32). A charge increment calls `ApoliAPI.synchronizePowerContainer` — a full container packet, not a single resource. When `now` is divisible by both 20 and 35 (every 140 ticks) a charging player can receive two full container syncs in the same tick. Wet regen also sends 4 bubble particles.
**Performance Cost**: ~0.1–0.8 ms/s on a 40-player SMP with a handful of origin users; Apoli sync is the dominant packet. Single-player: noise.
**Recommendation**: Do not rebuild a permanent origin set if that was rejected as unused/stale. Coalesce regen+harvest into one pass on shared ticks and one `sync`. Optionally stamp the last synced charge and skip `synchronizePowerContainer` when the value the client already has is unchanged (the current `next != charge` guard already covers no-op writes).
**Fix Priority**: Within 1 month

#### P-007: Scan client still walks every rendered entity when marked UUID refs are missing

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:187`
**Performance Impact**: 5.0 (Medium)
**Pattern Detected**: Fallback full client entity iteration instead of UUID map lookup
**Code Context**:

```java
if ((clientTick & 1) == 0 || CACHED.isEmpty()) {
    rebuildCache(level);
}
// rebuildCache reuses live CACHED refs, then:
if (have.size() < MARKED.size()) {
    for (Entity entity : level.entitiesForRendering()) {
        if (!(entity instanceof LivingEntity living)) {
            continue;
        }
        // match MARKED UUIDs …
    }
}
```

**Impact**: This is better than walking the render list every other tick unconditionally. After the first successful resolve, the common path is O(cached marks) ≤ 128. The remaining cliff is the miss path: hubs, item-filled farms, and TACZ bullet spam inflate `entitiesForRendering()` into the thousands while `MARKED` stays ≤128. That walk runs whenever a marked mob unloads, dies out of cache, or the first pulse arrives before the entity is in `CACHED`. Server `pruneDead` (`ScanOutlineService.java:230`) only drops UUIDs whose entity is loaded **and** dead (`entity != null && !entity.isAlive()`). Unloaded / dimension-changed ids stay in `MARKED` forever, so `have.size() < MARKED.size()` stays true and the client keeps walking the full render list every other tick until scan stops. Render still draws one see-through `renderLineBox` per cached body every frame. Flecks stay capped at 24 nearest (`FLECK_CAP`).
**Performance Cost**: 0.05–0.3 ms/tick on the hit path; 0.3–4 ms on the miss path with 500–2000 rendered entities, sticky for the whole scan if any mark unloads.
**Recommendation**: Resolve missing UUIDs via `level.getEntities().get(uuid)` for the ≤128 marked ids (client `EntityLookup` supports UUID). On the server, drop a UUID after two prune passes where `getEntity` is null. On the client, stop walking `entitiesForRendering` after one miss pass until the next S2C packet. Do not evict out-of-range marks that are still loaded.
**Fix Priority**: Within 1 month

#### P-008: Scan and Circuit still submit up to 128 see-through line boxes every frame

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:135`
**Performance Impact**: 4.5 (Medium)
**Pattern Detected**: Per-frame GPU line-box submission at the 128 cap through a no-depth `RenderType`
**Code Context**:

```java
for (LivingEntity living : CACHED) {
    if (!living.isAlive()) {
        continue;
    }
    if (expanding && living.distanceToSqr(scanOrigin) > revealSq) {
        continue;
    }
    float[] rgb = colorFor(living);
    AABB box = living.getBoundingBox().inflate(0.06);
    LevelRenderer.renderLineBox(pose, lines, box, rgb[0], rgb[1], rgb[2], 0.95f);
}
```

`CircuitOutlineClient.onRender` (`CircuitOutlineClient.java:83`) does the same for up to 128 precomputed AABBs. `SeeThroughRender` uses a 256 KiB line buffer and `NO_DEPTH_TEST`. While marks exist, `CircuitOutlineClient.onClientTick` also `player.pick(64.0)` every tick (`CircuitOutlineClient.java:127`) even though server sight defaults to 16.
**Impact**: One box per mark. At the 128 cap this is 128 `renderLineBox` submissions (24 edges each) through a see-through type with no frustum test. Circuit is cheaper because AABBs are precomputed; only one outline type is typically live (mode change stops scan). Combined with P-007 this is the Scan FPS cliff. The 64-block look ray is a cheap extra clip versus the box loop.
**Performance Cost**: 0.2–1.2 ms GPU/CPU per frame at the cap; less with a handful of marks.
**Recommendation**: Optionally drop marks that fail a frustum / distance test before `renderLineBox`. Keep see-through; that is the feature.
**Fix Priority**: Within 1 month

#### P-009: Look-cone fallback runs up to 32 line-of-sight clips per miss

**Location**: `src/main/java/com/staticresonance/mode/LookTargets.java:58`
**Performance Impact**: 4.3 (Medium)
**Pattern Detected**: Cast-time entity scan plus per-candidate world clip
**Code Context**:

```java
double fallback = Math.min(range, 32.0);
AABB around = player.getBoundingBox().inflate(fallback);
List<LivingEntity> candidates = new ArrayList<>(player.level().getEntitiesOfClass(
        LivingEntity.class, around, e -> /* alive, not self, extra, dist */));
if (candidates.size() > CONE_CANDIDATE_CAP) {
    candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
    candidates = candidates.subList(0, CONE_CANDIDATE_CAP);
}
for (LivingEntity living : candidates) {
    if (dot < minDot || !player.hasLineOfSight(living)) {
        continue;
    }
}
```

**Impact**: Control lock (`targetRange` default 64), Dust burst/zone, and radial ally snapshot all share this. A precise `ProjectileUtil` ray miss then inflates a 32-block box, copies the living list, optionally sorts, and clips up to 32 `hasLineOfSight` rays (each a block clip from eye to target eye). Cast-gated (not per tick). A 40° cone in a raid / farm is the expensive miss path; a clean ray hit returns immediately.
**Performance Cost**: 0.2–1.5 ms per miss in a crowded 32-block box. Hit path is cheap.
**Recommendation**: Keep `CONE_CANDIDATE_CAP = 32`. Skip `hasLineOfSight` for the closest 1–3 candidates first, or clip only after a cheaper chunk/section occupancy check. Do not drop the cone; it is the gameplay feel.
**Fix Priority**: Within 1 month

#### P-010: Circuit `isWorking` falls through to `hasNeighborSignal` per unmarked block

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:507`
**Performance Impact**: 4.6 (Medium)
**Pattern Detected**: Extra 6-neighbor redstone lookup on the Circuit sight hot path
**Code Context**:

```java
static boolean isWorking(Level level, BlockPos pos, BlockState state, @Nullable Interference inf) {
    if (inf != null) {
        return inf.forceOn;
    }
    // POWER / LIT / EXTENDED / TRIGGERED / OPEN / POWERED / REDSTONE_BLOCK …
    return level.hasNeighborSignal(pos);
}
```

**Impact**: Review-fix restored `hasNeighborSignal` so sight color matches vanilla “this piece is powered” for blocks without a readable property. `refreshSight` calls `isWorking` once per found circuit block (up to 128, once per second while moving). `hasNeighborSignal` checks six directions and can recurse into dust / diode updates. Blocks that already expose `POWER` / `POWERED` / `LIT` return earlier and stay cheap. Custom or poorly tagged circuit blocks pay the neighbor walk.
**Performance Cost**: 0.05–0.8 ms extra per Circuit refresh when many tagged blocks miss the property fast-path. Zero outside Circuit mode.
**Recommendation**: Keep the restore for correctness. Add explicit property/type arms for any remaining tagged blocks that currently fall through (rails, note blocks, bells) so the neighbor walk is rare. Do not call `hasNeighborSignal` from the inner cell loop for blocks already classified.
**Fix Priority**: Within 1 month

#### P-011: Faraday `LivingDamageEvent` has no idle gate and always consults Apoli

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:275`
**Performance Impact**: 4.7 (Medium)
**Pattern Detected**: Always-on damage fan-in for emergency floor
**Code Context**:

```java
public static void onDamage(LivingDamageEvent event) {
    if (event.isCanceled() || event.getEntity().level().isClientSide()) {
        return;
    }
    capIncoming(event.getEntity(), event.getAmount(), event.getSource().getDirectEntity(),
            event::setAmount, () -> event.setCanceled(true));
}
// capIncoming: instanceof ServerPlayer → hasModePower → isEmergencyEnabled → health math
```

**Impact**: Unlike `onProjectileImpact` / `onAttack` / `onHurt`, `onDamage` does not call `idleCombat()`. Every server-side living-damage event enters `capIncoming`. The `instanceof ServerPlayer` filter is cheap for mob-on-mob. Every player hit (any origin) then pays `ModeService.hasModePower` (cached) plus `isEmergencyEnabled` (20-tick `EMERGENCY_CACHE`) for origin players. This is required for the configured health floor even when no cage is up. High-DPS PvP / mob grinders on origin players make this a per-hit Apoli-adjacent read on the hottest vanilla event.
**Performance Cost**: Sub-0.1 ms per player hit typical; measurable in TACZ sprays (tens of hits/s × origin players).
**Recommendation**: Fast-path non-players (already done). Keep the emergency math. The existing `hasModePower` + `EMERGENCY_CACHE` TTL is the right shape; do not add a cage-only idle gate here or emergency will miss the first lethal hit.
**Fix Priority**: Within 1 month

#### P-012: Circuit interferences never expire while the chunk is unloaded

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:310`
**Performance Impact**: 5.2 (Medium)
**Pattern Detected**: Unbounded map growth + leftover global event tax when the toggled block unloads
**Code Context**:

```java
while (it.hasNext()) {
    Interference inf = it.next().getValue();
    if (level == null || !level.hasChunkAt(inf.pos)) {
        continue; // expire NOT applied
    }
    if (now >= inf.expireGameTime) {
        restore(level, inf);
        it.remove();
        continue;
    }
    // ...
}

public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
    if (INTERFERENCES.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) {
        return;
    }
    Interference inf = getInterference(level.dimension(), event.getPos());
    // ...
}
```

**Impact**: Toggle a wire, then walk away. The chunk unloads, `tickInterferences` skips the entry instead of dropping it, and `expireGameTime` is never applied. `INTERFERENCES` stays non-empty for the rest of the session, so `NeighborNotifyEvent` (vanilla fires this on almost every redstone / piston / observer update in the dimension) keeps doing two ConcurrentHashMap gets on **every** notify until restart or the chunk is loaded again after expiry. Logout still `restoreOwned` for that player; other players' unloaded toggles leak until their owner disconnects. No cap. Review-fix correctly deleted `INTERFERE_CHUNKS`; this leftover path re-creates the world-wide tax.
**Performance Cost**: One leaked entry: 0.02–0.2 ms extra on a large redstone computer, for the rest of the session instead of 160 ticks. Many leaked entries: the map grows with toggles in unloaded chunks.
**Recommendation**: Always `it.remove()` when `now >= inf.expireGameTime`. Restore only if the chunk is loaded; if not, drop the entry and skip restore (vanilla state is gone with the chunk anyway). Keep the `isEmpty()` notify gate.
**Fix Priority**: Within 1 month

### Low Performance Impact Findings

#### P-013: Scan abort still polls two Apoli resources every 5 ticks

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

`ModeService.setMode` already calls `ScanOutlineService.stop` when leaving Sense. The power is a safety net that still wakes every origin player every 5 ticks for two resource reads. `scan_stop` no longer runs `@e` tag selectors, so the fire path is cheap.
**Impact**: Cheap Apoli reads; `execute_command scan_stop` only if the Java path was skipped.
**Performance Cost**: Microseconds per origin player per 5 ticks.
**Recommendation**: Keep as a rare safety net, or fold the check into `ScanOutlineService.onServerTick` (already walks `MARKED`) and drop the power.
**Fix Priority**: Within 2 months

#### P-014: Mode / Faraday / Scan still dispatch Brigadier for one-shot FX

**Location**: `src/main/java/com/staticresonance/mode/ModeService.java:130`
**Performance Impact**: 2.4 (Low)
**Pattern Detected**: `performPrefixedCommand` parse+execute on infrequent paths
**Code Context**:

```java
server.getCommands().performPrefixedCommand(source, "function " + mode.feedbackFunction());
// FaradayService.runFunction → activate / deactivate / emergency_fx
// ScanOutlineService.runFunction → scan_start / scan_stop
```

**Impact**: Each call tokenizes and walks the command tree on the server thread. Mode change, cage raise/lower, and scan toggle are player-initiated (plus Faraday emergency). Functions are now VFX-only (duration/CD/scan radius live in Java).
**Performance Cost**: ~0.1–0.4 ms per invocation. Not a tick tax.
**Recommendation**: Move one-shot particles/sounds into the Java services (already the authority for spend/apply). Keep functions only if datapack authors need to restyle FX.
**Fix Priority**: Within 2 months

#### P-015: TickGate and `MODE_POWER_CACHE` only evict on login/logout/dim change

**Location**: `src/main/java/com/staticresonance/mode/TickGate.java:9`
**Performance Impact**: 2.1 (Low)
**Pattern Detected**: Unbounded ConcurrentHashMap keyed by player UUID
**Code Context**:

```java
final class TickGate {
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();
    // blocked / stamp / clear — clear is only called from logout subscribers
}
// ModeService.MODE_POWER_CACHE: CacheKey(UUID, clientSide) → CacheVal, TTL 20 ticks
```

**Impact**: `CAST_GATE`, `CHANGE_GATE`, `MODE_POWER_CACHE`, Faraday `GUNSHOT_TYPES` (by `EntityType`), `ACTIVE_CAGES`, `SESSIONS`, `SIGHTS`, `MARKED`, `ZONES`, `HANDS`, `HELD`, Faraday expire maps, `EMERGENCY_CACHE` all have logout cleanup except `GUNSHOT_TYPES` (bounded by registry size) and TickGate/`MODE_POWER_CACHE` if a player is removed without `PlayerLoggedOutEvent` (crash, kick edge). Entries are tiny (`UUID` + `long`).
**Performance Cost**: Bytes per ghost UUID. Not a gameplay hitch.
**Recommendation**: Optionally sweep TickGate/`MODE_POWER_CACHE` when `size()` exceeds online player count × 2. Keep the 20-tick TTL.
**Fix Priority**: Within 2 months

#### P-016: Radial HUD reallocates translatable components and layered fills every frame

**Location**: `src/main/java/com/staticresonance/client/ui/RadialModeMenuRenderer.java:80`
**Performance Impact**: 2.8 (Low)
**Pattern Detected**: Per-frame allocation on an open-only HUD
**Code Context**:

```java
if (!RadialModeMenu.isOpen()) {
    if (animActive) {
        resetHoverAnim();
    }
    return;
}
graphics.fill(0, 0, sw, sh, withAlpha(COL_SCRIM, a0));
graphics.fill(sw / 8, sh / 8, sw - sw / 8, sh - sh / 8, withAlpha(0x3A3A3A, a1));
graphics.fill(sw / 5, sh / 5, sw - sw / 5, sh - sh / 5, withAlpha(0x484848, a2));
```

Wedge geometry is batched into one `Tesselator` triangle pass. Labels call `Component.translatable` every frame, including config-backed Faraday/Control/Dust hints (`ticksToSec`, `manualCost`). Menu is hold-R only; closed path is a boolean check.
**Impact**: Open-only. No cost in combat. Three full-screen fills plus ~5×12×2 triangles per wedge is modest at GUI scale.
**Performance Cost**: A few hundred microseconds and some young-gen garbage while the wheel is held.
**Recommendation**: Cache `Component` instances for static labels. Keep live config interpolation. Keep the batched sector upload.
**Fix Priority**: Within 2 months

#### P-017: Climb and Control HANDS still subscribe to every player tick

**Location**: `src/main/java/com/staticresonance/mode/ClimbService.java:23`
**Performance Impact**: 2.6 (Low)
**Pattern Detected**: Per-player tick fan-in with cheap early-outs
**Code Context**:

```java
// ClimbService — client + server, every player
if (!ModeService.hasModePower(player)) {
    return;
}
if (player.isSpectator() || player.isInWater() || !player.horizontalCollision) {
    return;
}

// ControlService.onPlayerTick — server only
if (HANDS.containsKey(player.getUUID()) && !isUnderLock(player)) {
    HANDS.remove(player.getUUID());
}
```

**Impact**: Climb is dual-side on purpose (client motion is authoritative; dedicated client toml is prediction-only). `hasModePower` is TTL-cached, but every call allocates a `new CacheKey(uuid, clientSide)` record (`ModeService.java:39`) — Forge fires this for every player entity ticked (server: all online; client: local + other players in view). The expensive path (`setDeltaMovement` + `hurtMarked` + sparks every 8 ticks) only runs while an origin player is sliding on a wall; `hurtMarked` then fires 20 times/s. Control HANDS cleanup does a ConcurrentHashMap get for every server player every tick even when `HANDS` is empty.
**Performance Cost**: One record alloc × players in view per tick; microseconds otherwise. Measurable on a busy hub client (50 players × 20 allocs/s). Climb `hurtMarked` is tracker packets only while on a wall.
**Recommendation**: Gate Control `onPlayerTick` on `!HANDS.isEmpty()`. Store `hasModePower` in two `Map<UUID, CacheVal>` (server/client) so the hot call does not allocate. Set Climb `hurtMarked` every 4 ticks; clients already predict. Leave Climb dual-side; a server-only climb feels like rubber-banding.
**Fix Priority**: Within 2 months

#### P-018: Circuit sight tick looks up every sighted player before the 20-tick interval

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:186`
**Performance Impact**: 1.8 (Low)
**Pattern Detected**: Per-tick player-list lookup inside a 1 Hz scan
**Code Context**:

```java
for (UUID id : SIGHTS.keySet()) {
    ServerPlayer player = server.getPlayerList().getPlayer(id);
    if (player == null) {
        SIGHTS.remove(id);
        continue;
    }
    if (!scanTick) {
        continue;
    }
    // refresh if dirty / moved
}
```

**Impact**: Offline cleanup every tick is intentional. The `getPlayer` hash lookup runs 20× more often than the scan. One Circuit player: 20 lookups/s. Combined with Faraday/Control/Dust/Scan/MotionHarvest each owning a `ServerTickEvent` subscriber, Forge walks six empty-gated listeners every tick.
**Performance Cost**: Microseconds unless many Circuit players are online.
**Recommendation**: Check `scanTick` once before the loop; keep a 20-tick orphan sweep. Optionally collapse the six server-tick subscribers into one dispatcher.
**Fix Priority**: Within 2 months

---

## Code Pattern Performance Analysis

### Performance Anti-Pattern Detection

- **N+1 Query Patterns**: 5 instances detected across 4 files (`LookTargets` LoS clips, Faraday `nearestCage` per combat event, Circuit cell walk, Scan `entitiesForRendering` miss path, MotionHarvest per-player AABB)
- **Blocking Operations**: 3 Brigadier `performPrefixedCommand` sites (`ModeService`, `FaradayService`, `ScanOutlineService`) on the server thread; no thread-pool / off-thread world access
- **Memory Pressure Points**: 4 locations with excessive allocation (`CircuitService.refreshSight` `BlockPos`+`Entry` per hit, `LookTargets` candidate `ArrayList`, radial per-frame `Component`, Scan fleck `shown` list every 4 ticks)
- **Inefficient Data Access**: 6 queries with suboptimal patterns (full Apoli container sync, per-tick Faraday leftover scores, `entitiesForRendering` miss path, unfiltered player list, `hasNeighborSignal` fallback, Faraday dual AABB)
- **Concurrency Issues**: 0 correctness races on the hot path (all world mutation stays on the server thread; `ConcurrentHashMap` is used as identity maps, not as cross-thread world access)

### Database Access Pattern Analysis

- **ORM/Query Usage**: 22 scoreboard / Apoli / NBT / block-state operations analyzed; no SQL
- **Missing Async Patterns**: 0 database operations identified for async conversion (Minecraft world APIs are server-thread-only; off-thread `getBlockState` would be unsafe)
- **Query Complexity**: 4 complex queries requiring optimization review (Circuit section walk, Scan client miss-path walk, Faraday dual AABB aura, Control r=30 living sweep)
- **Connection Management**: Not applicable — single dedicated server thread; no connection pool. Analog: six independent `TickEvent.ServerTickEvent` subscribers instead of one dispatcher

### Resource Management Pattern Analysis

- **Memory Allocation Patterns**: Circuit nearest-section walk still allocates a `BlockPos` + `Entry` per hit up to 128. Scan/Circuit outline packets allocate a new `ArrayList` up to 128 per send. Radial allocates components only while open.
- **Garbage Collection Pressure**: 4 locations with excessive object creation (Circuit scan, LookTargets copy+sort, Scan fleck `shown` list every 4 ticks, radial labels)
- **Thread/Event Loop Usage**: All work is on the Minecraft server/client thread. Faraday combat listeners and `LivingDamageEvent` are the highest-frequency event-bus tax. No off-thread world access (correct).
- **Caching Opportunities**: `hasModePower` (20-tick TTL), Faraday `EMERGENCY_CACHE` (20-tick TTL), `GUNSHOT_TYPES`, HeldShot `cached` entity, Scan `CACHED` living refs, Ally NBT cache — already present. Faraday leftover scores and Apoli charge syncs are the remaining uncached/uncoalesced writes.
- **Resource Disposal**: Logout clears Faraday cages/CD/emergency cache, Control sessions/hands, Circuit sights + owned interferences, Scan marks, Dust zones, Ally cache, TickGates. `GUNSHOT_TYPES` is registry-bounded. No file/socket leaks.

---

## Architecture Performance Assessment

### Data Access Layer Analysis

- Persistence is Apoli resource powers plus dummy scoreboard objectives (`sr_faraday`, `sr_faraday_cd`, `sr_mc`, leftover `sr_scan`). There is no SQL, no connection pool, and no ORM.
- Faraday duration/CD now live in expire maps; leftover scores are a derived mirror. Writing that mirror every tick (P-001) is the main data-layer regression versus the old 5-tick clock.
- Charge regen/harvest live in Java and read `SrConfig` live. `ApoliResources.sync` after every charge change ships the whole power container (P-006).
- Ally lists are persistent-NBT with an in-memory `Set` cache invalidated on login/logout/write.

### Application Layer Analysis

- Tick work is session-gated: Dust `ZONES.isEmpty()`, Control `SESSIONS.isEmpty()`, Scan `MARKED.isEmpty()`, Circuit `SIGHTS.isEmpty()` after interference tick. Faraday always enters `onServerTick` but `tickHeld` / `tickCageExpiry` return immediately when maps are empty.
- Config snapshots in-flight Dust/Control/Faraday duration and hold; Faraday radii stay live each aura tick. Climb is dual-side prediction by design.
- CPU hot paths are entity AABB queries and Circuit section cell walks, not Java number-crunching. Particle/sound bursts are mostly one-shot (activate/deactivate/cast); Faraday silhouette + aura sparks and Scan flecks are the repeating VFX.
- I/O is scoreboard `setDirty` (P-001) and Apoli container packets (P-006). No disk reads on the tick path.

### Frontend Analysis (if applicable)

- Not a web app. Client cost is Forge `RenderLevelStageEvent` line boxes (Scan + Circuit, P-008), Scan flecks (24 nearest × 2 particles / 4 ticks), and the hold-R radial (P-016, gated on `isOpen()`).
- Core Web Vitals / bundle size / hydration do not apply. Analog: frame time while Circuit/Scan is active, and GUI allocations while the wheel is held.
- `SeeThroughRender` is a single 256 KiB no-depth line `RenderType`. Circuit AABBs are precomputed; Scan AABBs are taken from living bounding boxes every frame.
- Asset weight is vanilla particles + item icons on the radial. No custom textures in the hot path.

### Infrastructure Analysis

- Dedicated-server authoritative COMMON config. Clients cannot cheapen spend/apply. Climb client toml is prediction-only.
- No CDN, compression layer, or HTTP stack. Transfer cost is SimpleChannel (10 packet types, protocol `"9"`) plus vanilla scoreboard / Apoli / particle packets.
- Observability is SLF4J config load/reload logs only. No MSPT / packet / outline-count metrics.

---

## Performance Bottleneck Analysis

### Top Performance Bottlenecks

| Rank | Component | Issue | Impact Score | Estimated Cost |
|------|-----------|-------|--------------|----------------|
| 1 | FaradayService.tickCageExpiry | Per-tick leftover score writes for cage + CD | 7.3 | 20 mutations/s per caged player + 20/s during 2400-tick CD; scoreboard stays dirty |
| 2 | FaradayService.tickAura | Dual AABB + setPos/hurtMarked at 10 Hz | 7.2 | 0.2–2.5 ms + 50–400 tracker packets/s per cage in a farm |
| 3 | Faraday combat listeners | Global shouldBlock / nearestCage while any cage is up | 7.0 | 0.4–3 ms during TACZ/arrow sprays |
| 4 | CircuitService.refreshSight | Tagged-section cell walk until 128 marks | 5.8 | 0.3–4 ms/refresh at r=16; worse at config r=64 |
| 5 | ControlService.tickField | 30-block living sweep every 4 ticks | 5.4 | 0.2–1.5 ms/sweep in a populated cylinder |
| 6 | MotionHarvestService | All-player walk + full Apoli sync | 5.2 | 0.1–0.8 ms/s + 1–2 container packets/s per charging origin |
| 7 | ScanOutlineClient.rebuildCache | entitiesForRendering miss path | 5.0 | 0.3–4 ms client when marked refs are missing |
| 8 | Scan/Circuit outline render | 128 see-through line boxes / frame | 4.5 | 0.2–1.2 ms/frame at the cap |
| 9 | CircuitService.tickInterferences | Unloaded-chunk entries never expire | 5.2 | NeighborNotify stays world-hot for the session |

---

## Technical Recommendations

### Immediate Performance Fixes

1. Write Faraday leftover scores on arm / expiry / HUD interval only (P-001). Keep expire maps as the clock.
2. Skip Faraday hard-ring `setPos` / `hurtMarked` when the entity is already on the wall and velocity is unchanged (P-002). Keep mid-ring push feel.
3. Handle projectile intercept on `ProjectileImpactEvent` only; let attack/hurt check `HELD` (P-003). Keep `idleCombat()`.

### Performance Enhancements

1. Resolve Scan marks by UUID lookup instead of `entitiesForRendering()` (P-007).
2. Coalesce MotionHarvest regen+harvest into one pass and one Apoli sync (P-006).
3. Scan only Control Field’s newly expanded ring (P-005).
4. Walk Circuit Manhattan shells so dense wiring next to the caster hits 128 without scanning far tagged sections (P-004).
5. Frustum / distance cull outline boxes before `renderLineBox` (P-008).

### Architecture Improvements

1. Optional single `ServerTickEvent` dispatcher for Faraday / Control / Dust / Circuit / Scan / MotionHarvest empty-gated ticks (P-018).
2. Move one-shot FX out of Brigadier functions into the Java services (P-014).
3. Fold `scan_abort` into `ScanOutlineService.onServerTick` (P-013).
4. Add MSPT / outline-count / Apoli-sync debug counters behind a config flag so the next audit can be measured, not only static.

---

## Code Optimization Examples

### Throttle Faraday leftover score writes (references P-001)

**Before (Inefficient)**:

```java
int remaining = remainingTicks(CAGE_EXPIRE_AT, id, player, OBJ_FARADAY, now);
if (remaining <= 0) {
    tryDeactivate(player);
} else {
    setScore(player, OBJ_FARADAY, remaining); // every tick
}
```

**After (Optimized)**:

```java
int remaining = remainingTicks(CAGE_EXPIRE_AT, id, player, OBJ_FARADAY, now);
if (remaining <= 0) {
    tryDeactivate(player);
} else if (now % HUD_INTERVAL == 0L) {
    setScore(player, OBJ_FARADAY, remaining);
}
```

**Expected Improvement**: 20× fewer scoreboard mutations during a cage; 20× fewer during the 2400-tick CD if the same throttle is applied to `OBJ_FARADAY_CD`. HUD still updates every 0.5 s. `isCageActive` stays true until expiry.

### Skip no-op Faraday hard-wall teleports (references P-002)

**Before (Inefficient)**:

```java
if (dist <= hardR) {
    double wall = hardR + 0.05;
    living.setPos(pos.x + nx * wall, living.getY(), pos.z + nz * wall);
    living.setDeltaMovement(nx * HARD_PUSH, m.y, nz * HARD_PUSH);
    living.hurtMarked = true;
}
```

**After (Optimized)**:

```java
if (dist <= hardR) {
    double wall = hardR + 0.05;
    if (Math.abs(dist - wall) > 0.08) {
        living.setPos(pos.x + nx * wall, living.getY(), pos.z + nz * wall);
    }
    Vec3 push = new Vec3(nx * HARD_PUSH, m.y, nz * HARD_PUSH);
    if (m.distanceToSqr(push) > 1.0e-6) {
        living.setDeltaMovement(push);
        living.hurtMarked = true;
    }
}
```

**Expected Improvement**: In a 40-mob farm pressed against the wall, tracker packets drop from ~20/tick toward only the mobs that actually moved inward. Hard-wall feel stays.

### Resolve Scan marks by UUID (references P-007)

**Before (Inefficient)**:

```java
if (have.size() < MARKED.size()) {
    for (Entity entity : level.entitiesForRendering()) {
        if (!(entity instanceof LivingEntity living)) {
            continue;
        }
        // linear scan of every rendered entity
    }
}
```

**After (Optimized)**:

```java
if (have.size() < MARKED.size()) {
    for (UUID id : MARKED) {
        if (have.contains(id)) {
            continue;
        }
        Entity entity = level.getEntities().get(id);
        if (entity instanceof LivingEntity living && living.isAlive() && !living.isRemoved()) {
            next.add(living);
            have.add(id);
        }
    }
}
```

**Expected Improvement**: Miss-path cost becomes O(marks) ≤ 128 instead of O(all rendered entities). Typical busy-hub hitch of 0.3–4 ms goes to tens of microseconds.

### Coalesce charge regen and harvest sync (references P-006)

**Before (Inefficient)**:

```java
if (regenEvery > 0 && now % regenEvery == 0L) {
    forOriginPlayers(server, MotionHarvestService::regenTick); // may sync
}
if (motionEvery > 0 && now % motionEvery == 0L) {
    forOriginPlayers(server, MotionHarvestService::harvest);   // may sync again
}
```

**After (Optimized)**:

```java
boolean regen = regenEvery > 0 && now % regenEvery == 0L;
boolean motion = motionEvery > 0 && now % motionEvery == 0L;
if (!regen && !motion) {
    return;
}
for (ServerPlayer player : server.getPlayerList().getPlayers()) {
    if (!ModeService.hasModePower(player)) {
        continue;
    }
    boolean changed = false;
    if (regen) {
        changed |= regenTick(player);   // mutate only
    }
    if (motion) {
        changed |= harvest(player);     // mutate only
    }
    if (changed) {
        ApoliResources.sync(player);
    }
}
```

**Expected Improvement**: One container packet instead of two on the 140-tick overlap. Same charge math.

---

## Performance Optimization Priorities

### Phase 1: Critical Performance Fixes

- [ ] No critical items. Skip this phase.

### Phase 2: High Impact Optimizations

- [ ] Throttle Faraday leftover `setScore` to HUD interval / expiry (P-001)
- [ ] Skip Faraday hard-wall `setPos`/`hurtMarked` when already on the wall (P-002)
- [ ] Collapse Faraday attack/hurt intercept onto `HELD` + impact-only `nearestCage` (P-003)

### Phase 3: Medium Impact Improvements

- [ ] Circuit Manhattan-shell walk (P-004)
- [ ] Control Field ring-only sweep on expand (P-005)
- [ ] Coalesce MotionHarvest regen+harvest + one Apoli sync (P-006)
- [ ] Scan client UUID lookup (P-007)
- [ ] Frustum/distance cull outline boxes (P-008)
- [ ] LookTargets cheaper LoS order (P-009)
- [ ] Circuit `isWorking` property arms to avoid `hasNeighborSignal` (P-010)
- [ ] Drop Circuit interferences on expiry even if the chunk is unloaded (P-012)

### Phase 4: Performance Monitoring and Fine-tuning

- [ ] Gate Control HANDS tick on `!HANDS.isEmpty()` (P-017)
- [ ] Optional single server-tick dispatcher (P-018)
- [ ] Fold `scan_abort` into Java (P-013)
- [ ] Add debug counters: Faraday score writes/s, aura entity counts, Circuit cells visited, Apoli syncs/s
- [ ] Spark/SparkViewer profile of one Faraday-in-farm tick and one Circuit-in-megabase refresh before/after Phase 2

---

## Estimated Performance Improvement Impact

### Performance Gains by Priority

| Priority Level | Expected Improvement | Implementation Complexity |
|----------------|---------------------|--------------------------|
| Critical Fixes | None — no critical items | n/a |
| High Impact | 30–70% less Faraday-active MSPT/packets in farms and gunfights; scoreboard save churn drops ~20× during cage+CD | Low–medium (local Faraday changes, no protocol change) |
| Medium Impact | 20–50% less Circuit/Scan/Control hitch in dense areas; 1 fewer Apoli packet per 140 ticks per charging player | Medium (walk order, UUID lookup, sweep shape) |
| Low Impact | Microseconds on idle SMP; cleaner tick graph | Low |

### Resource Utilization Improvements

- **Database Load**: Not applicable (no SQL). Scoreboard `setDirty` rate during Faraday CD should drop from 20/s to ~2/s or less (P-001).
- **Memory Usage**: Small young-gen drop from Circuit/LookTargets/radial allocations; no heap leak to fix.
- **CPU Utilization**: Faraday aura + combat fan-in are the measurable dedicated-server wins. Circuit refresh and Scan miss-path are the measurable client/server hitch wins.
- **Concurrency/Throughput**: Single server thread. Lower per-tick Faraday/Circuit work raises headroom for vanilla + TACZ under the same MSPT budget. No thread-pool change recommended.

---

## Performance Monitoring Setup Recommendations

### Observability Recommendations

- **Metrics**: No APM in-tree. Use Spark (`/spark profiler`) on the dedicated server for MSPT and `FaradayService` / `CircuitService` / `MotionHarvestService` sampler ticks. On the client, Spark or the vanilla debug pie (`F3` + Alt) while Circuit/Scan is active.
- **Logging**: Keep config load/reload at INFO. Do not log per-tick Faraday remaining writes. If adding debug, gate it on a COMMON bool and emit a 1 Hz summary (cages, interferences, marked scans, Apoli syncs).
- **Distributed Tracing**: Not applicable (single process, no HTTP). Analog: Spark tick dump covering Faraday combat + aura in the same window.

### Recommended Performance Tracking

- Faraday: active cages, held shots, scoreboard writes/s, aura living-count, combat `shouldBlock` calls/s
- Circuit: cells visited last refresh, marks sent, `hasNeighborSignal` calls last refresh
- Charge: origin players considered, harvest AABB hits, Apoli syncs/s
- Client: `CACHED.size()`, `entitiesForRendering` miss-path walks/s, outline boxes submitted/frame
- Alerting thresholds: Faraday-active MSPT spike > 5 ms over baseline; Circuit refresh > 10 ms; client outline frame > 2 ms

### Performance Testing Recommendations

- Faraday in a 40-mob farm: raise cage, Spark 30 s, compare score writes + tracker packets before/after P-001/P-002
- Faraday + TACZ spray: one caged player, one shooter 20 blocks away; confirm P-003 idle vs active
- Circuit in a redstone factory at `sightRadius` 16 and 64; confirm early-exit at 128
- Scan at default 32 and config 128 in a hub; confirm P-007 miss-path no longer walks items/projectiles
- 20-player fake-player stress: only 2 origin users; confirm MotionHarvest cost stays on those two
- Tools: Spark 1.10+ for Forge 1.20.1; `/sr` commands already expose `scan_pulse` for repeatable pulses

---

## Summary

This performance analysis identified **0 critical**, **3 high**, **9 medium**, and **6 low** performance issues across the application stack. The analysis focused on code patterns, scoreboard/Apoli data access, resource utilization, and tick/render architecture.

**Key Strengths Identified**:

- Empty-map early-outs on Dust / Control / Scan / Circuit ticks; Faraday `idleCombat()` on projectile/attack/hurt
- `PacketLimits` (128 outlines, 256 allies) on every S2C list
- 20-tick `hasModePower` and Faraday `EMERGENCY_CACHE`; HeldShot entity cache; Scan incremental `CACHED` refs
- Circuit section cull + distance-ordered early-exit at 128 marks (no more collect-all-then-sort)
- Faraday expire timestamps (correct for `durationTicks <= 5`) and datapack Faraday tick functions removed
- `scan_stop` and Faraday `deactivate` no longer run leftover `@e` selectors
- Effect duration gates (Faraday slowness/resistance, Dust blindness, Control lock refresh)
- Logout/login cleanup on every session map; COMMON config snapshots in-flight Dust/Control/Faraday duration

**Critical Areas Requiring Immediate Attention**:

- Faraday leftover scores written every tick for cage and 2400-tick CD (P-001)
- Faraday aura dual AABB + 10 Hz `setPos`/`hurtMarked`, mid-ring fully dirtied again (P-002)
- Faraday combat listeners become server-wide while any cage is up (P-003)
- Circuit cell walk, Scan miss-path walk (sticky if a mark unloads), and Circuit entries that never expire in unloaded chunks (P-004, P-007, P-012)
- Full Apoli container sync on every charge regen/harvest (P-006)

**Expected Overall Performance Improvement**: Fixing the three Faraday highs should cut Faraday-active farm/gunfight overhead by roughly a third to two-thirds without changing feel. Medium Circuit/Scan/Control/harvest work then removes the remaining hitch class. Idle MSPT is already in good shape; do not spend time on P-012–P-018 unless a profiler names them.
