package com.staticresonance.logic;

import com.staticresonance.network.PacketLimits;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.UUID;

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

    @Test
    void fifoNeverExceedsOutlineCap() {
        LinkedHashSet<UUID> marked = new LinkedHashSet<>();
        for (int i = 0; i < 200; i++) {
            marked.add(UUID.randomUUID());
        }
        while (marked.size() > PacketLimits.MAX_OUTLINE_MARKS) {
            Iterator<UUID> it = marked.iterator();
            it.next();
            it.remove();
        }
        assertEquals(PacketLimits.MAX_OUTLINE_MARKS, marked.size());
    }
}
