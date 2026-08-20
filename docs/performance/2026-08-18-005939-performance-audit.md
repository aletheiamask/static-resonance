## Executive Summary

### Audit Overview

- **Target System**: Static Resonance (`static_resonance` / `com.staticresonance`) 1.0.15
- **Analysis Date**: 2026-08-18
- **Analysis Scope**: Full-stack Minecraft Forge 1.20.1 Origins content mod — server tick loops, Apoli/scoreboard persistence, SimpleChannel packets, client outline/HUD renderers, and granted datapack powers/functions
- **Technology Stack**: Minecraft 1.20.1, Forge 47.2.0, Origins/Apoli (Forge + Fabric-via-Connector), Java 17, Gradle, scoreboard + Apoli resources as persistence, Forge SimpleChannel protocol `"9"`

This is a live-tree static re-audit of the current `src/main` tree. Several 2026-08-17 remediations are still in place (Circuit `MARKS` gate, climb `hurtMarked` only on write, Dust/Faraday effect duration gates, Faraday clock in Java, Origins emergency no-op, scan fleck cap). Circuit sight no longer early-exits at 128 marks (full-volume walk then sort). Scan client now walks `entitiesForRendering()` instead of a 32-block AABB.

### Performance Assessment Summary

| Performance Level | Count | Percentage |
|-------------------|-------|------------|
| Critical Issues   | 0     | 0%         |
| High Impact       | 4     | 22%        |
| Medium Impact     | 8     | 44%        |
| Low Impact        | 6     | 33%        |
| **Total**         | **18** | **100%**   |

### Key Analysis Results

- **Performance Anti-Patterns**: 0 critical patterns; 4 high-impact patterns still fire on skill-active hot paths (Circuit volume walk, Scan client entity walk, Faraday combat fan-in, Faraday aura tracker spam)
- **Code Optimization Opportunities**: 4 high-impact optimizations plus 8 medium-impact session-gated or always-on costs
- **Architecture Assessment**: 8.6/10 performance best practices implemented (empty-map early-outs, packet caps, TTL `hasModePower` cache, logout cleanup, section-culled Circuit, duration-gated effects, Faraday chunk prefilter, INTERFERE_CHUNKS; Circuit cell walk, Scan UUID resolve, and Faraday spatial combat gating still missing)
- **Overall Code Performance Score**: 83/100 (based on static analysis of tick/render paths after the 1.0.15 remediations and the 2026-08-18 tree)

---

## Analysis Methodology

### Performance Analysis Approach

- **Static Code Analysis**: Comprehensive source code review for performance anti-patterns
- **Database Query Analysis**: Review of queries, indexing strategies, and data access patterns
- **Resource Utilization Assessment**: Analysis of memory, CPU, and I/O usage patterns
- **Architecture Performance Review**: Examination of caching, scaling, and optimization strategies

### Analysis Coverage

- **Files Analyzed**: 79 source files across 2 trees (41 Java under `src/main/java`, 34 datapack `.json`/`.mcfunction` under `src/main/resources/data`, plus 2 lang JSON and 2 meta files)
- **Database Queries Reviewed**: 24 data-access operations (Apoli `hasPower`/`getValue`/`setValue`/`sync`, scoreboard `sr_faraday`/`sr_faraday_cd`/`sr_mc`, player persistent-NBT allies, Circuit `getBlockState` walks, 2 granted `origins:action_over_time` powers). There is no SQL/ORM layer.
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

The 1.0.14 critical class (unbounded S2C `ArrayList` decode, per-tick NBT `data get entity` harvest, naive Circuit 33³ `level.getBlockState`) is no longer present. `PacketLimits.readCount` throws on oversized counts, motion harvest is Java, Circuit sight is section-culled with a 128-mark cap, Faraday slowness/resistance and Dust blindness are duration-gated, and the Faraday duration clock lives in `FaradayService.tickCageClock` (`systems/faraday_tick.json` is gone from the grant list and from `powers/systems/`). Remaining issues degrade MSPT/FPS under skill-active load; they do not cause outages or data loss under normal play.

### High Performance Impact Findings

#### P-001: Circuit sight walks every cell of every circuit-tagged section, then sorts

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:381`
**Performance Impact**: 7.6 (High)
**Pattern Detected**: Section-culled 16-block sphere walk with no early-exit on marks collected; full collect then distance sort
**Code Context**:

```java
private static void refreshSight(ServerPlayer player, Sight sight, boolean forceSend) {
    // ... clip 16-block cube to chunk sections, skip hasOnlyAir / !maybeHas(CIRCUIT_TAG)
    for (Map.Entry<Long, LevelChunkSection> taggedSection : tagged.entrySet()) {
        // ...
        for (int x = x0; x <= x1; x++) {
            int dx = x - ox;
            int lx = x & 15;
            for (int y = y0; y <= y1; y++) {
                int dy = y - oy;
                int ly = y & 15;
                for (int z = z0; z <= z1; z++) {
                    int dz = z - oz;
                    if (dx * dx + dy * dy + dz * dz > rSq) {
                        continue;
                    }
                    BlockState state = section.getBlockState(lx, ly, z & 15);
                    if (!state.is(CIRCUIT_TAG)) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    Interference inf = probeInterfere
                            ? getInterference(dim, BlockPos.asLong(x, y, z)) : null;
                    found.add(new CircuitOutlinePacket.Entry(pos, isWorking(level, pos, state, inf)));
                }
            }
        }
    }
    if (found.size() > MAX_MARKS) {
        found.sort(Comparator.comparingDouble(e -> origin.distSqr(e.pos())));
        found.subList(MAX_MARKS, found.size()).clear();
    }
}
```

**Impact**: Circuit mode refreshes when the player moves 4 Manhattan blocks or a nearby block changes (`SCAN_INTERVAL = 20`, `MOVE_SNAP = 4`, `RADIUS = 16`). Air / non-circuit sections skip via `hasOnlyAir` / `maybeHas`. Unlike the prior tree, there is no `break` at 128 marks: a factory whose palettes contain `#static_resonance:circuit` (55 block ids including hoppers, iron doors, every wooden button/plate, rails) still walks every cell of every tagged section (~3–5 chunks × several Y sections, up to ~35k `getBlockState` plus a `new BlockPos` + `Entry` per hit). Only after the walk does it sort and keep 128. Switching into Circuit calls `startSight` immediately, so this is the mode-select tax as well as the 1 Hz move refresh.
**Performance Cost**: ~5–40k section `getBlockState` calls per refresh in a redstone megabase; 0.5–8 ms typical, 12–30 ms possible when thousands of wires allocate then get thrown away. Wilderness Circuit is cheap.
**Recommendation**: Walk Manhattan shells (or origin-relative section order) and stop at `MAX_MARKS` (128). Keep the closest-128 feel by expanding from the player, not by collecting everything then sorting. Keep `RADIUS = 16` and client `pick(16)`.
**Fix Priority**: Within 1 week

