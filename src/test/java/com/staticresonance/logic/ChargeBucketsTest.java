package com.staticresonance.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChargeBucketsTest {
    @Test
    void rewardSteps() {
        assertEquals(0, ChargeBuckets.reward(0));
        assertEquals(1, ChargeBuckets.reward(1));
        assertEquals(1, ChargeBuckets.reward(2));
        assertEquals(2, ChargeBuckets.reward(3));
        assertEquals(2, ChargeBuckets.reward(4));
        assertEquals(3, ChargeBuckets.reward(5));
        assertEquals(3, ChargeBuckets.reward(6));
        assertEquals(4, ChargeBuckets.reward(7));
        assertEquals(4, ChargeBuckets.reward(8));
    }
}
