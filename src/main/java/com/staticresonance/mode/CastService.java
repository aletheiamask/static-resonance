package com.staticresonance.mode;

import com.staticresonance.StaticResonanceMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/**
 * F ({@code CastPacket}) → current mode skill. Avoids stacking Origins primary powers in the HUD.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID)
public final class CastService {
    private static final TickGate CAST_GATE = new TickGate(6);

    private CastService() {
    }

    public static boolean castPrimary(ServerPlayer player) {
        long gameTime = player.level().getGameTime();
        UUID id = player.getUUID();
        if (CAST_GATE.blocked(id, gameTime)) {
            return false;
        }
        CAST_GATE.stamp(id, gameTime);
        if (!ModeService.hasModePower(player)) {
            return false;
        }
        ResonanceMode mode = ModeService.getMode(player);
        if (mode == null) {
            mode = ResonanceMode.DUST;
        }
        return switch (mode) {
            case DUST -> DustService.cast(player);
            case SENSE -> ScanOutlineService.toggle(player);
            case CONTROL -> castControl(player);
            case OVERCHARGE -> FaradayService.tryActivate(player, FaradayService.ActivateReason.MANUAL);
            case CIRCUIT -> CircuitService.cast(player);
        };
    }

    private static boolean castControl(ServerPlayer player) {
        if (ControlService.getStyle(player) == ControlService.STYLE_FIELD) {
            if (ControlService.isFieldActive(player)) {
                ControlService.stopField(player, ControlService.StopReason.MANUAL);
                return true;
            }
            if (!ControlService.tryStartField(player)) {
                ActionBars.show(player, Component.translatable("ui.static_resonance.charge_too_low"));
                return false;
            }
            return true;
        }
        return ControlService.lockLookTarget(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CAST_GATE.clear(event.getEntity().getUUID());
    }
}
