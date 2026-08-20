package com.staticresonance.mode;

import com.staticresonance.StaticResonanceMod;
import com.staticresonance.config.SrConfig;
import com.staticresonance.logic.ChargeBuckets;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Nearby moving mobs refill charge. Replaces {@code motion_harvest}/{@code motion_try}
 * NBT reads and the four Brigadier {@code origins:command} buckets.
 * Walks the online player list; do not rebuild a permanent origin-player set.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID)
public final class MotionHarvestService {
    public static final TagKey<EntityType<?>> MOTION_SOURCES = TagKey.create(
            Registries.ENTITY_TYPE, new ResourceLocation(StaticResonanceMod.MOD_ID, "motion_sources"));

    private static final double AXIS_THRESHOLD = 0.03;
    private static final String OBJ_MC = "sr_mc";

    private MotionHarvestService() {
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
        long now = server.overworld().getGameTime();
        int regenEvery = SrConfig.COMMON.charge.regenIntervalTicks.get();
        int motionEvery = SrConfig.COMMON.charge.motionIntervalTicks.get();
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
                changed |= regenTick(player);
            }
            if (motion) {
                changed |= harvest(player);
            }
            if (changed) {
                ApoliResources.sync(player);
            }
        }
    }

    private static boolean regenTick(ServerPlayer player) {
        int charge = ModeService.getResourceValue(player, ModeService.CHARGE_POWER).orElse(0);
        int next;
        if (player.isInWaterOrRain()) {
            next = Math.max(0, charge - SrConfig.COMMON.charge.wetDrain.get());
            player.serverLevel().sendParticles(ParticleTypes.BUBBLE,
                    player.getX(), player.getY() + 1.0, player.getZ(),
                    4, 0.3, 0.4, 0.3, 0.02);
        } else if (charge < SrConfig.CHARGE_MAX) {
            next = Math.min(SrConfig.CHARGE_MAX, charge + SrConfig.COMMON.charge.dryGain.get());
        } else {
            return false;
        }
        return next != charge && ApoliResources.setValue(player, ModeService.CHARGE_POWER, next);
    }

    private static boolean harvest(ServerPlayer player) {
        int charge = ModeService.getResourceValue(player, ModeService.CHARGE_POWER).orElse(0);
        if (charge >= SrConfig.CHARGE_MAX) {
            return false;
        }

        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();
        double minDist = SrConfig.COMMON.charge.motionMinDist.get();
        double maxDist = SrConfig.COMMON.charge.motionMaxDist.get();
        double minSq = minDist * minDist;
        double maxSq = maxDist * maxDist;
        AABB box = new AABB(
                pos.x - maxDist, pos.y - maxDist, pos.z - maxDist,
                pos.x + maxDist, pos.y + maxDist, pos.z + maxDist);

        int count = 0;
        int cap = SrConfig.COMMON.charge.motionCap.get();
        for (Entity entity : level.getEntities(player, box, MotionHarvestService::isMotionSource)) {
            double distSq = entity.distanceToSqr(pos);
            if (distSq < minSq || distSq > maxSq || !isMoving(entity)) {
                continue;
            }
            count++;
            if (count >= cap) {
                break;
            }
        }

        writeMotionCount(player, count);
        int delta = ChargeBuckets.reward(count);
        if (delta <= 0) {
            return false;
        }
        int next = Math.min(SrConfig.CHARGE_MAX, charge + delta);
        boolean changed = next != charge && ApoliResources.setValue(player, ModeService.CHARGE_POWER, next);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                pos.x, pos.y + 1.0, pos.z, 4, 0.35, 0.4, 0.35, 0.02);
        return changed;
    }

    private static boolean isMotionSource(Entity entity) {
        return entity.getType().is(MOTION_SOURCES);
    }

    private static boolean isMoving(Entity entity) {
        Vec3 motion = entity.getDeltaMovement();
        return Math.abs(motion.x) >= AXIS_THRESHOLD
                || Math.abs(motion.y) >= AXIS_THRESHOLD
                || Math.abs(motion.z) >= AXIS_THRESHOLD;
    }

    private static void writeMotionCount(ServerPlayer player, int count) {
        Scoreboard board = player.getScoreboard();
        Objective obj = board.getObjective(OBJ_MC);
        if (obj == null) {
            return;
        }
        board.getOrCreatePlayerScore(player.getScoreboardName(), obj).setScore(count);
    }
}
