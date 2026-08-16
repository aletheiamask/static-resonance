package com.staticresonance.client;

import com.staticresonance.StaticResonanceMod;
import com.staticresonance.mode.ControlService;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * While magnetically locked, eat hotbar / scroll / swap / drop before the slot moves.
 * No snap-back: the selection never changes on this client.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID, value = Dist.CLIENT)
public final class LockHandsClient {
    private LockHandsClient() {
    }

    private static boolean selfLocked() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && ControlService.isUnderLock(mc.player);
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (selfLocked() && Minecraft.getInstance().screen == null) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!selfLocked()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        for (KeyMapping key : mc.options.keyHotbarSlots) {
            if (key.matches(event.getKey(), event.getScanCode())) {
                key.setDown(false);
            }
        }
        if (mc.screen != null) {
            return;
        }
        if (mc.options.keyDrop.matches(event.getKey(), event.getScanCode())) {
            mc.options.keyDrop.setDown(false);
        }
        if (mc.options.keySwapOffhand.matches(event.getKey(), event.getScanCode())) {
            mc.options.keySwapOffhand.setDown(false);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !selfLocked()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        for (KeyMapping key : mc.options.keyHotbarSlots) {
            while (key.consumeClick()) {
            }
        }
        if (mc.screen != null) {
            return;
        }
        while (mc.options.keyDrop.consumeClick()) {
        }
        while (mc.options.keySwapOffhand.consumeClick()) {
        }
    }

    @SubscribeEvent
    public static void onInvClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!selfLocked()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        Slot slot = screen.getSlotUnderMouse();
        if (slot == null || slot.container != mc.player.getInventory()) {
            return;
        }
        if (slot.getContainerSlot() == mc.player.getInventory().selected) {
            event.setCanceled(true);
        }
    }
}
