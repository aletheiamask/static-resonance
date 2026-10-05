package com.staticresonance.mode;

import com.staticresonance.StaticResonanceMod;
import com.staticresonance.config.SrConfig;
import com.staticresonance.logic.ExpireClock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faraday Cage activation, repel aura, and every-tick projectile intercept
 * (vanilla arrows plus TACZ kinetic bullets).
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID)
public final class FaradayService {
    public enum ActivateReason {
        MANUAL,
        EMERGENCY
    }

    public static final ResourceLocation EMERGENCY_POWER =
            new ResourceLocation(StaticResonanceMod.MOD_ID, "systems/faraday_emergency");
    public static final double HARD_PUSH = 0.45;
    public static final double MID_PUSH = 0.12;
    public static final double CATCH_RADIUS = 4.8;
    public static final double HOLD_RADIUS = 10.0;

    private static final TagKey<EntityType<?>> PROJECTILES = TagKey.create(
            Registries.ENTITY_TYPE, new ResourceLocation(StaticResonanceMod.MOD_ID, "projectiles"));

    private static final String OBJ_FARADAY = "sr_faraday";
    private static final String OBJ_FARADAY_CD = "sr_faraday_cd";
    private static final int REPEL_VFX_INTERVAL = 5;
    private static final int HUD_INTERVAL = 10;

    private static final Set<UUID> ACTIVE_CAGES = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> COOLDOWNS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> CAGE_EXPIRE_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CD_EXPIRE_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, HeldShot> HELD = new ConcurrentHashMap<>();
    private static final Map<EntityType<?>, Boolean> GUNSHOT_TYPES = new ConcurrentHashMap<>();
    private static final double STILL_MOTION_SQ = 1.0e-8;
    private static final double HARD_WALL_EPS = 0.08;
    private static final double HARD_PUSH_EPS_SQ = 1.0e-6;
    private static final int CLOCK_INTERVAL = 5;
    private static final int REPEL_SLOW_TICKS = 50;
    private static final int REPEL_SLOW_REFRESH = 20;
    private static final int CAGE_RESIST_TICKS = 120;
    private static final int CAGE_RESIST_AMP = 4;
    private static final int EMERGENCY_CACHE_TTL = 20;
    private static final Map<UUID, EmergVal> EMERGENCY_CACHE = new ConcurrentHashMap<>();

    private static UUID blockCacheId;
    private static long blockCacheTick = Long.MIN_VALUE;
    private static boolean blockCacheResult;

    private FaradayService() {
    }

    public static boolean isEmergencyEnabled(Player player) {
        if (!player.level().isClientSide() && !ModeService.hasModePower(player)) {
            return false;
        }
        UUID id = player.getUUID();
        long now = player.level().getGameTime();
        EmergVal cached = EMERGENCY_CACHE.get(id);
        if (cached != null && now - cached.at <= EMERGENCY_CACHE_TTL) {
            return cached.on;
        }
        boolean on = ApoliResources.getValue(player, EMERGENCY_POWER).orElse(1) != 0;
        EMERGENCY_CACHE.put(id, new EmergVal(on, now));
        return on;
    }

    public static boolean canActivateCage(Player player) {
        if (ACTIVE_CAGES.contains(player.getUUID())) {
            return false;
        }
        UUID id = player.getUUID();
        if (!COOLDOWNS.contains(id)) {
            return true;
        }
        Long expire = CD_EXPIRE_AT.get(id);
        if (expire == null) {
            return getScore(player.getScoreboard(), player, OBJ_FARADAY_CD) <= 0;
        }
        return expire <= player.level().getGameTime();
    }

    public static boolean isCageActive(Player player) {
        return ACTIVE_CAGES.contains(player.getUUID());
    }

    public static boolean toggleEmergency(ServerPlayer player) {
        int cur = ApoliResources.getValue(player, EMERGENCY_POWER).orElse(1);
        int next = cur == 0 ? 1 : 0;
        ApoliResources.setValue(player, EMERGENCY_POWER, next);
        ApoliResources.sync(player);
        EMERGENCY_CACHE.put(player.getUUID(), new EmergVal(next != 0, player.level().getGameTime()));
        return next != 0;
    }

