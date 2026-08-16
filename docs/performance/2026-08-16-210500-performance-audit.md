## Executive Summary

### Audit Overview

- **Target System**: Static Resonance (`static_resonance` / `com.staticresonance`) — Minecraft Forge 1.20.1 Origins hybrid (Java + datapack)
- **Analysis Date**: 2026-08-16
- **Analysis Scope**: Server tick (20 TPS / 50 ms budget), client tick + `RenderLevelStageEvent`, S2C SimpleChannel packets, Origins/Apoli `action_over_time` / `execute_command`, Faraday/sense datapack functions. Compared against `docs/performance/2026-08-15-165558-performance-audit.md` and `docs/performance/2026-08-15-184634-performance-audit.md`.
- **Technology Stack**: Minecraft 1.20.1, Forge 47.2.0, Origins/Apoli (Forge + Fabric-via-Connector), Java 17, Gradle, scoreboard + Apoli resources as persistence, SimpleChannel protocol `"9"`

### Performance Assessment Summary

| Performance Level | Count | Percentage |
|-------------------|-------|------------|
| Critical Issues   | 1     | 5%         |
| High Impact       | 4     | 20%        |
| Medium Impact     | 8     | 40%        |
| Low Impact        | 7     | 35%        |
| **Total**         | **20** | **100%**  |

### Key Analysis Results

- **Performance Anti-Patterns**: 1 critical pattern (always-on Circuit N³ block walk + up to 512 S2C marks). Prior Critical scan-volume / `force @a` storm is still gone.
- **Code Optimization Opportunities**: 4 high-impact remaining (Circuit `applyForce` neighbor re-pulse, client 128-block outline queries, 512 see-through line boxes, Faraday every-tick r=10 repel + all-dimension entity hunt).
- **Architecture Assessment**: 7/10 (session maps, dirty-flag Circuit refresh, batched scan pulse, and distance-limited VFX are solid; Circuit mode is a new unbudgeted voxel scan and Faraday repel radius grew).
- **Overall Code Performance Score**: 66/100 (was 74/100 on 2026-08-15 after VFX cleanup; Circuit mode and Faraday r=10 every-tick queries pull the score back).

**Already fixed vs still true (prior audits):**

| Prior finding | Status now |
|---------------|------------|
| 128-block filled dust + `color_entity` recast (2026-08-15-165558 P-001) | **Fixed.** Scan is a one-shot Java `getEntitiesOfClass` at r=32; rim + heartbeat VFX only. |
| Bare `force @a` particle fan-out | **Fixed.** Remaining particles use `force @a[distance=..N]`. |
| Faraday global `@e` hold + NBT Motion every 5 ticks | **Fixed.** `hold_projectiles.mcfunction` is no longer called from `tick_live`. Java `HELD` UUID map freezes shots. |
| Control Field `getEntitiesOfClass` every tick | **Improved.** `SWEEP_INTERVAL = 4`. Still a Medium (r=30 living sweep). |
| `spawnHeadRing` ~84 positioned `sendParticles` | **Fixed.** Four batched calls. |
| Magnetic Lock LOS-before-cone on 64-block cube | **Improved.** `LookTargets` tests cone/dot before `hasLineOfSight`. AABB is still 64. |
| Scan public Glowing / HP dust | **Fixed.** Scanner-only `ScanOutlinePacket`. |
| Dust double `@e` selector | **Fixed.** Java `DustService` one AABB. |
| Faraday hold distance / Control interval / cone order | **Still the right shape.** New cost is Faraday `REPEL_RADIUS = 10` every tick (was 3.0 in the 184634 audit). |
| Unconditional `faraday_tick`, motion NBT harvest, radial `ItemStack`s, `performPrefixedCommand`, Fabric reflection HUD reads, unused datapack | **Still true.** |

---

## Analysis Methodology

### Performance Analysis Approach

- **Static Code Analysis**: Read the mandated services/clients/UI plus tick neighbors (`DustService`, `ClimbService`, `LookTargets`, `ModeService`, `AllyService`, `CastService`, packets, Origins powers, Faraday/sense/systems functions).
- **Tick-model query analysis**: Counted per-tick `getEntitiesOfClass` / `getEntities`, N³ `getBlockState` walks, `@e` selectors, NBT `data get`/`data modify`, and S2C packet builders against the 50 ms server tick and the client render thread.
- **Resource Utilization Assessment**: Session maps, uncleared UUID sets, particle/line-box GPU cost, SimpleChannel payload size.
- **Architecture Performance Review**: Dual tick authorities (Origins AOT vs Forge `ServerTickEvent`), EventBus subscribers that fire when idle, command-string vs function manager.

### Analysis Coverage

- **Files Analyzed**: 38 Java sources under `src/main/java/com/staticresonance`, 38 `*.mcfunction`, ~35 datapack JSON (powers/tags/origin), plus both prior audit reports.
- **Database Queries Reviewed**: Not applicable as SQL. Analog: 12 `getEntitiesOfClass`/`getEntities` sites, 1 N³ block walk, 6 `@e` datapack selectors still on granted paths, 3 NBT Motion reads in `motion_try`.
- **API Endpoints Analyzed**: Not applicable as HTTP. Analog: SimpleChannel protocol `"9"` (10 packet types), `sr_internal` Brigadier tree, Origins `active_self` / `action_over_time` powers on the origin list.
- **Performance Patterns Checked**: Per-tick loops, N³ voxel walks, `getEntitiesOfClass`, particle spam, S2C rates, client 128-block queries, Origins `execute_command`, uncleared maps, idle EventBus subscribers.

### Analysis Capabilities

- **Pattern Detection**: N+1 selectors, inefficient loops, memory leaks, blocking command parse, EventBus idle cost.
- **Database Analysis**: Does not apply (no SQL). Scoreboard/`@e`/chunk-section access is the equivalent.
- **Resource Analysis**: MSPT, client frame time, particle/line-box overdraw, packet objects.
- **Architecture Assessment**: Session maps, dirty flags, dual tick authorities, Apoli read caching.
- **Frontend Analysis**: Not a web app. Client HUD + `RenderLevelStageEvent` line boxes + local particles are the analog of “frontend”.

---

## Performance Findings

### Critical Performance Issues

