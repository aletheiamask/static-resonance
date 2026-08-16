package com.staticresonance.network;

import com.staticresonance.mode.ActionBars;
import com.staticresonance.mode.DustService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client → server: 0 = burst, 1 = placed zone. */
public record SetDustStylePacket(int style) {

    public static void encode(SetDustStylePacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.style);
    }

    public static SetDustStylePacket decode(FriendlyByteBuf buf) {
        return new SetDustStylePacket(buf.readVarInt());
    }

    public static void handle(SetDustStylePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            int style = packet.style == DustService.STYLE_ZONE ? DustService.STYLE_ZONE : DustService.STYLE_BURST;
            if (!DustService.setStyle(player, style)) {
                return;
            }
            ActionBars.show(player, Component.translatable(style == DustService.STYLE_ZONE
                    ? "ui.static_resonance.dust_zone_selected"
                    : "ui.static_resonance.dust_burst_selected"));
        });
        ctx.setPacketHandled(true);
    }
}
