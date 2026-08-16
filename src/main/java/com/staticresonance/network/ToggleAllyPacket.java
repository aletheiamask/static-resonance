package com.staticresonance.network;

import com.staticresonance.mode.ActionBars;
import com.staticresonance.mode.AllyService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Client → server: toggle personal ally for the looked-up player. */
public record ToggleAllyPacket(UUID other) {

    public static void encode(ToggleAllyPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.other);
    }

    public static ToggleAllyPacket decode(FriendlyByteBuf buf) {
        return new ToggleAllyPacket(buf.readUUID());
    }

    public static void handle(ToggleAllyPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            boolean added = AllyService.toggle(player, packet.other);
            ServerPlayer other = player.server.getPlayerList().getPlayer(packet.other);
            String name = other != null ? other.getGameProfile().getName() : packet.other.toString().substring(0, 8);
            ActionBars.show(player, Component.translatable(
                    added ? "ui.static_resonance.ally_added" : "ui.static_resonance.ally_removed",
                    name));
        });
        ctx.setPacketHandled(true);
    }
}
