## Executive Summary

### Audit Overview

- **Target System**: Static Resonance (`static_resonance` / `com.staticresonance`) — Minecraft Forge 1.20.1 Origins hybrid (Java + datapack)
- **Analysis Date**: 2026-08-16 21:45
- **Analysis Scope**: Server tick (20 TPS / 50 ms budget), client tick + `RenderLevelStageEvent`, S2C SimpleChannel packets, Origins/Apoli `action_over_time` / `execute_command`, Faraday/sense datapack functions. Compared against `docs/performance/2026-08-15-165558-performance-audit.md`, `docs/performance/2026-08-15-184634-performance-audit.md`, and `docs/performance/2026-08-16-210500-performance-audit.md`. Just-landed Circuit / Scan / Faraday / SeeThrough patches were re-read in live source and **did land**.
- **Technology Stack**: Minecraft 1.20.1, Forge 47.2.0, Origins/Apoli (Forge + Fabric-via-Connector), Java 17, Gradle, scoreboard + Apoli resources as persistence, SimpleChannel protocol `"9"`

### Performance Assessment Summary

| Performance Level | Count | Percentage |
|-------------------|-------|------------|
| Critical Issues   | 0     | 0%         |
| High Impact       | 2     | 12%        |
| Medium Impact     | 8     | 47%        |
| Low Impact        | 7     | 41%        |
| **Total**         | **17** | **100%**  |

### Key Analysis Results

- **Performance Anti-Patterns**: 0 critical patterns remaining. The 210500 Critical (Circuit always-on N³ + 512 S2C) is **improved** (section walk + `hasOnlyAir` skip + `MOVE_SNAP=4` + `MAX_MARKS=128`) but the inner block walk is still High in packed factories. Prior scan-volume / `force @a` storm is still gone.
- **Code Optimization Opportunities**: 2 high-impact remaining (Circuit palette-less voxel walk; Faraday dual AOT + always-on EventBus + r=10 living query every 2 ticks). Client 128-query, 512-box flush storm, and all-dimension held-shot hunt **landed**.
- **Architecture Assessment**: 8/10 (session maps, dirty-flag Circuit refresh, snapped origin, Faraday 2-tick catch/repel, dim-pinned `HeldShot`, client `CACHED` list, 256 KB see-through buffer). Dual Faraday tick authority and unindexed Circuit volume walk remain.
- **Overall Code Performance Score**: 73/100 (was 66/100 at 21:05 after Circuit/Faraday landed as new cost; 74/100 on 2026-08-15 after VFX cleanup). The five named patches pulled the score back; remaining factory Circuit walk keeps it just under the prior peak.

**Already fixed vs still true (prior audits):**

| Prior finding | Status now |
|---------------|------------|
| 128-block filled dust + `color_entity` recast (2026-08-15-165558 P-001) | **Fixed.** Scan is a one-shot Java `getEntitiesOfClass` at r=32; rim + heartbeat VFX only. |
| Bare `force @a` particle fan-out | **Fixed.** Remaining particles use `force @a[distance=..N]`. |
| Faraday global `@e` hold + NBT Motion every 5 ticks | **Fixed.** `hold_projectiles.mcfunction` is not called from `tick_live`. Java `HELD` UUID map freezes shots. |
| Control Field `getEntitiesOfClass` every tick | **Improved.** `SWEEP_INTERVAL = 4`. Still a Medium (r=30 living sweep). |
| `spawnHeadRing` ~84 positioned `sendParticles` | **Fixed.** Four batched calls. |
| Magnetic Lock LOS-before-cone on 64-block cube | **Improved.** `LookTargets` tests cone/dot before `hasLineOfSight`. AABB is still 64. |
| Scan public Glowing / HP dust | **Fixed.** Scanner-only `ScanOutlinePacket`. |
| Dust double `@e` selector | **Fixed.** Java `DustService` one AABB. |
| 210500 P-001 Circuit N³ r=16 + 512 marks | **Improved, still High.** `MOVE_SNAP=4`, `MAX_MARKS=128`, section walk skips `hasOnlyAir`. Palette occupancy **not** implemented — packed sections still walk ~17k blocks. |
| 210500 P-002 `applyForce` neighbor re-pulse × 160 | **Fixed.** Idle path `alreadyForced` returns before `updateNeighborsAt` / `neighborChanged`. |
| 210500 P-003 Scan client inflate 128 every frame | **Fixed.** `inflate(SCAN_RADIUS)` (32); `CACHED` rebuilt once per client tick. Residual AABB query is Low. |
| 210500 P-004 512 boxes / 256-byte buffer | **Improved.** Buffer is `256 * 1024`; marks capped at 128. No LOD/frustum — Low leftover. |
| 210500 P-005 Faraday every-tick r=10 + all-dim hunt | **Improved.** `tickRepel`/`catchNearby` every 2 ticks; `HeldShot` stores `ResourceKey<Level> dim`. r=10 query + EventBus/AOT remain Medium/High. |
| Unconditional `faraday_tick`, motion NBT harvest, radial `ItemStack`s, `performPrefixedCommand`, Fabric reflection HUD reads, unused datapack | **Still true.** |

**Just-landed patch verification (live source, 2026-08-16 21:45):**

| Claimed patch | Live source | Landed? |
|---------------|-------------|---------|
| `CircuitService.MOVE_SNAP = 4` | `CircuitService.java:71` | **Yes** |
| `CircuitService.MAX_MARKS = 128` | `CircuitService.java:72` | **Yes** |
| Section walk skipping `hasOnlyAir` | `CircuitService.java:353-367` | **Yes** |
| `alreadyForced` skip on idle `applyForce` | `CircuitService.java:517-519` | **Yes** |
| `ScanOutlineClient` inflate `SCAN_RADIUS` (32) | `ScanOutlineClient.java:167` | **Yes** |
| `CACHED` rebuilt once per client tick | `ScanOutlineClient.java:90`, `162-170` | **Yes** |
| `SeeThroughRender` buffer `256 * 1024` | `SeeThroughRender.java:19` | **Yes** |
| Faraday `tickRepel`/`catchNearby` every 2 ticks | `FaradayService.java:186-189` | **Yes** |
| `HeldShot` stores `ResourceKey<Level> dim` | `FaradayService.java:524-533`, `442-445` | **Yes** |

