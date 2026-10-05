package com.staticresonance.mode;

import com.staticresonance.StaticResonanceMod;
import com.staticresonance.config.SrConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Circuit mode: F force-toggles the looked-at {@code #circuit} piece for a few seconds.
 * Idle Circuit does no world walk. One raycast on F, one neighbor pulse, then cancel-only hold.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID)
public final class CircuitService {
    public static final TagKey<Block> CIRCUIT_TAG = TagKey.create(
            Registries.BLOCK, new ResourceLocation(StaticResonanceMod.MOD_ID, "circuit"));

    private static final Map<ResourceKey<Level>, ConcurrentHashMap<Long, Interference>> INTERFERENCES =
            new ConcurrentHashMap<>();
    private static final int IDLE_SPARK_INTERVAL = 10;

    private CircuitService() {
    }

    private static int lookRange() {
        return SrConfig.COMMON.circuit.lookRange.get();
    }

    public static boolean cast(ServerPlayer player) {
        if (!ModeService.hasModePower(player) || ModeService.getMode(player) != ResonanceMode.CIRCUIT) {
            return false;
        }
        BlockHitResult look = lookCircuit(player);
        if (look == null) {
            ActionBars.show(player, Component.translatable("ui.static_resonance.circuit_miss"));
            return false;
        }
        return toggleLooked(player, look);
    }

    public static void onModeChanged(ServerPlayer player, ResonanceMode mode) {
        if (mode != ResonanceMode.CIRCUIT) {
            restoreOwned(player.getUUID());
        }
    }

