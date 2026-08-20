package com.staticresonance.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkPruneTest {
    @Test
    void loadedDeadDrops() {
        assertTrue(MarkPrune.shouldDrop(true, false, 0));
    }

    @Test
    void loadedAliveStaysEvenIfFar() {
        assertFalse(MarkPrune.shouldDrop(true, true, 99));
        assertEquals(0, MarkPrune.nextMissStrikes(true, 5));
    }

    @Test
    void firstUnloadMissKeeps() {
        int strikes = MarkPrune.nextMissStrikes(false, 0);
        assertEquals(1, strikes);
        assertFalse(MarkPrune.shouldDrop(false, false, strikes));
    }

    @Test
    void secondUnloadMissDrops() {
        int strikes = MarkPrune.nextMissStrikes(false, 1);
        assertEquals(2, strikes);
        assertTrue(MarkPrune.shouldDrop(false, false, strikes));
    }
}
