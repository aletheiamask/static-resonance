package com.staticresonance.mode;

import com.staticresonance.StaticResonanceMod;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ItemStackedOnOtherEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.entity.living.LivingSwapItemsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Control style (target vs field) + expanding Control Field session.
 * Single lock-apply path for both target and field modes.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID)
public final class ControlService {
    public static final ResourceLocation STYLE_POWER =
            new ResourceLocation(StaticResonanceMod.MOD_ID, "systems/control_style");
    public static final ResourceLocation FIELD_ACTIVE_POWER =
            new ResourceLocation(StaticResonanceMod.MOD_ID, "systems/field_active");

    public static final int STYLE_TARGET = 0;
    public static final int STYLE_FIELD = 1;
    public static final int FIELD_COST = 40;
    public static final int TARGET_COST = 15;
    public static final int FIELD_MAX_RADIUS = 30;
    public static final int FIELD_DURATION_TICKS = 1200;
    public static final int FIELD_EXPAND_INTERVAL = 5;
    public static final double MOVE_ABORT_THRESHOLD = 0.12;
    /** Max lock distance — roughly entity-visible range, not “nearest next to feet”. */
    public static final double TARGET_RANGE = 64.0;
    /** Half-angle cone (degrees) for “I can see it” soft aim after precise ray miss. */
    private static final double LOOK_CONE_DEG = 40.0;

    private static final int LOCK_EFFECT_TICKS = 100;
    private static final int STATUS_INTERVAL = 20;
    private static final int SWEEP_INTERVAL = 4;
    private static final int REFRESH_WHEN_REMAINING = 20;

    /** Authority for active field sessions. */
    private static final Map<UUID, FieldSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, HandLock> HANDS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> RESTORING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final DustParticleOptions RING_DUST =
            new DustParticleOptions(new Vector3f(0.70f, 0.53f, 1.0f), 1.1f);

    private ControlService() {
    }

    public static int getStyle(Player player) {
        return ApoliResources.getValue(player, STYLE_POWER).orElse(STYLE_TARGET);
    }

    public static boolean setStyle(ServerPlayer player, int style) {
        if (!ApoliResources.hasPower(player, STYLE_POWER)) {
            return false;
        }
        int v = style == STYLE_FIELD ? STYLE_FIELD : STYLE_TARGET;
        if (!ApoliResources.setValue(player, STYLE_POWER, v)) {
            return false;
        }
        ApoliResources.sync(player);
        return true;
    }

    /** Session map is authority; resource is a mirror for Origins conditions. */
    public static boolean isFieldActive(Player player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    /**
     * Atomic field start: guards → spend charge → open session.
     * Callers must not spend charge beforehand.
     */
    public static boolean tryStartField(ServerPlayer player) {
        if (!ModeService.hasModePower(player)) {
            return false;
        }
        if (ModeService.getMode(player) != ResonanceMode.CONTROL) {
            return false;
        }
        if (SESSIONS.containsKey(player.getUUID())) {
            return false;
        }
        int charge = ModeService.getResourceValue(player, ModeService.CHARGE_POWER).orElse(0);
        if (charge < FIELD_COST) {
            return false;
        }
        if (!ApoliResources.setValue(player, ModeService.CHARGE_POWER, charge - FIELD_COST)) {
            return false;
        }

        Vec3 pos = player.position();
        SESSIONS.put(player.getUUID(), new FieldSession(pos.x, pos.y, pos.z, player.level().getGameTime(), 0));
        setFieldActiveMirror(player, true);
        ApoliResources.sync(player);

        ServerLevel level = player.serverLevel();
        level.sendParticles(ParticleTypes.SONIC_BOOM, pos.x, pos.y + 1.0, pos.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.WITCH, pos.x, pos.y + 1.0, pos.z, 40, 0.6, 0.4, 0.6, 0.05);
        level.playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE,
                SoundSource.PLAYERS, 0.7f, 0.75f);
        level.playSound(null, player.blockPosition(), SoundEvents.RESPAWN_ANCHOR_CHARGE,
                SoundSource.PLAYERS, 0.55f, 0.9f);
        ActionBars.show(player, Component.translatable("ui.static_resonance.field_start"));
        return true;
    }

