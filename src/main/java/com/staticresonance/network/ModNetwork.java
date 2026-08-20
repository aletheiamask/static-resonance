package com.staticresonance.network;

import com.staticresonance.StaticResonanceMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ModNetwork {
    private static final String PROTOCOL = "10";
    private static int packetId = 0;

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(StaticResonanceMod.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();

    private ModNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(SetModePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .decoder(SetModePacket::decode)
                .encoder(SetModePacket::encode)
                .consumerMainThread(SetModePacket::handle)
                .add();
        CHANNEL.messageBuilder(ToggleFaradayEmergencyPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .decoder(ToggleFaradayEmergencyPacket::decode)
                .encoder(ToggleFaradayEmergencyPacket::encode)
                .consumerMainThread(ToggleFaradayEmergencyPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetControlStylePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .decoder(SetControlStylePacket::decode)
                .encoder(SetControlStylePacket::encode)
                .consumerMainThread(SetControlStylePacket::handle)
                .add();
        CHANNEL.messageBuilder(ScanOutlinePacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .decoder(ScanOutlinePacket::decode)
                .encoder(ScanOutlinePacket::encode)
                .consumerMainThread(ScanOutlinePacket::handle)
                .add();
        CHANNEL.messageBuilder(SetScanFilterPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .decoder(SetScanFilterPacket::decode)
                .encoder(SetScanFilterPacket::encode)
                .consumerMainThread(SetScanFilterPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetDustStylePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .decoder(SetDustStylePacket::decode)
                .encoder(SetDustStylePacket::encode)
                .consumerMainThread(SetDustStylePacket::handle)
                .add();
        CHANNEL.messageBuilder(ToggleAllyPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .decoder(ToggleAllyPacket::decode)
                .encoder(ToggleAllyPacket::encode)
                .consumerMainThread(ToggleAllyPacket::handle)
                .add();
        CHANNEL.messageBuilder(AllySyncPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .decoder(AllySyncPacket::decode)
                .encoder(AllySyncPacket::encode)
                .consumerMainThread(AllySyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(CastPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .decoder(CastPacket::decode)
                .encoder(CastPacket::encode)
                .consumerMainThread(CastPacket::handle)
                .add();
    }
}