#### P-002: Scan client rebuilds marks by walking every rendered entity

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:181`
**Performance Impact**: 7.2 (High)
**Pattern Detected**: Periodic full client entity iteration instead of UUID map lookup
**Code Context**:

```java
if ((clientTick & 1) == 0 || CACHED.isEmpty()) {
    rebuildCache(level);
}
// ...
private static void rebuildCache(ClientLevel level) {
    CACHED.clear();
    if (MARKED.isEmpty()) {
        return;
    }
    for (Entity entity : level.entitiesForRendering()) {
        if (!(entity instanceof LivingEntity living) || !MARKED.contains(living.getUUID())) {
            continue;
        }
        if (!living.isAlive()) {
            MARKED.remove(living.getUUID());
            continue;
        }
        CACHED.add(living);
    }
}
```

**Impact**: After a scan pulse the client walks **every entity the renderer is tracking** (items, XP orbs, armor stands, projectiles, plus livings) every other tick (10 Hz) to resolve at most 128 UUIDs. This is broader than a 32-block living AABB: hubs, item-filled farms, and TACZ bullet spam inflate `entitiesForRendering()` into the thousands while `MARKED` stays ≤128. Render then draws one see-through `renderLineBox` per cached body every frame. Flecks are now capped at 24 nearest (`FLECK_CAP`), so the remaining cliff is the rebuild + boxes, not particles.
**Performance Cost**: 0.3–4 ms/tick client-side with 500–2000 rendered entities; plus 128 line boxes/frame at the cap.
**Recommendation**: Resolve UUIDs via `ClientLevel.getEntity(uuid)` for the ≤128 marked ids. Rebuild every 2–4 ticks is fine once the query is O(marks). Keep prune-only-observed-dead and do not evict out-of-range marks.
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
public static void onAttack(LivingAttackEvent event) { /* same idleCombat + shouldBlock */ }
public static void onHurt(LivingHurtEvent event) { /* same */ }
// shouldBlock → HELD.containsKey or nearestCage
// nearestCage iterates ACTIVE_CAGES with a ±2 section prefilter
```

`idleCombat()` is `ACTIVE_CAGES.isEmpty() && HELD.isEmpty()`. The idle gate is correct. Once one player raises a cage, every projectile impact and every `LivingAttackEvent` / `LivingHurtEvent` on the server enters `shouldBlock`. A single TACZ/vanilla hit can fire all three listeners. Each call walks `ACTIVE_CAGES` (`getPlayer` + `isCageActive` scoreboard read + section prefilter + `distanceToSqr`). TACZ / high-arrow PvP makes this a per-bullet tax for the full cage duration (`sr_faraday` starts at 600 ticks).
**Impact**: One Overcharge player paying 50 charge turns on a server-wide intercept filter. Correctness is fine; cost scales with world combat volume, not with nearby threats. `GUNSHOT_TYPES` caching and the ±2 section prefilter are real improvements.
**Performance Cost**: A few microseconds per event × hundreds of bullets/attacks per second ≈ 0.4–3 ms extra server time during gunfights with a cage up. In-cage gunfights pay 2–3× `shouldBlock` per bullet.
**Recommendation**: Return the cage player from one `nearestCage` walk. Handle intercept on `ProjectileImpactEvent` only; let `onAttack` / `onHurt` check `HELD.containsKey` only. Keep `idleCombat()` for the zero-cage case.
**Fix Priority**: Within 1 week

#### P-004: Faraday aura scans two AABBs and dirties trackers every 2 ticks

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:195`
**Performance Impact**: 7.0 (High)
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
    // hard ring (≤5): setPos + setDeltaMovement + hurtMarked
    // mid ring (≤7): refreshRepelSlow; hurtMarked only if walking inward
}
```

**Impact**: Slowness is duration-gated (`REPEL_SLOW_TICKS = 50`, refresh only when remaining `< 20`) and mid-ring only dirties trackers when the mob walks inward. Remaining cost is two queries every 2 ticks (catch 4.8, repel 10) plus vanilla tracker packets: the hard ring `setPos`s every living body inside 5 blocks at 10 Hz whenever they are not already on the wall. A 40-mob farm next to an active cage is ~20 teleport+velocity packets per tick (400/s) plus sparks every 5 ticks.
**Performance Cost**: 0.2–2.5 ms CPU plus 50–400 extra packets/s per active cage in a dense mob area. Single-player wilderness: negligible.
**Recommendation**: Keep the 10 / 7 / 5 radius feel. Skip `setPos` when the entity is already outside the hard ring (or only correct when `|dist - HARD_RADIUS|` exceeds a small epsilon). Skip `hurtMarked` when velocity did not change. Optionally keep the two typed AABBs (already better than one untyped 20×4×20 sweep).
**Fix Priority**: Within 1 week

### Medium Performance Impact Findings

#### P-005: Control Field sweeps a growing 30-block cylinder every 4 ticks

**Location**: `src/main/java/com/staticresonance/mode/ControlService.java:456`
**Performance Impact**: 5.3 (Medium)
**Pattern Detected**: Session-gated but wide living-entity scan + effect writes
**Code Context**:

