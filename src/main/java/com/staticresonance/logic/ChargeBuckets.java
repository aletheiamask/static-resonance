package com.staticresonance.logic;

/**
 * Motion-harvest charge reward from nearby moving life.
 * Thresholds match {@code SrConfig.charge} comments: 1 / 3 / 5 / 7+ movers.
 */
public final class ChargeBuckets {
    private ChargeBuckets() {
    }

    public static int reward(int movers) {
        if (movers >= 7) {
            return 4;
        }
        if (movers >= 5) {
            return 3;
        }
        if (movers >= 3) {
            return 2;
        }
        if (movers >= 1) {
            return 1;
        }
        return 0;
    }
}
