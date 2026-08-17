package com.staticresonance.network;

import com.staticresonance.client.AllyListClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Server → client: full personal ally UUID list. */
public record AllySyncPacket(List<UUID> allies) {

    public AllySyncPacket(Collection<UUID> allies) {
        this(List.copyOf(allies));
    }

    public static void encode(AllySyncPacket packet, FriendlyByteBuf buf) {
        int n = PacketLimits.writeCount(buf, packet.allies.size(), PacketLimits.MAX_ALLIES);
        for (int i = 0; i < n; i++) {
            buf.writeUUID(packet.allies.get(i));
        }
    }

    public static AllySyncPacket decode(FriendlyByteBuf buf) {
        int n = PacketLimits.readCount(buf, PacketLimits.MAX_ALLIES);
        List<UUID> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(buf.readUUID());
        }
        return new AllySyncPacket(list);
    }

    public static void handle(AllySyncPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> AllyListClient.replace(packet.allies)));
        ctx.setPacketHandled(true);
    }
}