```java
if (now % SWEEP_INTERVAL == 0) {
    if (ModeService.getMode(player) != ResonanceMode.CONTROL) {
        stopField(player, StopReason.MODE_CHANGE);
        return;
    }
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
            applyLock(player, living, level, true, true);
        } else if (needsLockRefresh(living)) {
            refreshLockEffects(player, living);
        }
    }
}
```

**Impact**: One field session (40 charge, 60 s, stand still) is gated well (`SESSIONS.isEmpty()` early-out). At full radius the AABB is 60×5×60. A village / raid / farm inside that cylinder gets a living scan 5 times per second and three effects plus first-lock VFX per newly locked body. Refresh is already duration-gated (`REFRESH_WHEN_REMAINING = 20`).
**Performance Cost**: 0.2–1.5 ms per sweep at r=30 in a populated area. Zero when no session.
**Recommendation**: Keep the feel. Optionally scan only the newly expanded ring when `radius` grows, and reuse the existing lock-refresh gate (already good).
**Fix Priority**: Within 1 month

#### P-006: Circuit neighbor-notify still runs on every redstone update in a live-interference chunk

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:243`
**Performance Impact**: 4.4 (Medium)
**Pattern Detected**: Global event listener spatially filtered to chunks, not to interfered positions
**Code Context**:

```java
public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
    if (INTERFERENCES.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) {
        return;
    }
    BlockPos notifyPos = event.getPos();
    Set<Long> chunks = INTERFERE_CHUNKS.get(level.dimension());
    if (chunks == null || !chunks.contains(ChunkPos.asLong(notifyPos.getX() >> 4, notifyPos.getZ() >> 4))) {
        return;
    }
    Interference inf = getInterference(level.dimension(), notifyPos);
    if (inf == null) {
        return;
    }
    if (!inf.forceOn) {
        event.setCanceled(true);
        return;
    }
    applyForce(level, event.getPos(), inf.saved, true, false);
}
```

**Impact**: `INTERFERENCES.isEmpty()` plus `INTERFERE_CHUNKS` is a real improvement over a world-wide map get. While even one wire is forced for 160 ticks, every neighbor notify **in that chunk** (redstone clocks, pistons, observers) still hits the chunk set + interference map. `applyForce(..., firstPulse=false)` can `setBlock` / `updateNeighborsAt` and generate more notifies; `alreadyForced` breaks the infinite loop but not the clock-tower tax. A 20 Hz clock in the same chunk as an unrelated Circuit toggle pays this for 8 seconds.
**Performance Cost**: One CHM get per notify in live chunks; hundreds–thousands/tick on a large computer ≈ 0.05–0.5 ms extra while any interference exists in a busy chunk.
**Recommendation**: Skip unless `notifyPos.asLong()` is in the live interference map (already the second lookup — invert the order: packed-pos first, drop the chunk set, or keep a `Set<Long>` of packed positions and return before `getInterference`). Leave the 160-tick duration.
**Fix Priority**: Within 1 month

#### P-007: Scan and Circuit still submit up to 128 see-through line boxes every frame

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:134`
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

`CircuitOutlineClient.onRender` (`CircuitOutlineClient.java:84`) does the same for up to 128 precomputed AABBs. `SeeThroughRender` uses a 256 KiB line buffer and `NO_DEPTH_TEST`.
**Impact**: One box per mark (the second Scan halo is gone). At the 128 cap this is still 128 `renderLineBox` submissions (24 edges each) through a see-through type with no frustum test. Combined with P-002 this is the Scan FPS cliff. Circuit is cheaper because AABBs are precomputed and only one outline type is typically live (mode change stops scan).
**Performance Cost**: 0.2–1.2 ms GPU/CPU per frame at the cap; less with a handful of marks.
**Recommendation**: Optionally drop marks that fail a frustum / distance test before `renderLineBox`. Keep see-through; that is the feature.
**Fix Priority**: Within 1 month

#### P-008: Motion harvest walks every online player every second and fully syncs Apoli

**Location**: `src/main/java/com/staticresonance/mode/MotionHarvestService.java:52`
**Performance Impact**: 5.1 (Medium)
**Pattern Detected**: Unfiltered player-list scan + full power-container sync on charge gain
**Code Context**:

```java
if (now % BUBBLE_INTERVAL == 0L) {
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
        spawnWetBubbles(player);
    }
}
if (now % INTERVAL != 0L) {
    return;
}
for (ServerPlayer player : server.getPlayerList().getPlayers()) {
    harvest(player);
}
// harvest:
if (!ModeService.hasModePower(player)) {
    return;
}
// ... getEntities 10×10×10 for #motion_sources ...
if (next != charge && ApoliResources.setValue(player, ModeService.CHARGE_POWER, next)) {
    ApoliResources.sync(player);
}
```

**Impact**: Every 20 ticks the server iterates **all** online players (including those without the origin). `hasModePower` is TTL-cached (20 ticks), so the miss path is cheap. Origin players below 100 charge then run a 10-block AABB of `#static_resonance:motion_sources` (broad tag: affectable + villagers + most passives). A charge increment calls `ApoliAPI.synchronizePowerContainer` — a full container packet, not a single resource. Wet bubbles add a second all-player walk every 35 ticks. On a 40-player SMP with 3 origin users this is 40 cache hits + 3 entity scans + up to 3 full Apoli syncs per second.
**Performance Cost**: ~0.1–0.8 ms/s on a busy SMP; Apoli sync is the dominant packet. Single-player: noise.
**Recommendation**: Keep a `Set<UUID>` of origin players (invalidate on login/logout/`hasModePower` cache miss). Sync charge only, or piggy-back on the existing 35-tick `charge_tick` power instead of a full container write every harvest.
**Fix Priority**: Within 1 month

#### P-009: Scan stop still runs a 128-block `@e` selector for leftover tags