---

## Analysis Methodology

### Performance Analysis Approach

- **Static Code Analysis**: Re-read the mandated services/clients/UI plus tick neighbors (`DustService`, `ClimbService`, `LookTargets`, `ModeService`, `AllyService`, `CastService`, packets, Origins powers, Faraday/sense/systems functions). Did not assume the 21:05 findings still held — each named patch was confirmed in current source.
- **Tick-model query analysis**: Counted per-tick `getEntitiesOfClass` / `getEntities`, remaining N³ `getBlockState` walks, `@e` selectors, NBT `data get`/`data modify`, and S2C packet builders against the 50 ms server tick and the client render thread.
- **Resource Utilization Assessment**: Session maps, uncleared UUID sets, particle/line-box GPU cost, SimpleChannel payload size.
- **Architecture Performance Review**: Dual tick authorities (Origins AOT vs Forge `ServerTickEvent`), EventBus subscribers that fire when idle, command-string vs function manager.

### Analysis Coverage

- **Files Analyzed**: 38 Java sources under `src/main/java/com/staticresonance`, 38 `*.mcfunction`, ~35 datapack JSON (powers/tags/origin), plus the three prior audit reports.
- **Database Queries Reviewed**: Not applicable as SQL. Analog: 8 `getEntitiesOfClass`/`getEntities` sites, 1 remaining N³-in-section block walk, 5 `@e` datapack selectors still on granted paths (`motion_harvest`, `scan_stop` ×2, Faraday `deactivate` release), 3 NBT Motion reads in `motion_try`.
- **API Endpoints Analyzed**: Not applicable as HTTP. Analog: SimpleChannel protocol `"9"` (10 packet types), `sr_internal` Brigadier tree, Origins `active_self` / `action_over_time` powers on the origin list.
- **Performance Patterns Checked**: Per-tick loops, remaining N³ voxel walks, `getEntitiesOfClass`, particle spam, S2C rates, client outline queries, Origins `execute_command`, uncleared maps, idle EventBus subscribers.

### Analysis Capabilities

- **Pattern Detection**: N+1 selectors, inefficient loops, memory leaks, blocking command parse, EventBus idle cost.
- **Database Analysis**: Does not apply (no SQL). Scoreboard/`@e`/chunk-section access is the equivalent.
- **Resource Analysis**: MSPT, client frame time, particle/line-box overdraw, packet objects.
- **Architecture Assessment**: Session maps, dirty flags, dual tick authorities, Apoli read caching.
- **Frontend Analysis**: Not a web app. Client HUD + `RenderLevelStageEvent` line boxes + local particles are the analog of “frontend”.

---

## Performance Findings

### Critical Performance Issues

No Critical issues identified.

The 21:05 Critical (Circuit always-on N³ + 512 S2C marks) dropped below Critical after `MOVE_SNAP=4`, `MAX_MARKS=128`, and `hasOnlyAir` section skip. Standing still no longer walks the sphere. Packed factory floors still pay the volume walk (P-001, High). The 2026-08-15 Critical scan-volume / server-wide `force @a` storm is still absent.

### High Performance Impact Findings

#### P-001: Circuit sight still walks every block in non-air sections of a 16-block sphere

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:335-411`
**Performance Impact**: 8.0 (High)
**Pattern Detected**: Remaining N³ voxel walk — section iteration skips only `hasOnlyAir`; packed palettes still visit ~17k `getBlockState` + tag tests
**Code Context**:

```java
private static final int MOVE_SNAP = 4;
private static final int MAX_MARKS = 128;
// refreshSight:
var section = chunk.getSection(sectionIndex);
if (section.hasOnlyAir()) {
    continue;
}
for (int y = y0; y <= y1; y++) {
    for (int x = x0; x <= x1; x++) {
        for (int z = z0; z <= z1; z++) {
            if (dx * dx + dy * dy + dz * dz > rSq) continue;
            BlockState state = section.getBlockState(lx, ly, z & 15);
            if (!state.is(CIRCUIT_TAG)) continue;
            found.add(new CircuitOutlinePacket.Entry(pos, isWorking(level, pos, state, inf)));
        }
    }
}
```

**Impact**: The 21:05 Critical **did land its budget knobs**: origin must move 4 Manhattan blocks (or `dirty`) before a 20-tick rescan, marks cap at 128, empty cave/sky sections skip. What did **not** land is palette occupancy. `circuit.json` includes wire, every wood button/plate, hoppers, pistons, rails, iron doors. A factory / village section is almost never `hasOnlyAir`, so `startSight` (mode select, login, dim change) and every walk-rescan still test the full sphere (~17k blocks). `isWorking` still falls through to `level.hasNeighborSignal(pos)` for note blocks and other untagged-property hits. `found.sort` + `subList` still allocate when the factory exceeds 128 circuit blocks.
**Performance Cost**: Estimated 1–8 ms server-thread on a loaded redstone factory (down from 2–12 ms). Packet ~1–1.5 KB per refresh (128 `BlockPos` + boolean). Air/ocean Circuit play is now cheap. Multiple Circuit players still multiply the walk.
**Recommendation**: Skip sections whose `PalettedContainer` has no `#static_resonance:circuit` member. Early-out the inner loops once `found.size() == MAX_MARKS` if already sorted by distance (or collect in a bounded nearest heap). Cache last section occupancy for a few seconds.
**Fix Priority**: Immediate

