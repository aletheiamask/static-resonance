package com.staticresonance.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server → scanner client only: add or clear private scan outlines.
 */
public record ScanOutlinePacket(boolean clearAll, List<UUID> entityIds) {

    public static void encode(ScanOutlinePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.clearAll);
        buf.writeVarInt(packet.entityIds.size());
        for (UUID id : packet.entityIds) {
            buf.writeUUID(id);
        }
    }

    public static ScanOutlinePacket decode(FriendlyByteBuf buf) {
        boolean clearAll = buf.readBoolean();
        int n = buf.readVarInt();
        List<UUID> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(buf.readUUID());
        }
        return new ScanOutlinePacket(clearAll, List.copyOf(ids));
    }

    public static void handle(ScanOutlinePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.staticresonance.client.ScanOutlineClient.handle(packet)));
        ctx.setPacketHandled(true);
    }
}
