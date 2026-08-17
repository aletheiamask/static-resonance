package com.staticresonance.mode;

import com.staticresonance.StaticResonanceMod;
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
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID)
public final class MotionHarvestService {
    public static final TagKey<EntityType<?>> MOTION_SOURCES = TagKey.create(
            Registries.ENTITY_TYPE, new ResourceLocation(StaticResonanceMod.MOD_ID, "motion_sources"));

    private static final int INTERVAL = 20;
    private static final int BUBBLE_INTERVAL = 35;
    private static final double MIN_DIST = 0.5;
    private static final double MAX_DIST = 5.0;
    private static final double AXIS_THRESHOLD = 0.03;
    private static final int CAP = 8;
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
    }

    private static void spawnWetBubbles(ServerPlayer player) {
        if (!ModeService.hasModePower(player) || !player.isInWaterOrRain()) {
            return;
        }
        player.serverLevel().sendParticles(ParticleTypes.BUBBLE,
                player.getX(), player.getY() + 1.0, player.getZ(),
                4, 0.3, 0.4, 0.3, 0.02);
    }

    private static void harvest(ServerPlayer player) {
        if (!ModeService.hasModePower(player)) {
            return;
        }
        int charge = ModeService.getResourceValue(player, ModeService.CHARGE_POWER).orElse(0);
        if (charge >= 100) {
            return;
        }

        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();
        double minSq = MIN_DIST * MIN_DIST;
        double maxSq = MAX_DIST * MAX_DIST;
        AABB box = new AABB(
                pos.x - MAX_DIST, pos.y - MAX_DIST, pos.z - MAX_DIST,
                pos.x + MAX_DIST, pos.y + MAX_DIST, pos.z + MAX_DIST);

        int count = 0;
        for (Entity entity : level.getEntities(player, box, MotionHarvestService::isMotionSource)) {
            double distSq = entity.distanceToSqr(pos);
            if (distSq < minSq || distSq > maxSq || !isMoving(entity)) {
                continue;
            }
            count++;
            if (count >= CAP) {
                break;
            }
        }

        writeMotionCount(player, count);
        int delta = bucket(count);
        if (delta <= 0) {
            return;
        }
        int next = Math.min(100, charge + delta);
        if (next != charge && ApoliResources.setValue(player, ModeService.CHARGE_POWER, next)) {
            ApoliResources.sync(player);
        }
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                pos.x, pos.y + 1.0, pos.z, 4, 0.35, 0.4, 0.35, 0.02);
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

    private static int bucket(int count) {
        if (count >= 7) {
            return 4;
        }
        if (count >= 5) {
            return 3;
        }
        if (count >= 3) {
            return 2;
        }
        if (count >= 1) {
            return 1;
        }
        return 0;
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
