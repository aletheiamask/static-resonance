## Executive Summary

### Audit Overview

- **Target System**: Static Resonance (`static_resonance` / `com.staticresonance`) **1.0.20** (`gradle.properties` `mod_version=1.0.20`)
- **Analysis Date**: 2026-08-23
- **Analysis Scope**: Current `src/main` Java + granted datapack powers/functions. Focus: client scan overlay (`ScanOutlineClient` / `ScanBoxRenderer`), server scan pulse (`ScanOutlineService`), Faraday / Control / Dust / Circuit / Climb / motion-harvest tick loops, SimpleChannel packets (`PacketLimits.MAX_OUTLINE_MARKS = 128`), and COMMON config (`scanRadius` max 128, `seeThroughWalls`). Logout cleanup of `MARKED` / `CACHED` verified. No Java source was modified.
- **Technology Stack**: Minecraft 1.20.1, Forge 47.2.0, Origins/Apoli (Forge + Fabric-via-Connector), Java 17, Gradle, ForgeConfigSpec COMMON (`static_resonance-common.toml`), Apoli resource powers + dummy scoreboards as persistence, Forge SimpleChannel protocol `"10"`

This is a live-tree static audit of version 1.0.20. Historical critical defects (unbounded S2C list decode, per-tick NBT harvest, idle Circuit 33³ block walks, Faraday datapack `@e` clocks) are **not present**. Circuit is now F-raycast + expire-only hold. Scan is a one-shot pulse with incremental client UUID resolve. Remaining cost is skill-active: Faraday 10 Hz aura + global combat fan-in, a configurable 128-block scan cube, Control Field vanilla `GLOWING` + `FLASH`, and a per-frame AFTER_LEVEL line overlay.

### Performance Assessment Summary

| Performance Level | Count | Percentage |
|-------------------|-------|------------|
| Critical Issues   | 0     | 0%         |
| High Impact       | 5     | 24%        |
| Medium Impact     | 9     | 43%        |
| Low Impact        | 7     | 33%        |
| **Total**         | **21** | **100%**  |

### Key Analysis Results

- **Performance Anti-Patterns**: 0 critical; 5 high-impact patterns fire only while a skill is active (Faraday aura/combat, scan pulse at config-max radius, Control Field VFX, caster scan overlay). Idle origin players pay a small always-on Climb / Faraday / matrix-copy tax.
- **Code Optimization Opportunities**: 5 high-impact Faraday/scan/control fixes, 9 medium session-gated or always-on costs (shader reflection, full UUID packets, motion harvest, NeighborNotify, Apoli full-container sync).
- **Architecture Assessment**: 8.4/10 of the tick-loop best practices that matter for this mod are in place (empty-map early-outs, `PacketLimits`, TTL `hasModePower`, logout `MARKED`/`CACHED` clear, Circuit idle-no-walk, duration-gated slowness/blindness, Faraday section prefilter + `HeldShot` entity cache, HUD-interval Faraday scores, fleck cap 24). Missing: idle Faraday/server-tick early-out, combat spatial index, scan section budget, Control private outlines instead of vanilla glow, cached Iris query.
- **Overall Code Performance Score**: 82/100 (static analysis of tick, render, packet, and config paths in 1.0.20). Wilderness single-player with default config is comfortable. Dedicated-server mob farms, TACZ projectile spam, `scanRadius = 128`, and Control Field in a crowd are the spike cases.

---

## Analysis Methodology

### Performance Analysis Approach

- **Static Code Analysis**: Full read of `src/main/java/com/staticresonance` (46 production Java files) plus granted datapack powers/functions. Grep for `entitiesForRendering`, `getEntitiesOfClass`, `getEntities(`, `@SubscribeEvent`, `sendParticles`, `hurtMarked`, `GLOWING`/`FLASH`, `PacketLimits`, `seeThroughWalls`, `scanRadius`, and logout/`LoggingOut` handlers.
- **Tick / Render / Packet Review**: Server `TickEvent.ServerTickEvent` and `PlayerTickEvent` loops, client `ClientTickEvent` + `RenderLevelStageEvent` (AFTER_ENTITIES and AFTER_LEVEL), SimpleChannel encode/decode caps, ForgeConfigSpec ranges.
- **Resource Utilization Assessment**: Entity-section walks, GL state + immediate tessellator overlays, particle/tracker packets, Apoli container sync, ConcurrentHashMap session maps.
- **Architecture Performance Review**: Empty-map gates, TTL caches, packet caps, logout cleanup, config-max worst case (scan 128, field 64, motion 32, Faraday repel 32).

### Analysis Coverage

- **Files Analyzed**: 50 Java files (46 `src/main/java`, 4 `src/test/java`) + 33 datapack files under `src/main/resources/data` (12 `.mcfunction`, 14 powers, 1 origin, 4 tags, `minecraft/tags/functions/load.json`, `origins/origin_layers/origin.json`) + 2 lang JSON. Hot path focus: `ScanOutlineClient.java`, `ScanBoxRenderer.java`, `ScanOutlineService.java`, `FaradayService.java`, `ControlService.java`, `DustService.java`, `CircuitService.java`, `MotionHarvestService.java`, `LookTargets.java`, `PacketLimits.java`, `SrConfig.java`.
- **Database Queries Reviewed**: N/A — no SQL/ORM. Persistence reviewed instead: Apoli `hasPower` / `getValue` / `setValue` / `sync`, dummy scoreboards `sr_faraday` / `sr_faraday_cd` / `sr_mc`, player persistent-NBT allies, Circuit `getBlockState` on F only.
- **API Endpoints Analyzed**: 10 SimpleChannel packet types on 1 channel (`ModNetwork`, protocol `"10"`). Caps: `PacketLimits.MAX_OUTLINE_MARKS = 128`, `MAX_ALLIES = 256`.
- **Performance Patterns Checked**: Per-tick entity iteration (N+1 analog), per-frame overlay, packet amplification, config-max AABB volume, logout leaks, always-on event listeners, particle/tracker spam, reflection on the render thread.

### Analysis Capabilities

- **Pattern Detection**: Tick-loop entity walks, render-thread GL overlays, packet fan-out, session-map leaks, reflection, scoreboard dirtying, Origins `action_over_time`.
- **Database Analysis**: N/A (Forge mod, no DB).
- **Resource Analysis**: MSPT (entity section queries, `hurtMarked` tracker packets), FPS (AFTER_LEVEL lines, `FLASH`, vanilla glowing, particles), bandwidth (UUID lists, velocity packets), allocations on client tick.
- **Architecture Assessment**: Event-bus gating, packet caps, TTL caches, logout cleanup, config blast radius.
- **Frontend Analysis**: N/A for browser Core Web Vitals. Minecraft client renderer covered under findings P-005–P-008.

---

## Performance Findings

### Critical Performance Issues

No critical issues identified.

1.0.20 does not have unbounded S2C decode (`PacketLimits.readCount` throws on `n < 0 || n > max`), does not NBT-scan motion every tick, and does not walk the world while Circuit is idle. Faraday duration lives in expire maps with HUD-interval score mirrors. Logout clears server `ScanOutlineService.MARKED` / `UNLOAD_STRIKES` and client `MARKED` / `CACHED` / `FRAME`. Remaining findings degrade MSPT/FPS under skill-active load; they do not take the process down or corrupt saves under normal play.

### High Performance Impact Findings