#### P-002: Faraday still has two tick authorities and always-on combat EventBus; r=10 living query remains

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:186-228`, `399-419`, `447-499` and `src/main/resources/data/static_resonance/powers/systems/faraday_tick.json:1-8`
**Performance Impact**: 7.2 (High)
**Pattern Detected**: Unconditional Origins AOT + HIGHEST EventBus with no empty-map early-out + r=10 `LivingEntity` AABB every 2 ticks per cage
**Code Context**:

```java
if (now % 2 == 0) {
    tickRepel(player, now);   // REPEL_RADIUS = 10.0
    catchNearby(player);
}
// onProjectileImpact / onAttack / onHurt — no ACTIVE_CAGES.isEmpty() guard
if (shouldBlock(event.getProjectile())) {
    event.setCanceled(true);
}
// isCustomGunshot: ForgeRegistries.ENTITY_TYPES.getKey + path.contains on every miss
```

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

**Impact**: Interval-2 catch/repel and dim-pinned `HeldShot` **landed** (21:05 P-005 High is no longer every-tick / all-dimension). Remaining: (1) every origin player still dispatches `function static_resonance:faraday/tick` 4 times/sec with no Origins condition; (2) four HIGHEST handlers fire for the whole server even when `ACTIVE_CAGES` and `HELD` are empty — `isCustomGunshot` hits `ForgeRegistries` + string `contains` for non-`Projectile` entities; (3) while a cage is up, `tickRepel` still walks a 20×4×20 living box and `setPos` + `hurtMarked` in the hard ring; (4) `tickHeld` still `freeze()` (`setPos` + `hurtMarked`) **every** server tick for 35 ticks per shot, dirtying trackers. `tick_live` still reapplies 6 s Resistance/Fire Resistance and 11 forced particles to r=24.
**Performance Cost**: ~0.05–0.2 ms × origin players always (AOT). EventBus: tens to hundreds of µs per combat event; worse with TACZ + many arrows. Cage-up: 0.2–2 ms/tick in a village/raid (down from 0.3–3). Tracker spam from `freeze()` is extra.
**Recommendation**: Condition `faraday_tick` on `sr_faraday > 0 OR sr_faraday_cd > 0`, or delete the power once Java owns cooldown. `if (ACTIVE_CAGES.isEmpty() && HELD.isEmpty()) return;` at the top of each combat handler. Cache `isCustomGunshot` per `EntityType`. Keep catch r=4.8; consider a tighter hard-wall AABB for repel. Freeze motion without `setPos` every tick.
**Fix Priority**: Immediate

### Medium Performance Impact Findings

#### P-003: Control Field still sweeps all living entities to r=30 and emits one particle packet per rim point

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

**Impact**: Prior “every tick” High is **fixed** (`SWEEP_INTERVAL = 4`). Remaining: AABB grows to 60×5×60, includes animals/villagers, reapplies 3 synced effects when remaining duration < 20, and `spawnFieldEdge` still does up to 24 `RING_DUST` + 8 `WITCH` packets every 4 ticks for 1200 ticks. `AllyService.isProtected` reparses NBT ally lists when the candidate is a player (`readAllies` allocates a new `HashSet` + walks a `ListTag` every time).
**Performance Cost**: 0.1–1 ms per field every 4 ticks in a village; ~8 particle packets/tick at full radius.
**Recommendation**: Batch rim into 1–2 `sendParticles`. Optionally restrict to `#static_resonance:affectable`. Cache ally UUIDs on the caster. Keep r=30 and the 4-tick cadence.
**Fix Priority**: Within 1 month

#### P-004: Look-ray fallback still inflates a 64-block cube of living entities

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

**Impact**: Used by Control lock, Dust burst/zone, and radial ally snapshot (`RadialModeMenu.snapshotLookedPlayer`). Fast path `ProjectileUtil` is cheap. Miss path still walks a 128³ living set. Cone-before-LOS **fixed** the old 7.3 High. Dust and lock on a farm can still hitch the server thread (or the client, on radial open) on a miss.
**Performance Cost**: 0.5–5 ms hitch per miss in dense pens (down from 1–15 ms when LOS ran first).
**Recommendation**: Cap candidates (closest 16) or inflate 32 and keep `TARGET_RANGE = 64` only for the precise ray.
**Fix Priority**: Within 1 month

#### P-005: Motion harvest still NBT-reads Motion triples on a wide passive tag every second

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

#### P-006: Circuit `NeighborNotifyEvent` is subscribed for every redstone update in the world

**Location**: `src/main/java/com/staticresonance/mode/CircuitService.java:231-240`
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

**Impact**: Lookup is O(1), but Forge still dispatches this subscriber for **every** neighbor notify on the server (clocks, hoppers, observers) even when no player has Circuit interferences. The idle `applyForce` storm that used to *generate* these events is **fixed** (P-002 21:05); the idle subscriber remains. `onNote` is the same shape on a cooler event.
**Performance Cost**: Small per event; measurable in redstone-heavy worlds as EventBus overhead.
**Recommendation**: Return immediately when `INTERFERENCES.isEmpty()`. Pair with a static empty-flag if map checks show up in Spark.
**Fix Priority**: Within 1 month

#### P-007: Radial HUD still tessellates many immediate strips and allocates `ItemStack`s every frame

**Location**: `src/main/java/com/staticresonance/client/ui/RadialModeMenuRenderer.java:32` and `162-168`
**Performance Impact**: 4.5 (Medium)
**Pattern Detected**: Per-frame allocation + many `tesselator.end()` — **still true**, menu-open only
**Code Context**:

```java
private static final int SEGMENTS_PER_WEDGE = 24;
ItemStack stack = new ItemStack(wedge.iconItem());
graphics.renderItem(stack, ix, iy);
```

