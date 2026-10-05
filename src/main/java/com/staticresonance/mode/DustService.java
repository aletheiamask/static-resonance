package com.staticresonance.mode;

import com.staticresonance.StaticResonanceMod;
import com.staticresonance.config.SrConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dust style: single look-target (Control-like), or a placed smoke zone on that living entity.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID)
public final class DustService {
    public static final ResourceLocation STYLE_POWER =
            new ResourceLocation(StaticResonanceMod.MOD_ID, "systems/dust_style");

    public static final int STYLE_BURST = 0;
    public static final int STYLE_ZONE = 1;
    public static final int BLIND_REFRESH_TICKS = 40;
    private static final int ZONE_TICK_INTERVAL = 4;

    private static final Map<UUID, DustZone> ZONES = new ConcurrentHashMap<>();
    private static final int GROUND_RING_POINTS = 8;

    private DustService() {
    }

    public static int getStyle(Player player) {
        return ApoliResources.getValue(player, STYLE_POWER).orElse(STYLE_BURST);
    }

    public static boolean setStyle(ServerPlayer player, int style) {
        if (!ApoliResources.hasPower(player, STYLE_POWER)) {
            return false;
        }
        int v = style == STYLE_ZONE ? STYLE_ZONE : STYLE_BURST;
        if (!ApoliResources.setValue(player, STYLE_POWER, v)) {
            return false;
        }
        ApoliResources.sync(player);
        return true;
    }

    public static boolean cast(ServerPlayer player) {
        if (!ModeService.hasModePower(player) || ModeService.getMode(player) != ResonanceMode.DUST) {
            return false;
        }
        return getStyle(player) == STYLE_ZONE ? tryPlaceZone(player) : trySingle(player);
    }