#### P-001: Faraday aura runs two AABB queries and dirties mid-ring trackers at 10 Hz

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:243` (`tickAura` call), `FaradayService.java:562` (`tickAura` body)
**Performance Impact**: 7.4
**Pattern Detected**: High-frequency dual entity query + unconditional `hurtMarked` on every mid-ring living body (N+1 analog of `getEntities` / `getEntitiesOfClass` every 2 ticks per active cage)
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
    // hard ring: setPos skipped within HARD_WALL_EPS; hurtMarked skipped if velocity matches
    // mid ring:
    living.setDeltaMovement(m.x + nx * MID_PUSH, m.y, m.z + nz * MID_PUSH);
    living.hurtMarked = true;
}
```

**Impact**: Each active cage, every 2 ticks, walks a catch box (`CATCH_RADIUS = 4.8`) and a living repel box (`repelRadius` default 10, config max 32). Hard-ring `setPos` / `hurtMarked` already no-op when the body is on the wall and velocity already matches `HARD_PUSH`. Mid ring still **adds** `MID_PUSH` every 2 ticks, so `hurtMarked` is 10 Hz for every living body between `hardRadius` and `midRadius` (defaults 5–7, both live-config). A 40-mob cluster next to a cage is ~20 velocity packets per tick plus sparks every `REPEL_VFX_INTERVAL = 5`. Config `repelRadius` 32 roughly quadruples the living AABB versus default 10.
**Performance Cost**: ~0.2–2.5 ms MSPT plus 50–400 extra packets/s per cage in a dense mob area. Single-player wilderness: negligible. Hard-ring skip already helps; mid-ring is the remaining tracker storm.
**Recommendation**: Keep mid-ring push feel, but skip `setDeltaMovement` / `hurtMarked` when the entity is already moving outward above a small speed threshold, or apply mid push on a 5-tick cadence matching `REPEL_VFX_INTERVAL`. Merge catch + living into one `getEntities` with a typed predicate if both boxes stay. Cap `repelRadius` lower on dedicated servers, or document that 32 is a farm-lag knob.
**Fix Priority**: Within 1 week

#### P-002: Faraday combat listeners tax the whole server while any cage is up

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:259` (`onProjectileImpact`), `FaradayService.java:377` (`shouldBlock`), `FaradayService.java:523` (`nearestCage`)
**Performance Impact**: 7.1
**Pattern Detected**: Global event fan-in gated only by “any cage or held shot exists,” then a linear scan of `ACTIVE_CAGES` per projectile/attack
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
// shouldBlock → HELD.containsKey(id) || (!ACTIVE_CAGES.isEmpty() && nearestCage(entity) != null)
// nearestCage: for each UUID in ACTIVE_CAGES, getPlayer + ±2 section prefilter + distanceToSqr
```

**Impact**: `idleCombat()` is `ACTIVE_CAGES.isEmpty() && HELD.isEmpty()`. While **one** player has a cage, every `ProjectileImpactEvent`, `LivingAttackEvent`, and `LivingHurtEvent` on the server enters `shouldBlock`. TACZ servers fire this path for every kinetic bullet. `nearestCage` is section-prefiltered (`±2` chunk sections, ~32 blocks) which is the right idea, but it still iterates every caged player and does `getPlayerList().getPlayer` per shot. `LivingDamageEvent` / `LivingDeathEvent` correctly skip the idle gate (emergency raise must see the first lethal hit) and already TTL-cache `hasModePower` + `isEmergencyEnabled`.
**Performance Cost**: Microseconds per vanilla arrow; milliseconds-per-tick under TACZ spray (hundreds of impact events/s) with one cage up. Multi-cage servers multiply the inner loop. One-entity-per-tick `blockCacheId` only helps duplicate events for the **same** entity in the same game time.
**Recommendation**: Spatial hash: map caged players by `(dimension, chunkX, chunkZ)` and probe the projectile’s 3×3 chunk neighborhood instead of scanning `ACTIVE_CAGES`. Keep `idleCombat()` for the zero-cage case. Optionally ignore `ProjectileImpactEvent` when the projectile’s chunk is more than 2 sections from every cage (same prefilter, inverted).
**Fix Priority**: Within 1 week

#### P-003: Static Scan pulse walks a cube of up to 128 blocks (4096 entity sections)

**Location**: `src/main/java/com/staticresonance/mode/ScanOutlineService.java:110` (`pulse`), `src/main/java/com/staticresonance/config/SrConfig.java:154` (`scanRadius` 1–128)
**Performance Impact**: 7.0
**Pattern Detected**: One-shot `getEntitiesOfClass(LivingEntity)` over a cube whose half-extent is the configured radius; world toml may set 128. N+1 analog: walk every loaded living in a 256³ AABB, then filter to a sphere and cap at 128 UUIDs.
**Code Context**:

```java
double r = scanRadius(scanner);
AABB box = new AABB(pos.x - r, pos.y - r, pos.z - r, pos.x + r, pos.y + r, pos.z + r);
for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, e -> {
    if (!e.isAlive() || e == scanner || e instanceof ArmorStand || e.isSpectator()
            || !matchesFilter(e, filter)) {
        return false;
    }
    double distSq = e.distanceToSqr(pos);
    return distSq >= minSq && distSq <= maxSq;
})) {
    marked.add(living.getUUID());
}
evictOldest(marked); // PacketLimits.MAX_OUTLINE_MARKS = 128
```

**Impact**: Default `GameplayDefaults.SCAN_RADIUS = 32` is ~4×4×4 = 64 entity sections — acceptable as a paid F-cast. Config max 128 is a **256-block cube**: up to 16×16×16 = **4096** 16³ entity sections of loaded chunks, then a sphere filter (`distSq <= r*r`) that still paid the cube walk. The cube is ~1.9× the sphere volume, so nearly twice the living bodies are tested. Each first-seen `ServerPlayer` also sends two `ClientboundSoundPacket`s (`pingScanner`). Pulse is on-demand (toggle / login / respawn), not per tick — the spike is sharp, not continuous. Prune every 20 ticks only `getEntity(uuid)` per mark (cheap) and resends the full UUID list if anything died.
**Performance Cost**: Default 32: ~0.2–1 ms on pulse. Radius 128 in a loaded spawn/farm: several ms MSPT plus a 128-UUID S2C packet (~2 KB) and up to 256 sound packets if the sphere is full of players. Client then holds 128 live entity refs for the overlay (P-005).
**Recommendation**: Iterate entity sections whose center is inside the sphere, or shrink the AABB to the sphere’s bounding cylinder and break once `marked.size() == MAX_MARKS` **inside** the loop (currently the loop collects everyone, then `evictOldest` FIFO-drops the oldest). Lower the config max (e.g. 64) or warn in the comment that 128 is a lag knob. Snapshot radius already lives in `RADIUS_POWER` — good.
**Fix Priority**: Within 1 week for servers that expose the toml; default 32 can wait for Phase 2.

#### P-004: Control Field sweeps living entities every 4 ticks and applies vanilla GLOWING + FLASH

**Location**: `src/main/java/com/staticresonance/mode/ControlService.java:473` (`tickField` sweep), `ControlService.java:270` (`refreshLockEffects`), `ControlService.java:283` (`spawnHeadRing`)
**Performance Impact**: 7.2
**Pattern Detected**: Session-gated `getEntitiesOfClass` every 4 ticks out to `fieldMaxRadius` (default 30, config max 64) + vanilla `MobEffects.GLOWING` (all nearby clients outline-render) + `ParticleTypes.FLASH` on every first lock
**Code Context**:

```java
if (now % SWEEP_INTERVAL == 0) { // 4
    AABB box = new AABB(pos.x - r, pos.y - 2, pos.z - r, pos.x + r, pos.y + 3, pos.z + r);
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
// refreshLockEffects → MOVEMENT_SLOWDOWN amp 255 + DIG_SLOWDOWN + GLOWING
// spawnHeadRing (first lock, withVfx) → RING_DUST + WITCH + ELECTRIC_SPARK + FLASH
```

