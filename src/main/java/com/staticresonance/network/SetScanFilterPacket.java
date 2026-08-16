package com.staticresonance.network;

import com.staticresonance.mode.ActionBars;
import com.staticresonance.mode.ScanOutlineService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client → server: toggle one Static Scan filter bit. */
public record SetScanFilterPacket(int bit) {

    public static void encode(SetScanFilterPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.bit);
    }

    public static SetScanFilterPacket decode(FriendlyByteBuf buf) {
        return new SetScanFilterPacket(buf.readVarInt());
    }

    public static void handle(SetScanFilterPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            int next = ScanOutlineService.toggleFilter(player, packet.bit);
            ActionBars.show(player, Component.translatable(
                    "ui.static_resonance.scan_filter_set", next));
        });
        ctx.setPacketHandled(true);
    }
}