#### P-001: Circuit sight walks a 16-block sphere of block states on mode select and every step

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:333-363`
**Performance Impact**: 9.1 (Critical)
**Pattern Detected**: Always-on N³ voxel walk (`RADIUS = 16` → ~17k `hasChunkAt` + `getBlockState` + tag tests) plus up to 512 S2C outline entries
**Code Context**:

```java
private static void refreshSight(ServerPlayer player, Sight sight, boolean forceSend) {
    // RADIUS = 16; SCAN_INTERVAL = 20; MAX_MARKS = 512
    for (int dy = -r; dy <= r; dy++) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dy * dy + dz * dz > rSq) continue;
                cursor.set(origin.getX() + dx, y, origin.getZ() + dz);
                if (!level.hasChunkAt(cursor)) continue;
                BlockState state = level.getBlockState(cursor);
                if (!state.is(CIRCUIT_TAG)) continue;
                found.add(new CircuitOutlinePacket.Entry(pos, isWorking(level, pos, state, inf)));
            }
        }
    }
}
```

**Impact**: Selecting Circuit calls `startSight` immediately (`onModeChanged`). The scan is **free and persistent** for the whole time the player stays in Circuit. `onServerTick` re-runs it every 20 ticks whenever `dirty` or `lastOrigin` moved by one block, so walking is one full sphere per second. `isWorking` falls through to `level.hasNeighborSignal(pos)` for untagged-property blocks — a second neighbor query per hit. `circuit.json` includes wire, every wood button/plate, hoppers, pistons, rails, iron doors: a factory floor easily hits `MAX_MARKS = 512`, then sorts by Manhattan distance and ships a full `CircuitOutlinePacket` (512 `BlockPos` + boolean) to that player.
**Performance Cost**: Estimated 2–12 ms server-thread on a loaded redstone factory (worse with several Circuit players). Packet ~4–5 KB per refresh. Client then draws all marks (P-004). This is the new always-on cost; the old 128-block scan dust storm is gone, but Circuit is selected more often than Scan is pulsed.
**Recommendation**: Scan loaded chunk sections / `PalettedContainer` for `#static_resonance:circuit` instead of every block; or walk only sections that contain redstone-ish blocks. Cache section occupancy. Do not rescan on every block of movement — snap origin to 4-block grid. Cap live marks to 128–256 and fade by distance. Keep `SCAN_INTERVAL` and the dirty flag.
**Fix Priority**: Immediate

### High Performance Impact Findings

#### P-002: Circuit `applyForce` re-pulses neighbors every server tick for 160 ticks

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:279-303` and `448-462`
**Performance Impact**: 8.2 (High)
**Pattern Detected**: Per-tick `setBlock` / `updateNeighborsAt` / `neighborChanged` on every live interference
**Code Context**:

```java
applyForce(level, inf.key.pos, inf.saved, inf.forceOn, false);
// ...
if (block instanceof RedStoneWireBlock && state.hasProperty(RedStoneWireBlock.POWER)) {
    if (state.getValue(RedStoneWireBlock.POWER) != power) {
        level.setBlock(pos, state.setValue(RedStoneWireBlock.POWER, power), Block.UPDATE_CLIENTS);
    }
    if (on) {
        level.updateNeighborsAt(pos, block);
    }
}
```

**Impact**: `tickInterferences` runs on every `ServerTickEvent` END. Each `Interference` lasts `TOGGLE_TICKS = 160`. Force-on dust calls `updateNeighborsAt` **even when power is already 15**. Pistons call `neighborChanged` every tick. That is 160 neighbor floods per toggled piece, which re-enters `NeighborNotifyEvent` (P-011) and vanilla redstone. A player who F-toggles three wires in a clock farm multiplies the world's redstone work for 8 seconds.
**Performance Cost**: 0.5–10+ ms/tick extra redstone in a live circuit; can hitch neighboring chunk updates and item-hopper transfers.
**Recommendation**: Apply force once on cast and only re-apply when `getBlockState` drifted. Never `updateNeighborsAt` on the idle refresh path. For dust, set power once with `UPDATE_CLIENTS` and rely on the HIGH `NeighborNotifyEvent` cancel for force-off.
**Fix Priority**: Immediate

#### P-003: Scan outline client queries a 128-block cube every frame and every 4 ticks

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:101-103` and `174-178`
**Performance Impact**: 7.9 (High)
**Pattern Detected**: Client render-thread `getEntitiesOfClass` with `inflate(128.0)` while a scan can stay armed indefinitely
**Code Context**:

```java
AABB search = mc.player.getBoundingBox().inflate(128.0);
java.util.List<LivingEntity> marked = level.getEntitiesOfClass(
        LivingEntity.class, search, e -> e.isAlive() && MARKED.contains(e.getUUID()));
// ...
// spawnLocalFlecks, every SPARK_INTERVAL = 4 client ticks:
for (Entity entity : level.getEntitiesOfClass(Entity.class, search,
        e -> e.isAlive() && MARKED.contains(e.getUUID()) ...
```

**Impact**: Server scan is now a **one-shot r=32** pulse (`ScanOutlineService.SCAN_RADIUS = 32`, `REVEAL_MAX = 32`) but the client still searches a 256³ AABB on **every** `AFTER_TRANSLUCENT_BLOCKS` frame, then again every 4 ticks as `Entity.class` for two local particles per mark. Scan stays up until F or leaving Sense — minutes, not one pulse. The 184634 audit's P-012 walked `entitiesForRendering()` to toggle glowing; this is worse: a full class query at 128 blocks, every frame.
**Performance Cost**: 0.3–4 ms client frame in a populated overworld; doubled by the fleck pass. Integrated GPUs + farms drop FPS for the scanner only (private outline — good split, bad query).
**Recommendation**: Resolve `MARKED` UUIDs via the client entity lookup (or cache `Entity` refs and validate `isAlive`/`level`). Inflate by 32, not 128. Skip flecks off-frustum. Rebuild the marked list once per client tick, not per frame.
**Fix Priority**: Immediate

#### P-004: Circuit client draws up to 512 see-through line boxes through a 256-byte buffer

**Location**: `src/main/java/com/staticresonance/client/CircuitOutlineClient.java:68-95` and `src/main/java/com/staticresonance/client/SeeThroughRender.java:15-19`
**Performance Impact**: 7.5 (High)
**Pattern Detected**: Unbounded-until-512 immediate line boxes + tiny `RenderType` buffer → many flushes per frame
**Code Context**:

