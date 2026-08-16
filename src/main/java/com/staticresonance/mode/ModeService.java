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
    private static final Map<UUID, Long> LAST_CHANGE_GAME_TIME = new ConcurrentHashMap<>();

    private ModeService() {
    }

    public static boolean hasModePower(Player player) {
        return ApoliResources.hasPower(player, MODE_POWER);
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
        Long last = LAST_CHANGE_GAME_TIME.get(player.getUUID());
        if (last != null && gameTime - last < DEBOUNCE_TICKS) {
            return false;
        }

        if (!ApoliResources.setValue(player, MODE_POWER, mode.index())) {
            return false;
        }
        LAST_CHANGE_GAME_TIME.put(player.getUUID(), gameTime);
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
        LAST_CHANGE_GAME_TIME.remove(event.getEntity().getUUID());
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