**Location**: `src/main/resources/data/static_resonance/functions/sense/scan_stop.mcfunction:4`
**Performance Impact**: 4.8 (Medium)
**Pattern Detected**: Wide `@e` command selector on a path Java already owns
**Code Context**:

```mcfunction
tag @e[tag=sr_scanned,distance=..128] remove sr_scanned
tag @e[tag=sr_scan_pinged,distance=..128] remove sr_scan_pinged

scoreboard players set @s sr_scan 0
sr_internal scan_clear
```

**Impact**: Java `ScanOutlineService` marks UUIDs and never writes `sr_scanned` / `sr_scan_pinged`. Stop still compiles two `@e[distance=..128]` queries (256-block AABB of every living/item/projectile, then tag filter) via Brigadier, then `sr_internal scan_clear`. This runs on toggle-off, mode leave (`setMode` → `stop` → `scan_stop`), and the `scan_abort` safety net. In a mob farm or hub the selector is the expensive part; the tags are empty.
**Performance Cost**: 0.2–3 ms hitch per stop in a dense entity area; zero when few entities are loaded.
**Recommendation**: Delete the two `tag @e` lines. Keep `sr_internal scan_clear` and the one-shot particles/sound.
**Fix Priority**: Within 1 month

#### P-010: Look-cone fallback runs up to 32 line-of-sight clips per miss

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
    // ...
    if (dot < minDot || !player.hasLineOfSight(living)) {
        continue;
    }
}
```

**Impact**: Control lock (`TARGET_RANGE = 64`), Dust burst/zone, and radial ally snapshot all share this. A precise `ProjectileUtil` ray miss then inflates a 32-block box, copies the living list, optionally sorts, and clips up to 32 `hasLineOfSight` rays (each a block clip from eye to target eye). Cast-gated (not per tick). A 40° cone in a raid / farm is the expensive miss path; a clean ray hit returns immediately.
**Performance Cost**: 0.2–1.5 ms per miss in a crowded 32-block box. Hit path is cheap.
**Recommendation**: Keep `CONE_CANDIDATE_CAP = 32`. Skip `hasLineOfSight` for the closest 1–3 candidates first, or clip only after a cheaper chunk/section occupancy check. Do not drop the cone; it is the gameplay feel.
**Fix Priority**: Within 1 month

#### P-011: Faraday `LivingDamageEvent` has no idle gate and always consults Apoli

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:241`
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

**Impact**: Unlike `onProjectileImpact` / `onAttack` / `onHurt`, `onDamage` does not call `idleCombat()`. Every server-side living-damage event enters `capIncoming`. The `instanceof ServerPlayer` filter is cheap for mob-on-mob. Every player hit (any origin) then pays `ModeService.hasModePower` (cached) plus `ApoliResources.getValue(EMERGENCY_POWER)` for origin players. This is required for the 2-health floor even when no cage is up. High-DPS PvP / mob grinders on origin players make this a per-hit Apoli read on the hottest vanilla event.
**Performance Cost**: Sub-0.1 ms per player hit typical; measurable in TACZ sprays (tens of hits/s × origin players).
**Recommendation**: Fast-path non-players (already done). For players, skip Apoli when `!hasModePower` cache is cold-false. Consider reading a Java `Set<UUID>` of origin players instead of Apoli on every hit. Keep the emergency math.
**Fix Priority**: Within 1 month

