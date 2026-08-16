package com.staticresonance.mode;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Ensures Static Resonance scoreboard objectives exist (domain + scratch).
 * Prevents Origins command-conditions / functions from failing when objectives are missing.
 */
@Mod.EventBusSubscriber(modid = com.staticresonance.StaticResonanceMod.MOD_ID)
public final class SrScoreboards {
    private static final String[] OBJECTIVES = {
            "sr_scan",
            "sr_hp",
            "sr_mc",
            "sr_math",
            "sr_tmp",
            "sr_faraday",
            "sr_faraday_cd",
            "sr_faraday_held"
    };

    private SrScoreboards() {
    }

    public static void ensureAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ensureAll(server.getScoreboard());
    }

    public static void ensureAll(Scoreboard board) {
        for (String name : OBJECTIVES) {
            if (board.getObjective(name) == null) {
                board.addObjective(name, ObjectiveCriteria.DUMMY, Component.literal(name),
                        ObjectiveCriteria.RenderType.INTEGER);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ensureAll(event.getServer());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ensureAll(player.getServer());
            FaradayService.ensureObjectives(player);
        }
    }
}