    private static boolean toggleLooked(ServerPlayer player, BlockHitResult hit) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!state.is(CIRCUIT_TAG)) {
            return false;
        }
        if (!spend(player, SrConfig.COMMON.circuit.toggleCost.get())) {
            return false;
        }
        ResourceKey<Level> dim = level.dimension();
        long packed = pos.asLong();
        Interference existing = getInterference(dim, packed);
        boolean currentlyOn = isWorking(level, pos, state, existing);
        boolean forceOn = !currentlyOn;
        BlockState saved = existing != null ? existing.saved : state;
        BlockPos frozen = pos.immutable();
        dropQuiet(dim, packed);
        applyForce(level, pos, saved, forceOn);
        putInterference(dim, packed, new Interference(
                player.getUUID(), dim, frozen, saved, forceOn,
                level.getGameTime() + SrConfig.COMMON.circuit.toggleTicks.get()));
        spawnCastVfx(level, hit, forceOn);
        level.playSound(null, pos,
                forceOn ? SoundEvents.NOTE_BLOCK_PLING.value() : SoundEvents.REDSTONE_TORCH_BURNOUT,
                SoundSource.PLAYERS, 0.45f, forceOn ? 1.8f : 0.7f);
        ActionBars.show(player, Component.translatable(
                forceOn ? "ui.static_resonance.circuit_feed" : "ui.static_resonance.circuit_cut"));
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || INTERFERENCES.isEmpty()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        tickInterferences(server);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (INTERFERENCES.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (getInterference(level.dimension(), event.getPos()) != null) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onNote(NoteBlockEvent.Play event) {
        if (INTERFERENCES.isEmpty() || !(event.getLevel() instanceof Level level)) {
            return;
        }
        Interference inf = getInterference(level.dimension(), event.getPos());
        if (inf != null && !inf.forceOn) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            restoreOwned(player.getUUID());
        }
    }

    private static void tickInterferences(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        Iterator<Map.Entry<ResourceKey<Level>, ConcurrentHashMap<Long, Interference>>> dims =
                INTERFERENCES.entrySet().iterator();
        while (dims.hasNext()) {
            Map.Entry<ResourceKey<Level>, ConcurrentHashMap<Long, Interference>> dimEntry = dims.next();
            ConcurrentHashMap<Long, Interference> dimMap = dimEntry.getValue();
            ServerLevel level = server.getLevel(dimEntry.getKey());
            Iterator<Map.Entry<Long, Interference>> it = dimMap.entrySet().iterator();
            while (it.hasNext()) {
                Interference inf = it.next().getValue();
                if (now < inf.expireGameTime) {
                    if (level != null && now % IDLE_SPARK_INTERVAL == 0 && level.hasChunkAt(inf.pos)) {
                        spawnIdleSparks(level, inf.pos);
                    }
                    continue;
                }
                it.remove();
                if (level != null && level.hasChunkAt(inf.pos)) {
                    restore(level, inf);
                }
            }
            if (dimMap.isEmpty()) {
                dims.remove();
            }
        }
    }

    private static void restoreOwned(UUID owner) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        Iterator<Map.Entry<ResourceKey<Level>, ConcurrentHashMap<Long, Interference>>> dims =
                INTERFERENCES.entrySet().iterator();
        while (dims.hasNext()) {
            Map.Entry<ResourceKey<Level>, ConcurrentHashMap<Long, Interference>> dimEntry = dims.next();
            ConcurrentHashMap<Long, Interference> dimMap = dimEntry.getValue();
            Iterator<Map.Entry<Long, Interference>> it = dimMap.entrySet().iterator();
            while (it.hasNext()) {
                Interference inf = it.next().getValue();
                if (!inf.owner.equals(owner)) {
                    continue;
                }
                it.remove();
                if (server == null) {
                    continue;
                }
                ServerLevel level = server.getLevel(inf.dim);
                if (level != null && level.hasChunkAt(inf.pos)) {
                    restore(level, inf);
                }
            }
            if (dimMap.isEmpty()) {
                dims.remove();
            }
        }
    }

    private static void restore(ServerLevel level, Interference inf) {
        BlockState current = level.getBlockState(inf.pos);
        if (!current.is(inf.saved.getBlock())) {
            return;
        }
        level.setBlock(inf.pos, inf.saved, Block.UPDATE_ALL);
        level.updateNeighborsAt(inf.pos, inf.saved.getBlock());
        spawnRestoreVfx(level, inf.pos);
    }

    @Nullable
    private static BlockHitResult lookCircuit(ServerPlayer player) {
        HitResult hit = player.pick(lookRange(), 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockHitResult blockHit = (BlockHitResult) hit;
        if (!player.serverLevel().getBlockState(blockHit.getBlockPos()).is(CIRCUIT_TAG)) {
            return null;
        }
        return blockHit;
    }

    private static void spawnCastVfx(ServerLevel level, BlockHitResult hit, boolean forceOn) {
        Direction face = hit.getDirection();
        BlockPos pos = hit.getBlockPos();
        double x = pos.getX() + 0.5 + face.getStepX() * 0.55;
        double y = pos.getY() + 0.5 + face.getStepY() * 0.55;
        double z = pos.getZ() + 0.5 + face.getStepZ() * 0.55;
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, 14, 0.18, 0.18, 0.18, 0.08);
        level.sendParticles(SrFx.CHARGE_DUST, x, y, z, 10, 0.22, 0.22, 0.22, 0);
        level.sendParticles(ParticleTypes.END_ROD, x, y, z, 0,
                face.getStepX() * 0.35, face.getStepY() * 0.35, face.getStepZ() * 0.35, 0.08);
        if (forceOn) {
            level.sendParticles(ParticleTypes.END_ROD, x, y, z, 3, 0.05, 0.05, 0.05, 0.01);
        } else {
            level.sendParticles(ParticleTypes.WAX_OFF, x, y, z, 6, 0.15, 0.15, 0.15, 0.02);
        }
    }

    private static void spawnIdleSparks(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                2, 0.2, 0.2, 0.2, 0.01);
    }

    private static void spawnRestoreVfx(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.WAX_OFF,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                8, 0.25, 0.25, 0.25, 0.02);
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

    private static boolean isWorking(Level level, BlockPos pos, BlockState state, @Nullable Interference inf) {
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
        return level.hasNeighborSignal(pos);
    }

    private static void applyForce(ServerLevel level, BlockPos pos, BlockState original, boolean on) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(original.getBlock())) {
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
        if (block instanceof NoteBlock && on) {
            level.blockEvent(pos, block, 0, 0);
            return;
        }
        if (block instanceof ObserverBlock && state.hasProperty(BlockStateProperties.POWERED)) {
            if (on) {
                level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, true), Block.UPDATE_ALL);
            } else if (state.getValue(BlockStateProperties.POWERED)) {
                level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, false), Block.UPDATE_CLIENTS);
            }
            return;
        }
        if (block instanceof DispenserBlock && state.hasProperty(BlockStateProperties.TRIGGERED)) {
            if (state.getValue(BlockStateProperties.TRIGGERED) != on) {
                level.setBlock(pos, state.setValue(BlockStateProperties.TRIGGERED, on), Block.UPDATE_CLIENTS);
            }
            if (on) {
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

    @Nullable
    private static Interference getInterference(ResourceKey<Level> dim, BlockPos pos) {
        return getInterference(dim, pos.asLong());
    }

    @Nullable
    private static Interference getInterference(ResourceKey<Level> dim, long packed) {
        ConcurrentHashMap<Long, Interference> dimMap = INTERFERENCES.get(dim);
        return dimMap == null ? null : dimMap.get(packed);
    }

    private static void putInterference(ResourceKey<Level> dim, long packed, Interference inf) {
        INTERFERENCES.computeIfAbsent(dim, key -> new ConcurrentHashMap<>()).put(packed, inf);
    }

    private static void dropQuiet(ResourceKey<Level> dim, long packed) {
        ConcurrentHashMap<Long, Interference> dimMap = INTERFERENCES.get(dim);
        if (dimMap == null) {
            return;
        }
        dimMap.remove(packed);
        if (dimMap.isEmpty()) {
            INTERFERENCES.remove(dim, dimMap);
        }
    }

    private static final class Interference {
        private final UUID owner;
        private final ResourceKey<Level> dim;
        private final BlockPos pos;
        private final BlockState saved;
        private final boolean forceOn;
        private final long expireGameTime;

        private Interference(UUID owner, ResourceKey<Level> dim, BlockPos pos, BlockState saved,
                             boolean forceOn, long expireGameTime) {
            this.owner = owner;
            this.dim = dim;
            this.pos = pos;
            this.saved = saved;
            this.forceOn = forceOn;
            this.expireGameTime = expireGameTime;
        }
    }
}