**Impact**: Hold-R only (good; early-return when closed). Five wedges × (fill + hover rim + edge) + center disc/rings + three full-screen scrims. Each sector is a 25-iteration `TRIANGLE_STRIP` with immediate `tesselator.end()`. Open also snapshots a 64-block look (P-004).
**Performance Cost**: 0.3–2 ms per HUD frame on integrated GPUs.
**Recommendation**: Cache static `ItemStack`s; `SEGMENTS_PER_WEDGE = 12`; one scrim.
**Fix Priority**: Within 2 months

#### P-008: `ClimbService` calls `hasModePower` on every player every tick

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

**Impact**: Runs for **all** players (including non-origin) on both logical sides. Non-origin players still pay `ApoliResources.hasPower` (Forge container or Fabric `Method.invoke`) 20 times/sec. `ControlService.onPlayerTick` is cheaper (`HANDS.containsKey` first) but still visits every server player. `CastKeyHandler` / `RadialModeMenu` also `hasModePower` every client tick (P-014).
**Performance Cost**: Tens of µs × player count (Forge) to low hundreds (Connector) per tick, always-on.
**Recommendation**: Check `horizontalCollision` before Apoli. Cache `hasModePower` for 10 ticks. Skip client if server-only sparks are the only reason to be on the server thread (movement must stay on both sides for prediction).
**Fix Priority**: Within 2 months

#### P-009: Faraday `freeze` dirties entity trackers every tick for each held shot

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:335-363` and `387-392`
**Performance Impact**: 4.4 (Medium)
**Pattern Detected**: Per-tick `setPos` + `hurtMarked` on an already-frozen entity
**Code Context**:

```java
private static void freeze(Entity entity) {
    entity.setDeltaMovement(Vec3.ZERO);
    entity.hasImpulse = true;
    entity.hurtMarked = true;
    entity.setPos(entity.getX(), entity.getY(), entity.getZ());
}
```

**Impact**: `tickHeld` is **not** on the 2-tick interval — it runs every server tick for up to `HOLD_TICKS = 35`. `setPos` to the same coordinates plus `hurtMarked` forces a tracker sync to every player who can see the arrow. Dim lookup is now O(1) (patch landed). Spark VFX every 5 ticks is modest.
**Performance Cost**: Tracker packets × held shots × nearby players for 1.75 s per catch. Worse in a skeleton/TACZ hail.
**Recommendation**: Set motion + `NoGravity` on catch; on later ticks only re-zero motion if it drifted. Do not `setPos`/`hurtMarked` unless the entity moved.
**Fix Priority**: Within 1 month

#### P-010: Scan outline client still AABB-queries living entities instead of resolving UUIDs

**Location**: `src/main/java/com/staticresonance/client/ScanOutlineClient.java:162-170`
**Performance Impact**: 4.0 (Medium)
**Pattern Detected**: Client-tick `getEntitiesOfClass` while a scan can stay armed indefinitely
**Code Context**:

```java
AABB search = player.getBoundingBox().inflate(ScanOutlineService.SCAN_RADIUS);
CACHED.addAll(level.getEntitiesOfClass(LivingEntity.class, search,
        e -> e.isAlive() && MARKED.contains(e.getUUID())));
```

**Impact**: The 21:05 High **landed**: inflate is 32 not 128, rebuild is once per client tick not every frame, flecks iterate `CACHED`. Residual: work is still O(living in 64³), not O(marks). `MARKED` only grows (`addAll`) until stop/logout, so the set can stay larger than what the AABB returns. Render still draws **two** see-through boxes per cached living entity.
**Performance Cost**: 0.05–0.8 ms client tick in a populated overworld (down from 0.3–4 ms/frame). Farms inside 32 still pay.
**Recommendation**: Resolve via `((ClientLevel) level).getEntities().get(id)` (or equivalent UUID lookup). Replace `MARKED` each pulse instead of union.
**Fix Priority**: Within 1 month

### Low Performance Impact Findings

#### P-011: Circuit client still draws up to 128 depthless line boxes every frame with no frustum cull

**Location**: `src/main/java/com/staticresonance/client/CircuitOutlineClient.java:48-96` and `src/main/java/com/staticresonance/client/SeeThroughRender.java:15-19`
**Performance Impact**: 3.8 (Low)
**Pattern Detected**: Immediate line boxes; buffer-flush storm **fixed**
**Code Context**:

```java
private static final RenderType LINES = create(
        "sr_see_through_lines", ..., 256 * 1024, false, false, ...);
