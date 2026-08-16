package com.staticresonance.client.ui;

import com.staticresonance.mode.ResonanceMode;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;

import javax.annotation.Nullable;

/**
 * Main radial slots: five combat modes (equal 72° wedges).
 * Angles in screen space (Y-down atan2): right=0, down=90, left=±180, up=-90.
 */
public enum RadialWedge {
    /** Top — Faraday / Overcharge */
    OVERCHARGE(ResonanceMode.OVERCHARGE, -126f, -54f, RadialWedge.Anchor.TOP),
    /** Right — Control */
    CONTROL(ResonanceMode.CONTROL, -54f, 18f, RadialWedge.Anchor.RIGHT),
    /** Bottom-right — Sense */
    SENSE(ResonanceMode.SENSE, 18f, 90f, RadialWedge.Anchor.BOTTOM),
    /** Bottom-left — Dust */
    DUST(ResonanceMode.DUST, 90f, 162f, RadialWedge.Anchor.LEFT),
    /** Upper-left — Circuit / Redstone Sight */
    CIRCUIT(ResonanceMode.CIRCUIT, 162f, -126f, RadialWedge.Anchor.TOP_LEFT);

    public enum Anchor {
        TOP, RIGHT, BOTTOM, LEFT, TOP_LEFT
    }

    /** Shared radial geometry (GUI scale fraction of min(sw, sh)). */
    public static final float OUTER_FRAC = 0.22f;
    public static final float INNER_FRAC = 0.42f;
    public static final double DEADZONE_FRACTION = 0.28;
    public static final float AIM_RADIUS_FRAC = 0.65f;

    private final ResonanceMode mode;
    private final float startDeg;
    private final float endDeg;
    private final Anchor labelAnchor;

    RadialWedge(ResonanceMode mode, float startDeg, float endDeg, Anchor labelAnchor) {
        this.mode = mode;
        this.startDeg = startDeg;
        this.endDeg = endDeg;
        this.labelAnchor = labelAnchor;
    }

    public ResonanceMode mode() {
        return mode;
    }

    public String skillLangKey() {
        return mode.skillLangKey();
    }

    public String hintLangKey() {
        return mode.hintLangKey();
    }

    public Item iconItem() {
        return mode.iconItem();
    }

    public boolean hasSubMenu() {
        return mode.hasSubMenu();
    }

    public float startDeg() {
        return startDeg;
    }

    public float endDeg() {
        return endDeg;
    }

    public Anchor labelAnchor() {
        return labelAnchor;
    }

    public float midDeg() {
        float s = normalizeDeg(startDeg);
        float e = normalizeDeg(endDeg);
        float span;
        if (s < e) {
            span = e - s;
        } else {
            span = (e + 360f) - s;
        }
        return normalizeDeg(s + span * 0.5f);
    }

    public static RadialWedge fromAngleRad(double angleRad) {
        float deg = (float) (angleRad * Mth.RAD_TO_DEG);
        for (RadialWedge w : values()) {
            if (containsAngle(deg, w.startDeg, w.endDeg)) {
                return w;
            }
        }
        return DUST;
    }

    @Nullable
    public static RadialWedge ofMode(ResonanceMode mode) {
        for (RadialWedge w : values()) {
            if (w.mode == mode) {
                return w;
            }
        }
        return null;
    }

    static boolean containsAngle(float deg, float startDeg, float endDeg) {
        float a = normalizeDeg(deg);
        float s = normalizeDeg(startDeg);
        float e = normalizeDeg(endDeg);
        if (s < e) {
            return a >= s && a < e;
        }
        return a >= s || a < e;
    }

    private static float normalizeDeg(float deg) {
        float d = deg % 360f;
        if (d <= -180f) {
            d += 360f;
        } else if (d > 180f) {
            d -= 360f;
        }
        return d;
    }
}