    /**
     * Manual G toggles the cage (raise costs config manualCost; lower is free).
     * Emergency never spends and never dismisses an existing cage.
     */
    public static boolean tryActivate(ServerPlayer player, ActivateReason reason) {
        if (!ModeService.hasModePower(player)) {
            return false;
        }

        if (reason == ActivateReason.MANUAL && isCageActive(player)) {
            return tryDeactivate(player);
        }

        if (reason == ActivateReason.MANUAL) {
            if (!canActivateCage(player)) {
                return false;
            }
            if (ModeService.getMode(player) != ResonanceMode.OVERCHARGE) {
                return false;
            }
            int cost = SrConfig.COMMON.faraday.manualCost.get();
            int preCharge = ModeService.getResourceValue(player, ModeService.CHARGE_POWER).orElse(0);
            if (preCharge < cost) {
                ActionBars.show(player, Component.translatable("ui.static_resonance.charge_too_low"));
                return false;
            }
            int next = preCharge - cost;
            ApoliResources.setValue(player, ModeService.CHARGE_POWER, next);
            ApoliResources.sync(player);
            armCage(player);
            showCountdown(player);
            return true;
        }

        if (!isEmergencyEnabled(player) || isCageActive(player)) {
            return false;
        }
        armCage(player);
        runFunction(player, "function static_resonance:faraday/emergency_fx");
        sendEmergencyReport(player);
        return true;
    }

    public static boolean tryDeactivate(ServerPlayer player) {
        if (!isCageActive(player)) {
            return false;
        }
        releaseNear(player);
        runFunction(player, "function static_resonance:faraday/deactivate");
        ensureObjectives(player);
        UUID id = player.getUUID();
        CAGE_EXPIRE_AT.remove(id);
        ACTIVE_CAGES.remove(id);
        setScore(player, OBJ_FARADAY, 0);
        int cooldown = SrConfig.COMMON.faraday.cooldownTicks.get();
        if (cooldown > 0) {
            CD_EXPIRE_AT.put(id, player.level().getGameTime() + cooldown);
            setScore(player, OBJ_FARADAY_CD, cooldown);
            COOLDOWNS.add(id);
        } else {
            CD_EXPIRE_AT.remove(id);
            setScore(player, OBJ_FARADAY_CD, 0);
            COOLDOWNS.remove(id);
        }
        return true;
    }

