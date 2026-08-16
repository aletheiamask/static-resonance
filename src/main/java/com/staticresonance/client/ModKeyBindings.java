package com.staticresonance.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.staticresonance.StaticResonanceMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public final class ModKeyBindings {
    public static final String CATEGORY = "key.categories." + StaticResonanceMod.MOD_ID;

    public static final KeyMapping MODE_MENU = new KeyMapping(
            "key.static_resonance.mode_menu",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            CATEGORY
    );

    public static final KeyMapping CAST = new KeyMapping(
            "key.static_resonance.cast",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F,
            CATEGORY
    );

    private ModKeyBindings() {
    }
}