    public static void stopField(ServerPlayer player, StopReason reason) {
        FieldSession removed = SESSIONS.remove(player.getUUID());
        setFieldActiveMirror(player, false);
        ApoliResources.sync(player);
        if (removed == null && reason == StopReason.SILENT) {
            return;
        }
        // Effects expire on their own — do not strip tags from other players' locks.
        if (reason == StopReason.SILENT) {
            return;
        }
        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();
        level.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y + 1.0, pos.z, 24, 0.5, 0.4, 0.5, 0.02);
        level.playSound(null, player.blockPosition(), SoundEvents.BEACON_DEACTIVATE,
                SoundSource.PLAYERS, 0.5f, 1.2f);
        String key = reason == StopReason.MOVED
                ? "ui.static_resonance.field_abort_moved"
                : "ui.static_resonance.field_end";
        ActionBars.show(player, Component.translatable(key));
    }

    /**
     * Single-target lock: living entity under the player's look ray → spend {@link #TARGET_COST} → apply.
     * Atomic authority (Origins must not spend beforehand).
     *
     * @return true if a target was locked and charge spent
     */
    public static boolean lockLookTarget(ServerPlayer player) {
        if (!ModeService.hasModePower(player) || ModeService.getMode(player) != ResonanceMode.CONTROL) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        LivingEntity target = LookTargets.findLookLiving(player, TARGET_RANGE, LOOK_CONE_DEG,
                e -> isLockable(player, e));
        if (target == null) {
            level.playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_BASS.value(),
                    SoundSource.PLAYERS, 0.5f, 0.6f);
            ActionBars.show(player, Component.translatable("ui.static_resonance.magnetic_lock_miss"));
            return false;
        }
        int charge = ModeService.getResourceValue(player, ModeService.CHARGE_POWER).orElse(0);
        if (charge < TARGET_COST) {
            ActionBars.show(player, Component.translatable("ui.static_resonance.charge_too_low"));
            return false;
        }
        if (!ApoliResources.setValue(player, ModeService.CHARGE_POWER, charge - TARGET_COST)) {
            return false;
        }
        ApoliResources.sync(player);

        Vec3 pos = player.position();
        // Paid single-target cast: always full VFX (field path uses first-only to avoid spam)
        applyLock(player, target, level, true, false);
        spawnHeadRing(level, target);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y + 1.0, pos.z, 24, 0.55, 0.55, 0.55, 0.12);
        level.sendParticles(ParticleTypes.WITCH, pos.x, pos.y + 1.0, pos.z, 12, 0.4, 0.4, 0.4, 0.04);
        level.playSound(null, player.blockPosition(), SoundEvents.RESPAWN_ANCHOR_CHARGE,
                SoundSource.PLAYERS, 0.7f, 1.4f);
        level.playSound(null, player.blockPosition(), SoundEvents.ELDER_GUARDIAN_CURSE,
                SoundSource.PLAYERS, 0.25f, 1.8f);
        ActionBars.show(player, Component.translatable("ui.static_resonance.magnetic_lock_hit")
                .withStyle(s -> s.withColor(0xB388FF)));
        return true;
    }

    private static boolean isLockable(ServerPlayer player, Entity e) {
        return e instanceof LivingEntity living
                && living.isAlive()
                && living != player
                && !living.isSpectator()
                && !AllyService.isProtected(player, living);
    }

    /**
     * First lock (no lock-slowness yet): full CC + pull + VFX.
     * Refresh while already locked: effects only.
     */
    public static void applyLock(ServerPlayer viewer, LivingEntity target, ServerLevel level,
                                 boolean pullDown, boolean withVfx) {
        boolean first = !isUnderLock(target);
        if (first) {
            captureHands(target);
        }
        refreshLockEffects(viewer, target);
        if (pullDown) {
            Vec3 m = target.getDeltaMovement();
            target.setDeltaMovement(0, Math.min(m.y, -0.15), 0);
            target.hurtMarked = true;
        }
        if (withVfx && first) {
            spawnHeadRing(level, target);
        }
    }

    /** True while our lock slowness (amp 255) is active — expires with the effect (no permanent tag). */
    public static boolean isUnderLock(LivingEntity target) {
        var effect = target.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        return effect != null && effect.getAmplifier() >= 255;
    }

    private static void captureHands(LivingEntity target) {
        if (!(target instanceof ServerPlayer player)) {
            return;
        }
        HANDS.putIfAbsent(player.getUUID(), new HandLock(
                player.getInventory().selected,
                player.getMainHandItem().copy()));
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (HANDS.containsKey(player.getUUID()) && !isUnderLock(player)) {
            HANDS.remove(player.getUUID());
        }
    }

    public static void refreshLockEffects(ServerPlayer viewer, LivingEntity target) {
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, LOCK_EFFECT_TICKS, 255, false, true, true));
        target.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, LOCK_EFFECT_TICKS, 1, false, true, true));
        target.addEffect(new MobEffectInstance(MobEffects.GLOWING, LOCK_EFFECT_TICKS, 0, false, true, true));
    }

    private static boolean needsLockRefresh(LivingEntity target) {
        var slowness = target.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        return slowness == null
                || slowness.getAmplifier() < 255
                || slowness.getDuration() < REFRESH_WHEN_REMAINING;
    }

    public static void spawnHeadRing(ServerLevel level, Entity target) {
        double cx = target.getX();
        double bodyH = target.getBbHeight();
        double cy = target.getY() + bodyH + 0.35;
        double cz = target.getZ();
        double r = Math.max(0.45, target.getBbWidth() * 0.55);
        level.sendParticles(RING_DUST, cx, cy, cz, 16, r, 0.04, r, 0);
        level.sendParticles(ParticleTypes.WITCH, cx, cy, cz, 8, r * 0.6, 0.05, r * 0.6, 0);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, cx, target.getY() + bodyH * 0.5, cz,
                16, 0.3, bodyH * 0.25, 0.3, 0.05);
        level.sendParticles(ParticleTypes.FLASH, cx, target.getY() + bodyH * 0.6, cz, 1, 0, 0, 0, 0);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SESSIONS.isEmpty()) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (UUID id : SESSIONS.keySet()) {
            FieldSession session = SESSIONS.get(id);
            if (session == null) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                SESSIONS.remove(id);
                continue;
            }
            tickField(player, session);
        }
    }

    @SubscribeEvent
    public static void onSwapHands(LivingSwapItemsEvent.Hands event) {
        if (isUnderLock(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !isUnderLock(player)) {
            return;
        }
        HandLock lock = HANDS.get(player.getUUID());
        if (lock == null) {
            return;
        }
        ItemStack inSlot = player.getInventory().getItem(lock.slot);
        if (ItemStack.matches(inSlot, lock.stack)) {
            return;
        }
        event.setCanceled(true);
        restoreHands(player, lock);
    }

    @SubscribeEvent
    public static void onStackOnOther(ItemStackedOnOtherEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || !isUnderLock(player)) {
            return;
        }
        HandLock lock = HANDS.get(player.getUUID());
        if (lock == null) {
            return;
        }
        if (event.getSlot().container == player.getInventory()
                && event.getSlot().getContainerSlot() == lock.slot) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMainHandChange(LivingEquipmentChangeEvent event) {
        if (RESTORING.get() || event.getSlot() != EquipmentSlot.MAINHAND) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }
        if (!isUnderLock(player)) {
            HANDS.remove(player.getUUID());
            return;
        }
        HandLock lock = HANDS.get(player.getUUID());
        if (lock == null) {
            return;
        }
        restoreHands(player, lock);
    }

    private static void restoreHands(ServerPlayer player, HandLock lock) {
        RESTORING.set(Boolean.TRUE);
        try {
            if (player.getInventory().selected != lock.slot) {
                player.getInventory().selected = lock.slot;
                player.connection.send(new ClientboundSetCarriedItemPacket(lock.slot));
            }
            ItemStack current = player.getInventory().getItem(lock.slot);
            if (ItemStack.matches(current, lock.stack)) {
                return;
            }
            int found = findMatchingSlot(player, lock.stack, lock.slot);
            if (found >= 0) {
                ItemStack there = player.getInventory().getItem(found);
                player.getInventory().setItem(found, current);
                player.getInventory().setItem(lock.slot, there);
            } else if (!current.isEmpty()) {
                player.getInventory().placeItemBackInInventory(current.copy());
                player.getInventory().setItem(lock.slot, lock.stack.copy());
            } else {
                player.getInventory().setItem(lock.slot, lock.stack.copy());
            }
            player.inventoryMenu.broadcastChanges();
        } finally {
            RESTORING.set(Boolean.FALSE);
        }
    }

    private static int findMatchingSlot(ServerPlayer player, ItemStack want, int skip) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (i != skip && ItemStack.matches(player.getInventory().getItem(i), want)) {
                return i;
            }
        }
        return -1;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            HANDS.remove(player.getUUID());
            if (SESSIONS.remove(player.getUUID()) != null) {
                setFieldActiveMirror(player, false);
            }
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (!SESSIONS.containsKey(player.getUUID())
                    && ApoliResources.getValue(player, FIELD_ACTIVE_POWER).orElse(0) != 0) {
                setFieldActiveMirror(player, false);
                ApoliResources.sync(player);
            }
            if (isUnderLock(player)) {
                captureHands(player);
            }
        }
    }

    @SubscribeEvent
    public static void onChangeDim(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isUnderLock(player)) {
            captureHands(player);
        }
    }

    private static void tickField(ServerPlayer player, FieldSession session) {
        if (ModeService.getMode(player) != ResonanceMode.CONTROL) {
            stopField(player, StopReason.MODE_CHANGE);
            return;
        }
        long now = player.level().getGameTime();
        long elapsed = now - session.startGameTime;
        if (elapsed >= FIELD_DURATION_TICKS) {
            stopField(player, StopReason.TIMEOUT);
            return;
        }

        Vec3 pos = player.position();
        double dx = pos.x - session.anchorX;
        double dy = pos.y - session.anchorY;
        double dz = pos.z - session.anchorZ;
        if (dx * dx + dy * dy + dz * dz > MOVE_ABORT_THRESHOLD * MOVE_ABORT_THRESHOLD) {
            stopField(player, StopReason.MOVED);
            return;
        }

        int targetR = (int) Math.min(FIELD_MAX_RADIUS, elapsed / FIELD_EXPAND_INTERVAL);
        if (targetR > session.radius) {
            session.radius = targetR;
        }

        ServerLevel level = player.serverLevel();
        if (now % SWEEP_INTERVAL == 0) {
            if (session.radius > 0) {
                spawnFieldEdge(level, pos, session.radius, now);
            }
            double r = Math.max(0.5, session.radius);
            AABB box = new AABB(
                    pos.x - r, pos.y - 2, pos.z - r,
                    pos.x + r, pos.y + 3, pos.z + r
            );
            for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, e ->
                    e.isAlive()
                            && e != player
                            && !AllyService.isProtected(player, e)
                            && e.distanceToSqr(pos) <= r * r)) {
                if (!isUnderLock(living)) {
                    applyLock(player, living, level, true, true);
                } else if (needsLockRefresh(living)) {
                    refreshLockEffects(player, living);
                }
            }
        }

        if (now % STATUS_INTERVAL == 0) {
            int secLeft = (int) ((FIELD_DURATION_TICKS - elapsed) / 20);
            ActionBars.show(player, Component.translatable(
                    "ui.static_resonance.field_status", session.radius, secLeft));
        }
    }

    private static void spawnFieldEdge(ServerLevel level, Vec3 center, int radius, long time) {
        int points = Math.min(12, 6 + radius / 3);
        double phase = (time % 40) * 0.05;
        for (int i = 0; i < points; i++) {
            double a = phase + (Math.PI * 2.0 * i) / points;
            double x = center.x + Math.cos(a) * radius;
            double z = center.z + Math.sin(a) * radius;
            level.sendParticles(RING_DUST, x, center.y + 0.2, z, 1, 0, 0, 0, 0);
            if (i % 3 == 0) {
                level.sendParticles(ParticleTypes.WITCH, x, center.y + 0.25, z, 1, 0, 0, 0, 0);
            }
        }
    }

    private static void setFieldActiveMirror(ServerPlayer player, boolean active) {
        ApoliResources.setValue(player, FIELD_ACTIVE_POWER, active ? 1 : 0);
    }

    public enum StopReason {
        MANUAL, MOVED, TIMEOUT, MODE_CHANGE, LOGOUT, SILENT
    }

    private record HandLock(int slot, ItemStack stack) {
    }

    private static final class FieldSession {
        final double anchorX;
        final double anchorY;
        final double anchorZ;
        final long startGameTime;
        int radius;

        FieldSession(double anchorX, double anchorY, double anchorZ, long startGameTime, int radius) {
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.anchorZ = anchorZ;
            this.startGameTime = startGameTime;
            this.radius = radius;
        }
    }
}