#### P-012: Faraday held-shot clock does a UUID entity lookup every tick

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:344`
**Performance Impact**: 4.1 (Medium)
**Pattern Detected**: Per-tick `level.getEntity` + `nearestCage` for each held projectile
**Code Context**:

```java
private static void tickHeld(ServerLevel anyLevel) {
    if (HELD.isEmpty()) {
        return;
    }
    Iterator<Map.Entry<UUID, HeldShot>> it = HELD.entrySet().iterator();
    while (it.hasNext()) {
        HeldShot held = it.next().getValue();
        Entity entity = findEntity(anyLevel.getServer(), held); // level.getEntity(uuid)
        // ...
        ServerPlayer cage = nearestCage(entity);
        // freeze + optional spark every 5 ticks
    }
}
```

**Impact**: `tickHeld` runs every server tick from `onServerTick` (before the cage loop). TACZ kinetic bullets are `discard()`ed, so the map is mostly vanilla arrows/tridents held 35 ticks inside 4.8 blocks. Each entry pays `getEntity` + a full `nearestCage` walk. A volley of 20 arrows caught at once is 20 lookups × 35 ticks. Empty `HELD` returns immediately.
**Performance Cost**: Tens of microseconds with 0–2 held shots; 0.1–0.4 ms with a large vanilla volley.
**Recommendation**: Store the `Entity` (or entity id int) on `HeldShot` and only re-resolve if `!entity.isAlive()` / removed. Call `nearestCage` every 2–5 ticks unless the projectile drifted. Keep the 35-tick hold.
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

`ModeService.setMode` already calls `ScanOutlineService.stop` when leaving Sense. The power is a safety net that still wakes every origin player every 5 ticks for two resource reads. When it fires it also runs P-009's `@e` stop function.
**Impact**: Cheap Apoli reads; `execute_command scan_stop` only if the Java path was skipped.
**Performance Cost**: Microseconds per origin player per 5 ticks.
**Recommendation**: Keep as a rare safety net, or fold the check into `ScanOutlineService.onServerTick` (already walks `MARKED`) and drop the power.
**Fix Priority**: Within 2 months

#### P-014: Mode / Faraday / Scan still dispatch Brigadier for one-shot FX

**Location**: `src/main/java/com/staticresonance/mode/ModeService.java:138`
**Performance Impact**: 2.4 (Low)
**Pattern Detected**: `performPrefixedCommand` parse+execute on infrequent paths
**Code Context**:

```java
server.getCommands().performPrefixedCommand(source, "function " + mode.feedbackFunction());
// FaradayService.runFunction → activate / deactivate / emergency_fx
// ScanOutlineService.runFunction → scan_start / scan_stop
```

**Impact**: Each call tokenizes and walks the command tree on the server thread. Mode change, cage raise/lower, and scan toggle are player-initiated (plus Faraday emergency). `scan_start` then immediately `sr_internal scan_clear` before Java `pulse`, so one toggle pays function parse + command parse + pulse.
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

**Impact**: `CAST_GATE`, `CHANGE_GATE`, `MODE_POWER_CACHE`, Faraday `GUNSHOT_TYPES` (by `EntityType`), `ACTIVE_CAGES`, `SESSIONS`, `SIGHTS`, `MARKED`, `ZONES`, `HANDS`, `HELD` all have logout cleanup except `GUNSHOT_TYPES` (bounded by registry size) and TickGate/`MODE_POWER_CACHE` if a player is removed without `PlayerLoggedOutEvent` (crash, kick edge). Entries are tiny (`UUID` + `long`).
**Performance Cost**: Bytes per ghost UUID. Not a gameplay hitch.
**Recommendation**: Optionally sweep TickGate/`MODE_POWER_CACHE` when `size()` exceeds online player count × 2. Keep the 20-tick TTL.
**Fix Priority**: Within 2 months

#### P-016: Radial HUD reallocates translatable components and layered fills every frame

**Location**: `src/main/java/com/staticresonance/client/ui/RadialModeMenuRenderer.java:103`
**Performance Impact**: 2.8 (Low)
**Pattern Detected**: Per-frame allocation on an open-only HUD
**Code Context**:

```java
graphics.fill(0, 0, sw, sh, withAlpha(COL_SCRIM, a0));
graphics.fill(sw / 8, sh / 8, sw - sw / 8, sh - sh / 8, withAlpha(0x3A3A3A, a1));
graphics.fill(sw / 5, sh / 5, sw - sw / 5, sh - sh / 5, withAlpha(0x484848, a2));
// drawScanSub also:
font.width(Component.translatable("ui.static_resonance.scan_passive")) // twice per frame
```

Wedge geometry is batched into one `Tesselator` triangle pass (`ensureSectorBatch` / `flushSectors`) — that part is good. Labels call `Component.translatable` every frame; scan sub computes `font.width` on a freshly allocated component twice. Menu is hold-R only.
**Impact**: Open-only. No cost in combat. Three full-screen fills plus ~5×12×2 triangles per wedge is modest at GUI scale.
**Performance Cost**: A few hundred microseconds and some young-gen garbage while the wheel is held.
**Recommendation**: Cache `Component` instances for static labels. Compute scan-passive width once. Keep the batched sector upload.
**Fix Priority**: Within 2 months

#### P-017: Faraday deactivate still runs a projectile `@e` selector after Java already released

**Location**: `src/main/resources/data/static_resonance/functions/faraday/deactivate.mcfunction:11`
**Performance Impact**: 2.5 (Low)
**Pattern Detected**: Duplicate entity query on a one-shot path
**Code Context**:

```mcfunction
execute as @e[type=#static_resonance:projectiles,tag=sr_faraday_held,distance=..12] run function static_resonance:faraday/release_projectile
```

`FaradayService.tryDeactivate` already calls `releaseNear` (`getEntities` in a 10-block box keyed by `HELD`) before `runFunction(deactivate)`. Java held shots never receive `sr_faraday_held`, so the selector is a leftover empty scan plus a function parse.
**Impact**: Cage lower only. Empty tag filter is cheap unless many `#static_resonance:projectiles` are in 12 blocks.
**Performance Cost**: Sub-0.2 ms typical; more if a TACZ volley is in the box (type-tag still matches).
**Recommendation**: Drop the `@e` line; keep particles/sound/scoreboard in the function or move them to Java next to `releaseNear`.
**Fix Priority**: Within 2 months

#### P-018: Circuit sight tick looks up every sighted player before the 20-tick interval

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:188`
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
    if (now % SCAN_INTERVAL != 0) {
        continue;
    }
    // refresh if dirty / moved
}
```

**Impact**: Offline cleanup every tick is intentional. The `getPlayer` hash lookup runs 20× more often than the scan. One Circuit player: 20 lookups/s. Fine. Combined with Faraday/Control/Dust/Scan/MotionHarvest each owning a `ServerTickEvent` subscriber, Forge walks six empty-gated listeners every tick.
**Performance Cost**: Microseconds unless many Circuit players are online.
**Recommendation**: Check `now % SCAN_INTERVAL` once before the loop; keep a 20-tick orphan sweep. Optionally collapse the six server-tick subscribers into one dispatcher.
**Fix Priority**: Within 2 months

---

## Code Pattern Performance Analysis

### Performance Anti-Pattern Detection

- **N+1 Query Patterns**: 5 instances detected across 4 files (`LookTargets` LoS clips, Faraday `nearestCage` per combat event, Circuit cell walk, Scan `entitiesForRendering`, MotionHarvest per-player AABB)
- **Blocking Operations**: 3 Brigadier `performPrefixedCommand` sites (`ModeService`, `FaradayService`, `ScanOutlineService`) on the server thread; no thread-pool / off-thread world access
- **Memory Pressure Points**: 4 locations with excessive allocation (`CircuitService.refreshSight` `BlockPos`+`Entry` per hit, `LookTargets` candidate `ArrayList`, radial per-frame `Component`, Scan `rebuildCache` `CACHED.clear`)
- **Inefficient Data Access**: 6 queries with suboptimal patterns (full Apoli container sync, scoreboard `isCageActive` per cage per event, `@e[distance=..128]`, `entitiesForRendering`, unfiltered player list, `level.getEntity` every held tick)
- **Concurrency Issues**: 0 correctness races on the hot path (all world mutation stays on the server thread; `ConcurrentHashMap` is used as identity maps, not as cross-thread world access)

### Database Access Pattern Analysis

- **ORM/Query Usage**: 24 scoreboard / Apoli / NBT / block-state operations analyzed; no SQL
- **Missing Async Patterns**: 0 database operations identified for async conversion (Minecraft world APIs are server-thread-only; off-thread `getBlockState` would be unsafe)
- **Query Complexity**: 4 complex queries requiring optimization review (Circuit section walk, Scan client entity walk, Faraday dual AABB aura, Control r=30 living sweep)
- **Connection Management**: Not applicable — single dedicated server thread; no connection pool. Analog: six independent `TickEvent.ServerTickEvent` subscribers instead of one dispatcher

### Resource Management Pattern Analysis

- **Memory Allocation Patterns**: Circuit collect-all-then-sort is the largest young-gen spike (thousands of `BlockPos`/`Entry` on a dense refresh). Scan/Circuit outline packets allocate a new `ArrayList` up to 128 per send. Radial allocates components only while open.
- **Garbage Collection Pressure**: 4 locations with excessive object creation (Circuit scan, LookTargets copy+sort, Scan fleck `shown` list every 4 ticks, radial labels)
- **Thread/Event Loop Usage**: 0 blocking I/O on the server thread; 3 Brigadier parses and 4 high-frequency entity/block walks contend for the same 50 ms tick budget
- **Caching Opportunities**: 4 frequently computed operations with incomplete caching (`hasModePower` is cached; `getMode` / emergency resource / `isCageActive` scoreboard / origin-player set are not)
- **Resource Disposal**: Logout clears TickGate, caches, SIGHTS, SESSIONS, HANDS, MARKED, ZONES, ACTIVE_CAGES, COOLDOWNS, client MARKS. Ghost TickGate/`MODE_POWER_CACHE` entries possible without logout (P-015). No native/buffer leak; `SeeThroughRender` buffer is a static `RenderType`.

---

## Architecture Performance Assessment

### Data Access Layer Analysis

- Persistence is three small stores: Apoli resource powers (`charge`, `mode`, styles, scan flags), dummy scoreboard objectives (`sr_faraday`, `sr_faraday_cd`, `sr_mc`, scratch), and player persistent-NBT ally UUIDs. There is no SQL, no chunk-data attachment, and no world-saved Circuit interference (toggles die on logout/expiry).
- Apoli reads are the hottest “query”. `ModeService.hasModePower` has a 20-tick TTL cache keyed by UUID+side and is invalidated on login/logout/dim/reload. `getMode` / `getResourceValue` still hit the container every call (Climb is fine; Faraday `onDamage` and harvest are not).
- `ApoliResources.sync` always serializes the **entire** power container. Harvest (P-008) and every spend path use it. Fine for rare casts; wasteful at 1 Hz harvest.

### Application Layer Analysis

- Authority is correctly split: JSON gates + one-shot FX, Java for spend/apply (Control, Faraday, Dust, Scan, Circuit, harvest). Dual-spend is avoided.
- Hot loops are session-gated (`SESSIONS`, `ACTIVE_CAGES`, `SIGHTS`, `ZONES`, `MARKED`, `HELD`) with empty-map early-outs. Motion harvest and Climb are the remaining always-on server walks (Climb is well gated after `hasModePower` + `horizontalCollision`).
- Packet surface is small and bounded: 10 types, `PacketLimits.MAX_OUTLINE_MARKS = 128`, `MAX_ALLIES = 256`, decode throws on oversized counts. Cast/mode are debounced (`TickGate` 6 / 4 ticks).
- Six `ServerTickEvent` subscribers plus several `PlayerTickEvent` listeners add Forge dispatch overhead that is tiny compared to the walks they contain.

### Frontend Analysis (if applicable)

- Not a web app. Client analog of Core Web Vitals is frame time while Circuit/Scan outlines or the radial wheel are live.
- No JS bundle. Client cost is `SeeThroughRender` line boxes (P-007), Scan `entitiesForRendering` (P-002), Circuit `pick` (now gated on `MARKS.isEmpty()` — fixed), and hold-R radial fills (P-016).
- Scan flecks are capped at 24 nearest (`FLECK_CAP`) — the 128×2 particle flood from the prior audit is gone.
- Radial wedge geometry is a single batched triangle upload (`ensureSectorBatch`); item icons and strings are per-wedge.

### Infrastructure Analysis

- Dedicated-thread Minecraft server: no connection pool, no async world access, no horizontal scale. “Load” is MSPT and S2C tracker packets, not request throughput.
- Forge SimpleChannel protocol `"9"` with `consumerMainThread` — all handlers already on the correct thread.
- Scoreboard objectives are ensured once on `ServerStartedEvent` / login (`SrScoreboards`). Faraday clock is Java (`tickCageClock` every 5 ticks); the old granted `faraday_tick` power is not in `powers/systems/` and is not listed on the origin.

---

## Performance Bottleneck Analysis

### Top Performance Bottlenecks

| Rank | Component | Issue | Impact Score | Estimated Cost |
|------|-----------|-------|--------------|----------------|
| 1 | `CircuitService.refreshSight` | Full tagged-section cell walk + sort, no mark early-exit | 7.6 | 0.5–30 ms per refresh in a redstone factory |
| 2 | `ScanOutlineClient.rebuildCache` | `entitiesForRendering()` every other tick | 7.2 | 0.3–4 ms/tick client in hubs |
| 3 | `FaradayService` combat listeners | Global `shouldBlock` while any cage/held shot exists | 7.0 | 0.4–3 ms extra during TACZ/arrow spray |
| 4 | `FaradayService.tickAura` | Dual AABB + hard-ring `setPos`/`hurtMarked` at 10 Hz | 7.0 | 0.2–2.5 ms + 50–400 packets/s in mob farms |
| 5 | `MotionHarvestService` | All-player walk + full Apoli sync at 1 Hz | 5.1 | 0.1–0.8 ms/s on busy SMPs |
| 6 | `ControlService.tickField` | r≤30 living sweep every 4 ticks | 5.3 | 0.2–1.5 ms per sweep when a field is up |
| 7 | `scan_stop.mcfunction` | `@e[distance=..128]` leftover tags | 4.8 | 0.2–3 ms hitch per scan stop |
| 8 | Client outline renderers | 128 see-through `renderLineBox` / frame | 4.5 | 0.2–1.2 ms/frame at the cap |

---

## Technical Recommendations

### Immediate Performance Fixes

1. Walk Circuit sections outward from the player and stop at 128 marks (P-001) — restores the early-exit and keeps nearest-first correctness without collecting thousands of `BlockPos`.
2. Resolve Scan marks with `ClientLevel.getEntity(uuid)` (P-002) — turns a 10 Hz full-render-list walk into ≤128 map lookups.
3. Collapse Faraday combat to one `nearestCage` and stop running `onAttack`/`onHurt` through the full intercept path (P-003).
4. Stop hard-ring `setPos`/`hurtMarked` when the entity did not move (P-004).

### Performance Enhancements

1. Maintain an origin-player UUID set and stop iterating the whole player list in `MotionHarvestService` (P-008); avoid full Apoli container sync on +1..4 charge.
2. Delete leftover `@e` selectors in `scan_stop` and Faraday `deactivate` (P-009, P-017).
3. Scan only the newly expanded Control Field ring (P-005).
4. Frustum-cull outline boxes before `renderLineBox` (P-007).

### Architecture Improvements

1. Cache `getMode` / emergency-enabled next to `hasModePower`, or keep a Java `EnumMap`/bit on the origin player set so Faraday `onDamage` does not touch Apoli on every hit (P-011).
2. Collapse the six `ServerTickEvent` subscribers into one `TickDispatcher` so empty-map checks and `gameTime % n` live in one place (P-018).
3. Treat Apoli `sync()` as expensive I/O: batch harvest + `charge_tick` onto one write, or add a single-resource sync if the backend allows.

---

## Code Optimization Examples

### Circuit nearest-first early-exit (references P-001)

**Before (Inefficient)**:

```java
for (Map.Entry<Long, LevelChunkSection> taggedSection : tagged.entrySet()) {
    // walk every cell in every tagged section
    found.add(new CircuitOutlinePacket.Entry(pos, isWorking(level, pos, state, inf)));
}
if (found.size() > MAX_MARKS) {
    found.sort(Comparator.comparingDouble(e -> origin.distSqr(e.pos())));
    found.subList(MAX_MARKS, found.size()).clear();
}
```

**After (Optimized)**:

```java
// Sort tagged sections by distance of section center to origin, then:
scan:
for (Map.Entry<Long, LevelChunkSection> taggedSection : tagged.entrySet()) {
    // walk cells; optionally visit in origin-relative order
    found.add(new CircuitOutlinePacket.Entry(pos, isWorking(level, pos, state, inf)));
    if (found.size() >= MAX_MARKS) {
        break scan;
    }
}
```

**Expected Improvement**: Dense factories drop from tens of thousands of cell visits + allocations to ~128 hits plus the sections needed to find them (~70–95% less work when wires are common). Wilderness unchanged.

### Scan client UUID resolve (references P-002)

**Before (Inefficient)**:

```java
for (Entity entity : level.entitiesForRendering()) {
    if (!(entity instanceof LivingEntity living) || !MARKED.contains(living.getUUID())) {
        continue;
    }
    CACHED.add(living);
}
```

**After (Optimized)**:

```java
for (UUID id : MARKED) {
    Entity entity = level.getEntity(id);
    if (entity instanceof LivingEntity living && living.isAlive()) {
        CACHED.add(living);
    }
}
```

**Expected Improvement**: Client rebuild goes from O(rendered entities) to O(marks ≤ 128). Hubs/farms should lose the 0.3–4 ms hitch; outline count unchanged.

### Faraday hard-ring tracker spam (references P-004)

**Before (Inefficient)**:

```java
if (moved) {
    living.setPos(tx, living.getY(), tz);
}
if (velChanged) {
    living.setDeltaMovement(nx * HARD_PUSH, m.y, nz * HARD_PUSH);
}
if (moved || velChanged) {
    living.hurtMarked = true;
}
```

**After (Optimized)**:

```java
double slack = 0.08;
if (dist + slack < HARD_RADIUS && moved) {
    living.setPos(tx, living.getY(), tz);
}
if (velChanged) {
    living.setDeltaMovement(nx * HARD_PUSH, m.y, nz * HARD_PUSH);
    living.hurtMarked = true;
}
```

**Expected Improvement**: Mobs already on the 5-block wall stop generating 10 Hz teleport packets. Farm-adjacent cages should drop most of the 50–400 packets/s tax; the push feel stays.

### Motion harvest origin-player set (references P-008)

**Before (Inefficient)**:

```java
for (ServerPlayer player : server.getPlayerList().getPlayers()) {
    harvest(player); // hasModePower then AABB + Apoli.sync
}
```

**After (Optimized)**:

```java
for (UUID id : ORIGIN_PLAYERS) {
    ServerPlayer player = server.getPlayerList().getPlayer(id);
    if (player != null) {
        harvest(player);
    }
}
// inside harvest, on charge change:
ApoliResources.setValue(player, ModeService.CHARGE_POWER, next);
// skip ApoliResources.sync — charge_tick / next HUD read will pick it up,
// or sync at most every 35 ticks
```

**Expected Improvement**: 40-player SMPs stop paying 40 harvest probes/s. Origin players keep +1..4 charge/s. Full-container packets drop from 1 Hz to the existing 35-tick charge clock.

---

## Performance Optimization Priorities

### Phase 1: Critical Performance Fixes

- [ ] No critical items. Skip to Phase 2.

### Phase 2: High Impact Optimizations

- [ ] P-001: Origin-order Circuit walk with stop at 128 marks
- [ ] P-002: Scan client `getEntity(uuid)` rebuild
- [ ] P-003: Single `nearestCage` + slim `onAttack`/`onHurt`
- [ ] P-004: Epsilon / dirty-check Faraday hard-ring `setPos`/`hurtMarked`

### Phase 3: Medium Impact Improvements

- [ ] P-008: Origin-player set + cheaper charge sync
- [ ] P-009: Remove `@e[distance=..128]` from `scan_stop`
- [ ] P-005: Control Field ring-only sweep
- [ ] P-006: Packed-pos first on Circuit neighbor-notify
- [ ] P-007: Frustum / distance cull outline boxes
- [ ] P-010: Cheap-first LookTargets cone
- [ ] P-011: Origin-set fast path on `LivingDamageEvent`
- [ ] P-012: Cache held `Entity` on `HeldShot`

### Phase 4: Performance Monitoring and Fine-tuning

- [ ] P-013 / P-017 / P-014: Drop leftover datapack polls and Brigadier FX
- [ ] P-015 / P-018: Cache sweep + single server-tick dispatcher
- [ ] P-016: Cache radial `Component`s
- [ ] Add Spark tick profiling on a Circuit factory world, a Scan hub, and a Faraday+TACZ arena
- [ ] Watch MSPT, entity-tracker packets/s, and client frame time with outlines at the 128 cap

---

## Estimated Performance Improvement Impact

### Performance Gains by Priority

| Priority Level | Expected Improvement | Implementation Complexity |
|----------------|---------------------|--------------------------|
| Critical Fixes | None (0 findings) | N/A |
| High Impact | 40–70% less MSPT/FPS cost on Circuit factories, Scan hubs, and Faraday gunfights | Low–Medium (local loop changes, no gameplay redesign) |
| Medium Impact | 15–30% less SMP background cost (harvest, `@e` stops, field/aura extras) | Low–Medium |
| Low Impact | 2–8% less allocation / command-parse noise | Low |

### Resource Utilization Improvements

- **Database Load**: Not applicable as SQL. Apoli/scoreboard writes: ~50–80% fewer full-container syncs if harvest batches onto `charge_tick`; leftover `@e` selectors go to zero.
- **Memory Usage**: Circuit refresh young-gen spike drops sharply with early-exit (no thousands of discarded `BlockPos`/`Entry`). Client Scan rebuild stops allocating per rendered entity.
- **CPU Utilization**: Circuit + Scan + Faraday combat/aura are the MSPT/frame cliffs. Expected 0.5–8 ms saved per Circuit refresh in factories, 0.3–4 ms/tick client after Scan UUID resolve, 0.4–3 ms during caged gunfights after combat slimming.
- **Concurrency/Throughput**: Still single-threaded. Throughput analog is entities/projectiles processed per tick without exceeding 50 ms. Faraday packet/s in farms should fall with P-004.

---

## Performance Monitoring Setup Recommendations

### Observability Recommendations

- **Metrics**: Spark tick profiler (`/spark profiler start`) with samplers on `CircuitService.refreshSight`, `FaradayService.tickAura`, `FaradayService.shouldBlock`, `ScanOutlineClient.rebuildCache`, `MotionHarvestService.harvest`. Track MSPT, TPS, entity count, and `PacketCount` (entity velocity / teleport).
- **Logging**: Keep current SLF4J/Log4j at info for backend detect only. Do not log per-tick. Optional debug counter: Circuit cells visited vs marks sent, Faraday `shouldBlock` calls/s, harvest players scanned vs origin players.
- **Distributed Tracing**: Not applicable (no request graph). Use Spark Viewer world/region heat and entity-tracker graphs instead.

### Recommended Performance Tracking

- Server MSPT p50/p95 with 0 / 1 / 4 origin players, Circuit in a 16-block redstone cube, Faraday cage next to a 40-mob farm, Control Field at r=30
- Client frame time with 0 / 32 / 128 Scan marks in a hub (`entitiesForRendering` > 500)
- S2C entity packets/s while a cage hard-ring touches a farm
- Alert if dedicated-server MSPT > 40 for 10 s while any `SIGHTS` / `ACTIVE_CAGES` / `SESSIONS` entry exists

### Performance Testing Recommendations

- Load-test Circuit: fill a 33³ with redstone wire + repeaters, toggle Circuit, walk 4 blocks, break a dust (dirty refresh)
- Load-test Scan: pulse in a villager trading hall / raid; watch client tick time on `rebuildCache`
- Load-test Faraday: TACZ spray at a caged player plus 30 zombies in the hard ring
- Tools for this stack: Spark (`/spark`), Spark Viewer, Minecraft `debug pie` / `F3+2`, VisualVM or async-profiler attached to `runServer` / `runClient`, Forge `forge.logging.console.level`

---

## Summary

This performance analysis identified **0 critical**, **4 high**, **8 medium**, and **6 low** performance issues across the application stack. The analysis focused on code patterns, scoreboard/Apoli data access, resource utilization, and architectural performance of the Forge 1.20.1 Origins hybrid.

**Key Strengths Identified**:

- Empty-map / empty-set early-outs on Faraday, Control, Dust, Circuit, and Scan server ticks
- Bounded S2C lists (`PacketLimits` 128 / 256) with decode rejection
- `hasModePower` 20-tick cache; logout/login/dim invalidation
- Circuit section culling (`hasOnlyAir` / `maybeHas`) and INTERFERE_CHUNKS
- Duration-gated Dust blindness, Faraday slowness/resistance, Control lock refresh
- Climb `hurtMarked` only when velocity is written; Circuit client `pick` idle-outs when `MARKS` is empty
- Scan flecks capped at 24; Faraday emergency Origins action is a no-op (Java owns the path)
- Faraday clock is Java; `faraday_tick` is no longer shipped under `powers/systems/`

**Critical Areas Requiring Immediate Attention**:

- P-001 Circuit full-volume walk + sort (regression vs mark-cap early-exit)
- P-002 Scan client `entitiesForRendering()` rebuild
- P-003 Faraday global combat fan-in while any cage is up
- P-004 Faraday hard-ring `setPos` / tracker spam at 10 Hz
- P-008 Motion harvest all-player walk + full Apoli sync

**Expected Overall Performance Improvement**: 40–70% less skill-active MSPT/FPS cost after Phase 2, plus 15–30% less SMP background cost after Phase 3, without changing radii, costs, or the 128-mark outline cap.