    public static boolean trySingle(ServerPlayer player) {
        LivingEntity target = LookTargets.findLookLiving(player, e ->
                e.isAlive() && !e.isSpectator() && !AllyService.isProtected(player, e));
        ServerLevel level = player.serverLevel();
        if (target == null) {
            level.playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_BASS.value(),
                    SoundSource.PLAYERS, 0.5f, 0.6f);
            ActionBars.show(player, Component.translatable("ui.static_resonance.dust_single_miss"));
            return false;
        }
        int cost = SrConfig.COMMON.dust.burstCost.get();
        int charge = ModeService.getResourceValue(player, ModeService.CHARGE_POWER).orElse(0);
        if (charge < cost) {
            ActionBars.show(player, Component.translatable("ui.static_resonance.charge_too_low"));
            return false;
        }
        if (!ApoliResources.setValue(player, ModeService.CHARGE_POWER, charge - cost)) {
            return false;
        }
        ApoliResources.sync(player);
        target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 80, 0, false, true, true));
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1, false, true, true));
        spawnSingleVfx(level, target);
        ActionBars.show(player, Component.translatable("ui.static_resonance.dust_single_hit"));
        return true;
    }

    private static void spawnSingleVfx(ServerLevel level, LivingEntity target) {
        double x = target.getX();
        double y = target.getY() + target.getBbHeight() * 0.5;
        double z = target.getZ();
        double w = Math.max(0.35, target.getBbWidth() * 0.6);
        level.sendParticles(ParticleTypes.WHITE_ASH, x, y, z, 28, w, 0.5, w, 0.04);
        level.sendParticles(ParticleTypes.CLOUD, x, y, z, 16, w * 0.8, 0.35, w * 0.8, 0.02);
        level.sendParticles(SrFx.CHARGE_DUST, x, y, z, 12, w * 0.7, 0.35, w * 0.7, 0);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, 10, w * 0.6, 0.3, w * 0.6, 0.04);
        spawnGroundRing(level, x, target.getY() + 0.08, z, Math.max(0.4, target.getBbWidth() * 0.7));
        level.playSound(null, target.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.45f, 0.8f);
        level.playSound(null, target.blockPosition(), SoundEvents.REDSTONE_TORCH_BURNOUT, SoundSource.PLAYERS, 0.5f, 1.4f);
        level.playSound(null, target.blockPosition(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 0.35f, 0.5f);
    }

    public static boolean tryPlaceZone(ServerPlayer player) {
        LivingEntity focus = LookTargets.findLookLiving(player);
        if (focus == null) {
            ActionBars.show(player, Component.translatable("ui.static_resonance.dust_zone_miss"));
            return false;
        }
        int cost = SrConfig.COMMON.dust.zoneCost.get();
        int charge = ModeService.getResourceValue(player, ModeService.CHARGE_POWER).orElse(0);
        if (charge < cost) {
            ActionBars.show(player, Component.translatable("ui.static_resonance.charge_too_low"));
            return false;
        }
        if (!ApoliResources.setValue(player, ModeService.CHARGE_POWER, charge - cost)) {
            return false;
        }
        ApoliResources.sync(player);

        Vec3 at = focus.position();
        ZONES.put(player.getUUID(), new DustZone(player.serverLevel().dimension(),
                at.x, at.y, at.z,
                player.level().getGameTime() + SrConfig.COMMON.dust.zoneDurationTicks.get(),
                SrConfig.COMMON.dust.zoneRadius.get()));

        ServerLevel level = player.serverLevel();
        level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 1.0, at.z, 24, 1.4, 0.6, 1.4, 0.02);
        level.sendParticles(ParticleTypes.WHITE_ASH, at.x, at.y + 1.0, at.z, 28, 1.8, 0.7, 1.8, 0.04);
        level.sendParticles(SrFx.CHARGE_DUST, at.x, at.y + 1.0, at.z, 16, 1.5, 0.5, 1.5, 0);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 1.0, at.z, 8, 1.2, 0.4, 1.2, 0.04);
        spawnGroundRing(level, at.x, at.y + 0.08, at.z, SrConfig.COMMON.dust.zoneRadius.get() * 0.45);
        level.playSound(null, focus.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.4f, 0.65f);
        level.playSound(null, focus.blockPosition(), SoundEvents.REDSTONE_TORCH_BURNOUT, SoundSource.PLAYERS, 0.45f, 1.2f);
        ActionBars.show(player, Component.translatable("ui.static_resonance.dust_zone_placed"));
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ZONES.isEmpty()) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (UUID id : ZONES.keySet()) {
            DustZone zone = ZONES.get(id);
            if (zone == null) {
                continue;
            }
            ServerLevel level = server.getLevel(zone.dimension);
            if (level == null || now >= zone.expireAt) {
                ZONES.remove(id);
                ServerPlayer owner = server.getPlayerList().getPlayer(id);
                if (owner != null) {
                    ActionBars.show(owner, Component.translatable("ui.static_resonance.dust_zone_end"));
                }
                continue;
            }
            if (now % ZONE_TICK_INTERVAL != 0) {
                continue;
            }
            tickZone(server.getPlayerList().getPlayer(id), level, zone);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ZONES.remove(event.getEntity().getUUID());
    }

    private static void tickZone(ServerPlayer owner, ServerLevel level, DustZone zone) {
        double r = zone.radius;
        AABB box = new AABB(zone.x - r, zone.y - 1.0, zone.z - r, zone.x + r, zone.y + 3.0, zone.z + r);
        double rSq = r * r;
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, e ->
                e.isAlive() && e.distanceToSqr(zone.x, zone.y, zone.z) <= rSq)) {
            if (owner != null && AllyService.isProtected(owner, living)) {
                continue;
            }
            if (owner != null && living == owner) {
                continue;
            }
            var blindness = living.getEffect(MobEffects.BLINDNESS);
            if (blindness == null || blindness.getDuration() < 20) {
                living.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLIND_REFRESH_TICKS, 0, false, true, true));
            }
        }
        level.sendParticles(ParticleTypes.WHITE_ASH, zone.x, zone.y + 1.0, zone.z, 8, 1.6, 0.5, 1.6, 0.02);
        level.sendParticles(ParticleTypes.CLOUD, zone.x, zone.y + 1.1, zone.z, 6, 1.4, 0.4, 1.4, 0.01);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, zone.x, zone.y + 1.0, zone.z, 2, 1.2, 0.35, 1.2, 0.02);
    }

    private static void spawnGroundRing(ServerLevel level, double cx, double cy, double cz, double radius) {
        double r = Math.max(0.35, radius);
        for (int i = 0; i < GROUND_RING_POINTS; i++) {
            double a = (Math.PI * 2.0 * i) / GROUND_RING_POINTS;
            level.sendParticles(SrFx.CHARGE_DUST, cx + Math.cos(a) * r, cy, cz + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    private static final class DustZone {
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        final double x;
        final double y;
        final double z;
        final long expireAt;
        final double radius;

        DustZone(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                 double x, double y, double z, long expireAt, double radius) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.expireAt = expireAt;
            this.radius = radius;
        }
    }
}
