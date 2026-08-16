package com.staticresonance.network;

import com.staticresonance.mode.ActionBars;
import com.staticresonance.mode.ControlService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: set Control style (0 = target, 1 = field) from radial sub-wheel.
 */
public record SetControlStylePacket(int style) {

    public static void encode(SetControlStylePacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.style);
    }

    public static SetControlStylePacket decode(FriendlyByteBuf buf) {
        return new SetControlStylePacket(buf.readVarInt());
    }

    public static void handle(SetControlStylePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            int style = packet.style == 1 ? 1 : 0;
            if (!ControlService.setStyle(player, style)) {
                return;
            }
            ActionBars.show(player,
                    Component.translatable(style == 1
                            ? "ui.static_resonance.control_field_selected"
                            : "ui.static_resonance.control_target_selected"));
        });
        ctx.setPacketHandled(true);
    }
}
