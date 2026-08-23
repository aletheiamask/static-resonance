package com.staticresonance.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketLimitsTest {
    @Test
    void caps() {
        assertEquals(128, PacketLimits.MAX_OUTLINE_MARKS);
        assertEquals(256, PacketLimits.MAX_ALLIES);
    }

    @Test
    void readCountRejectsOverMax() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(129);
        assertThrows(IllegalArgumentException.class, () -> PacketLimits.readCount(buf, 128));
    }

    @Test
    void readCountRejectsNegative() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(-1);
        assertThrows(IllegalArgumentException.class, () -> PacketLimits.readCount(buf, 128));
    }

    @Test
    void writeCountClampsToMax() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        int n = PacketLimits.writeCount(buf, 200, 128);
        assertEquals(128, n);
        assertEquals(128, buf.readVarInt());
    }

    @Test
    void readCountAcceptsMax() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(128);
        assertEquals(128, PacketLimits.readCount(buf, 128));
    }
}
