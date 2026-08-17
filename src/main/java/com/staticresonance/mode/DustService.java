package com.staticresonance.mode;

import com.staticresonance.StaticResonanceMod;
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.level.block.Blocks;
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
    public static final int BURST_COST = 5;
    public static final int ZONE_COST = 10;
    public static final double ZONE_RADIUS = 6.0;
    public static final int ZONE_DURATION_TICKS = 200;
    public static final int BLIND_REFRESH_TICKS = 40;
    private static final int ZONE_TICK_INTERVAL = 4;

    private static final Map<UUID, DustZone> ZONES = new ConcurrentHashMap<>();
    private static final BlockParticleOption SAND_DUST =
            new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState());

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
        int charge = ModeService.getResourceValue(player, ModeService.CHARGE_POWER).orElse(0);
        if (charge < BURST_COST) {
            ActionBars.show(player, Component.translatable("ui.static_resonance.charge_too_low"));
            return false;
        }
        if (!ApoliResources.setValue(player, ModeService.CHARGE_POWER, charge - BURST_COST)) {
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
        level.sendParticles(SAND_DUST, x, y, z, 28, w, 0.5, w, 0.04);
        level.sendParticles(ParticleTypes.CLOUD, x, y, z, 16, w * 0.8, 0.35, w * 0.8, 0.02);
        level.sendParticles(ParticleTypes.CRIT, x, y, z, 10, w * 0.6, 0.3, w * 0.6, 0.01);
        level.playSound(null, target.blockPosition(), SoundEvents.SAND_BREAK, SoundSource.PLAYERS, 1.0f, 0.7f);
        level.playSound(null, target.blockPosition(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 0.35f, 0.5f);
    }

    public static boolean tryPlaceZone(ServerPlayer player) {
        LivingEntity focus = LookTargets.findLookLiving(player);
        if (focus == null) {
            ActionBars.show(player, Component.translatable("ui.static_resonance.dust_zone_miss"));
            return false;
        }
        int charge = ModeService.getResourceValue(player, ModeService.CHARGE_POWER).orElse(0);
        if (charge < ZONE_COST) {
            ActionBars.show(player, Component.translatable("ui.static_resonance.charge_too_low"));
            return false;
        }
        if (!ApoliResources.setValue(player, ModeService.CHARGE_POWER, charge - ZONE_COST)) {
            return false;
        }
        ApoliResources.sync(player);

        Vec3 at = focus.position();
        ZONES.put(player.getUUID(), new DustZone(player.serverLevel().dimension(),
                at.x, at.y, at.z, player.level().getGameTime() + ZONE_DURATION_TICKS));

        ServerLevel level = player.serverLevel();
        level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 1.0, at.z, 24, 1.4, 0.6, 1.4, 0.02);
        level.sendParticles(SAND_DUST, at.x, at.y + 1.0, at.z, 40, 1.8, 0.7, 1.8, 0.05);
        level.playSound(null, focus.blockPosition(), SoundEvents.SAND_BREAK, SoundSource.PLAYERS, 1.0f, 0.7f);
        level.playSound(null, focus.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.35f, 0.6f);
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
        double r = ZONE_RADIUS;
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
            living.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLIND_REFRESH_TICKS, 0, false, true, true));
        }
        level.sendParticles(SAND_DUST, zone.x, zone.y + 1.0, zone.z, 10, 1.6, 0.5, 1.6, 0.02);
        level.sendParticles(ParticleTypes.CLOUD, zone.x, zone.y + 1.1, zone.z, 6, 1.4, 0.4, 1.4, 0.01);
    }

    private static final class DustZone {
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        final double x;
        final double y;
        final double z;
        final long expireAt;

        DustZone(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                 double x, double y, double z, long expireAt) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.expireAt = expireAt;
        }
    }
}
