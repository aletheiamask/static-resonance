package com.staticresonance.logic;

/**
 * Scan mark eviction: drop loaded-dead immediately; drop unloaded after
 * {@link #UNLOAD_STRIKES_BEFORE_DROP} consecutive misses. Loaded living stays.
 */
public final class MarkPrune {
    public static final int UNLOAD_STRIKES_BEFORE_DROP = 2;

    private MarkPrune() {
    }

    public static int nextMissStrikes(boolean present, int current) {
        return present ? 0 : current + 1;
    }

    public static boolean shouldDrop(boolean present, boolean alive, int missStrikes) {
        if (present) {
            return !alive;
        }
        return missStrikes >= UNLOAD_STRIKES_BEFORE_DROP;
    }
}
