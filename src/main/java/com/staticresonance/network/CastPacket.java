package com.staticresonance.network;

import com.staticresonance.mode.CastService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client → server: F cast of the current mode skill. */
public record CastPacket() {

    public static void encode(CastPacket packet, FriendlyByteBuf buf) {
    }

    public static CastPacket decode(FriendlyByteBuf buf) {
        return new CastPacket();
    }

    public static void handle(CastPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                CastService.castPrimary(player);
            }
        });
        ctx.setPacketHandled(true);
    }
}