**Impact**: Unlike Sense outlines (private S2C to the caster), Control uses **vanilla glowing**, so every client who can see the victim runs Minecraft’s glowing outline pass. A field that expands to 30–64 blocks in a mob farm can lock dozens of bodies in one sweep; each first lock also emits `ParticleTypes.FLASH` (full-screen white flash, notorious GPU hitch) plus 16+8+16 particles. Duration-gate `needsLockRefresh` (refresh only when slowness remaining `< 20`) is correct and avoids per-sweep effect spam after the first hit. Field VFX edge (`spawnFieldEdge`) is capped at 12 points — fine. Session map is empty-gated on the server tick.
**Performance Cost**: Sweep itself ~0.1–1.5 ms / 4 ticks at r=30; r=64 in a crowded chunk is the MSPT spike. Client FPS drop is larger: N glowing entities for **all** nearby players, plus a burst of FLASH on first lock. Default duration 1200 ticks (60 s) keeps the glow set alive for a full minute.
**Recommendation**: Drop `FLASH` from the field path (keep it on paid single-target lock if the beat is needed). Replace `GLOWING` on field victims with the existing caster-only scan boxes, or a team-only glow. Cap first-lock VFX per sweep (e.g. 3 head rings). Optionally sweep every 8 ticks after the radius has finished expanding (`FIELD_EXPAND_INTERVAL = 5` → r=max in 2.5 s at default 30).
**Fix Priority**: Within 1 week for FLASH; GLOWING is a design change (Phase 2) but the FPS cost is real.

#### P-005: Caster scan overlay draws 8 line segments per mark AFTER_LEVEL, optionally with depth off

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:122` (`onRender`), `src/main/java/com/staticresonance/client/ScanBoxRenderer.java:33` (`draw`), `SrConfig.java:289` (`seeThroughWalls`)
**Performance Impact**: 6.9
**Pattern Detected**: Extra world overlay pass after the entire level (and shader composite); immediate tessellator LINES, 8 segments × up to 128 marks; `disableDepthTest` when `seeThroughWalls` or Iris reports a pack
**Code Context**:

```java
// ScanOutlineClient.onRender AFTER_LEVEL:
FRAME.clear();
for (LivingEntity living : CACHED) {
    AABB box = interpolatedBox(living, partialTick);
    if (frustum != null && !frustum.isVisible(box)) continue;
    if (entirelyBehind(camera, box)) continue;
    FRAME.add(new ScanBoxRenderer.Box(box, rgb[0], rgb[1], rgb[2], 0.95f * pulse));
}
boolean seeThrough = SrConfig.COMMON.visuals.seeThroughWalls.get() || ShaderPacks.inUse();
ScanBoxRenderer.draw(camera, WORLD_PROJECTION, WORLD_VIEW, seeThrough, FRAME);
// ScanBoxRenderer: 8 yaw-facing L-brackets; RenderSystem.disableDepthTest() if seeThrough
```

**Impact**: Drawing after `AFTER_LEVEL` is an intentional shader-pack workaround (comment: Oculus/ImmediatelyFast must not feed lines into `gbuffers_line`). Cost: a second lines shader bind, projection backup/restore, and `new Matrix4f(projection)` every frame while any mark is cached. At the packet cap, 128 boxes × 8 segments × 2 vertices = **2048** line vertices per frame. Frustum + `entirelyBehind` (includes `Math.sqrt` of half-extents) cull well when looking away; `seeThroughWalls` default is **false**, but `ShaderPacks.inUse()` forces see-through whenever Iris reports a pack (or, on API mismatch, whenever Oculus/Iris is installed — P-007). Depth-off lines overdraw the whole framebuffer. Eight segments instead of a 24-edge hitbox is already the right reduction.
**Performance Cost**: 0 marks: overlay body skipped after a cheap `CACHED.isEmpty()` (but AFTER_ENTITIES still copies matrices — P-006). 10 marks, depth on: ~0.1–0.3 ms GPU. 128 marks, depth off, shader pack: 0.5–2+ ms + overdraw, visible FPS dip on integrated GPUs.
**Recommendation**: Skip `ScanBoxRenderer.draw` work when `FRAME` is empty (already). Keep 8-segment brackets. Do not enable see-through unless the user opted in **or** a pack is actually in use (cache `ShaderPacks.inUse()`, P-007). Optionally LOD: beyond 48 m draw 4 segments. Avoid `new Matrix4f` by reusing a static scratch.
**Fix Priority**: Within 2 weeks (pair with P-006 / P-007)

### Medium Performance Impact Findings

#### P-006: AFTER_ENTITIES copies world matrices every frame even when no scan marks exist

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:123`
**Performance Impact**: 5.2
**Pattern Detected**: Always-on `RenderLevelStageEvent` work for every client that has the mod loaded
**Code Context**:

```java
if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
    WORLD_PROJECTION.set(event.getProjectionMatrix());
    WORLD_VIEW.set(event.getPoseStack().last().pose());
    haveWorldMatrices = true;
    return;
}
```

**Impact**: Two `Matrix4f.set` copies run at 60–120 FPS for **all** players, including those who never used Sense. AFTER_LEVEL then clears `haveWorldMatrices` and returns on `CACHED.isEmpty()`. Necessary only while marks are live (or during the 20-tick reveal).
**Performance Cost**: Tiny per frame (~a few thousand cycles) but paid by every client, every frame, forever. The hottest idle client cost in this mod besides Climb’s `hasModePower` cache probe.
**Recommendation**: Capture matrices only when `!CACHED.isEmpty() || !MARKED.isEmpty()`.
**Fix Priority**: Easy win; do with P-005.

#### P-007: `ShaderPacks.inUse()` reflects on the Iris API every overlay frame

**Location**: `src/main/java/com/staticresonance/client/ShaderPacks.java:19`, called from `ScanOutlineClient.java:167`
**Performance Impact**: 5.5
**Pattern Detected**: Per-frame `Method.invoke` on the render thread; fallback `assumeInUse = loaderPresent` forces see-through for the whole session if the Iris API class is missing
**Code Context**:

```java
static boolean inUse() {
    if (!tried) resolve();
    if (inUseMethod != null) {
        try {
            return Boolean.TRUE.equals(inUseMethod.invoke(api));
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }
    return assumeInUse;
}
```

**Impact**: While marks are drawn, every AFTER_LEVEL frame boxes a boolean, invokes Iris, and unboxes. If `net.irisshaders.iris.api.v0.IrisApi` is absent but the `oculus`/`iris` modid is loaded, `assumeInUse` stays true → depth test off for every scan box (P-005 overdraw) even with shaders disabled.
**Performance Cost**: Reflection: tens of microseconds/frame (worse on Graal/weird classloaders). Wrong `assumeInUse`: GPU overdraw for the whole scan lifetime.
**Recommendation**: Cache the boolean for 20 ticks (shader packs do not toggle every frame). On resolve failure, default **false** (opt-in see-through via config), not true.
**Fix Priority**: Within 2 weeks