```java
private static final RenderType LINES = create(
        "sr_see_through_lines", ..., 256, false, false, ...);
// CircuitOutlineClient.onRender:
for (Map.Entry<BlockPos, Boolean> mark : MARKS.entrySet()) {
    AABB box = new AABB(pos).inflate(aim ? 0.03 : 0.004);
    LevelRenderer.renderLineBox(pose, lines, box, r, g, b, a);
}
buffers.endBatch(type);
```

**Impact**: Each `renderLineBox` emits 24 line segments. 512 boxes ≈ 12k segments, depth-disabled, translucent. `SeeThroughRender` buffer size is 256 bytes, so the buffer source flushes repeatedly. `player.pick` also runs every frame to highlight the looked-at piece. Circuit sight is on for the whole mode, so this is idle Circuit gameplay, not a cast burst. Scan outline (P-003) uses the same `RenderType` and draws **two** boxes per living entity.
**Performance Cost**: 1–8 ms GPU/CPU on a factory floor; worse when Circuit + Scan overlays stack.
**Recommendation**: Raise buffer size (at least 256 KB). LOD: skip marks behind the camera, shrink to 128 nearest, or draw a cheap debug-line shader in one strip. Do not `endBatch` mid-world if vanilla translucency still needs the same type.
**Fix Priority**: Within 1 week

