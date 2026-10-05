package com.staticresonance.client.ui;

import com.staticresonance.StaticResonanceMod;
import com.staticresonance.config.SrConfig;
import com.staticresonance.logic.GameplayDefaults;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * Mods → Config screen. Loaded only after {@code cloth_config} is confirmed present.
 */
public final class ClothConfigGate {
    private ClothConfigGate() {
    }

    public static void register() {
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> create(parent)));
    }

    static Screen create(Screen parent) {
        SrConfig.Common c = SrConfig.COMMON;
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.static_resonance.title"))
                .setSavingRunnable(SrConfig.COMMON_SPEC::save);
        ConfigEntryBuilder e = builder.entryBuilder();

        ConfigCategory charge = builder.getOrCreateCategory(cat("charge"));
        addInt(e, charge, "charge.regenIntervalTicks", c.charge.regenIntervalTicks, 1, 1200);
        addInt(e, charge, "charge.dryGain", c.charge.dryGain, 0, SrConfig.CHARGE_MAX);
        addInt(e, charge, "charge.wetDrain", c.charge.wetDrain, 0, SrConfig.CHARGE_MAX);
        addInt(e, charge, "charge.motionIntervalTicks", c.charge.motionIntervalTicks, 1, 1200);
        addDouble(e, charge, "charge.motionMinDist", c.charge.motionMinDist, 0.0, 16.0);
        addDouble(e, charge, "charge.motionMaxDist", c.charge.motionMaxDist, 0.5, 32.0);
        addInt(e, charge, "charge.motionCap", c.charge.motionCap, 1, 32);

        ConfigCategory dust = builder.getOrCreateCategory(cat("dust"));
        addInt(e, dust, "dust.burstCost", c.dust.burstCost, 0, SrConfig.CHARGE_MAX);
        addInt(e, dust, "dust.zoneCost", c.dust.zoneCost, 0, SrConfig.CHARGE_MAX);
        addDouble(e, dust, "dust.zoneRadius", c.dust.zoneRadius, 0.5, 32.0);
        addInt(e, dust, "dust.zoneDurationTicks", c.dust.zoneDurationTicks, 1, 72000);

        ConfigCategory sense = builder.getOrCreateCategory(cat("sense"));
        addInt(e, sense, "sense.scanCost", c.sense.scanCost, 0, SrConfig.CHARGE_MAX);
        addInt(e, sense, "sense.scanRadius", c.sense.scanRadius, 1, 128);

        ConfigCategory control = builder.getOrCreateCategory(cat("control"));
        addInt(e, control, "control.targetCost", c.control.targetCost, 0, SrConfig.CHARGE_MAX);
        addInt(e, control, "control.fieldCost", c.control.fieldCost, 0, SrConfig.CHARGE_MAX);
        addInt(e, control, "control.fieldMaxRadius", c.control.fieldMaxRadius, 1, 64);
        addInt(e, control, "control.fieldDurationTicks", c.control.fieldDurationTicks, 1, 72000);
        addDouble(e, control, "control.targetRange", c.control.targetRange, 1.0, 128.0);
        addInt(e, control, "control.lockEffectTicks", c.control.lockEffectTicks, 1, 72000);
        addDouble(e, control, "control.moveAbortThreshold", c.control.moveAbortThreshold, 0.0, 8.0);

        ConfigCategory faraday = builder.getOrCreateCategory(cat("faraday"));
        addInt(e, faraday, "faraday.manualCost", c.faraday.manualCost, 0, SrConfig.CHARGE_MAX);
        addInt(e, faraday, "faraday.durationTicks", c.faraday.durationTicks, 1, 72000);
        addInt(e, faraday, "faraday.cooldownTicks", c.faraday.cooldownTicks, 0, 72000);
        addDouble(e, faraday, "faraday.repelRadius", c.faraday.repelRadius, 0.5, 32.0);
        addDouble(e, faraday, "faraday.hardRadius", c.faraday.hardRadius, 0.5, 32.0);
        addDouble(e, faraday, "faraday.midRadius", c.faraday.midRadius, 0.5, 32.0);
        addInt(e, faraday, "faraday.holdTicks", c.faraday.holdTicks, 1, 200);
        addDouble(e, faraday, "faraday.emergencyMinHealth", c.faraday.emergencyMinHealth, 0.0, 20.0);

        ConfigCategory circuit = builder.getOrCreateCategory(cat("circuit"));
        addInt(e, circuit, "circuit.toggleCost", c.circuit.toggleCost, 0, SrConfig.CHARGE_MAX);
        addInt(e, circuit, "circuit.lookRange", c.circuit.lookRange, 1, 32);
        addInt(e, circuit, "circuit.toggleTicks", c.circuit.toggleTicks, 1, 72000);

        ConfigCategory climb = builder.getOrCreateCategory(cat("climb"));
        addDouble(e, climb, "climb.climbSpeed", c.climb.climbSpeed, 0.01, 1.0);

        ConfigCategory visuals = builder.getOrCreateCategory(cat("visuals"));
        visuals.addEntry(e.startBooleanToggle(label("visuals.seeThroughWalls"), c.visuals.seeThroughWalls.get())
                .setDefaultValue(GameplayDefaults.SEE_THROUGH_WALLS)
                .setSaveConsumer(c.visuals.seeThroughWalls::set)
                .build());

        return builder.build();
    }

    private static Component cat(String id) {
        return Component.translatable("config.static_resonance.category." + id);
    }

    private static Component label(String id) {
        return Component.translatable("config.static_resonance." + id);
    }

    private static void addInt(ConfigEntryBuilder e, ConfigCategory cat, String id,
            ForgeConfigSpec.IntValue value, int min, int max) {
        cat.addEntry(e.startIntField(label(id), value.get())
                .setDefaultValue(value.getDefault())
                .setMin(min)
                .setMax(max)
                .setSaveConsumer(value::set)
                .build());
    }

    private static void addDouble(ConfigEntryBuilder e, ConfigCategory cat, String id,
            ForgeConfigSpec.DoubleValue value, double min, double max) {
        cat.addEntry(e.startDoubleField(label(id), value.get())
                .setDefaultValue(value.getDefault())
                .setMin(min)
                .setMax(max)
                .setSaveConsumer(value::set)
                .build());
    }
}