#### P-008: Client cache rebuild walks `entitiesForRendering()` on each S2C generation (and every 20 ticks if incomplete)

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:114` (tick cadence), `ScanOutlineClient.java:230` (`rebuildCache`)
**Performance Impact**: 5.8
**Pattern Detected**: Client-side N+1 analog — `ClientLevel.entitiesForRendering()` has no UUID index, so unresolved marks scan the entire client entity list
**Code Context**:

```java
if ((clientTick & 1) == 0 || CACHED.isEmpty()) {
    rebuildCache(level);
}
// rebuildCache keeps live refs; if have.size() < MARKED.size()
//   && (walkedGeneration != markGeneration || (clientTick % 20) == 0):
for (Entity entity : level.entitiesForRendering()) { ... }
```

**Impact**: Design is already incremental: live `LivingEntity` refs are reused; the full render list is walked at most once per `markGeneration` and then every 20 ticks only while marks are missing (unloaded / not yet spawned). `ClientLevel` really does not index by UUID — the comment is accurate. Coupled with server prune (`PRUNE_INTERVAL = 20`) sending `clearAll` + full UUID list, a single death bumps `markGeneration` and triggers another full walk. Farms with thousands of client entities make that walk visible. While `MARKED` is empty, `CACHED.isEmpty()` is true so `rebuildCache` still runs **every** client tick (early-outs on `MARKED.isEmpty()`).
**Performance Cost**: Complete cache: ~128 UUID contains + isAlive, even ticks only — cheap. Incomplete cache in a 2000-entity client world: 0.2–1 ms every 20 ticks. Idle (no marks): a HashSet-less early return every tick — Low, listed here because it shares the method.
**Recommendation**: Skip `rebuildCache` entirely when `MARKED.isEmpty()`. When walking, prefer `level.getEntity(id)` if the client entity lookup by UUID is available via the vanilla `uuidToId` map (`ClientLevel.getEntity(int)` needs entityId; there is still no public UUID index — keep the walk, but do not walk on empty). Avoid bumping `markGeneration` when the UUID set is identical after prune.
**Fix Priority**: Phase 2

#### P-009: Scan S2C always replaces the full UUID list (up to 128 × 16 bytes)

**Location**: `src/main/java/com/staticresonance/mode/ScanOutlineService.java:135`, `ScanOutlineService.java:231`, `src/main/java/com/staticresonance/network/PacketLimits.java:7`
**Performance Impact**: 5.0
**Pattern Detected**: Full-state S2C instead of delta; cap is correct (`MAX_OUTLINE_MARKS = 128`) but prune resends the whole set
**Code Context**:

```java
ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> scanner),
        new ScanOutlinePacket(true, new ArrayList<>(marked)));
```

**Impact**: Pulse and changed-prune both send `clearAll=true` plus every remaining UUID. 128 UUIDs ≈ 2 KB plus encode/decode `ArrayList` + `List.copyOf`. Caster-only (`PacketDistributor.PLAYER`) — no broadcast. Decode is bounded (`readCount` throws). Client `handle` then `MARKED.clear(); addAll` and `markGeneration++`, forcing P-008’s walk.
**Performance Cost**: Negligible on LAN; on a constrained mobile uplink, a prune every 20 ticks while mobs die in a farm is extra chatter. Worse coupling is the client cache invalidation, not the bytes.
**Recommendation**: Send deltas (`added` / `removed`) or skip the packet when `pruneDead` changed nothing (already: `if (!pruneDead(...) || marked == null) continue`). If the set is unchanged, do not increment client `markGeneration`. Hash the UUID set and skip identical payloads.
**Fix Priority**: Phase 2

#### P-010: Motion harvest walks every online player, then an entity AABB up to 32 blocks

**Location**: `src/main/java/com/staticresonance/mode/MotionHarvestService.java:41`
**Performance Impact**: 5.4
**Pattern Detected**: Server tick over `getPlayerList().getPlayers()`; origin players pay `getEntities` in a cube of `motionMaxDist` (default 5, config max 32)
**Code Context**:

```java
for (ServerPlayer player : server.getPlayerList().getPlayers()) {
    if (!ModeService.hasModePower(player)) continue;
    if (regen) changed |= regenTick(player);
    if (motion) changed |= harvest(player);
    if (changed) ApoliResources.sync(player);
}
// harvest: AABB ±maxDist, getEntities(..., isMotionSource), cap motionCap (default 8, max 32)
```

**Impact**: Gated by `regenIntervalTicks` (default 35) and `motionIntervalTicks` (default 20). `hasModePower` is TTL-cached 20 ticks — good. Non-origin players are a ConcurrentHashMap probe. Each origin player not at `CHARGE_MAX` walks nearby tagged motion sources. Default 5-block cube is cheap; config 32 is a 64³ AABB. `ApoliResources.sync` pushes the **whole** power container on any charge change (P-013). Wet regen also sends 4 bubble particles.
**Performance Cost**: 40 origin players × default box every 20 ticks: small. Same 40 with `motionMaxDist = 32` in loaded farms: 1–4 ms spikes on the harvest tick. Full-container sync multiplies bandwidth.
**Recommendation**: Keep walking the online list (comment already rejects a permanent origin-player set). Early-out harvest when `charge >= CHARGE_MAX` (already). Clamp dedicated-server `motionMaxDist` or stop the entity walk once `count >= motionCap` (already breaks). Sync only the charge resource if Apoli allows a partial sync.
**Fix Priority**: Phase 2 (document 32 as a lag knob)

#### P-011: Circuit `NeighborNotifyEvent` is server-wide while any interference is live

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:130`
**Performance Impact**: 5.1
**Pattern Detected**: Hot redstone event with an empty-map gate, but no chunk gate once the map is non-empty
**Code Context**:

```java
public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
    if (INTERFERENCES.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) {
        return;
    }
    if (getInterference(level.dimension(), event.getPos()) != null) {
        event.setCanceled(true);
    }
}
```

**Impact**: Idle Circuit does **no** world walk (comment is accurate; F is one `player.pick`). The remaining cost is this listener: a redstone clock fires `NeighborNotifyEvent` extremely often. Empty-map return is free. While **one** hopper is force-toggled (`toggleTicks` default 160), every neighbor notify on the server does a dimension map lookup. The lookup is O(1); the event volume is the problem. `tickInterferences` walks the tiny map every server tick only to expire — fine.
**Performance Cost**: Idle: ~0. Uncontended HashMap get × redstone event rate (thousands/s on a clock farm) while a 8-second interference exists. Rarely the MSPT villain unless Circuit is used next to a big clock.
**Recommendation**: Keep a `Set<SectionPos>` of interfered sections and return before the map get when the event section is not in the set. Expire entries even if the chunk is unloaded (currently restore is skipped without `hasChunkAt`, but the map entry is still removed on timeout — good).
**Fix Priority**: Phase 3