#### P-005: Faraday ticks catch + r=10 living repel every server tick and hunts held shots across all dimensions

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:163-188`, `300-311`, `330-348`, `437-449`, `452-462`
**Performance Impact**: 7.4 (High)
**Pattern Detected**: Per-tick dual AABB queries per cage; `findEntity` walks `getAllLevels()`; repel radius **regressed** from 3.0 (184634 audit) to 10.0
**Code Context**:

```java
public static final double REPEL_RADIUS = 10.0;
tickHeld(server.overworld());
tickRepel(player, now);
catchNearby(player);
// tickHeld:
Entity entity = findEntity(anyLevel, held.id);
// findEntity:
for (ServerLevel level : server.getAllLevels()) {
    Entity found = level.getEntity(id);
```

**Impact**: While any cage is up (`sr_faraday` 600 ticks = 30 s), every server tick: (1) `catchNearby` `getEntities` in r=4.8, (2) `tickRepel` `getEntitiesOfClass(LivingEntity)` in a 20×4×20 box, with `setPos` + `hurtMarked` in the hard ring, (3) `tickHeld` looks up every frozen shot, and on overworld miss iterates every dimension. Datapack `hold_projectiles` is no longer on the live path (prior High **fixed**), but Java now does more per-tick work than the 184634 r=3 aura. `isCustomGunshot` hits `ForgeRegistries` + string `contains` for non-`Projectile` entities on the catch predicate.
**Performance Cost**: 0.3–3 ms/tick per cage in a village/raid; `setPos` on many mobs dirty trackers. All-dimension UUID hunt is cheap per shot but scales with held count × dimension count.
**Recommendation**: Interval repel/catch to 2 ticks; keep catch radius 4.8. Restore a smaller hard-wall query or reuse one AABB. Store `ServerLevel` + entity id on `HeldShot` so `findEntity` does not scan the nether/end. Early-out `tickHeld` when `HELD` is empty (already present) and skip `getAllLevels` on the common overworld hit.
**Fix Priority**: Within 1 week

### Medium Performance Impact Findings

#### P-006: Faraday still has two tick authorities; `faraday_tick` has no Origins condition

**Location**: `src/main/resources/data/static_resonance/powers/systems/faraday_tick.json:1-8` and `src/main/java/com/staticresonance/mode/FaradayService.java:162-188`
**Performance Impact**: 6.0 (Medium)
**Pattern Detected**: Unconditional `action_over_time` + Java `ACTIVE_CAGES` tick for the same cage
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

**Impact**: **Still true** from both prior audits. Every origin player dispatches `function static_resonance:faraday/tick` 4 times/sec. The function early-outs on scores, then `tick_active` reapplies 6 s Resistance/Fire Resistance and `particles.mcfunction` (11 forced particles to r=24). Java independently ticks catch/repel/HUD. Two clocks, two VFX paths.
**Performance Cost**: ~0.05–0.2 ms × origin players always; plus 0.2–1 ms and GPU while a cage is up.
**Recommendation**: Condition the AOT on `sr_faraday > 0 OR sr_faraday_cd > 0`, or move cooldown into Java and delete the power. Refresh potions every 40–80 ticks (`activate` already gives 30 s).
**Fix Priority**: Within 1 month

#### P-007: Control Field still sweeps all living entities to r=30 and emits one particle packet per rim point

**Location**: `src/main/java/com/staticresonance/mode/ControlService.java:462-503`
**Performance Impact**: 5.8 (Medium)
**Pattern Detected**: Interval-improved but still wide `LivingEntity` query + per-point `sendParticles`
**Code Context**:

```java
if (now % SWEEP_INTERVAL == 0) { // 4
    spawnFieldEdge(level, pos, session.radius, now);
    for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, e ->
            e.isAlive() && e != player && !AllyService.isProtected(player, e)
                    && e.distanceToSqr(pos) <= r * r)) {
```

**Impact**: Prior “every tick” High is **fixed** (`SWEEP_INTERVAL = 4`). Remaining: AABB grows to 60×5×60, includes animals/villagers, reapplies 3 synced effects on refresh, and `spawnFieldEdge` still does up to 24 `RING_DUST` + 8 `WITCH` packets every 4 ticks for 1200 ticks. `AllyService.isProtected` reparses NBT ally lists when the candidate is a player.
**Performance Cost**: 0.1–1 ms per field every 4 ticks in a village; ~8 particle packets/tick at full radius.
**Recommendation**: Batch rim into 1–2 `sendParticles`. Optionally restrict to `#static_resonance:affectable`. Cache ally UUIDs. Keep r=30 and the 4-tick cadence.
**Fix Priority**: Within 1 month

#### P-008: Look-ray fallback still inflates a 64-block cube of living entities

**Location**: `src/main/java/com/staticresonance/mode/LookTargets.java:57-72`
**Performance Impact**: 5.5 (Medium)
**Pattern Detected**: Wide AABB on G-press miss; LOS now after cone (prior High **improved**)
**Code Context**:

```java
AABB around = player.getBoundingBox().inflate(range); // DEFAULT_RANGE = 64
for (LivingEntity living : player.level().getEntitiesOfClass(LivingEntity.class, around, e ->
        e.isAlive() && e != player && !e.isSpectator()
                && extra.test(e) && e.distanceToSqr(player) <= rangeSq)) {
    // cone, then hasLineOfSight
```

**Impact**: Used by Control lock, Dust burst/zone, and radial ally snapshot. Fast path `ProjectileUtil` is cheap. Miss path still walks a 128³ living set. Cone-before-LOS **fixed** the old 7.3 High. Dust and lock on a farm can still hitch the server thread on a miss.
**Performance Cost**: 0.5–5 ms hitch per miss in dense pens (down from 1–15 ms when LOS ran first).
**Recommendation**: Cap candidates (closest 16) or inflate 32 and keep `TARGET_RANGE = 64` only for the precise ray.
**Fix Priority**: Within 1 month

#### P-009: Motion harvest still NBT-reads Motion triples on a wide passive tag every second

**Location**: `src/main/resources/data/static_resonance/functions/systems/motion_try.mcfunction:4-6` and `motion_charge.json:1-16`
**Performance Impact**: 4.8 (Medium)
**Pattern Detected**: Per-entity `data get` + `origins:command` score re-checks — **still true**
**Code Context**:

```mcfunction
execute store result score #mx sr_tmp run data get entity @s Motion[0] 100
execute store result score #my sr_tmp run data get entity @s Motion[1] 100
execute store result score #mz sr_tmp run data get entity @s Motion[2] 100
```

**Impact**: Granted `motion_charge` runs every 20 ticks while charge < 100. `motion_sources` is `#affectable` **plus** ~35 passives (villagers, livestock, fish, bees). Each does 3 NBT reads; Apoli then parses four `execute if score @s sr_mc` commands. Villages/farms make this a dense NBT walk every second per origin player.
**Performance Cost**: 0.1–1.5 ms/s per player in animal-dense areas.
**Recommendation**: Narrow the tag to hostiles + players; read `getDeltaMovement()` in Java; drop `origins:command`.
**Fix Priority**: Within 2 months

#### P-010: Faraday combat EventBus subscribers always run `isInterceptable` at HIGHEST

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:197-228` and `394-414`
**Performance Impact**: 4.7 (Medium)
**Pattern Detected**: Idle EventBus on every projectile impact / living attack / hurt / damage
**Code Context**:

```java
@SubscribeEvent(priority = EventPriority.HIGHEST)
public static void onProjectileImpact(ProjectileImpactEvent event) {
    if (shouldBlock(event.getProjectile())) {
        event.setCanceled(true);
    }
}
```

**Impact**: Four HIGHEST handlers fire for the whole server even when `ACTIVE_CAGES` and `HELD` are empty. `shouldBlock` → `isInterceptable` (type checks, then `ForgeRegistries.ENTITY_TYPES.getKey` + `path.contains("bullet")` for custom shots) → `nearestCage` only if interceptable. Vanilla melee short-circuits after `LivingEntity` check. Arrow-heavy farms still pay registry/string work on every impact.
**Performance Cost**: Tens to hundreds of µs per combat event; worse with TACZ + many arrows.
**Recommendation**: If `ACTIVE_CAGES.isEmpty() && HELD.isEmpty()` return immediately at the top of each handler. Cache `isCustomGunshot` per `EntityType`.
**Fix Priority**: Within 1 month

#### P-011: Circuit `NeighborNotifyEvent` is subscribed for every redstone update in the world

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:229-237`
**Performance Impact**: 4.6 (Medium)
**Pattern Detected**: Idle EventBus on a hot vanilla event
**Code Context**:

```java
@SubscribeEvent(priority = EventPriority.HIGH)
public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
    if (!(event.getLevel() instanceof ServerLevel level)) return;
    Interference inf = INTERFERENCES.get(new InterfereKey(level.dimension(), event.getPos()));
    if (inf != null && !inf.forceOn) event.setCanceled(true);
}
```

**Impact**: Lookup is O(1), but Forge still dispatches this subscriber for **every** neighbor notify on the server (clocks, hoppers, observers) even when no player has Circuit interferences. Combined with P-002, force-on dust *generates* the events this handler then sees.
**Performance Cost**: Small per event; measurable in redstone-heavy worlds as EventBus overhead.
**Recommendation**: Unregister or no-op when `INTERFERENCES.isEmpty()`. Pair with P-002 so the handler is not fed by our own per-tick pulses.
**Fix Priority**: Within 1 month

#### P-012: Radial HUD still tessellates many immediate strips and allocates `ItemStack`s every frame

**Location**: `src/main/java/com/staticresonance/client/ui/RadialModeMenuRenderer.java:32` and `162-168`
**Performance Impact**: 4.5 (Medium)
**Pattern Detected**: Per-frame allocation + many `tesselator.end()` — **still true**, menu-open only
**Code Context**:

```java
private static final int SEGMENTS_PER_WEDGE = 24;
ItemStack stack = new ItemStack(wedge.iconItem());
graphics.renderItem(stack, ix, iy);
```

**Impact**: Hold-R only (good; early-return when closed). Five wedges × (fill + hover rim + edge) + center disc/rings + three full-screen scrims. Each sector is a 25-iteration `TRIANGLE_STRIP` with immediate `tesselator.end()`.
**Performance Cost**: 0.3–2 ms per HUD frame on integrated GPUs.
**Recommendation**: Cache static `ItemStack`s; `SEGMENTS_PER_WEDGE = 12`; one scrim.
**Fix Priority**: Within 2 months

#### P-013: `ClimbService` calls `hasModePower` on every player every tick

**Location**: `src/main/java/com/staticresonance/mode/ClimbService.java:23-34`
**Performance Impact**: 4.2 (Medium)
**Pattern Detected**: Idle `PlayerTickEvent` + Apoli container lookup
**Code Context**:

```java
@SubscribeEvent
public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
    if (event.phase != TickEvent.Phase.END) return;
    Player player = event.player;
    if (!ModeService.hasModePower(player) || player.isSpectator() || player.isInWater()) {
        return;
    }
    if (!player.horizontalCollision) return;
```

**Impact**: Runs for **all** players (including non-origin) on both logical sides. Non-origin players still pay `ApoliResources.hasPower` (Forge container or Fabric `Method.invoke`) 20 times/sec. `ControlService.onPlayerTick` is similar but only to drop `HANDS` entries.
**Performance Cost**: Tens of µs × player count (Forge) to low hundreds (Connector) per tick, always-on.
**Recommendation**: Check `horizontalCollision` before Apoli. Cache `hasModePower` for 10 ticks. Skip client if server-only sparks are the only reason to be on the server thread (movement must stay on both sides for prediction).
**Fix Priority**: Within 2 months

### Low Performance Impact Findings

#### P-014: Dead scan-step powers, 64/128 rims, and a 128-block tag sweep still ship

**Location**: `src/main/resources/data/static_resonance/powers/systems/scan_step_1.json` (and 2–4); `functions/sense/scan_pulse.mcfunction:14-15`; `scan_stop.mcfunction:4-5`
**Performance Impact**: 3.6 (Low)
**Pattern Detected**: Load-time parse + leftover wide selectors; steps are **not** on `origins/static_resonance.json`
**Code Context**:

```mcfunction
execute if score @s sr_scan matches 65..128 run function static_resonance:sense/scan_rim_128
# scan_stop:
tag @e[tag=sr_scanned,distance=..128] remove sr_scanned
tag @e[tag=sr_scan_pinged,distance=..128] remove sr_scan_pinged
```

**Impact**: `scan_step_1`–`4` are not granted, so they no longer poll every 5 ticks (prior Medium **mostly fixed**). They still parse on `/reload`. `scan_abort` **is** granted and evaluates `scan_active` + mode every 5 ticks. `scan_stop` still sweeps a 128 cylinder for shared tags even though live pulses no longer set `sr_scanned`.
**Performance Cost**: Load/reload noise; 0.05–0.2 ms AOT abort poll; rare 128 `@e[tag]` on stop.
**Recommendation**: Delete unused step powers and rim_64/rim_128, or grant a single Java-driven expand. Bound stop to 32.
**Fix Priority**: Within 2 months

#### P-015: Mode / Faraday / Scan still dispatch functions via `performPrefixedCommand`

**Location**: `src/main/java/com/staticresonance/mode/ModeService.java:105`, `FaradayService.java:512`, `ScanOutlineService.java:96`
**Performance Impact**: 3.4 (Low)
**Pattern Detected**: String command parse — **still true**
**Code Context**:

```java
server.getCommands().performPrefixedCommand(source, "function " + mode.feedbackFunction());
```

**Impact**: Not per-tick. Mode switch, Faraday activate/deactivate/emergency, scan start/stop each parse Brigadier and run particle functions.
**Performance Cost**: Sub-millisecond per cast; dominated by the particles they spawn.
**Recommendation**: `server.getFunctions().execute` with a cached `ResourceLocation`.
**Fix Priority**: Within 2 months

#### P-016: Client tick / HUD read Apoli resources every tick (Fabric path is reflection)

**Location**: `src/main/java/com/staticresonance/client/ui/RadialModeMenu.java:156` and `ApoliResources.java:176-186`
**Performance Impact**: 3.1 (Low)
**Pattern Detected**: Per-tick component lookup — **still true**
**Code Context**:

```java
if (!ModeService.hasModePower(player)) {
    reset(mc);
    wasDown = false;
    return;
}
```

**Impact**: Every client tick: `hasModePower`. Cast key does the same. Open HUD adds mode, charge, Faraday emergency, control/dust style, scan filter — each a container + registry walk; Fabric uses `Method.invoke`.
**Performance Cost**: Tens of µs (Forge) to low hundreds (Connector) per tick.
**Recommendation**: Cache `hasModePower` 5–10 ticks; cache HUD reads 2–3 ticks.
**Fix Priority**: Within 2 months

#### P-017: Faraday score reads still allocate via `getOrCreatePlayerScore`

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:521-526`
**Performance Impact**: 3.0 (Low)
**Pattern Detected**: Write-on-read — **still true**; now also used by `ActionBars.show` to suppress other bars
**Code Context**:

```java
return board.getOrCreatePlayerScore(player.getScoreboardName(), obj).getScore();
```

**Impact**: `isCageActive` / `canActivateCage` on every Faraday tick, every actionbar write, and HUD-adjacent server messages.
**Performance Cost**: Small map churn if the score is missing; otherwise a map get.
**Recommendation**: Read-only lookup; create scores only in `activate`.
**Fix Priority**: Within 2 months

#### P-018: Unused datapack still parsed at load

**Location**: `src/main/resources/data/static_resonance/origins/static_resonance.json:3-19`
**Performance Impact**: 2.2 (Low)
**Pattern Detected**: Load-time parse of dead resources — **still true**, plus new leftovers
**Code Context**: `powers/active/switch_mode.json` (lang: unused), `powers/mode/pulse_explosion.json`, empty `functions/control/lock_apply.mcfunction` and `functions/overcharge/pulse.mcfunction`, `tags/blocks/conductive.json`, ungranted `scan_step_1`–`4`, uncalled `faraday/hold_projectiles.mcfunction` and `sense/color_entity.mcfunction`.
**Impact**: `/reload` still registers them. No per-tick cost after load.
**Recommendation**: Archive after a full caller search (already done for these names).
**Fix Priority**: Within 2 months

#### P-019: Scan `MARKED` only grows; pulses resend the full UUID list

**Location**: `src/main/java/com/staticresonance/mode/ScanOutlineService.java:101-130`
**Performance Impact**: 2.6 (Low)
**Pattern Detected**: Uncleared set until logout/stop; full-list S2C
**Code Context**:

```java
Set<UUID> marked = MARKED.computeIfAbsent(scanner.getUUID(), id -> new HashSet<>());
// pulse adds every living in r=32; never removes leavers
ModNetwork.CHANNEL.send(..., new ScanOutlinePacket(false, seen));
```

**Impact**: Logout/login/respawn/stop clear the map (good). While scan is up, dead or far entities stay in `MARKED`. Login/respawn re-pulse. If step powers were ever re-granted, every 5 ticks would resend the whole `seen` list. Client `MARKED.addAll` never evicts until `clearAll`.
**Performance Cost**: Tiny memory; client 128 query (P-003) stays large because the set never shrinks.
**Recommendation**: Replace the set each pulse; send a snapshot; drop UUIDs the client cannot resolve for N seconds.
**Fix Priority**: Within 2 months

#### P-020: Faraday live tick still reapplies 6-second effects every 5 ticks

**Location**: `src/main/resources/data/static_resonance/functions/faraday/tick_live.mcfunction:1-4`
**Performance Impact**: 2.8 (Low)
**Pattern Detected**: Redundant effect refresh — **still true** (datapack actionbar title was removed)
**Code Context**:

```mcfunction
effect give @s minecraft:resistance 6 4 true
effect give @s minecraft:fire_resistance 6 0 true
function static_resonance:faraday/particles
```

**Impact**: `activate` already applies 30 s effects. Live tick overwrites them 4 times/sec for 30 s. Java `showCountdown` already owns the actionbar every 10 ticks.
**Performance Cost**: Minor effect-map + packet churn.
**Recommendation**: Refresh every 40–80 ticks or rely on the 30 s grant.
**Fix Priority**: Within 2 months

---

## Code Pattern Performance Analysis

### Performance Anti-Pattern Detection

- **N+1 Query Patterns**: 3 instances (`isWorking` → `hasNeighborSignal` per circuit hit; Faraday `findEntity` × held shots × dimensions; Ally `readAllies` NBT per player candidate in Field).
- **Blocking Operations**: 2 parse/reflection costs (`performPrefixedCommand`; Fabric `Method.invoke` HUD/tick). Not candidates for worker threads — world state stays on the server/client tick threads.
- **Memory Pressure Points**: Circuit `ArrayList` of up to 512 packet entries + `HashMap` rebuild each scan; radial per-frame `ItemStack` + tessellator begins; `new ArrayList<>(SIGHTS/SESSIONS/ACTIVE_CAGES.keySet())` each tick when non-empty.
- **Inefficient Data Access**: Circuit N³ `getBlockState`; client 128 `getEntitiesOfClass` per frame; Faraday r=10 every tick; motion NBT triples; LookTargets 64 inflate.
- **Concurrency Issues**: 0 real races (server tick is single-threaded). `ConcurrentHashMap` on `SIGHTS`/`INTERFERENCES`/`HELD`/`SESSIONS` is conservative and safe.

### Database Access Pattern Analysis

- **Does not apply** as SQL/ORM/connection pools. This is a Minecraft dedicated-server + integrated client. Persistence is scoreboard objectives (`SrScoreboards.ensureAll`), Apoli resource powers, and a small persistent-data ally list.
- **“Query” stand-ins reviewed**: 12 Java entity AABB queries, 1 N³ block walk, granted `@e` in `motion_harvest` and `scan_stop` / Faraday deactivate release, 3 NBT Motion reads.
- **Missing indexes**: Vanilla chunk sections are the only spatial index. Circuit ignores section palettes and walks blocks.
- **Missing async patterns**: 0. Entity/block access cannot leave the server thread.

### Resource Management Pattern Analysis

- **Memory Allocation Patterns**: Session maps (`SESSIONS`, `ACTIVE_CAGES`, `SIGHTS`, `ZONES`, `HELD`, `HANDS`) are player-bounded and cleared on logout — good. Circuit `Sight.marks` rebuilds a `HashMap` per scan. Scan `MARKED` is cleared on stop/logout but not pruned while live.
- **Garbage Collection Pressure**: Circuit packet lists, field-edge particle packets, radial open frames. Scan no longer allocates per-entity particle storms.
- **Thread / Event Loop Usage**: All gameplay on the server thread (correct). Client outline work is on the render thread during `RenderLevelStageEvent` — the dangerous place for 128-block queries (P-003).
- **Caching Opportunities**: Apoli `hasModePower` / charge / style; Circuit section occupancy; Faraday `EntityType` gunshot flag; ally UUID set.
- **Resource Disposal**: Logout hooks exist for sights, cages, sessions, hands, zones, scan marks, client `MARKS`. No leaked server maps found. Scan client marks persist until explicit clear (intended).

---

## Architecture Performance Assessment

### Data Access Layer Analysis

- Not a database layer. Scoreboard objectives are created once (`SrScoreboards`) — healthy. Faraday `getOrCreatePlayerScore` is still a write-capable read (P-017).
- Entity/block access is unindexed world walking. Distance-limited selectors are used for Dust (r=6), motion (r=5), Faraday catch (4.8), and all remaining particle viewers. Circuit (r=16 sphere of **blocks**) and client scan (r=128) are the two that ignore that budget.
- Apoli reads have no TTL cache.

### Application Layer Analysis

- Java owns charge spend for Control / Faraday / Dust / Scan / Circuit (atomic). Session maps avoid scanning the whole player list when empty (`SIGHTS.isEmpty()`, `SESSIONS.isEmpty()`, `ZONES.isEmpty()`, `ACTIVE_CAGES` after `tickHeld`).
- Dual tick authorities remain for Faraday (Origins AOT + Java). Circuit, Control Field, and Dust are Java-only ticks — the right direction.
- Circuit interference is a second redstone authority that **fights** vanilla by re-applying state every tick (P-002).
- Functions still go through `performPrefixedCommand` (P-015).
- Mode-change debounce (4 ticks) is a good anti-spam control.
- Network protocol `"9"` adds Circuit S2C. Handlers use `DistExecutor` + main-thread consumers — correct dedicated-server split.

### Frontend Analysis (if applicable)

- **Not a web frontend.** Core Web Vitals, JS bundle, hydration, and CLS **do not apply**.
- Client surface: `RadialModeMenu` / `RadialModeMenuRenderer` (hold-R only), `ScanOutlineClient`, `CircuitOutlineClient`, `LockHandsClient`, `SeeThroughRender`.
- Equivalent risks: HUD frame time, see-through line overdraw, local particle flecks, leftover 128-block search radius.
- Textures are a few GUI icons. No extra asset pipeline cost.
- Menu early-returns when closed — good. Circuit/Scan overlays do **not** early-return for “player not looking” beyond empty maps.

### Infrastructure Analysis

- **CDN / HTTP compression / connection pool / API gateway: do not apply.** Multiplayer cost is Minecraft particle, entity-tracker, and SimpleChannel packets on the Netty server thread (queued from the tick).
- Particle commands use `force @a[distance=..N]` (intentional so Minimal clients still see signature casts inside the radius).
- Gradle `org.gradle.daemon=false` and `-Xmx3G` affect builds only.
- No Spark/Timings hook, no particle-budget config. Observability is Log4j only.

---

## Performance Bottleneck Analysis

### Top Performance Bottlenecks

| Rank | Component | Issue | Impact Score | Estimated Cost |
|------|-----------|-------|--------------|----------------|
| 1 | Circuit `refreshSight` | N³ r=16 block walk, always-on in Circuit | 9.1 | 2–12 ms / step in factories; 512 S2C marks |
| 2 | Circuit `applyForce` | Neighbor pulse every tick × 160 | 8.2 | Redstone storm 0.5–10 ms/tick |
| 3 | `ScanOutlineClient` | 128-block `getEntitiesOfClass` every frame | 7.9 | 0.3–4 ms client frame while scan armed |
| 4 | `CircuitOutlineClient` | ≤512 depthless line boxes, buffer 256 | 7.5 | 1–8 ms GPU/CPU in Circuit |
| 5 | Faraday Java tick | r=10 living + catch every tick; all-dim hunt | 7.4 | 0.3–3 ms/tick per cage |
| 6 | Faraday dual AOT | Unconditional command + Java | 6.0 | 0.05–0.2 ms × players always |
| 7 | Control Field | Living sweep r=30 / 4 ticks + rim packets | 5.8 | 0.1–1 ms / 4 ticks + ~8 pkt/tick |
| 8 | LookTargets miss | 64-block living cube | 5.5 | 0.5–5 ms hitch on miss |

---

## Technical Recommendations

### Immediate Performance Fixes

1. Replace Circuit's every-block sphere with a section/palette scan or a 4-block-snapped origin; lower `MAX_MARKS` (P-001).
2. Apply Circuit force once; stop per-tick `updateNeighborsAt` / `neighborChanged` (P-002).
3. Resolve scan marks by UUID on the client; drop `inflate(128)` to the real 32-block gameplay radius (P-003).

### Performance Enhancements

1. Grow `SeeThroughRender` buffer and LOD Circuit boxes (P-004).
2. Interval Faraday repel/catch; pin `HeldShot` to a level (P-005). Restore the smaller repel query if gameplay allows.
3. Condition or remove `faraday_tick` AOT; slow potion refresh (P-006, P-020).
4. Batch `spawnFieldEdge`; cache allies (P-007).

### Architecture Improvements

1. One tick authority per ability (Java), datapack only for one-shot VFX.
2. EventBus early-outs when `INTERFERENCES` / `ACTIVE_CAGES` / `HELD` are empty (P-010, P-011).
3. Cache Apoli reads on client; invoke functions via the function manager (P-015, P-016).
4. Delete ungranted scan-step powers and leftover 128 rims so radius 32 cannot silently grow back (P-014).

---

## Code Optimization Examples

### Scan Circuit sections, not every block (references P-001)

**Before (Inefficient)**:

```java
for (int dy = -r; dy <= r; dy++) {
    for (int dx = -r; dx <= r; dx++) {
        for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dy * dy + dz * dz > rSq) continue;
            cursor.set(origin.getX() + dx, y, origin.getZ() + dz);
            if (!level.hasChunkAt(cursor)) continue;
            BlockState state = level.getBlockState(cursor);
            if (!state.is(CIRCUIT_TAG)) continue;
            found.add(new CircuitOutlinePacket.Entry(pos, isWorking(...)));
        }
    }
}
```

**After (Optimized)**:

```java
BlockPos snapped = new BlockPos(origin.getX() & ~3, origin.getY(), origin.getZ() & ~3);
if (!forceSend && snapped.equals(sight.lastOrigin) && !sight.dirty) return;
// Walk only chunk sections that intersect the sphere; skip sections whose
// palette has no #static_resonance:circuit member. Cap found at 128.
```

**Expected Improvement**: ~80–95% fewer `getBlockState` calls when the area is not a factory; walking no longer rescan every block.

### Apply Circuit force once (references P-002)

**Before (Inefficient)**:

```java
if (now >= inf.expireGameTime) { restore(level, inf); it.remove(); continue; }
applyForce(level, inf.key.pos, inf.saved, inf.forceOn, false);
// applyForce: if (on) level.updateNeighborsAt(pos, block);
```

**After (Optimized)**:

```java
BlockState current = level.getBlockState(inf.key.pos);
if (!current.is(inf.saved.getBlock())) { it.remove(); continue; }
if (driftedFromForce(current, inf)) {
    applyForce(level, inf.key.pos, inf.saved, inf.forceOn, false);
}
```

**Expected Improvement**: Removes 160 neighbor floods per toggle; factory clocks stop fighting the cage.

### Resolve scan marks by UUID (references P-003)

**Before (Inefficient)**:

```java
AABB search = mc.player.getBoundingBox().inflate(128.0);
List<LivingEntity> marked = level.getEntitiesOfClass(
        LivingEntity.class, search, e -> e.isAlive() && MARKED.contains(e.getUUID()));
