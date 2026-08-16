package com.staticresonance.network;

import com.staticresonance.mode.ActionBars;
import com.staticresonance.mode.FaradayService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: toggle Faraday emergency auto-trigger (from radial sub-panel).
 */
public record ToggleFaradayEmergencyPacket() {

    public static void encode(ToggleFaradayEmergencyPacket packet, FriendlyByteBuf buf) {
    }

    public static ToggleFaradayEmergencyPacket decode(FriendlyByteBuf buf) {
        return new ToggleFaradayEmergencyPacket();
    }

    public static void handle(ToggleFaradayEmergencyPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            boolean enabled = FaradayService.toggleEmergency(player);
            ActionBars.show(player,
                    Component.translatable(enabled
                            ? "ui.static_resonance.faraday_emergency_on"
                            : "ui.static_resonance.faraday_emergency_off"));
        });
        ctx.setPacketHandled(true);
    }
}
