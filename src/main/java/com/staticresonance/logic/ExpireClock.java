package com.staticresonance.logic;

/** Remaining ticks until a game-time expire stamp. Never negative. */
public final class ExpireClock {
    private ExpireClock() {
    }

    public static int remaining(long expireAt, long now) {
        long left = expireAt - now;
        if (left <= 0L) {
            return 0;
        }
        if (left > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) left;
    }
}