```

**After (Optimized)**:

```java
List<LivingEntity> marked = new ArrayList<>(MARKED.size());
for (UUID id : MARKED) {
    Entity e = ((ClientLevel) level).getEntities().get(id);
    if (e instanceof LivingEntity living && living.isAlive()) marked.add(living);
}
```

**Expected Improvement**: Client work becomes O(marks) (~tens) instead of O(living in 256³) every frame.

### Interval Faraday catch/repel and pin held shots (references P-005)

**Before (Inefficient)**:

```java
tickRepel(player, now);      // every tick, REPEL_RADIUS = 10
catchNearby(player);         // every tick
Entity entity = findEntity(anyLevel, held.id); // all dimensions on miss
```

**After (Optimized)**:

```java
if (now % 2 == 0) {
    tickRepel(player, now);
    catchNearby(player);
}
Entity entity = held.level.getEntity(held.id);
```

**Expected Improvement**: ~50% fewer Faraday AABB queries; no nether/end UUID walks for overworld arrows.

---

## Performance Optimization Priorities

### Phase 1: Critical Performance Fixes

- [ ] Replace Circuit N³ sphere with section/palette or snapped-origin scan; cap marks (P-001)
- [ ] Stop per-tick Circuit neighbor re-pulse (P-002)
- [ ] Client scan: UUID lookup, inflate 32, cache list off the render thread (P-003)

### Phase 2: High Impact Optimizations

- [ ] Larger see-through buffer + LOD for Circuit/Scan boxes (P-004)
- [ ] Faraday: interval catch/repel; store level on `HeldShot`; early-out combat handlers (P-005, P-010)
- [ ] Condition `faraday_tick` or delete it once Java owns cooldown (P-006)

### Phase 3: Medium Impact Improvements

- [ ] Batch Control Field rim particles; cache allies (P-007)
- [ ] Cap LookTargets cone candidates (P-008)
- [ ] Narrow `motion_sources` / Java motion read (P-009)
- [ ] Skip `NeighborNotify` subscriber when `INTERFERENCES` is empty (P-011)
- [ ] Cache radial `ItemStack`s; fewer sectors (P-012)
- [ ] Climb: collision before Apoli; cache `hasModePower` (P-013)

### Phase 4: Performance Monitoring and Fine-tuning

- [ ] Spark profile: Circuit factory walk, Faraday 30 s, Field r=30, Scan-on exploration
- [ ] Config: max circuit marks, scan client radius, Faraday query interval
- [ ] Function manager invoke (P-015); read-only Faraday scores (P-017); Apoli TTL (P-016)
- [ ] Delete unused scan-step/rims/hold_projectiles/switch_mode/pulse_explosion (P-014, P-018)
- [ ] Snapshot-replace scan `MARKED` (P-019); slower Faraday effect refresh (P-020)

---

## Estimated Performance Improvement Impact

### Performance Gains by Priority

| Priority Level | Expected Improvement | Implementation Complexity |
|----------------|---------------------|--------------------------|
| Critical Fixes | Circuit factory MSPT down ~70–90%; scanner FPS no longer walks 128³ every frame | Medium (section scan + client UUID list) |
| High Impact | Faraday cage MSPT ~50% lower; Circuit GPU flush storm gone; idle combat EventBus cheap | Low–Medium |
| Medium Impact | Smoother Field/HUD; smaller lock-miss hitch; less always-on Apoli/NBT | Low–Medium |
| Low Impact | Leaner `/reload`, slightly less command/score/GC noise | Low |

### Resource Utilization Improvements

- **Database Load**: Not applicable (no SQL). Entity/block “query” volume drops most from P-001/P-003/P-005.
- **Memory Usage**: Smaller Circuit mark maps and no per-frame 128-entity lists; session maps already bounded.
- **CPU Utilization**: Server thread: Circuit walk + Faraday tick. Client: render-thread entity query + line boxes. Both should fall under a few ms after Phase 1–2.
- **Concurrency / Throughput**: More origin players can sit in Circuit / Faraday without tick-skip. No thread-pool changes (and none should be added for world access).

---

## Performance Monitoring Setup Recommendations

### Observability Recommendations

- **Metrics**: Spark (Forge) sampler on the dedicated server. Watch MSPT, `minecraft:entities`, block `getBlockState` samples inside `CircuitService.refreshSight`, particle packet rates. No Prometheus/Datadog in this stack.
- **Logging**: Keep activate/fail logs. Do not add per-tick logs. A debug command should print Circuit mark count, Faraday `HELD` size, Field radius, Scan `MARKED` size.
- **Distributed Tracing**: Not applicable (single process). Treat one 50 ms server tick as the trace: Origins AOT → `ServerTickEvent` (Circuit / Faraday / Control / Dust) → packets.

### Recommended Performance Tracking

- MSPT / TPS: idle origin players vs Circuit walking a factory vs Faraday 30 s vs Field r=30 vs Scan left on
- Circuit: `refreshSight` time, mark count, packet bytes, client frame time with 512 boxes
- Scan: client `getEntitiesOfClass` time at inflate 128 vs UUID resolve
- Faraday: living-in-r=10 count, held-shot count, particle packets/sec to r=24
- Alert if MSPT > 50 (TPS < 20) for >2 s with a single Circuit player in a redstone room

### Performance Testing Recommendations

- Load: redstone factory (clocks + hoppers), village, skeleton / TACZ range, 4–8 origin players
- Lifecycle: Circuit mode on/off, toggle 5 wires, logout, dimension change, `/reload`
- Tools: Spark `--timeout 60`; F3 + pie / entity counts; particle slider Minimal vs All; compare Circuit mark cap 128 vs 512
- Do not treat this static report as a substitute for those numbers

---

## Summary

This performance analysis identified **1 critical**, **4 high**, **8 medium**, and **7 low** performance issues across the Minecraft server+client tick stack.

**Key Strengths Identified**:

- Prior Critical scan-volume + server-wide `force @a` storm is still gone; Scan is a one-shot Java r=32 pulse + private S2C
- Faraday projectile hold is Java `HELD` (global `@e` + NBT hold **fixed**); particle viewers are distance-limited
- Control Field sweep interval 4; `spawnHeadRing` is 4 batched packets; lock cone runs before LOS
- Session maps early-out when empty; logout/login/dim cleanup exists for Circuit, Faraday, Field, Dust, Scan
- Charge spend is Java-owned and atomic; mode debounce is 4 ticks
- Radial HUD does no work when closed; packets are main-thread and `DistExecutor`-safe

**Critical Areas Requiring Immediate Attention**:

- Circuit always-on N³ r=16 walk + 512 S2C marks (`P-001`)
- Circuit per-tick neighbor re-pulse for 160 ticks (`P-002`)
- Scan client 128-block query every render frame (`P-003`)
- 512 depthless Circuit line boxes through a 256-byte buffer (`P-004`)
- Faraday every-tick r=10 living repel + all-dimension held-shot hunt (`P-005`)

**Expected Overall Performance Improvement**: Phase 1–2 should put Circuit factory walks and Faraday cages back under a few milliseconds of the 50 ms tick, and stop Scan/Circuit from owning the client render thread. Combined with VFX work already in tree, remaining cost is query-shaped (blocks/entities), not particle-storm-shaped. Confirm with Spark in a redstone factory before calling Circuit done.
