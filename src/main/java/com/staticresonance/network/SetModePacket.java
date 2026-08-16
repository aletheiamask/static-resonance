package com.staticresonance.network;

import com.staticresonance.mode.ModeService;
import com.staticresonance.mode.ResonanceMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: set resonance mode to a fixed index (0–3).
 */
public record SetModePacket(int modeIndex) {

    public static void encode(SetModePacket packet, FriendlyByteBuf buf) {
        buf.writeByte(packet.modeIndex);
    }

    public static SetModePacket decode(FriendlyByteBuf buf) {
        return new SetModePacket(buf.readByte());
    }

    public static void handle(SetModePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            ResonanceMode mode = ResonanceMode.byIndex(packet.modeIndex);
            if (mode == null) {
                return;
            }
            ModeService.setMode(player, mode);
        });
        ctx.setPacketHandled(true);
    }
}
