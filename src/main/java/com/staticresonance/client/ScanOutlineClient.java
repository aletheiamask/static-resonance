package com.staticresonance.client;

import com.staticresonance.StaticResonanceMod;
import com.staticresonance.config.SrConfig;
import com.staticresonance.mode.ScanOutlineService;
import com.staticresonance.network.PacketLimits;
import com.staticresonance.network.ScanOutlinePacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
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
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Caster-only colored lock corners for scan marks. Drawn after the level so shader packs
 * cannot composite the lines into the sky.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID, value = Dist.CLIENT)
public final class ScanOutlineClient {
    private static final int SPARK_INTERVAL = 4;
    private static final int FLECK_CAP = 24;
    private static final int REVEAL_TICKS = 20;
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
    private static final List<ScanBoxRenderer.Box> FRAME = new ArrayList<>();
    private static final Matrix4f WORLD_PROJECTION = new Matrix4f();
    private static final Matrix4f WORLD_VIEW = new Matrix4f();
    private static boolean haveWorldMatrices;
    private static int clientTick;
    private static int revealTicks;
    private static int markGeneration;
    private static int walkedGeneration = -1;
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
        markGeneration++;
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
        if (MARKED.isEmpty()) {
            CACHED.clear();
        } else if ((clientTick & 1) == 0 || CACHED.isEmpty()) {
            rebuildCache(level);
        }
        if (!CACHED.isEmpty() && clientTick % SPARK_INTERVAL == 0) {
            spawnLocalFlecks(level, mc.player);
        }
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            if (!MARKED.isEmpty()) {
                WORLD_PROJECTION.set(event.getProjectionMatrix());
                WORLD_VIEW.set(event.getPoseStack().last().pose());
                haveWorldMatrices = true;
            }
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        if (!haveWorldMatrices) {
            return;
        }
        haveWorldMatrices = false;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || CACHED.isEmpty()) {
            return;
        }
        Camera camera = event.getCamera();
        Frustum frustum = event.getFrustum();
        float partialTick = event.getPartialTick();
        float reveal = revealRadius();
        boolean expanding = revealTicks < REVEAL_TICKS && scanOrigin != null;
        double revealSq = reveal * reveal;
        FRAME.clear();
        for (LivingEntity living : CACHED) {
            if (!living.isAlive()) {
                continue;
            }
            if (expanding && living.distanceToSqr(scanOrigin) > revealSq) {
                continue;
            }
            AABB box = interpolatedBox(living, partialTick);
            if (frustum != null && !frustum.isVisible(box)) {
                continue;
            }
            if (entirelyBehind(camera, box)) {
                continue;
            }
            float[] rgb = colorFor(living);
            float pulse = 0.72f + 0.23f * Mth.sin((clientTick + partialTick) * 0.18f);
            FRAME.add(new ScanBoxRenderer.Box(box, rgb[0], rgb[1], rgb[2], 0.95f * pulse));
        }
        boolean seeThrough = SrConfig.COMMON.visuals.seeThroughWalls.get() || ShaderPacks.inUse();
        ScanBoxRenderer.draw(camera, WORLD_PROJECTION, WORLD_VIEW, seeThrough, FRAME);
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
        return scanRadius() * Math.min(1.0f, revealTicks / (float) REVEAL_TICKS);
    }

    private static float scanRadius() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? 0f : ScanOutlineService.scanRadius(mc.player);
    }

    private static boolean revealed(LivingEntity living) {
        if (revealTicks >= REVEAL_TICKS || scanOrigin == null) {
            return true;
        }
        float r = revealRadius();
        return living.distanceToSqr(scanOrigin) <= r * r;
    }

    private static AABB interpolatedBox(LivingEntity living, float partialTick) {
        double x = Mth.lerp(partialTick, living.xo, living.getX());
        double y = Mth.lerp(partialTick, living.yo, living.getY());
        double z = Mth.lerp(partialTick, living.zo, living.getZ());
        return living.getBoundingBox()
                .move(x - living.getX(), y - living.getY(), z - living.getZ())
                .inflate(0.06);
    }

    private static boolean entirelyBehind(Camera camera, AABB box) {
        Vec3 cam = camera.getPosition();
        Vec3 look = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
        double hx = box.getXsize() * 0.5;
        double hy = box.getYsize() * 0.5;
        double hz = box.getZsize() * 0.5;
        double radius = Math.sqrt(hx * hx + hy * hy + hz * hz);
        double dx = (box.minX + box.maxX) * 0.5 - cam.x;
        double dy = (box.minY + box.maxY) * 0.5 - cam.y;
        double dz = (box.minZ + box.maxZ) * 0.5 - cam.z;
        return dx * look.x + dy * look.y + dz * look.z < -radius;
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

    private static void rebuildCache(ClientLevel level) {
        if (MARKED.isEmpty()) {
            CACHED.clear();
            return;
        }
        // ClientLevel / TransientEntitySectionManager does not index by UUID.
        // Keep live refs; walk the render list at most once per S2C generation.
        List<LivingEntity> next = new ArrayList<>(CACHED.size());
        Set<UUID> have = new HashSet<>();
        for (LivingEntity living : CACHED) {
            UUID id = living.getUUID();
            if (!MARKED.contains(id)) {
                continue;
            }
            if (!living.isAlive()) {
                MARKED.remove(id);
                continue;
            }
            if (living.isRemoved()) {
                continue;
            }
            next.add(living);
            have.add(id);
        }
        if (have.size() < MARKED.size()
                && (walkedGeneration != markGeneration || (clientTick % 20) == 0)) {
            walkedGeneration = markGeneration;
            for (Entity entity : level.entitiesForRendering()) {
                if (!(entity instanceof LivingEntity living)) {
                    continue;
                }
                UUID id = living.getUUID();
                if (!MARKED.contains(id) || have.contains(id)) {
                    continue;
                }
                if (!living.isAlive()) {
                    MARKED.remove(id);
                    continue;
                }
                next.add(living);
                have.add(id);
                if (have.size() >= MARKED.size()) {
                    break;
                }
            }
        }
        CACHED.clear();
        CACHED.addAll(next);
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

    private static void spawnLocalFlecks(ClientLevel level, Player player) {
        List<LivingEntity> shown = new ArrayList<>(CACHED.size());
        for (LivingEntity entity : CACHED) {
            if (revealed(entity)) {
                shown.add(entity);
            }
        }
        if (shown.size() > FLECK_CAP) {
            Vec3 here = player.position();
            shown.sort(Comparator.comparingDouble(e -> e.distanceToSqr(here)));
            shown = shown.subList(0, FLECK_CAP);
        }
        for (LivingEntity entity : shown) {
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
        FRAME.clear();
        haveWorldMatrices = false;
        revealTicks = 0;
        markGeneration++;
        walkedGeneration = markGeneration;
        scanOrigin = null;
    }

    private static void clearAll() {
        clearScanMarks();
    }
}