// CircuitOutlineClient.onRender:
for (Map.Entry<BlockPos, Boolean> mark : MARKS.entrySet()) {
    AABB box = new AABB(pos).inflate(aim ? 0.03 : 0.004);
    LevelRenderer.renderLineBox(pose, lines, box, r, g, b, a);
}
buffers.endBatch(type);
```

**Impact**: 21:05 High **mostly fixed**: buffer is 256 KB (one flush for 128 boxes × 24 segments), mark cap is 128 not 512. Remaining: no behind-camera skip, `player.pick` every frame, Circuit sight is on for the whole mode. Scan overlay (two boxes per living) can stack.
**Performance Cost**: 0.3–2 ms GPU/CPU on a factory floor (down from 1–8 ms).
**Recommendation**: Skip marks behind the camera; optional cheap debug-line strip. Keep the 256 KB buffer.
**Fix Priority**: Within 2 months

#### P-012: Mode / Faraday / Scan still dispatch functions via `performPrefixedCommand`

**Location**: `src/main/java/com/staticresonance/mode/ModeService.java:105`, `FaradayService.java:502-509`, `ScanOutlineService.java:91-98`
**Performance Impact**: 3.4 (Low)
**Pattern Detected**: String command parse — **still true**
**Code Context**:

```java
server.getCommands().performPrefixedCommand(source, "function " + mode.feedbackFunction());
```

**Impact**: Not per-tick. Mode switch, Faraday activate/deactivate/emergency, scan start/stop each parse Brigadier and run particle functions. `scan_start` also calls `sr_internal scan_clear` (a second parse) before Java `pulse`.
**Performance Cost**: Sub-millisecond per cast; dominated by the particles they spawn.
**Recommendation**: `server.getFunctions().execute` with a cached `ResourceLocation`.
**Fix Priority**: Within 2 months

#### P-013: Client tick / HUD read Apoli resources every tick (Fabric path is reflection)

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

#### P-014: Faraday score reads still allocate via `getOrCreatePlayerScore`

**Location**: `src/main/java/com/staticresonance/mode/FaradayService.java:516-522`
**Performance Impact**: 3.0 (Low)
**Pattern Detected**: Write-on-read — **still true**; also used by `ActionBars.show` and `nearestCage`
**Code Context**:

```java
return board.getOrCreatePlayerScore(player.getScoreboardName(), obj).getScore();
```

**Impact**: `isCageActive` / `canActivateCage` on every Faraday tick, every actionbar write (all modes, to suppress other bars), every `nearestCage` candidate, and HUD-adjacent server messages.
**Performance Cost**: Small map churn if the score is missing; otherwise a map get.
**Recommendation**: Read-only lookup; create scores only in `activate`.
**Fix Priority**: Within 2 months

#### P-015: Dead scan-step powers, 64/128 rims, and leftover Faraday hold functions still ship

**Location**: `src/main/resources/data/static_resonance/powers/systems/scan_step_1.json` (and 2–4); `functions/sense/scan_pulse.mcfunction:14-15`; `scan_stop.mcfunction:4-5`; `functions/faraday/hold_projectiles.mcfunction`
**Performance Impact**: 2.4 (Low)
**Pattern Detected**: Load-time parse + leftover wide selectors; steps are **not** on `origins/static_resonance.json`
**Code Context**:

```mcfunction
execute if score @s sr_scan matches 65..128 run function static_resonance:sense/scan_rim_128
# scan_stop:
tag @e[tag=sr_scanned,distance=..128] remove sr_scanned
tag @e[tag=sr_scan_pinged,distance=..128] remove sr_scan_pinged
```

**Impact**: `scan_step_1`–`4` are not granted, so they no longer poll every 5 ticks. They still parse on `/reload`. `scan_abort` **is** granted and evaluates `scan_active` + mode every 5 ticks. `scan_stop` still sweeps a 128 cylinder for shared tags even though live pulses no longer set `sr_scanned`. `hold_projectiles` / `color_entity` / `switch_mode` / `pulse_explosion` / empty `lock_apply` / `overcharge/pulse` / `conductive.json` remain uncalled from the live origin.
**Performance Cost**: Load/reload noise; 0.05–0.2 ms AOT abort poll; rare 128 `@e[tag]` on stop.
**Recommendation**: Delete unused step powers and rim_64/rim_128, or grant a single Java-driven expand. Bound stop to 32. Archive uncalled Faraday hold helpers.
**Fix Priority**: Within 2 months

#### P-016: Faraday live tick still reapplies 6-second effects every 5 ticks

**Location**: `src/main/resources/data/static_resonance/functions/faraday/tick_live.mcfunction:1-4`
**Performance Impact**: 2.8 (Low)
**Pattern Detected**: Redundant effect refresh — **still true** (datapack actionbar title was removed)
**Code Context**:

```mcfunction
effect give @s minecraft:resistance 6 4 true
effect give @s minecraft:fire_resistance 6 0 true
function static_resonance:faraday/particles
```

**Impact**: `activate` already applies 30 s effects. Live tick overwrites them 4 times/sec for 30 s (`sr_faraday = 600`). Java `showCountdown` already owns the actionbar every 10 ticks. Particles are distance-limited (good).
**Performance Cost**: Minor effect-map + packet churn.
**Recommendation**: Refresh every 40–80 ticks or rely on the 30 s grant.
**Fix Priority**: Within 2 months

#### P-017: Scan `MARKED` only grows; pulses resend the full UUID list

**Location**: `src/main/java/com/staticresonance/mode/ScanOutlineService.java:101-130`
**Performance Impact**: 2.6 (Low)
**Pattern Detected**: Uncleared set until logout/stop; full-list S2C
**Code Context**:

```java
Set<UUID> marked = MARKED.computeIfAbsent(scanner.getUUID(), id -> new HashSet<>());
// pulse adds every living in r=32; never removes leavers
ModNetwork.CHANNEL.send(..., new ScanOutlinePacket(false, seen));
```

**Impact**: Logout/login/respawn/stop clear the map (good). While scan is up, dead or far entities stay in `MARKED`. Login/respawn re-pulse. Client `MARKED.addAll` never evicts until `clearAll`. `scan_start` calls `sr_internal scan_clear` then Java `pulse` — two packets on start.
**Performance Cost**: Tiny memory; client AABB (P-010) stays large because the set never shrinks.
**Recommendation**: Replace the set each pulse; send a snapshot; drop UUIDs the client cannot resolve for N seconds.
**Fix Priority**: Within 2 months

---

## Code Pattern Performance Analysis

### Performance Anti-Pattern Detection

- **N+1 Query Patterns**: 3 instances (`isWorking` → `hasNeighborSignal` per untagged circuit hit; Faraday `isCustomGunshot` registry lookup per intercept candidate; Ally `readAllies` NBT per player candidate in Field).
- **Blocking Operations**: 2 parse/reflection costs (`performPrefixedCommand`; Fabric `Method.invoke` HUD/tick). Not candidates for worker threads — world state stays on the server/client tick threads.
- **Memory Pressure Points**: Circuit `ArrayList` of up to 128 packet entries + `HashMap` rebuild each scan; radial per-frame `ItemStack` + tessellator begins; `new ArrayList<>(SIGHTS/SESSIONS/ACTIVE_CAGES.keySet())` each tick when non-empty.
- **Inefficient Data Access**: Circuit N³-in-section `getBlockState`; Faraday r=10 every 2 ticks; motion NBT triples; LookTargets 64 inflate; Scan client AABB instead of UUID resolve.
- **Concurrency Issues**: 0 real races (server tick is single-threaded). `ConcurrentHashMap` on `SIGHTS`/`INTERFERENCES`/`HELD`/`SESSIONS` is conservative and safe.

### Database Access Pattern Analysis

- **Does not apply** as SQL/ORM/connection pools. This is a Minecraft dedicated-server + integrated client. Persistence is scoreboard objectives (`SrScoreboards.ensureAll`), Apoli resource powers, and a small persistent-data ally list.
- **“Query” stand-ins reviewed**: 8 Java entity AABB queries, 1 remaining N³-in-section block walk, granted `@e` in `motion_harvest` and `scan_stop` / Faraday deactivate release, 3 NBT Motion reads.
- **Missing indexes**: Vanilla chunk sections are the only spatial index. Circuit now skips `hasOnlyAir` sections but still ignores palettes and walks every occupied block.
- **Missing async patterns**: 0. Entity/block access cannot leave the server thread.

### Resource Management Pattern Analysis

- **Memory Allocation Patterns**: Session maps (`SESSIONS`, `ACTIVE_CAGES`, `SIGHTS`, `ZONES`, `HELD`, `HANDS`) are player-bounded and cleared on logout — good. Circuit `Sight.marks` rebuilds a `HashMap` per scan. Scan `MARKED` is cleared on stop/logout but not pruned while live.
- **Garbage Collection Pressure**: Circuit packet lists, field-edge particle packets, radial open frames, Faraday `freeze` tracker dirties. Scan no longer allocates per-entity particle storms.
- **Thread / Event Loop Usage**: All gameplay on the server thread (correct). Client outline work is on the render thread during `RenderLevelStageEvent` — now O(cached marks), which is the right place after the 21:05 patch.
- **Caching Opportunities**: Apoli `hasModePower` / charge / style; Circuit section occupancy / palette; Faraday `EntityType` gunshot flag; ally UUID set.
- **Resource Disposal**: Logout hooks exist for sights, cages, sessions, hands, zones, scan marks, client `MARKS`. No leaked server maps found. Scan client marks persist until explicit clear (intended).

---

## Architecture Performance Assessment

### Data Access Layer Analysis

- Not a database layer. Scoreboard objectives are created once (`SrScoreboards`) — healthy. Faraday `getOrCreatePlayerScore` is still a write-capable read (P-014).
- Entity/block access is unindexed world walking. Distance-limited selectors are used for Dust (r=6), motion (r=5), Faraday catch (4.8), and all remaining particle viewers. Circuit (r=16 sphere of **blocks** in non-air sections) is the access that still ignores a real budget.
- Apoli reads have no TTL cache.

### Application Layer Analysis

- Java owns charge spend for Control / Faraday / Dust / Scan / Circuit (atomic). Session maps avoid scanning the whole player list when empty (`SIGHTS.isEmpty()`, `SESSIONS.isEmpty()`, `ZONES.isEmpty()`, `ACTIVE_CAGES` after `tickHeld`).
- Dual tick authorities remain for Faraday (Origins AOT + Java). Circuit, Control Field, and Dust are Java-only ticks — the right direction.
- Circuit interference no longer fights vanilla every tick (`alreadyForced` skip landed). First-pulse neighbor update is cast-only.
- Functions still go through `performPrefixedCommand` (P-012).
- Mode-change debounce (4 ticks) is a good anti-spam control.
- Network protocol `"9"` adds Circuit S2C. Handlers use `DistExecutor` + main-thread consumers — correct dedicated-server split.

### Frontend Analysis (if applicable)

- **Not a web frontend.** Core Web Vitals, JS bundle, hydration, and CLS **do not apply**.
- Client surface: `RadialModeMenu` / `RadialModeMenuRenderer` (hold-R only), `ScanOutlineClient`, `CircuitOutlineClient`, `LockHandsClient`, `SeeThroughRender`.
- Equivalent risks: HUD frame time, see-through line overdraw, local particle flecks. The leftover 128-block search radius **is gone**.
- Textures are a few GUI icons. No extra asset pipeline cost.
- Menu early-returns when closed — good. Circuit/Scan overlays early-return on empty maps. Scan render uses the per-tick `CACHED` list (good). Circuit still has no frustum cull (P-011).

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
| 1 | Circuit `refreshSight` | N³-in-section walk; no palette skip | 8.0 | 1–8 ms / walk-rescan in factories; 128 S2C marks |
| 2 | Faraday remaining | Dual AOT + EventBus + r=10 / 2 ticks | 7.2 | 0.05–0.2 ms × players always; 0.2–2 ms/tick per cage |
| 3 | Control Field | Living sweep r=30 / 4 ticks + rim packets | 5.8 | 0.1–1 ms / 4 ticks + ~8 pkt/tick |
| 4 | LookTargets miss | 64-block living cube | 5.5 | 0.5–5 ms hitch on miss |
| 5 | Motion harvest | NBT Motion triples / 20 ticks | 4.8 | 0.1–1.5 ms/s in farms |
| 6 | Circuit EventBus | `NeighborNotify` always dispatched | 4.6 | EventBus overhead in clock farms |
| 7 | Faraday `freeze` | `setPos`/`hurtMarked` every tick × 35 | 4.4 | Tracker packets per held shot |
| 8 | Radial HUD | Per-frame `ItemStack` + tessellator | 4.5 | 0.3–2 ms hold-R only |

---

## Technical Recommendations

### Immediate Performance Fixes

1. Skip Circuit sections whose palette has no `#static_resonance:circuit` member; early-out at `MAX_MARKS` (P-001).
2. Condition or remove `faraday_tick` AOT; EventBus early-out when `ACTIVE_CAGES` and `HELD` are empty; cache `isCustomGunshot` (P-002).
3. Stop Faraday `freeze()` from `setPos`/`hurtMarked` when motion is already zero (P-009).

