package com.staticresonance.mode;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;

/**
 * The five live combat modes and their F-key skills.
 * Indices match {@code static_resonance:systems/mode}.
 * Radial layout lives in {@link com.staticresonance.client.ui.RadialWedge}.
 */
public enum ResonanceMode {
    DUST(0, "skill.static_resonance.dust_curtain", "hint.static_resonance.dust_curtain",
            "static_resonance:mode/dust", Items.SAND, 5, SubLayer.DUST),
    SENSE(1, "skill.static_resonance.static_scan", "hint.static_resonance.static_scan",
            "static_resonance:mode/sense", Items.SPYGLASS, 8, SubLayer.SENSE),
    CONTROL(2, "skill.static_resonance.magnetic_lock", "hint.static_resonance.magnetic_lock",
            "static_resonance:mode/control", Items.IRON_BARS, 15, SubLayer.CONTROL),
    OVERCHARGE(3, "skill.static_resonance.faraday_cage", "hint.static_resonance.faraday_cage",
            "static_resonance:mode/overcharge", Items.LIGHTNING_ROD, 50, SubLayer.FARADAY),
    CIRCUIT(4, "skill.static_resonance.redstone_sight", "hint.static_resonance.redstone_sight",
            "static_resonance:mode/circuit", Items.REPEATER, 8, SubLayer.NONE);

    /** Radial sub-panel for this combat mode (NONE = LMB selects immediately). */
    public enum SubLayer {
        NONE,
        FARADAY,
        CONTROL,
        DUST,
        SENSE
    }

    private final int index;
    private final String skillLangKey;
    private final String hintLangKey;
    private final String feedbackFunction;
    private final Item iconItem;
    private final int nominalCost;
    private final SubLayer subLayer;

    ResonanceMode(int index, String skillLangKey, String hintLangKey,
                  String feedbackFunction, Item iconItem, int nominalCost, SubLayer subLayer) {
        this.index = index;
        this.skillLangKey = skillLangKey;
        this.hintLangKey = hintLangKey;
        this.feedbackFunction = feedbackFunction;
        this.iconItem = iconItem;
        this.nominalCost = nominalCost;
        this.subLayer = subLayer;
    }

    public int index() {
        return index;
    }

    public String skillLangKey() {
        return skillLangKey;
    }

    public String hintLangKey() {
        return hintLangKey;
    }

    public String feedbackFunction() {
        return feedbackFunction;
    }

    public Item iconItem() {
        return iconItem;
    }

    public int nominalCost() {
        return nominalCost;
    }

    public SubLayer subLayer() {
        return subLayer;
    }

    public boolean hasSubMenu() {
        return subLayer != SubLayer.NONE;
    }

    public ResonanceMode next() {
        return byIndex((index + 1) % values().length);
    }

    public ResonanceMode previous() {
        int n = values().length;
        return byIndex((index - 1 + n) % n);
    }

    @Nullable
    public static ResonanceMode byIndex(int index) {
        for (ResonanceMode mode : values()) {
            if (mode.index == index) {
                return mode;
            }
        }
        return null;
    }
}
