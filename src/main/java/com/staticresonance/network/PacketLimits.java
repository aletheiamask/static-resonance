package com.staticresonance.network;

import net.minecraft.network.FriendlyByteBuf;

/** Shared S2C list caps. Writers already bound these; readers must not trust the wire. */
public final class PacketLimits {
    public static final int MAX_OUTLINE_MARKS = 128;
    public static final int MAX_ALLIES = 256;

    private PacketLimits() {
    }

    public static int readCount(FriendlyByteBuf buf, int max) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) {
            throw new IllegalArgumentException("Invalid entry count: " + n);
        }
        return n;
    }

    public static int writeCount(FriendlyByteBuf buf, int size, int max) {
        int n = Math.min(size, max);
        buf.writeVarInt(n);
        return n;
    }
}