### Performance Enhancements

1. Batch `spawnFieldEdge`; cache allies (P-003).
2. Cap LookTargets cone candidates (P-004).
3. Resolve scan marks by UUID on the client; snapshot-replace `MARKED` (P-010, P-017).
4. LOD Circuit boxes behind the camera (P-011).

### Architecture Improvements

1. One tick authority per ability (Java), datapack only for one-shot VFX.
2. EventBus early-outs when `INTERFERENCES` / `ACTIVE_CAGES` / `HELD` are empty (P-002, P-006).
3. Cache Apoli reads on client; invoke functions via the function manager (P-012, P-013).
4. Delete ungranted scan-step powers and leftover 128 rims so radius 32 cannot silently grow back (P-015).

---

## Code Optimization Examples

### Skip Circuit sections without circuit palette members (references P-001)

**Before (Inefficient)**:

```java
var section = chunk.getSection(sectionIndex);
if (section.hasOnlyAir()) {
    continue;
}
for (int y = y0; y <= y1; y++) {
    for (int x = x0; x <= x1; x++) {
        for (int z = z0; z <= z1; z++) {
            BlockState state = section.getBlockState(lx, ly, z & 15);
            if (!state.is(CIRCUIT_TAG)) continue;
            found.add(new CircuitOutlinePacket.Entry(pos, isWorking(...)));
        }
    }
}
```

