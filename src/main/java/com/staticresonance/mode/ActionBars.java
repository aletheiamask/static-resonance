package com.staticresonance.mode;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Actionbar writes. While Faraday is up, only the cage countdown may use the bar.
 */
public final class ActionBars {
    private ActionBars() {
    }

    public static void show(ServerPlayer player, Component text) {
        if (FaradayService.isCageActive(player)) {
            return;
        }
        player.displayClientMessage(text, true);
    }
}
