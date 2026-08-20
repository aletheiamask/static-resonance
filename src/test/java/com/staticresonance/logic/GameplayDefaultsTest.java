package com.staticresonance.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GameplayDefaultsTest {
    @Test
    void catalogQuotedDefaults() {
        assertEquals(100, GameplayDefaults.CHARGE_MAX);
        assertEquals(40, GameplayDefaults.CHARGE_START);
        assertEquals(35, GameplayDefaults.CHARGE_REGEN_INTERVAL_TICKS);
        assertEquals(2, GameplayDefaults.CHARGE_DRY_GAIN);
        assertEquals(3, GameplayDefaults.CHARGE_WET_DRAIN);
        assertEquals(5, GameplayDefaults.DUST_BURST_COST);
        assertEquals(10, GameplayDefaults.DUST_ZONE_COST);
        assertEquals(6.0, GameplayDefaults.DUST_ZONE_RADIUS);
        assertEquals(200, GameplayDefaults.DUST_ZONE_DURATION_TICKS);
        assertEquals(8, GameplayDefaults.SCAN_COST);
        assertEquals(32, GameplayDefaults.SCAN_RADIUS);
        assertEquals(15, GameplayDefaults.CONTROL_TARGET_COST);
        assertEquals(40, GameplayDefaults.CONTROL_FIELD_COST);
        assertEquals(30, GameplayDefaults.CONTROL_FIELD_MAX_RADIUS);
        assertEquals(1200, GameplayDefaults.CONTROL_FIELD_DURATION_TICKS);
        assertEquals(64.0, GameplayDefaults.CONTROL_TARGET_RANGE);
        assertEquals(50, GameplayDefaults.FARADAY_MANUAL_COST);
        assertEquals(600, GameplayDefaults.FARADAY_DURATION_TICKS);
        assertEquals(2400, GameplayDefaults.FARADAY_COOLDOWN_TICKS);
        assertEquals(8, GameplayDefaults.CIRCUIT_TOGGLE_COST);
        assertEquals(16, GameplayDefaults.CIRCUIT_LOOK_RANGE);
        assertEquals(160, GameplayDefaults.CIRCUIT_TOGGLE_TICKS);
        assertEquals(0.18, GameplayDefaults.CLIMB_SPEED);
        assertFalse(GameplayDefaults.SEE_THROUGH_WALLS);
    }
}
