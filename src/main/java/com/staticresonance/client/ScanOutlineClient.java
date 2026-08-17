package com.staticresonance.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.staticresonance.StaticResonanceMod;
import com.staticresonance.mode.ScanOutlineService;
import com.staticresonance.network.PacketLimits;
import com.staticresonance.network.ScanOutlinePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Caster-only colored line boxes for scan marks. See-through via {@link SeeThroughRender}.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID, value = Dist.CLIENT)
public final class ScanOutlineClient {
    private static final int SPARK_INTERVAL = 4;
    private static final int REVEAL_TICKS = 20;
    private static final float REVEAL_MAX = 32.0f;
    private static final float[] COLOR_PASSIVE = {0.28f, 0.92f, 0.38f};
    private static final float[] COLOR_PLAYER = {1.00f, 1.00f, 1.00f};
    private static final float[] COLOR_HOSTILE = {1.00f, 0.22f, 0.20f};
    private static final DustParticleOptions DUST_PASSIVE =
            new DustParticleOptions(new Vector3f(0.28f, 0.92f, 0.38f), 1.0f);
    private static final DustParticleOptions DUST_PLAYER =
            new DustParticleOptions(new Vector3f(1.00f, 1.00f, 1.00f), 1.0f);
    private static final DustParticleOptions DUST_HOSTILE =
            new DustParticleOptions(new Vector3f(1.00f, 0.22f, 0.20f), 1.0f);

    private static final Set<UUID> MARKED = new LinkedHashSet<>();
    private static final List<LivingEntity> CACHED = new ArrayList<>();
    private static int clientTick;
    private static int revealTicks;
    private static Vec3 scanOrigin;

    private ScanOutlineClient() {
    }

    public static void handle(ScanOutlinePacket packet) {
        List<UUID> ids = packet.entityIds();
        if (packet.clearAll() && ids.isEmpty()) {
            clearScanMarks();
            return;
        }
        boolean first = MARKED.isEmpty();
        if (packet.clearAll()) {
            MARKED.clear();
        }
        MARKED.addAll(ids);
        while (MARKED.size() > PacketLimits.MAX_OUTLINE_MARKS) {
            Iterator<UUID> it = MARKED.iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
        if (first) {
            Minecraft mc = Minecraft.getInstance();
            scanOrigin = mc.player != null ? mc.player.position() : null;
            revealTicks = 0;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        clientTick++;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        if (revealTicks < REVEAL_TICKS && !MARKED.isEmpty()) {
            revealTicks++;
        }
        if ((clientTick & 1) == 0 || CACHED.isEmpty()) {
            rebuildCache(level, mc.player);
        }
        if (!CACHED.isEmpty() && clientTick % SPARK_INTERVAL == 0) {
            spawnLocalFlecks(level);
        }
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || CACHED.isEmpty()) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        RenderType type = SeeThroughRender.lines();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(type);
        float reveal = revealRadius();
        boolean expanding = revealTicks < REVEAL_TICKS && scanOrigin != null;
        double revealSq = reveal * reveal;
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
        buffers.endBatch(type);
        pose.popPose();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clearAll();
    }

    @SubscribeEvent
    public static void onClone(ClientPlayerNetworkEvent.Clone event) {
        clearAll();
    }

    private static float revealRadius() {
        return REVEAL_MAX * Math.min(1.0f, revealTicks / (float) REVEAL_TICKS);
    }

    private static boolean revealed(LivingEntity living) {
        if (revealTicks >= REVEAL_TICKS || scanOrigin == null) {
            return true;
        }
        float r = revealRadius();
        return living.distanceToSqr(scanOrigin) <= r * r;
    }

    private static float[] colorFor(LivingEntity living) {
        if (living instanceof Player) {
            return COLOR_PLAYER;
        }
        if (living.getType().getCategory() == MobCategory.MONSTER) {
            return COLOR_HOSTILE;
        }
        return COLOR_PASSIVE;
    }

    private static void rebuildCache(ClientLevel level, Player player) {
        CACHED.clear();
        if (MARKED.isEmpty()) {
            return;
        }
        AABB search = player.getBoundingBox().inflate(ScanOutlineService.SCAN_RADIUS);
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, search,
                e -> MARKED.contains(e.getUUID()))) {
            if (!living.isAlive()) {
                MARKED.remove(living.getUUID());
                continue;
            }
            CACHED.add(living);
        }
    }

    private static DustParticleOptions dustFor(Entity entity) {
        if (entity instanceof Player) {
            return DUST_PLAYER;
        }
        if (entity instanceof LivingEntity living
                && living.getType().getCategory() == MobCategory.MONSTER) {
            return DUST_HOSTILE;
        }
        return DUST_PASSIVE;
    }

    private static void spawnLocalFlecks(ClientLevel level) {
        for (LivingEntity entity : CACHED) {
            if (!revealed(entity)) {
                continue;
            }
            double x = entity.getX();
            double y = entity.getY() + entity.getBbHeight() * 0.65;
            double z = entity.getZ();
            double w = Math.max(0.25, entity.getBbWidth() * 0.45);
            level.addParticle(dustFor(entity),
                    x + (level.random.nextDouble() - 0.5) * w * 2,
                    y + (level.random.nextDouble() - 0.3) * 0.4,
                    z + (level.random.nextDouble() - 0.5) * w * 2,
                    0, 0.01, 0);
            level.addParticle(ParticleTypes.ELECTRIC_SPARK,
                    x + (level.random.nextDouble() - 0.5) * w,
                    y,
                    z + (level.random.nextDouble() - 0.5) * w,
                    0, 0.02, 0);
        }
    }

    private static void clearScanMarks() {
        MARKED.clear();
        CACHED.clear();
        revealTicks = 0;
        scanOrigin = null;
    }

    private static void clearAll() {
        MARKED.clear();
        CACHED.clear();
        revealTicks = 0;
        scanOrigin = null;
    }
}
