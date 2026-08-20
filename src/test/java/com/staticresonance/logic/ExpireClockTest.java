package com.staticresonance.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpireClockTest {
    @Test
    void remainingIsExpireMinusNow() {
        assertEquals(600, ExpireClock.remaining(1000L, 400L));
    }

    @Test
    void remainingIsZeroAtOrAfterExpire() {
        assertEquals(0, ExpireClock.remaining(1000L, 1000L));
        assertEquals(0, ExpireClock.remaining(1000L, 1001L));
    }
}