#### P-012: Faraday server tick always resolves the overworld clock; cooldown keeps the listener busy for 2400 ticks

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:218`
**Performance Impact**: 4.6
**Pattern Detected**: Missing triple-empty early-out; leftover CD set keeps per-tick work for two minutes after the cage drops
**Code Context**:

```java
public static void onServerTick(TickEvent.ServerTickEvent event) {
    if (event.phase != TickEvent.Phase.END) return;
    var server = ServerLifecycleHooks.getCurrentServer();
    if (server == null) return;
    tickHeld(server.overworld());
    long now = server.overworld().getGameTime();
    tickCageExpiry(server, now);
    if (now % CLOCK_INTERVAL == 0L) tickCageFx(server);
    if (ACTIVE_CAGES.isEmpty()) return;
    // aura + HUD
}
```

**Impact**: Unlike Scan/Control/Dust/Circuit, Faraday does not return immediately when all session maps are empty. `tickHeld` does return if `HELD.isEmpty()`. `tickCageExpiry` still runs; default `cooldownTicks = 2400` keeps `COOLDOWNS` non-empty for 2 minutes per drop, writing `sr_faraday_cd` every `HUD_INTERVAL = 10` (this is already much better than the old per-tick score write). Score writes are **not** every tick in 1.0.20.
**Performance Cost**: Fully idle: `getCurrentServer` + `overworld().getGameTime()` every tick — microseconds. After a cage: 2 minutes of UUID iteration + scoreboard writes at 2 Hz. Not High; easy to finish.
**Recommendation**: `if (ACTIVE_CAGES.isEmpty() && HELD.isEmpty() && COOLDOWNS.isEmpty()) return;` before resolving the overworld. Keep HUD-interval scores.
**Fix Priority**: Phase 3 (trivial)

#### P-013: Every charge/mode write calls `ApoliAPI.synchronizePowerContainer` (full container)

**Location**: `src/main/java/com/staticresonance/mode/ApoliResources.java:151` (Forge), `MotionHarvestService.java:68`, every spend path
**Performance Impact**: 5.3
**Pattern Detected**: Coarse-grained capability sync on a 20-tick harvest / 35-tick regen / every spend
**Code Context**:

```java
public void sync(Player player) {
    io.github.edwinmindcraft.apoli.api.ApoliAPI.synchronizePowerContainer(player);
}
```

**Impact**: Correctness requires clients to see charge for the radial HUD. The Forge API syncs the **entire** power container (mode, scan flags, styles, emergency, field_active, charge). Harvest + regen can sync 2–3 times per second per origin player when both clocks align. Connector `FabricBackend` uses reflection `PowerHolderComponent.sync(Entity)` — heavier per call (P-021).
**Performance Cost**: One extra capability packet per origin player on those clocks. Fine at 5 origin players; noisy at 40. Spend paths (F cast) are user-rate limited (`CastService` TickGate 6).
**Recommendation**: If Apoli exposes a single-power sync, use it for charge ticks. Coalesce regen+harvest into one sync per player per tick (already: `if (changed) sync` after both). Do not sync when `next == charge`.
**Fix Priority**: Phase 2

#### P-014: Faraday aura/silhouette particles are broadcast per living body

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:609`, `FaradayService.java:741`
**Performance Impact**: 4.8
**Pattern Detected**: `ServerLevel.sendParticles` inside the 10 Hz living loop (every 5 ticks) plus 11 silhouette particles every `CLOCK_INTERVAL = 5`
**Code Context**:

```java
if (showVfx) {
    level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
            living.getX(), living.getY() + living.getBbHeight() * 0.5, living.getZ(),
            5, 0.15, 0.2, 0.15, 0.03);
}
// spawnCageSilhouette: END_ROD 3 + ELECTRIC_SPARK 6 + GLOW 2 every 5 ticks
```

**Impact**: `showVfx` is `now % 5 == 0`, but `tickAura` only runs on even ticks, so VFX is every 10 ticks aligned to both. Still: 5 sparks × N mid/hard-ring bodies, sent to all tracking players. Silhouette is a flat 11 particles / 5 ticks / cage — acceptable. Activate function also fires one `minecraft:flash` (`faraday/activate.mcfunction`) — one-shot, same GPU class as P-004.
**Performance Cost**: 40 mobs × 5 sparks × tracking clients every 0.5 s. Particle packets dominate over CPU here.
**Recommendation**: One spark burst at the cage origin instead of per-entity; or only VFX the nearest 6 bodies. Keep silhouette.
**Fix Priority**: Phase 3

### Low Performance Impact Findings

#### P-015: Origins `scan_abort` action-over-time interval is 5 ticks for every origin player

**Location**: `src/main/resources/data/static_resonance/powers/systems/scan_abort.json:3`
**Performance Impact**: 3.8
**Pattern Detected**: Granted `origins:action_over_time` that re-checks two resources at 4 Hz even while inactive
**Code Context**: `interval: 5`, condition `scan_active == 1 AND mode != 1`, then `scan_stop`. Java already `stop()`s on mode change (`ModeService.setMode`) and on the 20-tick Sense prune.
**Impact**: Origins evaluates the power every 5 ticks for every Static Resonance player. When inactive, two resource reads fail the `and` quickly. Redundant with Java; 5 is tighter than needed.
**Performance Cost**: Origins condition overhead × origin players × 4/s. Small.
**Recommendation**: Raise `interval` to 20, or drop the power now that Java owns abort.
**Fix Priority**: Phase 4

#### P-016: `ClimbService` runs on every `PlayerTickEvent` (client + server)

**Location**: `src/main/java/com/staticresonance/mode/ClimbService.java:23`
**Performance Impact**: 3.6
**Pattern Detected**: Always-on player tick; `hasModePower` is TTL-cached; `hurtMarked` only after a real velocity write
**Code Context**: Early-outs: spectator / water / `!horizontalCollision`. Origin wall-slide sets `hurtMarked = true` every tick while climbing (velocity packets). Sparks every 8 ticks server-side only.
**Impact**: 100-player servers: 100 cache probes/tick. Climbing origin: tracker packets at 20 Hz — intended for smooth motion.
**Recommendation**: Leave as-is unless climb feels like packet spam; then mark hurt only when `m.y` actually changed by more than epsilon (already gated by `m.y < climb`).
**Fix Priority**: Phase 4

#### P-017: Dust zone `getEntitiesOfClass` + 16 particles every 4 ticks

**Location**: `src/main/java/com/staticresonance/mode/DustService.java:144`, `DustService.java:180`
**Performance Impact**: 3.5
**Pattern Detected**: Session-gated zone tick; default radius 6, duration 200; blindness duration-gated (`< 20`)
**Code Context**: `ZONE_TICK_INTERVAL = 4`; 10 `SAND_DUST` + 6 `CLOUD` at the zone origin (not per entity). Logout removes the zone.
**Impact**: One small AABB plus 16 particles / 4 ticks while a zone exists. Fine. Config radius max 32 would make this Medium — same class of knob as scan/motion.
**Recommendation**: Document `zoneRadius` 32 as a lag knob; keep per-origin (not per-victim) particles.
**Fix Priority**: Phase 4

#### P-018: Client ally set is not cleared on `LoggingOut`

**Location**: `src/main/java/com/staticresonance/client/AllyListClient.java:9`, contrast `ScanOutlineClient.java:171`
**Performance Impact**: 2.4
**Pattern Detected**: Session cache without a client disconnect clear (server `AllyService.CACHE` **does** clear on logout)
**Code Context**: `ScanOutlineClient.onLoggingOut` → `clearAll()` for marks. `AllyListClient` has `clear()` but nothing calls it on `LoggingOut` / `Clone`.
**Impact**: Up to `MAX_ALLIES` (256) UUIDs retained on the client until replace-on-next-login. Tiny leak; next `AllySyncPacket` overwrites.
**Recommendation**: Call `AllyListClient.clear()` from the existing `LoggingOut` handler (or a tiny shared disconnect method).
**Fix Priority**: Phase 4

