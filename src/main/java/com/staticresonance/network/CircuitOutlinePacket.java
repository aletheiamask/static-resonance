package com.staticresonance.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → caster client only: circuit block outlines (working / idle).
 */
public record CircuitOutlinePacket(boolean clearAll, List<Entry> entries) {

    public record Entry(BlockPos pos, boolean working) {
    }

    public static void encode(CircuitOutlinePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.clearAll);
        int n = PacketLimits.writeCount(buf, packet.entries.size(), PacketLimits.MAX_OUTLINE_MARKS);
        for (int i = 0; i < n; i++) {
            Entry entry = packet.entries.get(i);
            buf.writeBlockPos(entry.pos);
            buf.writeBoolean(entry.working);
        }
    }

    public static CircuitOutlinePacket decode(FriendlyByteBuf buf) {
        boolean clearAll = buf.readBoolean();
        int n = PacketLimits.readCount(buf, PacketLimits.MAX_OUTLINE_MARKS);
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entries.add(new Entry(buf.readBlockPos(), buf.readBoolean()));
        }
        return new CircuitOutlinePacket(clearAll, List.copyOf(entries));
    }

    public static void handle(CircuitOutlinePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.staticresonance.client.CircuitOutlineClient.handle(packet)));
        ctx.setPacketHandled(true);
    }
}
