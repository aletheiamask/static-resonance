package com.staticresonance.logic;

/**
 * Forge COMMON default numbers. {@code SrConfig} reads these in {@code defineInRange}.
 * Quoted costs in the ability catalog must match this table, not old datapack values.
 * A world's {@code static_resonance-common.toml} still wins at runtime.
 */
public final class GameplayDefaults {
    private GameplayDefaults() {
    }

    public static final int CHARGE_MAX = 100;
    public static final int CHARGE_START = 40;
    public static final int CHARGE_REGEN_INTERVAL_TICKS = 35;
    public static final int CHARGE_DRY_GAIN = 2;
    public static final int CHARGE_WET_DRAIN = 3;
    public static final int CHARGE_MOTION_INTERVAL_TICKS = 20;
    public static final double CHARGE_MOTION_MIN_DIST = 0.5;
    public static final double CHARGE_MOTION_MAX_DIST = 5.0;
    public static final int CHARGE_MOTION_CAP = 8;

    public static final int DUST_BURST_COST = 5;
    public static final int DUST_ZONE_COST = 10;
    public static final double DUST_ZONE_RADIUS = 6.0;
    public static final int DUST_ZONE_DURATION_TICKS = 200;

    public static final int SCAN_COST = 8;
    public static final int SCAN_RADIUS = 32;

    public static final int CONTROL_TARGET_COST = 15;
    public static final int CONTROL_FIELD_COST = 40;
    public static final int CONTROL_FIELD_MAX_RADIUS = 30;
    public static final int CONTROL_FIELD_DURATION_TICKS = 1200;
    public static final double CONTROL_TARGET_RANGE = 64.0;
    public static final int CONTROL_LOCK_EFFECT_TICKS = 100;
    public static final double CONTROL_MOVE_ABORT = 0.12;

    public static final int FARADAY_MANUAL_COST = 50;
    public static final int FARADAY_DURATION_TICKS = 600;
    public static final int FARADAY_COOLDOWN_TICKS = 2400;
    public static final double FARADAY_REPEL_RADIUS = 10.0;
    public static final double FARADAY_HARD_RADIUS = 5.0;
    public static final double FARADAY_MID_RADIUS = 7.0;
    public static final int FARADAY_HOLD_TICKS = 35;
    public static final double FARADAY_EMERGENCY_MIN_HEALTH = 2.0;

    public static final int CIRCUIT_TOGGLE_COST = 8;
    public static final int CIRCUIT_LOOK_RANGE = 16;
    public static final int CIRCUIT_TOGGLE_TICKS = 160;

    public static final double CLIMB_SPEED = 0.18;

    public static final boolean SEE_THROUGH_WALLS = false;
}