**After (Optimized)**:

```java
var section = chunk.getSection(sectionIndex);
if (section.hasOnlyAir() || !sectionHasCircuit(section)) {
    continue;
}
// Walk only this section. Stop collecting once found.size() == MAX_MARKS
// if candidates are already nearest-first (or use a bounded heap).
```

**Expected Improvement**: ~80–95% fewer `getBlockState` calls in villages/caves that are not factories; factory walks stay bounded by the 128-mark cap if collection is nearest-first.

### Faraday EventBus + AOT early-out (references P-002)

**Before (Inefficient)**:

```java
@SubscribeEvent(priority = EventPriority.HIGHEST)
public static void onProjectileImpact(ProjectileImpactEvent event) {
    if (shouldBlock(event.getProjectile())) {
        event.setCanceled(true);
    }
}
// faraday_tick.json: unconditional interval 5 → function static_resonance:faraday/tick
```

**After (Optimized)**:

```java
@SubscribeEvent(priority = EventPriority.HIGHEST)
public static void onProjectileImpact(ProjectileImpactEvent event) {
    if (ACTIVE_CAGES.isEmpty() && HELD.isEmpty()) return;
    if (shouldBlock(event.getProjectile())) {
        event.setCanceled(true);
    }
}
// faraday_tick.json: condition sr_faraday > 0 OR sr_faraday_cd > 0
```

**Expected Improvement**: Idle servers stop paying Faraday command dispatch and HIGHEST combat handlers. TACZ farms no longer registry-lookup every bullet when no cage is up.

### Resolve scan marks by UUID (references P-010)

**Before (Inefficient)**:

```java
AABB search = player.getBoundingBox().inflate(ScanOutlineService.SCAN_RADIUS);
CACHED.addAll(level.getEntitiesOfClass(LivingEntity.class, search,
        e -> e.isAlive() && MARKED.contains(e.getUUID())));
```

**After (Optimized)**:

```java
CACHED.clear();
for (UUID id : MARKED) {
    Entity e = level.getEntities().get(id);
    if (e instanceof LivingEntity living && living.isAlive()) CACHED.add(living);
}
```

**Expected Improvement**: Client work becomes O(marks) (~tens) instead of O(living in 64³) every tick while scan is armed.

### Freeze held shots without tracker spam (references P-009)

**Before (Inefficient)**:

```java
held.ticksLeft -= 1;
freeze(entity); // setPos + hurtMarked every tick
```

**After (Optimized)**:

```java
held.ticksLeft -= 1;
if (!entity.getDeltaMovement().equals(Vec3.ZERO)) {
    entity.setDeltaMovement(Vec3.ZERO);
    entity.hasImpulse = true;
}
```

**Expected Improvement**: Removes ~35 tracker syncs per caught arrow; hold feel stays the same.

---

## Performance Optimization Priorities

### Phase 1: Critical Performance Fixes

- [x] Circuit: `MOVE_SNAP=4`, `MAX_MARKS=128`, `hasOnlyAir` section skip (21:05 P-001 budget knobs — landed)
- [x] Circuit: stop per-tick neighbor re-pulse (`alreadyForced` — 21:05 P-002 landed)
- [x] Client scan: inflate 32, cache list once per tick (21:05 P-003 landed)
- [ ] Replace remaining Circuit volume walk with palette occupancy / nearest-heap (P-001)
- [ ] Faraday: condition AOT + EventBus empty early-out + cached gunshot flag (P-002)

### Phase 2: High Impact Optimizations

- [x] Larger see-through buffer (`256 * 1024`) + mark cap 128 (21:05 P-004 landed)
- [x] Faraday: interval catch/repel; store dim on `HeldShot` (21:05 P-005 landed)
- [ ] Faraday: `freeze` without per-tick `setPos` (P-009)
- [ ] Condition `faraday_tick` or delete it once Java owns cooldown (P-002, P-016)

### Phase 3: Medium Impact Improvements

- [ ] Batch Control Field rim particles; cache allies (P-003)
- [ ] Cap LookTargets cone candidates (P-004)
- [ ] Narrow `motion_sources` / Java motion read (P-005)
- [ ] Skip `NeighborNotify` subscriber when `INTERFERENCES` is empty (P-006)
- [ ] Cache radial `ItemStack`s; fewer sectors (P-007)
- [ ] Climb: collision before Apoli; cache `hasModePower` (P-008)
- [ ] Client scan UUID lookup + snapshot `MARKED` (P-010, P-017)

