package com.staticresonance.mode;

import com.staticresonance.StaticResonanceMod;
import com.staticresonance.network.CircuitOutlinePacket;
import com.staticresonance.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.NoteBlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Circuit mode: selecting the mode reveals nearby wiring (free).
 * F force-toggles the looked-at piece for {@link #TOGGLE_TICKS}.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID)
public final class CircuitService {
    public static final TagKey<Block> CIRCUIT_TAG = TagKey.create(
            Registries.BLOCK, new ResourceLocation(StaticResonanceMod.MOD_ID, "circuit"));

    public static final int TOGGLE_COST = 8;
    public static final int RADIUS = 16;
    public static final int TOGGLE_TICKS = 160;
    private static final int SCAN_INTERVAL = 20;
    private static final int MOVE_SNAP = 4;
    private static final int MAX_MARKS = 128;

    private static final Map<UUID, Sight> SIGHTS = new ConcurrentHashMap<>();
    private static final Map<InterfereKey, Interference> INTERFERENCES = new ConcurrentHashMap<>();

    private CircuitService() {
    }

    public static boolean isSightActive(Player player) {
        return SIGHTS.containsKey(player.getUUID());
    }

    public static boolean cast(ServerPlayer player) {
        if (!ModeService.hasModePower(player) || ModeService.getMode(player) != ResonanceMode.CIRCUIT) {
            return false;
        }
        if (!isSightActive(player)) {
            startSight(player);
        }
        BlockPos look = lookCircuit(player);
        if (look == null || player.serverLevel().getBlockState(look).is(Blocks.REDSTONE_BLOCK)) {
            ActionBars.show(player, Component.translatable("ui.static_resonance.circuit_miss"));
            return false;
        }
        return toggleLooked(player, look);
    }

    public static void onModeChanged(ServerPlayer player, ResonanceMode mode) {
        if (mode == ResonanceMode.CIRCUIT) {
            startSight(player);
        } else {
            stopSight(player, false);
        }
    }

    private static void startSight(ServerPlayer player) {
        Sight existing = SIGHTS.get(player.getUUID());
        if (existing != null) {
            refreshSight(player, existing, true);
            return;
        }
        Sight sight = new Sight();
        SIGHTS.put(player.getUUID(), sight);
        refreshSight(player, sight, true);
        sight.lastOrigin = player.blockPosition().immutable();
        sight.dirty = false;
        player.serverLevel().playSound(null, player.blockPosition(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.55f, 1.6f);
    }

    private static void stopSight(ServerPlayer player, boolean announce) {
        if (SIGHTS.remove(player.getUUID()) == null) {
            return;
        }
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new CircuitOutlinePacket(true, List.of()));
        if (announce) {
            ActionBars.show(player, Component.translatable("ui.static_resonance.circuit_closed"));
        }
    }

    private static boolean toggleLooked(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        BlockState state = level.getBlockState(pos);
        if (!state.is(CIRCUIT_TAG) || state.is(Blocks.REDSTONE_BLOCK)) {
            return false;
        }
        if (!spend(player, TOGGLE_COST)) {
            return false;
        }
        InterfereKey key = new InterfereKey(level.dimension(), pos.immutable());
        Interference existing = INTERFERENCES.get(key);
        boolean currentlyOn = isWorking(level, pos, state, existing);
        boolean forceOn = !currentlyOn;
        BlockState saved = existing != null ? existing.saved : state;
        INTERFERENCES.put(key, new Interference(
                player.getUUID(), key, saved, forceOn, level.getGameTime() + TOGGLE_TICKS));
        applyForce(level, pos, saved, forceOn, true);
        Sight sight = SIGHTS.get(player.getUUID());
        if (sight != null) {
            sight.marks.put(pos.immutable(), forceOn);
            sendSight(player, sight);
        }
        level.playSound(null, pos,
                forceOn ? SoundEvents.NOTE_BLOCK_PLING.value() : SoundEvents.REDSTONE_TORCH_BURNOUT,
                SoundSource.PLAYERS, 0.45f, forceOn ? 1.8f : 0.7f);
        ActionBars.show(player, Component.translatable(
                forceOn ? "ui.static_resonance.circuit_feed" : "ui.static_resonance.circuit_cut"));
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        tickInterferences(server);
        if (SIGHTS.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (UUID id : new ArrayList<>(SIGHTS.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || ModeService.getMode(player) != ResonanceMode.CIRCUIT) {
                if (player != null) {
                    stopSight(player, false);
                } else {
                    SIGHTS.remove(id);
                }
                continue;
            }
            Sight sight = SIGHTS.get(id);
            if (sight == null) {
                continue;
            }
            if (now % SCAN_INTERVAL == 0) {
                BlockPos here = player.blockPosition();
                if (sight.dirty || sight.lastOrigin == null
                        || sight.lastOrigin.distManhattan(here) >= MOVE_SNAP) {
                    refreshSight(player, sight, false);
                    sight.lastOrigin = here.immutable();
                    sight.dirty = false;
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        markSightsDirty(event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        markSightsDirty(event.getLevel(), event.getPos());
    }

    private static void markSightsDirty(net.minecraft.world.level.LevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl) || SIGHTS.isEmpty()) {
            return;
        }
        for (UUID id : SIGHTS.keySet()) {
            ServerPlayer player = sl.getServer().getPlayerList().getPlayer(id);
            if (player == null || player.level() != sl) {
                continue;
            }
            if (player.blockPosition().distManhattan(pos) <= RADIUS + 2) {
                Sight sight = SIGHTS.get(id);
                if (sight != null) {
                    sight.dirty = true;
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (INTERFERENCES.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Interference inf = INTERFERENCES.get(new InterfereKey(level.dimension(), event.getPos()));
        if (inf != null && !inf.forceOn) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onNote(NoteBlockEvent.Play event) {
        if (INTERFERENCES.isEmpty() || !(event.getLevel() instanceof Level level)) {
            return;
        }
        Interference inf = INTERFERENCES.get(new InterfereKey(level.dimension(), event.getPos()));
        if (inf != null && !inf.forceOn) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        stopSight(player, false);
        restoreOwned(player.getUUID());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && ModeService.getMode(player) == ResonanceMode.CIRCUIT) {
            startSight(player);
        }
    }

    @SubscribeEvent
    public static void onChangeDim(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        stopSight(player, false);
        if (ModeService.getMode(player) == ResonanceMode.CIRCUIT) {
            startSight(player);
        }
    }

    private static void tickInterferences(MinecraftServer server) {
        if (INTERFERENCES.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        Iterator<Map.Entry<InterfereKey, Interference>> it = INTERFERENCES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<InterfereKey, Interference> entry = it.next();
            Interference inf = entry.getValue();
            ServerLevel level = server.getLevel(inf.key.dim);
            if (level == null || !level.hasChunkAt(inf.key.pos)) {
                continue;
            }
            if (now >= inf.expireGameTime) {
                restore(level, inf);
                it.remove();
                continue;
            }
            BlockState current = level.getBlockState(inf.key.pos);
            if (!current.is(inf.saved.getBlock())) {
                it.remove();
                continue;
            }
            applyForce(level, inf.key.pos, inf.saved, inf.forceOn, false);
        }
    }

    private static void restoreOwned(UUID owner) {
        Iterator<Map.Entry<InterfereKey, Interference>> it = INTERFERENCES.entrySet().iterator();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        while (it.hasNext()) {
            Interference inf = it.next().getValue();
            if (!inf.owner.equals(owner)) {
                continue;
            }
            if (server != null) {
                ServerLevel level = server.getLevel(inf.key.dim);
                if (level != null && level.hasChunkAt(inf.key.pos)) {
                    restore(level, inf);
                }
            }
            it.remove();
        }
    }

    private static void restore(ServerLevel level, Interference inf) {
        BlockState current = level.getBlockState(inf.key.pos);
        if (!current.is(inf.saved.getBlock())) {
            return;
        }
        level.setBlock(inf.key.pos, inf.saved, Block.UPDATE_ALL);
        level.updateNeighborsAt(inf.key.pos, inf.saved.getBlock());
    }

    private static void refreshSight(ServerPlayer player, Sight sight, boolean forceSend) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        int r = RADIUS;
        int rSq = r * r;
        int minX = origin.getX() - r;
        int maxX = origin.getX() + r;
        int minY = Math.max(origin.getY() - r, level.getMinBuildHeight());
        int maxY = Math.min(origin.getY() + r, level.getMaxBuildHeight() - 1);
        int minZ = origin.getZ() - r;
        int maxZ = origin.getZ() + r;
        List<CircuitOutlinePacket.Entry> found = new ArrayList<>();
        int minCx = minX >> 4;
        int maxCx = maxX >> 4;
        int minCz = minZ >> 4;
        int maxCz = maxZ >> 4;
        int minCy = minY >> 4;
        int maxCy = maxY >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                var chunk = level.getChunk(cx, cz);
                for (int cy = minCy; cy <= maxCy; cy++) {
                    int sectionIndex = chunk.getSectionIndexFromSectionY(cy);
                    if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) {
                        continue;
                    }
                    var section = chunk.getSection(sectionIndex);
                    if (section.hasOnlyAir() || !section.maybeHas(state -> state.is(CIRCUIT_TAG))) {
                        continue;
                    }
                    int x0 = Math.max(minX, cx << 4);
                    int x1 = Math.min(maxX, (cx << 4) + 15);
                    int y0 = Math.max(minY, cy << 4);
                    int y1 = Math.min(maxY, (cy << 4) + 15);
                    int z0 = Math.max(minZ, cz << 4);
                    int z1 = Math.min(maxZ, (cz << 4) + 15);
                    for (int y = y0; y <= y1; y++) {
                        int ly = y & 15;
                        for (int x = x0; x <= x1; x++) {
                            int dx = x - origin.getX();
                            int lx = x & 15;
                            for (int z = z0; z <= z1; z++) {
                                int dz = z - origin.getZ();
                                int dy = y - origin.getY();
                                if (dx * dx + dy * dy + dz * dz > rSq) {
                                    continue;
                                }
                                BlockState state = section.getBlockState(lx, ly, z & 15);
                                if (!state.is(CIRCUIT_TAG)) {
                                    continue;
                                }
                                BlockPos pos = new BlockPos(x, y, z);
                                Interference inf = INTERFERENCES.get(new InterfereKey(level.dimension(), pos));
                                found.add(new CircuitOutlinePacket.Entry(pos, isWorking(level, pos, state, inf)));
                            }
                        }
                    }
                }
            }
        }
        if (found.size() > MAX_MARKS) {
            found.sort((a, b) -> Integer.compare(a.pos().distManhattan(origin), b.pos().distManhattan(origin)));
            found.subList(MAX_MARKS, found.size()).clear();
            ActionBars.show(player, Component.translatable("ui.static_resonance.circuit_dense"));
        }
        Map<BlockPos, Boolean> next = new HashMap<>(found.size());
        for (CircuitOutlinePacket.Entry entry : found) {
            next.put(entry.pos(), entry.working());
        }
        if (!forceSend && next.equals(sight.marks)) {
            return;
        }
        sight.marks = next;
        sendSight(player, sight);
    }

    private static void sendSight(ServerPlayer player, Sight sight) {
        List<CircuitOutlinePacket.Entry> entries = new ArrayList<>(sight.marks.size());
        for (Map.Entry<BlockPos, Boolean> e : sight.marks.entrySet()) {
            entries.add(new CircuitOutlinePacket.Entry(e.getKey(), e.getValue()));
        }
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new CircuitOutlinePacket(false, entries));
    }

    @Nullable
    private static BlockPos lookCircuit(ServerPlayer player) {
        HitResult hit = player.pick(RADIUS, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        if (!player.serverLevel().getBlockState(pos).is(CIRCUIT_TAG)) {
            return null;
        }
        return pos.immutable();
    }

    private static boolean spend(ServerPlayer player, int cost) {
        int charge = ModeService.getResourceValue(player, ModeService.CHARGE_POWER).orElse(0);
        if (charge < cost) {
            ActionBars.show(player, Component.translatable("ui.static_resonance.charge_too_low"));
            return false;
        }
        if (!ApoliResources.setValue(player, ModeService.CHARGE_POWER, charge - cost)) {
            return false;
        }
        ApoliResources.sync(player);
        return true;
    }

    static boolean isWorking(Level level, BlockPos pos, BlockState state, @Nullable Interference inf) {
        if (inf != null) {
            return inf.forceOn;
        }
        if (state.getBlock() instanceof HopperBlock && state.hasProperty(HopperBlock.ENABLED)) {
            return !state.getValue(HopperBlock.ENABLED);
        }
        if (state.hasProperty(BlockStateProperties.POWER)) {
            return state.getValue(BlockStateProperties.POWER) > 0;
        }
        if (state.hasProperty(BlockStateProperties.LIT)
                && (state.getBlock() instanceof RedstoneTorchBlock || state.getBlock() instanceof RedstoneLampBlock
                || state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH))) {
            return state.getValue(BlockStateProperties.LIT);
        }
        if (state.hasProperty(BlockStateProperties.EXTENDED)) {
            return state.getValue(BlockStateProperties.EXTENDED);
        }
        if (state.hasProperty(BlockStateProperties.TRIGGERED)) {
            return state.getValue(BlockStateProperties.TRIGGERED);
        }
        if (state.hasProperty(BlockStateProperties.OPEN)
                && (state.is(Blocks.IRON_DOOR) || state.is(Blocks.IRON_TRAPDOOR))) {
            return state.getValue(BlockStateProperties.OPEN);
        }
        if (state.hasProperty(BlockStateProperties.POWERED)) {
            return state.getValue(BlockStateProperties.POWERED);
        }
        if (state.is(Blocks.REDSTONE_BLOCK)) {
            return true;
        }
        return level.hasNeighborSignal(pos);
    }

    private static boolean alreadyForced(BlockState state, boolean on) {
        if (state.getBlock() instanceof RedStoneWireBlock && state.hasProperty(RedStoneWireBlock.POWER)) {
            return state.getValue(RedStoneWireBlock.POWER) == (on ? 15 : 0);
        }
        if (state.is(Blocks.REDSTONE_BLOCK)) {
            return true;
        }
        if (state.hasProperty(BlockStateProperties.LIT)
                && (state.getBlock() instanceof RedstoneTorchBlock || state.getBlock() instanceof RedstoneLampBlock
                || state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)
                || state.is(Blocks.REDSTONE_LAMP))) {
            return state.getValue(BlockStateProperties.LIT) == on;
        }
        if (state.hasProperty(BlockStateProperties.OPEN)
                && (state.is(Blocks.IRON_DOOR) || state.is(Blocks.IRON_TRAPDOOR))) {
            return state.getValue(BlockStateProperties.OPEN) == on;
        }
        if (state.getBlock() instanceof HopperBlock && state.hasProperty(HopperBlock.ENABLED)) {
            return state.getValue(HopperBlock.ENABLED) == !on;
        }
        if (state.hasProperty(BlockStateProperties.POWERED)) {
            return state.getValue(BlockStateProperties.POWERED) == on;
        }
        if (state.hasProperty(BlockStateProperties.POWER)) {
            return state.getValue(BlockStateProperties.POWER) == (on ? 15 : 0);
        }
        return false;
    }

    private static void applyForce(ServerLevel level, BlockPos pos, BlockState original,
                                   boolean on, boolean firstPulse) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(original.getBlock())) {
            return;
        }
        if (!firstPulse && alreadyForced(state, on)) {
            return;
        }
        Block block = state.getBlock();
        if (block instanceof RedStoneWireBlock && state.hasProperty(RedStoneWireBlock.POWER)) {
            int power = on ? 15 : 0;
            if (state.getValue(RedStoneWireBlock.POWER) != power) {
                level.setBlock(pos, state.setValue(RedStoneWireBlock.POWER, power), Block.UPDATE_CLIENTS);
            }
            if (on) {
                level.updateNeighborsAt(pos, block);
            }
            return;
        }
        if (state.is(Blocks.REDSTONE_BLOCK)) {
            return;
        }
        if (state.hasProperty(BlockStateProperties.LIT)
                && (block instanceof RedstoneTorchBlock || block instanceof RedstoneLampBlock
                || state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)
                || state.is(Blocks.REDSTONE_LAMP))) {
            if (state.getValue(BlockStateProperties.LIT) != on) {
                level.setBlock(pos, state.setValue(BlockStateProperties.LIT, on), Block.UPDATE_CLIENTS);
            }
            return;
        }
        if (block instanceof PistonBaseBlock && state.hasProperty(BlockStateProperties.POWERED)) {
            BlockState next = state.setValue(BlockStateProperties.POWERED, on);
            if (next != state) {
                level.setBlock(pos, next, Block.UPDATE_CLIENTS);
            }
            next.neighborChanged(level, pos, block, pos, false);
            return;
        }
        if (block instanceof DoorBlock door && state.is(Blocks.IRON_DOOR)) {
            door.setOpen(null, level, state, pos, on);
            return;
        }
        if (block instanceof TrapDoorBlock && state.is(Blocks.IRON_TRAPDOOR)
                && state.hasProperty(BlockStateProperties.OPEN)) {
            if (state.getValue(BlockStateProperties.OPEN) != on) {
                level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, on), Block.UPDATE_ALL);
            }
            return;
        }
        if (block instanceof HopperBlock && state.hasProperty(HopperBlock.ENABLED)) {
            boolean enabled = !on;
            if (state.getValue(HopperBlock.ENABLED) != enabled) {
                level.setBlock(pos, state.setValue(HopperBlock.ENABLED, enabled), Block.UPDATE_CLIENTS);
            }
            return;
        }
        if (block instanceof NoteBlock && firstPulse && on) {
            level.blockEvent(pos, block, 0, 0);
            return;
        }
        if (block instanceof ObserverBlock && state.hasProperty(BlockStateProperties.POWERED)) {
            if (firstPulse && on) {
                level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, true), Block.UPDATE_ALL);
            } else if (!on && state.getValue(BlockStateProperties.POWERED)) {
                level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, false), Block.UPDATE_CLIENTS);
            }
            return;
        }
        if (block instanceof DispenserBlock && state.hasProperty(BlockStateProperties.TRIGGERED)) {
            if (state.getValue(BlockStateProperties.TRIGGERED) != on) {
                level.setBlock(pos, state.setValue(BlockStateProperties.TRIGGERED, on), Block.UPDATE_CLIENTS);
            }
            if (firstPulse && on) {
                level.scheduleTick(pos, block, 1);
            }
            return;
        }
        if ((block instanceof LeverBlock || block instanceof ButtonBlock || block instanceof PressurePlateBlock
                || block instanceof DiodeBlock)
                && state.hasProperty(BlockStateProperties.POWERED)) {
            if (state.getValue(BlockStateProperties.POWERED) != on) {
                int flags = on ? Block.UPDATE_ALL : Block.UPDATE_CLIENTS;
                level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, on), flags);
                if (on) {
                    level.updateNeighborsAt(pos, block);
                }
            }
            return;
        }
        if (state.hasProperty(BlockStateProperties.POWERED)
                && state.getValue(BlockStateProperties.POWERED) != on) {
            int flags = on ? Block.UPDATE_ALL : Block.UPDATE_CLIENTS;
            level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, on), flags);
        } else if (state.hasProperty(BlockStateProperties.POWER)) {
            int power = on ? 15 : 0;
            if (state.getValue(BlockStateProperties.POWER) != power) {
                level.setBlock(pos, state.setValue(BlockStateProperties.POWER, power),
                        on ? Block.UPDATE_ALL : Block.UPDATE_CLIENTS);
            }
        }
    }

    private static final class Sight {
        private Map<BlockPos, Boolean> marks = new HashMap<>();
        private BlockPos lastOrigin;
        private boolean dirty = true;
    }

    private record InterfereKey(ResourceKey<Level> dim, BlockPos pos) {
    }

    static final class Interference {
        private final UUID owner;
        private final InterfereKey key;
        private final BlockState saved;
        private final boolean forceOn;
        private final long expireGameTime;

        private Interference(UUID owner, InterfereKey key, BlockState saved,
                             boolean forceOn, long expireGameTime) {
            this.owner = owner;
            this.key = key;
            this.saved = saved;
            this.forceOn = forceOn;
            this.expireGameTime = expireGameTime;
        }
    }
}
