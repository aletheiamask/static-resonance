package com.staticresonance.client;

import com.staticresonance.StaticResonanceMod;
import com.staticresonance.client.ui.RadialModeMenu;
import com.staticresonance.mode.ModeService;
import com.staticresonance.network.CastPacket;
import com.staticresonance.network.ModNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * F casts the current mode skill. Consumes the vanilla offhand-swap click when it shares F.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID, value = Dist.CLIENT)
public final class CastKeyHandler {
    private CastKeyHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.screen != null) {
            return;
        }
        if (RadialModeMenu.isOpen() || !ModKeyBindings.CAST.isDown()) {
            return;
        }
        if (!ModeService.hasModePower(player)) {
            return;
        }
        if (!ModKeyBindings.CAST.consumeClick()) {
            return;
        }
        while (mc.options.keySwapOffhand.consumeClick()) {
            // F is vanilla offhand — eat it so only the skill fires
        }
        ModNetwork.CHANNEL.sendToServer(new CastPacket());
    }
}
