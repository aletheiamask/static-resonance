package com.staticresonance.client;

import com.staticresonance.StaticResonanceMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Client cache of the local player's ally UUIDs. */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID, value = Dist.CLIENT)
public final class AllyListClient {
    private static final Set<UUID> ALLIES = new HashSet<>();

    private AllyListClient() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ALLIES.clear();
    }

    public static void replace(Collection<UUID> next) {
        ALLIES.clear();
        ALLIES.addAll(next);
    }

    public static boolean isAlly(UUID id) {
        return ALLIES.contains(id);
    }

    public static void clear() {
        ALLIES.clear();
    }
}
