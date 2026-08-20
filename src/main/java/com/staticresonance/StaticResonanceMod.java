/*
 * Static Resonance
 * Copyright (c) 2026 Pipothai. All Rights Reserved.
 * GitHub:  https://github.com/aletheiamask
 * YouTube: https://www.youtube.com/@Tuna_Sandwich_TOT
 */
package com.staticresonance;

import com.mojang.logging.LogUtils;
import com.staticresonance.config.SrConfig;
import com.staticresonance.network.ModNetwork;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Static Resonance — Origins content mod for Forge 1.20.1.
 * Gameplay is data-driven via Apoli/Origins JSON; Java adds radial mode UI + networking.
 */
@Mod(StaticResonanceMod.MOD_ID)
public class StaticResonanceMod {
    public static final String MOD_ID = "static_resonance";
    private static final Logger LOGGER = LogUtils.getLogger();

    public StaticResonanceMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SrConfig.COMMON_SPEC);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::register);
        LOGGER.info("Static Resonance loaded — by Pipothai. All Rights Reserved. github.com/aletheiamask · youtube.com/@Tuna_Sandwich_TOT");
    }
}