    private static void armCage(ServerPlayer player) {
        runFunction(player, "function static_resonance:faraday/activate");
        ensureObjectives(player);
        int duration = SrConfig.COMMON.faraday.durationTicks.get();
        UUID id = player.getUUID();
        CAGE_EXPIRE_AT.put(id, player.level().getGameTime() + duration);
        setScore(player, OBJ_FARADAY, duration);
        ACTIVE_CAGES.add(id);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        tickHeld(server.overworld());
        long now = server.overworld().getGameTime();
        tickCageExpiry(server, now);
        if (now % CLOCK_INTERVAL == 0L) {
            tickCageFx(server);
        }
        if (ACTIVE_CAGES.isEmpty()) {
            return;
        }
        for (UUID id : ACTIVE_CAGES) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                ACTIVE_CAGES.remove(id);
                CAGE_EXPIRE_AT.remove(id);
                continue;
            }
            if (now % 2 == 0) {
                tickAura(player, now);
            }
            if (now % HUD_INTERVAL == 0) {
                showCountdown(player);
            }
        }
    }

    /** Chat report for an emergency raise; duration comes from config, not a fixed string. */
    private static void sendEmergencyReport(ServerPlayer player) {
        String sec = String.format(java.util.Locale.ROOT, "%.1f",
                SrConfig.COMMON.faraday.durationTicks.get() / 20.0);
        player.sendSystemMessage(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("SR-AEGIS").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatable("ui.static_resonance.emergency_vitals")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(Component.translatable("ui.static_resonance.emergency_reroute")
                        .withStyle(ChatFormatting.GRAY)));
        player.sendSystemMessage(Component.translatable("ui.static_resonance.emergency_grid", sec)
                .withStyle(ChatFormatting.DARK_AQUA));
    }

    private static void showCountdown(ServerPlayer player) {
        int ticks = remainingTicks(CAGE_EXPIRE_AT, player.getUUID(), player, OBJ_FARADAY,
                player.level().getGameTime());
        int sec = Math.max(0, ticks / 20);
        player.displayClientMessage(Component.translatable("ui.static_resonance.faraday_status", sec), true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (idleCombat()) {
            return;
        }
        if (shouldBlock(event.getProjectile())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(LivingAttackEvent event) {
        if (idleCombat() || event.isCanceled() || event.getEntity().level().isClientSide()) {
            return;
        }
        if (shouldBlock(event.getSource().getDirectEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onHurt(LivingHurtEvent event) {
        if (idleCombat() || event.isCanceled() || event.getEntity().level().isClientSide()) {
            return;
        }
        if (shouldBlock(event.getSource().getDirectEntity())) {
            event.setCanceled(true);
        }
    }

    /** Emergency floor. Do not idle-gate on cages — the first lethal hit must still raise. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDamage(LivingDamageEvent event) {
        if (event.isCanceled() || event.getEntity().level().isClientSide()) {
            return;
        }
        capIncoming(event.getEntity(), event.getAmount(), event.getSource().getDirectEntity(),
                event::setAmount, () -> event.setCanceled(true));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!ModeService.hasModePower(player) || !isEmergencyEnabled(player)) {
            return;
        }
        if (!isCageActive(player) && !tryActivate(player, ActivateReason.EMERGENCY)) {
            return;
        }
        player.setHealth(emergencyMinHealth());
        event.setCanceled(true);
    }

    private static boolean capIncoming(LivingEntity victim, float incoming, Entity direct,
                                       java.util.function.Consumer<Float> setAmount, Runnable cancel) {
        if (!(victim instanceof ServerPlayer player) || incoming <= 0.0f) {
            return false;
        }
        if (!ModeService.hasModePower(player) || !isEmergencyEnabled(player)) {
            return false;
        }
        if (player.getHealth() - incoming >= emergencyMinHealth()) {
            return false;
        }
        if (!isCageActive(player)) {
            tryActivate(player, ActivateReason.EMERGENCY);
        }
        if (isInterceptable(direct) && isCageActive(player)) {
            if (player.level() instanceof ServerLevel sl) {
                catchEntity(sl, direct);
            }
            cancel.run();
            return true;
        }
        float maxDamage = Math.max(0.0f, player.getHealth() - emergencyMinHealth());
        if (incoming > maxDamage) {
            setAmount.accept(maxDamage);
        }
        return false;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID id = player.getUUID();
            ACTIVE_CAGES.remove(id);
            COOLDOWNS.remove(id);
            CAGE_EXPIRE_AT.remove(id);
            CD_EXPIRE_AT.remove(id);
            EMERGENCY_CACHE.remove(id);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        long now = player.level().getGameTime();
        UUID id = player.getUUID();
        int left = getScore(player.getScoreboard(), player, OBJ_FARADAY);
        if (left > 0) {
            CAGE_EXPIRE_AT.put(id, now + left);
            ACTIVE_CAGES.add(id);
        }
        int cd = getScore(player.getScoreboard(), player, OBJ_FARADAY_CD);
        if (cd > 0) {
            CD_EXPIRE_AT.put(id, now + cd);
            COOLDOWNS.add(id);
        }
    }

    /** Keep attack/hurt callers; TACZ bullets may skip {@code ProjectileImpactEvent}. */
    private static boolean shouldBlock(Entity entity) {
        if (entity == null || !isInterceptable(entity)) {
            return false;
        }
        UUID id = entity.getUUID();
        long now = entity.level().getGameTime();
        if (id.equals(blockCacheId) && now == blockCacheTick) {
            return blockCacheResult;
        }
        boolean result = HELD.containsKey(id)
                || (!ACTIVE_CAGES.isEmpty() && nearestCage(entity) != null);
        blockCacheId = id;
        blockCacheTick = now;
        blockCacheResult = result;
        return result;
    }

    private static void catchEntity(ServerLevel level, Entity entity) {
        if (HELD.containsKey(entity.getUUID())) {
            freeze(entity);
            return;
        }
        if (isCustomGunshot(entity)) {
            // TACZ bullets ignore vanilla Motion; eating the shot is the reliable block.
            spawnCatchSparks(level, entity);
            entity.discard();
            return;
        }
        HELD.put(entity.getUUID(), new HeldShot(entity.getUUID(), level.dimension(),
                SrConfig.COMMON.faraday.holdTicks.get(), entity));
        entity.setNoGravity(true);
        freeze(entity);
        spawnCatchSparks(level, entity);
    }

    private static void tickHeld(ServerLevel anyLevel) {
        if (HELD.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, HeldShot>> it = HELD.entrySet().iterator();
        while (it.hasNext()) {
            HeldShot held = it.next().getValue();
            Entity entity = findEntity(anyLevel.getServer(), held);
            if (entity == null || !entity.isAlive()) {
                it.remove();
                continue;
            }
            held.ticksLeft -= 1;
            if (held.ticksLeft % 2 == 0 || held.ticksLeft <= 0) {
                ServerPlayer cage = nearestCage(entity);
                if (cage == null || entity.distanceToSqr(cage) > HOLD_RADIUS * HOLD_RADIUS) {
                    release(entity);
                    it.remove();
                    continue;
                }
            }
            freeze(entity);
            if (held.ticksLeft % 5 == 0 && entity.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                        entity.getX(), entity.getY(), entity.getZ(), 2, 0.08, 0.08, 0.08, 0.01);
            }
            if (held.ticksLeft <= 0) {
                release(entity);
                it.remove();
            }
        }
    }

    private static void releaseNear(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        double r = HOLD_RADIUS;
        Vec3 pos = player.position();
        AABB box = new AABB(pos.x - r, pos.y - 2, pos.z - r, pos.x + r, pos.y + 3, pos.z + r);
        for (Entity entity : level.getEntities(player, box, e -> HELD.containsKey(e.getUUID()))) {
            release(entity);
            HELD.remove(entity.getUUID());
        }
    }

    private static void release(Entity entity) {
        entity.setNoGravity(false);
        entity.setDeltaMovement(0.0, -0.15, 0.0);
        entity.hurtMarked = true;
        if (entity.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.SMOKE,
                    entity.getX(), entity.getY(), entity.getZ(), 4, 0.1, 0.1, 0.1, 0.01);
        }
    }

    private static boolean idleCombat() {
        return ACTIVE_CAGES.isEmpty() && HELD.isEmpty();
    }

    private static void freeze(Entity entity) {
        Vec3 motion = entity.getDeltaMovement();
        boolean drifted = motion.lengthSqr() > STILL_MOTION_SQ
                || entity.xo != entity.getX()
                || entity.yo != entity.getY()
                || entity.zo != entity.getZ();
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setNoGravity(true);
        if (!drifted) {
            return;
        }
        entity.hasImpulse = true;
        entity.hurtMarked = true;
    }

    private static void spawnCatchSparks(ServerLevel level, Entity entity) {
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                entity.getX(), entity.getY(), entity.getZ(), 8, 0.15, 0.15, 0.15, 0.05);
    }

    private static boolean isInterceptable(Entity entity) {
        if (entity instanceof LivingEntity || entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            return false;
        }
        if (entity instanceof Projectile || entity.getType().is(PROJECTILES)) {
            return true;
        }
        return isCustomGunshot(entity);
    }

    private static boolean isCustomGunshot(Entity entity) {
        EntityType<?> type = entity.getType();
        Boolean cached = GUNSHOT_TYPES.get(type);
        if (cached != null) {
            return cached;
        }
        boolean hit = classifyGunshot(type);
        GUNSHOT_TYPES.put(type, hit);
        return hit;
    }

    private static boolean classifyGunshot(EntityType<?> type) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
        if (id == null) {
            return false;
        }
        String path = id.getPath();
        if ("tacz".equals(id.getNamespace())) {
            return path.contains("bullet") || path.contains("ammo") || path.contains("kinetic");
        }
        return path.contains("bullet") || path.contains("projectile");
    }

    private static ServerPlayer nearestCage(Entity entity) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        int ecx = entity.getBlockX() >> 4;
        int ecy = entity.getBlockY() >> 4;
        int ecz = entity.getBlockZ() >> 4;
        ServerPlayer best = null;
        double bestDist = CATCH_RADIUS * CATCH_RADIUS;
        for (UUID id : ACTIVE_CAGES) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || player.level() != entity.level()) {
                continue;
            }
            if (Math.abs((player.getBlockX() >> 4) - ecx) >= 2
                    || Math.abs((player.getBlockY() >> 4) - ecy) >= 2
                    || Math.abs((player.getBlockZ() >> 4) - ecz) >= 2) {
                continue;
            }
            double d = player.distanceToSqr(entity);
            if (d <= bestDist) {
                bestDist = d;
                best = player;
            }
        }
        return best;
    }

    private static Entity findEntity(MinecraftServer server, HeldShot held) {
        if (held.cached != null && held.cached.isAlive() && !held.cached.isRemoved()) {
            return held.cached;
        }
        ServerLevel level = server.getLevel(held.dim);
        Entity found = level == null ? null : level.getEntity(held.id);
        held.cached = found;
        return found;
    }

    private static void tickAura(ServerPlayer player, long now) {
        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();
        double r = SrConfig.COMMON.faraday.repelRadius.get();
        boolean showVfx = now % REPEL_VFX_INTERVAL == 0;
        double catchSq = CATCH_RADIUS * CATCH_RADIUS;
        AABB catchBox = new AABB(
                pos.x - CATCH_RADIUS, pos.y - 1.5, pos.z - CATCH_RADIUS,
                pos.x + CATCH_RADIUS, pos.y + 2.5, pos.z + CATCH_RADIUS
        );
        for (Entity entity : level.getEntities(player, catchBox, FaradayService::isInterceptable)) {
            if (entity.distanceToSqr(pos) <= catchSq) {
                catchEntity(level, entity);
            }
        }
        AABB repelBox = new AABB(
                pos.x - r, pos.y - 1.5, pos.z - r,
                pos.x + r, pos.y + 2.5, pos.z + r
        );
        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, repelBox, e ->
                e.isAlive() && e != player)) {
            double dx = living.getX() - pos.x;
            double dz = living.getZ() - pos.z;
            double distSq = dx * dx + dz * dz;
            if (distSq > r * r) {
                continue;
            }
            double dist = Math.sqrt(Math.max(distSq, 1.0e-4));
            double nx = dx / dist;
            double nz = dz / dist;
            if (distSq < 1.0e-4) {
                nx = 1.0;
                nz = 0.0;
            }

            double hardR = SrConfig.COMMON.faraday.hardRadius.get();
            if (dist <= hardR) {
                double wall = hardR + 0.05;
                if (Math.abs(dist - wall) > HARD_WALL_EPS) {
                    living.setPos(pos.x + nx * wall, living.getY(), pos.z + nz * wall);
                }
                Vec3 m = living.getDeltaMovement();
                Vec3 push = new Vec3(nx * HARD_PUSH, m.y, nz * HARD_PUSH);
                if (m.distanceToSqr(push) > HARD_PUSH_EPS_SQ) {
                    living.setDeltaMovement(push);
                    living.hurtMarked = true;
                }
                if (showVfx) {
                    level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                            living.getX(), living.getY() + living.getBbHeight() * 0.5, living.getZ(),
                            5, 0.15, 0.2, 0.15, 0.03);
                }
                continue;
            }

            if (dist <= SrConfig.COMMON.faraday.midRadius.get()) {
                refreshRepelSlow(living, 2);
                Vec3 m = living.getDeltaMovement();
                living.setDeltaMovement(m.x + nx * MID_PUSH, m.y, m.z + nz * MID_PUSH);
                living.hurtMarked = true;
            } else {
                refreshRepelSlow(living, 0);
            }
            if (showVfx) {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                        living.getX(), living.getY() + living.getBbHeight() * 0.5, living.getZ(),
                        2, 0.1, 0.15, 0.1, 0.01);
            }
        }
    }

    private static void refreshRepelSlow(LivingEntity living, int amplifier) {
        var slowness = living.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        if (slowness != null
                && slowness.getAmplifier() >= amplifier
                && slowness.getDuration() >= REPEL_SLOW_REFRESH) {
            return;
        }
        living.addEffect(new MobEffectInstance(
                MobEffects.MOVEMENT_SLOWDOWN, REPEL_SLOW_TICKS, amplifier, true, false, true));
    }

    private static void runFunction(ServerPlayer player, String command) {
        var server = player.getServer();
        if (server == null) {
            return;
        }
        server.getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withSuppressedOutput().withPermission(2),
                command);
    }

    static void ensureObjectives(Player player) {
        SrScoreboards.ensureAll(player.getScoreboard());
    }

    /** Expire maps are the clock; leftover scores are a HUD/datapack mirror. */
    private static void tickCageExpiry(MinecraftServer server, long now) {
        if (!COOLDOWNS.isEmpty()) {
            for (UUID id : COOLDOWNS) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null) {
                    COOLDOWNS.remove(id);
                    CD_EXPIRE_AT.remove(id);
                    continue;
                }
                if (isCageActive(player)) {
                    continue;
                }
                int remaining = remainingTicks(CD_EXPIRE_AT, id, player, OBJ_FARADAY_CD, now);
                if (remaining <= 0) {
                    setScore(player, OBJ_FARADAY_CD, 0);
                    CD_EXPIRE_AT.remove(id);
                    COOLDOWNS.remove(id);
                } else if (now % HUD_INTERVAL == 0L) {
                    setScore(player, OBJ_FARADAY_CD, remaining);
                }
            }
        }
        if (ACTIVE_CAGES.isEmpty()) {
            return;
        }
        for (UUID id : ACTIVE_CAGES) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                ACTIVE_CAGES.remove(id);
                CAGE_EXPIRE_AT.remove(id);
                continue;
            }
            int remaining = remainingTicks(CAGE_EXPIRE_AT, id, player, OBJ_FARADAY, now);
            if (remaining <= 0) {
                tryDeactivate(player);
            } else if (now % HUD_INTERVAL == 0L) {
                setScore(player, OBJ_FARADAY, remaining);
            }
        }
    }

    private static int remainingTicks(Map<UUID, Long> expires, UUID id, ServerPlayer player,
                                      String objective, long now) {
        Long expire = expires.get(id);
        if (expire == null) {
            int left = getScore(player.getScoreboard(), player, objective);
            if (left <= 0) {
                return 0;
            }
            expires.put(id, now + left);
            return left;
        }
        return ExpireClock.remaining(expire, now);
    }

    private static void tickCageFx(MinecraftServer server) {
        if (ACTIVE_CAGES.isEmpty()) {
            return;
        }
        for (UUID id : ACTIVE_CAGES) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                continue;
            }
            refreshCageEffects(player);
            spawnCageSilhouette(player);
        }
    }

    private static void refreshCageEffects(ServerPlayer player) {
        var resist = player.getEffect(MobEffects.DAMAGE_RESISTANCE);
        if (resist == null || resist.getAmplifier() < CAGE_RESIST_AMP || resist.getDuration() < 40) {
            player.addEffect(new MobEffectInstance(
                    MobEffects.DAMAGE_RESISTANCE, CAGE_RESIST_TICKS, CAGE_RESIST_AMP, true, false, true));
        }
        var fire = player.getEffect(MobEffects.FIRE_RESISTANCE);
        if (fire == null || fire.getDuration() < 40) {
            player.addEffect(new MobEffectInstance(
                    MobEffects.FIRE_RESISTANCE, CAGE_RESIST_TICKS, 0, true, false, true));
        }
    }

    private static void spawnCageSilhouette(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        level.sendParticles(ParticleTypes.END_ROD, x, y + 0.08, z, 3, 0.7, 0.02, 0.7, 0);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y + 1.2, z, 6, 0.7, 0.6, 0.7, 0.02);
        level.sendParticles(ParticleTypes.GLOW, x, y + 2.05, z, 2, 0.25, 0.08, 0.25, 0);
    }

    private static int getScore(Scoreboard board, Player player, String objective) {
        Objective obj = board.getObjective(objective);
        if (obj == null) {
            return 0;
        }
        String name = player.getScoreboardName();
        if (!board.hasPlayerScore(name, obj)) {
            return 0;
        }
        return board.getOrCreatePlayerScore(name, obj).getScore();
    }

    private static float emergencyMinHealth() {
        return SrConfig.COMMON.faraday.emergencyMinHealth.get().floatValue();
    }

    private static void setScore(ServerPlayer player, String objective, int value) {
        Scoreboard board = player.getScoreboard();
        Objective obj = board.getObjective(objective);
        if (obj == null) {
            return;
        }
        board.getOrCreatePlayerScore(player.getScoreboardName(), obj).setScore(value);
    }

    private record EmergVal(boolean on, long at) {
    }

    private static final class HeldShot {
        final UUID id;
        final ResourceKey<Level> dim;
        int ticksLeft;
        @Nullable
        Entity cached;

        HeldShot(UUID id, ResourceKey<Level> dim, int ticksLeft, @Nullable Entity cached) {
            this.id = id;
            this.dim = dim;
            this.ticksLeft = ticksLeft;
            this.cached = cached;
        }
    }
}