#### P-019: Scan flecks copy/sort `CACHED` every 4 ticks (capped at 24)

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:291`
**Performance Impact**: 3.2
**Pattern Detected**: Client-tick particle spawn with a full-list copy and distance sort when over cap
**Code Context**: `SPARK_INTERVAL = 4`, `FLECK_CAP = 24`, two `addParticle` per shown entity (dust + electric spark). Uses `revealed()` during the 20-tick expand.
**Impact**: 24 × 2 = 48 local particles / 4 ticks = 12/tick average — within vanilla particle budgets. Sort of 128 refs is cheap. New `ArrayList` every 4 ticks while scanning.
**Recommendation**: Reuse a static `shown` list; skip sort when `CACHED.size() <= FLECK_CAP`. Optional: skip flecks if particle status is MINIMAL.
**Fix Priority**: Phase 4

#### P-020: Look-target cone fallback inflates 32 blocks and raycasts LoS per candidate

**Location**: `src/main/java/com/staticresonance/mode/LookTargets.java:64`
**Performance Impact**: 3.4
**Pattern Detected**: Cast-time `getEntitiesOfClass` + `hasLineOfSight` (world clip) up to `CONE_CANDIDATE_CAP = 32`
**Code Context**: Precise `ProjectileUtil.getEntityHitResult` first; cone only on miss. Fallback range `min(range, 32)`. Used by Control lock, Dust, ally snapshot (radial open only).
**Impact**: Not a tick loop. Worst case: 32 LoS clips on F miss. Control `targetRange` config max 128 only affects the **ray**, not the cone inflate.
**Recommendation**: Keep. Cap is already there.
**Fix Priority**: No change required; listed for completeness.

#### P-021: Connector `FabricBackend` reflects on every Apoli read

**Location**: `src/main/java/com/staticresonance/mode/ApoliResources.java:160`
**Performance Impact**: 3.9
**Pattern Detected**: Reflection `invoke` per `hasPower`/`getValue` with a PowerType cache; Forge path is direct
**Code Context**: Power types cached in `ConcurrentHashMap`; `invalidate()` on reload. `hasModePower` TTL (20) hides a lot of `hasPower` traffic. `getValue(CHARGE)` on the radial every frame while open is still a reflect chain on Connector.
**Impact**: Forge users: none. Connector users: extra microseconds on harvest/HUD. Acceptable for dual-loader support.
**Recommendation**: Cache last charge for 1 tick on the client HUD if profiling shows it; not a 1.0.20 blocker.
**Fix Priority**: Phase 4

---

## Code Pattern Performance Analysis

### Performance Anti-Pattern Detection

| Pattern | Where it shows up in this mod | Status |
|---------|-------------------------------|--------|
| Unbounded S2C list | Historical; `PacketLimits.readCount` now throws | Fixed |
| Per-tick NBT / `@e` harvest | Replaced by `MotionHarvestService` Java | Fixed |
| Idle Circuit volume walk | Replaced by one F `player.pick` | Fixed |
| N+1 entity walk every tick | Faraday aura 10 Hz (P-001); Control Field / 4 ticks (P-004); Dust / 4 ticks (P-017); Scan pulse on demand (P-003); client `entitiesForRendering` on generation (P-008) | Active, gated |
| Per-frame extra pass | AFTER_LEVEL lines (P-005); AFTER_ENTITIES matrix copy always (P-006) | Active |
| Global event fan-in | Faraday combat while any cage (P-002); Circuit NeighborNotify while any interference (P-011) | Active, empty-gated |
| Full-state sync | Scan UUID replace (P-009); Apoli container (P-013) | Active, capped |
| Session leak on logout | Server scan `MARKED` cleared; client `MARKED`/`CACHED` cleared; Faraday maps cleared; **client allies not cleared** (P-018); `HELD` projectiles expire via `nearestCage == null` | Mostly clean |
| Reflection on hot path | `ShaderPacks.inUse` (P-007); Fabric Apoli (P-021) | Active |

### Database Access Pattern Analysis

N/A — Forge 1.20.1 Origins add-on, no SQL, no ORM, no connection pool.

Persistence that **does** exist:

- **Apoli resource powers** (`systems/charge`, `mode`, `scan_active`, `scan_radius`, `scan_filter`, styles, `field_active`, `faraday_emergency`): read/write + full-container sync (P-013).
- **Dummy scoreboards** (`sr_faraday`, `sr_faraday_cd`, `sr_mc`, …): Faraday leftover HUD written at `HUD_INTERVAL = 10` in 1.0.20 (not every tick). `ServerScoreboard.setScore` still `setDirty()`; 2 Hz during cage/CD is acceptable.
- **Player persistent NBT** (`StaticResonance.Allies`): loaded once into `AllyService.CACHE`, copied on toggle, synced with `MAX_ALLIES = 256`.

### Resource Management Pattern Analysis

**Strengths**

- Session maps are `ConcurrentHashMap` with empty-map early-outs on Scan prune, Control field, Dust zones, Circuit interferences.
- `PacketLimits` on outline marks and allies; client also FIFO-evicts at 128.
- `TickGate` on mode change (4) and F-cast (6).
- `hasModePower` TTL 20 ticks; Faraday `isEmergencyEnabled` TTL 20; Faraday gunshot type cache; Faraday `HeldShot.cached` entity pointer; one-entity `shouldBlock` cache per game time.
- Effect duration gates: Faraday slowness refresh, Dust blindness, Control lock refresh, cage resistance.
- Client scan cache keeps live entity refs; flecks capped at 24.
- Logout: server scan maps, Faraday cage/CD/emergency cache, Control sessions/hands, Dust zones, Circuit owned interferences, Ally server cache, Cast/mode gates. Client scan `LoggingOut` + `Clone`.

**Gaps**

- Faraday server tick missing the triple-empty return (P-012).
- Client allies survive disconnect (P-018).
- `rebuildCache` allocates `ArrayList` + `HashSet` every even tick while scanning (P-008).
- `ScanBoxRenderer` allocates a new `Matrix4f` per draw (P-005).
- Faraday `GUNSHOT_TYPES` lives for process lifetime (tiny; entity types are few).

---

## Architecture Performance Assessment

### Data Access Layer Analysis

There is no DB layer. The closest equivalent is Apoli’s power container + the overworld scoreboard.

- Reads on hot paths go through TTL caches (`hasModePower`, Faraday emergency) except charge (`getValue` every harvest/regen/HUD).
- Writes always `sync()` the whole container (P-013).
- Faraday scores are derived from expire timestamps (correct clock; HUD-interval mirror). `isCageActive` is `ACTIVE_CAGES.contains` — not a scoreboard read on the combat path.
- Ally NBT is not re-parsed per lock (cached `Set.copyOf`).

Score: **8.5/10** for this architecture.

### Application Layer Analysis

Five mode services + harvest + climb, all Forge event subscribers.

| Service | Idle cost | Active cost |
|---------|-----------|-------------|
| ScanOutlineService | 0 (`MARKED.isEmpty()`) | One pulse AABB; prune `/20` `getEntity` |
| FaradayService | Overworld clock (P-012) | 10 Hz dual AABB + combat fan-in |
| ControlService | 0 (`SESSIONS.isEmpty()`) | `/4` living sweep + glow/flash |
| DustService | 0 (`ZONES.isEmpty()`) | `/4` small AABB + 16 particles |
| CircuitService | 0 (`INTERFERENCES.isEmpty()`) | Expire walk + NeighborNotify |
| MotionHarvestService | Player-list + TTL `hasModePower` on regen/motion clocks | Origin AABB |
| ClimbService | TTL `hasModePower` every player tick | Velocity write on collision |

Command feedback still uses `performPrefixedCommand` → datapack functions (scan start/stop, Faraday activate, mode jingle). Those functions no longer `@e`-scan; they are VFX + `sr_internal scan_clear`.

Score: **8.3/10**. Faraday is the outlier.

### Frontend Analysis (if applicable)

N/A as a browser frontend (no Core Web Vitals, no bundle, no hydration).

Minecraft client layer (for completeness): radial HUD is hold-R only and batches annular sectors (`SEGMENTS_PER_WEDGE = 12`) into one tessellator begin/end — appropriate. Scan overlay is the real client cost (P-005–P-008). Lock-hands client only runs while `isUnderLock(self)`. Cast key is Phase.START consumeClick — cheap.

### Infrastructure Analysis

- **Event bus**: Many `@SubscribeEvent` classes; empty-map gates keep most off the hot path. Faraday combat and Circuit NeighborNotify are the remaining global listeners.
- **Networking**: SimpleChannel protocol `"10"`, 10 packet types, main-thread consumers, `PacketLimits` on the two list codecs. C2S mode/cast/style packets are tiny (`writeByte` / empty `CastPacket`).
- **Config**: COMMON spec is the dedicated-server authority. Blast-radius knobs (`scanRadius` 128, `fieldMaxRadius` 64, `motionMaxDist` 32, `repelRadius` 32, `seeThroughWalls`) are the operational risk, not code bugs.
- **Datapack**: One granted AOT (`scan_abort` interval 5), one `conditioned_attribute` `tick_rate` 20, static knockback attribute, modify-damage-when-wet (event, not tick). Faraday tick JSON is gone.

Score: **8.6/10**.

---

## Performance Bottleneck Analysis

### Top Performance Bottlenecks

| Rank | Bottleneck | Location | Why it hurts | Est. share of skill-active cost |
|------|------------|----------|--------------|--------------------------------|
| 1 | Faraday mid-ring `hurtMarked` + dual AABB @ 10 Hz | `FaradayService.tickAura:562` | Tracker/velocity packets × mobs in 5–10 (max 32) m | 25–35% of cage MSPT+net |
| 2 | Faraday combat fan-in while any cage | `FaradayService.shouldBlock:377` | Every projectile/attack on the server | 15–30% on TACZ; ~0 in vanilla PvE |
| 3 | Control Field GLOWING + FLASH + `/4` sweep | `ControlService.tickField:473` | All nearby clients outline N mobs; FLASH hitch | 20–40% of field FPS hit |
| 4 | Scan pulse cube at config radius 128 | `ScanOutlineService.pulse:110` | Up to 4096 entity sections, once per F | Spike, not sustained |
| 5 | AFTER_LEVEL 8-segment overlay × 128, depth off | `ScanBoxRenderer.draw:33` | Extra pass + overdraw with shaders / see-through | 10–25% of caster FPS while scanning |
| 6 | Client `entitiesForRendering` on new mark generation | `ScanOutlineClient.rebuildCache:257` | Full client entity list, no UUID index | Spike after pulse/prune |
| 7 | Apoli full-container sync on harvest/regen | `ApoliResources.sync:151` | Capability packet × origin players | Bandwidth, not MSPT |
| 8 | Always-on AFTER_ENTITIES matrix copy | `ScanOutlineClient.onRender:123` | Every client, every frame | Dominant **idle** client cost |

---

## Technical Recommendations

### Immediate Performance Fixes

1. **Faraday mid-ring**: skip `setDeltaMovement` / `hurtMarked` when already moving outward faster than `MID_PUSH`, or drop mid-ring cadence to 5 ticks (P-001).
2. **Faraday idle tick**: return when `ACTIVE_CAGES`, `HELD`, and `COOLDOWNS` are all empty (P-012).
3. **Scan overlay idle**: do not copy world matrices unless `MARKED`/`CACHED` is non-empty (P-006).
4. **Shader query**: cache `IrisApi.isShaderPackInUse` for 20 ticks; default false on resolve failure (P-007).
5. **Control Field**: remove `ParticleTypes.FLASH` from `spawnHeadRing` when called from the field sweep (P-004).
6. **`rebuildCache`**: return immediately without allocating if `MARKED.isEmpty()` even when `CACHED.isEmpty()` (P-008).

### Performance Enhancements

1. Spatial index for `nearestCage` / combat intercept (P-002).
2. Sphere-section scan pulse + early-exit at 128 marks (P-003); consider lowering toml max from 128 to 64.
3. Scan packet hash / delta so prune does not bump `markGeneration` (P-009 + P-008).
4. Replace field `GLOWING` with caster-only boxes (P-004).
5. Partial Apoli charge sync (P-013).
6. Aura VFX at cage origin instead of per-entity (P-014).
7. Circuit interfered-section set for NeighborNotify (P-011).

### Architecture Improvements

1. Treat COMMON maxes as **lag SLOs**: publish recommended dedicated-server caps (`scanRadius ≤ 48`, `repelRadius ≤ 12`, `fieldMaxRadius ≤ 24`, `motionMaxDist ≤ 8`, `seeThroughWalls = false`).
2. One client disconnect hook that clears scan marks **and** allies (P-018).
3. Keep Circuit as raycast-only; do not reintroduce volume sight.
4. Optional Spark/JFR or Spark Profiler markers around `pulse`, `tickAura`, and `ScanBoxRenderer.draw` for 1.0.21+ (no production overhead if gated).
5. Do not add a permanent `ORIGIN_PLAYERS` set (harvest comment is right — login/origin-swap bugs cost more than a player-list walk every 20 ticks).

---

## Code Optimization Examples

### Example 1 — Skip Faraday mid-ring tracker spam (`FaradayService.java`)

**Before** (current 1.0.20, mid ring always dirties):

```java
if (dist <= SrConfig.COMMON.faraday.midRadius.get()) {
    refreshRepelSlow(living, 2);
    Vec3 m = living.getDeltaMovement();
    living.setDeltaMovement(m.x + nx * MID_PUSH, m.y, m.z + nz * MID_PUSH);
    living.hurtMarked = true;
} else {
    refreshRepelSlow(living, 0);
}
```

**After** (keep push feel, skip no-op packets):

```java
if (dist <= SrConfig.COMMON.faraday.midRadius.get()) {
    refreshRepelSlow(living, 2);
    Vec3 m = living.getDeltaMovement();
    double outward = m.x * nx + m.z * nz;
    if (outward < MID_PUSH * 0.85) {
        living.setDeltaMovement(m.x + nx * MID_PUSH, m.y, m.z + nz * MID_PUSH);
        living.hurtMarked = true;
    }
} else {
    refreshRepelSlow(living, 0);
}
```

### Example 2 — Do not capture scan matrices when idle (`ScanOutlineClient.java`)

**Before**:

```java
if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
    WORLD_PROJECTION.set(event.getProjectionMatrix());
    WORLD_VIEW.set(event.getPoseStack().last().pose());
    haveWorldMatrices = true;
    return;
}
```

**After**:

```java
if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
    if (CACHED.isEmpty() && MARKED.isEmpty()) {
        haveWorldMatrices = false;
        return;
    }
    WORLD_PROJECTION.set(event.getProjectionMatrix());
    WORLD_VIEW.set(event.getPoseStack().last().pose());
    haveWorldMatrices = true;
    return;
}
```

### Example 3 — Cache Iris + fail closed (`ShaderPacks.java` + `ScanOutlineClient.java`)

**Before**: `inUseMethod.invoke(api)` every AFTER_LEVEL frame; `assumeInUse = loaderPresent` on resolve failure.

**After**:

```java
private static long cachedAtTick = Long.MIN_VALUE;
private static boolean cachedInUse;