### Phase 4: Performance Monitoring and Fine-tuning

- [ ] Spark profile: Circuit factory walk, Faraday 30 s, Field r=30, Scan-on exploration
- [ ] Config: max circuit marks, Faraday query interval, Circuit palette skip toggle
- [ ] Function manager invoke (P-012); read-only Faraday scores (P-014); Apoli TTL (P-013)
- [ ] Delete unused scan-step/rims/hold_projectiles/switch_mode/pulse_explosion (P-015)
- [ ] LOD Circuit boxes (P-011); slower Faraday effect refresh (P-016)

---

## Estimated Performance Improvement Impact

### Performance Gains by Priority

| Priority Level | Expected Improvement | Implementation Complexity |
|----------------|---------------------|--------------------------|
| Critical Fixes | Circuit factory MSPT down another ~70–90% vs today's section walk; Faraday idle EventBus/AOT gone | Medium (palette scan + EventBus guards) |
| High Impact | Faraday cage tracker spam gone; Field/HUD smoother after batching | Low–Medium |
| Medium Impact | Smaller lock-miss hitch; less always-on Apoli/NBT | Low–Medium |
| Low Impact | Leaner `/reload`, slightly less command/score/GC noise | Low |

### Resource Utilization Improvements

- **Database Load**: Not applicable (no SQL). Entity/block “query” volume drops most from remaining P-001 / P-002 / P-010.
- **Memory Usage**: Smaller Circuit mark maps (already 128). Session maps already bounded. Scan `MARKED` still grows until stop.
- **CPU Utilization**: Server thread: Circuit walk + Faraday tick. Client: line boxes (buffer flush storm gone). Circuit factory walk is still the item that can eat several ms of the 50 ms tick.
- **Concurrency / Throughput**: More origin players can sit in Faraday without EventBus tax after P-002. Circuit still does not scale with factory density until palette skip. No thread-pool changes (and none should be added for world access).

---

## Performance Monitoring Setup Recommendations

### Observability Recommendations

- **Metrics**: Spark (Forge) sampler on the dedicated server. Watch MSPT, `minecraft:entities`, block `getBlockState` samples inside `CircuitService.refreshSight`, particle packet rates, Faraday EventBus time. No Prometheus/Datadog in this stack.
- **Logging**: Keep activate/fail logs. Do not add per-tick logs. A debug command should print Circuit mark count, Faraday `HELD` size, Field radius, Scan `MARKED` size, and whether the last Circuit scan skipped N air sections.
- **Distributed Tracing**: Not applicable (single process). Treat one 50 ms server tick as the trace: Origins AOT → `ServerTickEvent` (Circuit / Faraday / Control / Dust) → packets.

### Recommended Performance Tracking

- MSPT / TPS: idle origin players vs Circuit walking a factory vs Faraday 30 s vs Field r=30 vs Scan left on
- Circuit: `refreshSight` time, mark count, packet bytes, sections skipped (`hasOnlyAir` vs palette), client frame time with 128 boxes
- Scan: client `getEntitiesOfClass` time at inflate 32 vs UUID resolve
- Faraday: living-in-r=10 count, held-shot count, EventBus hits with cages empty, particle packets/sec to r=24
- Alert if MSPT > 50 (TPS < 20) for >2 s with a single Circuit player in a redstone room

### Performance Testing Recommendations

- Load: redstone factory (clocks + hoppers), village, skeleton / TACZ range, 4–8 origin players
- Lifecycle: Circuit mode on/off, walk 4 blocks, toggle 5 wires, logout, dimension change, `/reload`
- Tools: Spark `--timeout 60`; F3 + pie / entity counts; particle slider Minimal vs All; compare Circuit mark cap 128 vs a palette-skip build
- Confirm the five named patches in a factory/village profile — do not treat this static report as a substitute for those numbers

---

## Summary

This performance analysis identified **0 critical**, **2 high**, **8 medium**, and **7 low** performance issues across the Minecraft server+client tick stack.

**Key Strengths Identified**:

- All five just-landed patches **are in live source**: Circuit `MOVE_SNAP=4` / `MAX_MARKS=128` / `hasOnlyAir` / `alreadyForced`; Scan inflate 32 + per-tick `CACHED`; SeeThrough 256 KB; Faraday interval-2 + dim-pinned `HeldShot`
- Prior Critical scan-volume + server-wide `force @a` storm is still gone; Scan is a one-shot Java r=32 pulse + private S2C
- Faraday projectile hold is Java `HELD` (global `@e` + NBT hold **fixed**); particle viewers are distance-limited
- Control Field sweep interval 4; `spawnHeadRing` is 4 batched packets; lock cone runs before LOS
- Session maps early-out when empty; logout/login/dim cleanup exists for Circuit, Faraday, Field, Dust, Scan
- Charge spend is Java-owned and atomic; mode debounce is 4 ticks
- Radial HUD does no work when closed; packets are main-thread and `DistExecutor`-safe

**Critical Areas Requiring Immediate Attention**:

- Circuit remaining N³-in-section walk — palette skip not landed (`P-001`)
- Faraday dual AOT + always-on EventBus + r=10 living query every 2 ticks (`P-002`)
- Faraday `freeze` tracker dirties every tick (`P-009`)
- Control Field r=30 rim packets + ally NBT reparse (`P-003`)

**Expected Overall Performance Improvement**: Phase 1 should put Circuit factory walks under a couple of milliseconds of the 50 ms tick (palette skip) and make Faraday combat/AOT free when no cage is up. Combined with VFX work and the five landed patches, remaining cost is query-shaped (blocks/entities), not particle-storm-shaped. Confirm with Spark in a redstone factory before calling Circuit done.
