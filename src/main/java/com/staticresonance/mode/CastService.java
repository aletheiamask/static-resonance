package com.staticresonance.mode;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * One Origins primary (G) → current mode skill. Avoids stacking four G powers in Origins HUD.
 */
public final class CastService {
    private CastService() {
    }

    public static boolean castPrimary(ServerPlayer player) {
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
}