static boolean inUse() {
    if (!tried) resolve();
    Minecraft mc = Minecraft.getInstance();
    long tick = mc.level == null ? 0L : mc.level.getGameTime();
    if (tick == cachedAtTick) return cachedInUse;
    cachedAtTick = tick;
    if (inUseMethod != null) {
        try {
            cachedInUse = Boolean.TRUE.equals(inUseMethod.invoke(api));
            return cachedInUse;
        } catch (ReflectiveOperationException e) {
            cachedInUse = false;
            return false;
        }
    }
    cachedInUse = false; // config seeThroughWalls remains the opt-in
    return false;
}
```

### Example 4 — Faraday server tick triple-empty return (`FaradayService.java`)

**Before**: always `getCurrentServer()` + `overworld().getGameTime()` + `tickCageExpiry`.

**After**:

```java
if (event.phase != TickEvent.Phase.END) return;
if (ACTIVE_CAGES.isEmpty() && HELD.isEmpty() && COOLDOWNS.isEmpty()) return;
var server = ServerLifecycleHooks.getCurrentServer();
if (server == null) return;
tickHeld(server.overworld());
long now = server.overworld().getGameTime();
tickCageExpiry(server, now);
```

---

## Performance Optimization Priorities

### Phase 1: Critical Performance Fixes

No critical defects. Use this phase for the **highest** skill-active costs that are small diffs:

- P-001 mid-ring `hurtMarked` skip
- P-004 field-path `FLASH` removal
- P-006 idle matrix skip
- P-012 Faraday empty-map return
- P-007 Iris cache / fail closed

### Phase 2: High Impact Optimizations

- P-002 combat spatial index
- P-003 scan sphere sections + early-exit at 128; consider config max 64
- P-004 replace field `GLOWING` with caster-only boxes
- P-005 overlay LOD / reuse projection matrix
- P-008 skip identical prune generations
- P-013 charge-only Apoli sync

### Phase 3: Medium Impact Improvements

- P-009 delta/hashed scan packets
- P-010 / docs for `motionMaxDist` 32
- P-011 Circuit section gate on NeighborNotify
- P-014 aura VFX at origin
- P-005 4-segment distant brackets

### Phase 4: Performance Monitoring and Fine-tuning

- P-015 `scan_abort` interval 20 or delete
- P-018 client ally clear on logout
- P-019 reuse fleck list
- Spark Profiler markers (gated)
- Dedicated-server recommended toml snippet in `docs/` (not a new markdown unless requested later)

---

## Estimated Performance Improvement Impact

### Performance Gains by Priority

| Phase | Focus | Est. MSPT (cage + farm) | Est. caster FPS (scan 128 + shaders) | Est. idle |
|-------|--------|-------------------------|--------------------------------------|-----------|
| 1 | Faraday mid-ring, FLASH, idle skips | **15–35%** less cage MSPT/packets | **5–10%** (FLASH hitch gone on field) | Matrix copy gone (~measurable on integrated GPU) |
| 2 | Combat index, scan cube, glow, sync | **10–25%** TACZ+cage; **scan spike −40–70%** at r=128 | **15–40%** if glow leaves other clients | — |
| 3 | Packets, NeighborNotify, VFX origin | **5–10%** redstone+Circuit / particle net | **5–15%** depth-on LOD | — |
| 4 | AOT, flecks, ally clear | <3% | <3% | Leak hygiene |
| **Combined** | | **~25–50%** of skill-active server cost | **~20–45%** of scan/field FPS cost | Idle already <2% of frame |

Gains assume default play plus one Faraday cage in a 30–40 mob cluster, or one scan at config-max. Wilderness defaults stay within noise.

### Resource Utilization Improvements

- **CPU (server)**: Fewer `getEntitiesOfClass` payloads if scan early-exits; fewer Faraday tracker dirty flags; combat path O(cages-in-chunk) instead of O(cages)×O(projectiles).
- **CPU (client)**: No AFTER_ENTITIES copy while idle; cached Iris boolean; fewer `entitiesForRendering` walks if prune does not bump generation.
- **GPU**: Depth test stays on unless the user or a live shader pack asked; no field FLASH burst; fewer glowing outlines if Control moves private.
- **Network**: Mid-ring velocity packets drop sharply; Apoli harvest sync coalesced; scan prune payloads skip when unchanged.
- **Memory**: Stop idle `rebuildCache` tick; ally clear on logout; reuse fleck/`FRAME` lists (FRAME already reused).

---

## Performance Monitoring Setup Recommendations

### Observability Recommendations

- **Spark Profiler** (Forge 1.20.1): `/spark profiler start --timeout 30` during (a) idle lobby, (b) Faraday cage in a mob farm, (c) Sense pulse at the world’s `scanRadius`, (d) Control Field at max radius. Look for `FaradayService.tickAura`, `ScanOutlineService.pulse`, `ControlService.tickField`, `ScanOutlineClient.onRender`, `ScanBoxRenderer.draw`.
- **Spark tick monitor**: `/spark tickmonitor` while a TACZ player fires into a cage (P-002).
- **Forge debug**: `debug` profiler pie (`Shift+F3`) on the caster while 128 marks are up — GPU vs CPU split for AFTER_LEVEL.
- Do not add production logging on these paths; a `DEBUG` logger in `pulse`/`tickAura` would itself show up in MSPT.

### Recommended Performance Tracking

| Metric | How | Budget (dedicated, 20 TPS) |
|--------|-----|----------------------------|
| Server MSPT idle (mod loaded, no skills) | Spark | < 0.15 ms in this mod’s subscribers combined |
| Faraday `tickAura` with 40 mobs @ default radii | Spark sampler | < 1.5 ms / 2 ticks |
| Scan `pulse` at world’s `scanRadius` | Spark + one F | < 2 ms at 32; < 6 ms at 128 |
| Client frame time with 128 see-through marks | F3 + Spark client | < 1.5 ms in `ScanBoxRenderer.draw` |
| ScanOutlinePacket size | Wireshark / Forge network debug | ≤ 128 UUIDs (~2 KB) |
| `sr_faraday` score writes / s | Count `setScore` | ≤ 2 Hz per caged player (already) |

### Performance Testing Recommendations

1. **Idle soak**: 4 origin players AFK 10 minutes; Spark compare vs vanilla+Origins without this mod. Climb / Faraday clock / matrix copy should be the only visible slices.
2. **Scan radius sweep**: `scanRadius` 32 vs 64 vs 128 in a packed spawn chunk; record pulse MSPT and client `rebuildCache` time.
3. **Faraday farm**: cage next to a 40-mob spawner, `repelRadius` 10 vs 32; count `hurtMarked` packets (Spark net, or Spark health).
4. **TACZ intercept**: one cage, two players spraying; confirm P-002 dominates until spatial index lands.
5. **Control Field**: 30 m in a village vs a farm; screenshot glowing outline cost on a **spectator** client (not the caster).
6. **Logout leak**: connect, scan, Faraday, field, dust, circuit, disconnect; heap dump / just read maps — `MARKED`/`CACHED` empty (already), `AllyListClient` currently not.
7. **Shader pack on/off**: confirm `seeThroughWalls=false` still depth-tests when Iris is installed but no pack is active (P-007 fail-closed).
8. Unit tests already cover `ChargeBuckets`, `ExpireClock`, `MarkPrune`, `GameplayDefaults` — add a test that `evictOldest` never exceeds 128 and that `PacketLimits.readCount(129)` throws.

---

## Summary

**Key strengths.** 1.0.20 is a much tighter Origins combat add-on than the 1.0.14-era tree: `PacketLimits` on both list codecs, Circuit idle-no-walk, Java motion harvest, Faraday expire clocks with HUD-interval scores, duration-gated effects, `hasModePower` TTL, Faraday section prefilter and `HeldShot` cache, caster-only scan packets, incremental client entity cache with a 24-fleck cap, and verified logout clears of server `MARKED`/`UNLOAD_STRIKES` plus client `MARKED`/`CACHED`/`FRAME`. Empty-map early-outs on Scan, Control, Dust, and Circuit are correct.

**Critical areas.** There are **no critical** outage bugs. The five High items are skill-active: Faraday 10 Hz mid-ring tracker spam (P-001), Faraday global combat fan-in (P-002), configurable 128-block scan cube (P-003), Control Field vanilla `GLOWING`+`FLASH` (P-004), and the AFTER_LEVEL 8-segment overlay at 128 marks with depth optionally off (P-005). COMMON toml maxes (`scanRadius` 128, `seeThroughWalls`, `repelRadius` 32) are the operational blast radius.

**Expected overall improvement.** Implementing Phase 1+2 should cut roughly **a quarter to a half** of Faraday-in-a-farm MSPT/packets and **a fifth to two-fifths** of caster/spectator FPS cost during max-radius scan and Control Field, with idle already in good shape (<2% of a frame once P-006 lands). Do not reintroduce Circuit volume sight or per-tick NBT harvest; those historical wins are the reason this audit’s Critical bucket is empty.
