package com.staticresonance.mode;

import com.staticresonance.StaticResonanceMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read/write {@code systems/mode} (and other resources) via Apoli.
 * Safe on client for reads (synced container) and on server for writes.
 * Supports Forge Apoli and Fabric Apoli (Connector) — see {@link ApoliResources}.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID)
public final class ModeService {
    public static final ResourceLocation MODE_POWER =
            new ResourceLocation(StaticResonanceMod.MOD_ID, "systems/mode");
    public static final ResourceLocation CHARGE_POWER =
            new ResourceLocation(StaticResonanceMod.MOD_ID, "systems/charge");

    /** Minimum game ticks between successful mode changes per player. */
    private static final int DEBOUNCE_TICKS = 4;
    private static final int POWER_CACHE_TTL = 20;
    private static final TickGate CHANGE_GATE = new TickGate(DEBOUNCE_TICKS);
    private static final Map<CacheKey, CacheVal> MODE_POWER_CACHE = new ConcurrentHashMap<>();

    private ModeService() {
    }

    public static boolean hasModePower(Player player) {
        CacheKey key = new CacheKey(player.getUUID(), player.level().isClientSide);
        long now = player.level().getGameTime();
        CacheVal cached = MODE_POWER_CACHE.get(key);
        if (cached != null && now - cached.at <= POWER_CACHE_TTL) {
            return cached.has;
        }
        boolean has = ApoliResources.hasPower(player, MODE_POWER);
        MODE_POWER_CACHE.put(key, new CacheVal(has, now));
        return has;
    }

    /**
     * @return mode index, or empty if the player lacks the resource power
     */
    public static OptionalInt getModeIndex(Player player) {
        return getResourceValue(player, MODE_POWER);
    }

    @Nullable
    public static ResonanceMode getMode(Player player) {
        OptionalInt index = getModeIndex(player);
        return index.isPresent() ? ResonanceMode.byIndex(index.getAsInt()) : null;
    }

    public static OptionalInt getResourceValue(Player player, ResourceLocation powerId) {
        return ApoliResources.getValue(player, powerId);
    }

    /**
     * Server-only: set mode if allowed.
     * Same index → silent no-op. Debounced against packet spam.
     *
     * @return true if mode value changed
     */
    public static boolean setMode(ServerPlayer player, ResonanceMode mode) {
        if (!ApoliResources.hasPower(player, MODE_POWER)) {
            return false;
        }

        OptionalInt current = ApoliResources.getValue(player, MODE_POWER);
        if (current.isPresent() && current.getAsInt() == mode.index()) {
            return false; // same mode: silent
        }

        long gameTime = player.level().getGameTime();
        UUID id = player.getUUID();
        if (CHANGE_GATE.blocked(id, gameTime)) {
            return false;
        }

        if (!ApoliResources.setValue(player, MODE_POWER, mode.index())) {
            return false;
        }
        CHANGE_GATE.stamp(id, gameTime);
        ApoliResources.sync(player);
        if (current.isPresent() && current.getAsInt() == ResonanceMode.SENSE.index()) {
            ScanOutlineService.stop(player);
        }
        CircuitService.onModeChanged(player, mode);
        runFeedback(player, mode);
        return true;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        CHANGE_GATE.clear(id);
        invalidatePowerCache(id);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        invalidatePowerCache(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onChangeDim(PlayerEvent.PlayerChangedDimensionEvent event) {
        invalidatePowerCache(event.getEntity().getUUID());
    }

    private static void invalidatePowerCache(UUID id) {
        MODE_POWER_CACHE.remove(new CacheKey(id, true));
        MODE_POWER_CACHE.remove(new CacheKey(id, false));
    }

    private record CacheKey(UUID id, boolean clientSide) {
    }

    private record CacheVal(boolean has, long at) {
    }

    private static void runFeedback(ServerPlayer player, ResonanceMode mode) {
        var server = player.getServer();
        if (server == null) {
            return;
        }
        var source = player.createCommandSourceStack()
                .withSuppressedOutput()
                .withPermission(2);
        server.getCommands().performPrefixedCommand(source, "function " + mode.feedbackFunction());
    }
}
