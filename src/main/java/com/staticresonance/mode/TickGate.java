package com.staticresonance.mode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player game-time window. Stamp policy is the caller's. */
final class TickGate {
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();
    private final int window;

    TickGate(int window) {
        this.window = window;
    }

    boolean blocked(UUID id, long now) {
        Long at = last.get(id);
        return at != null && now - at < window;
    }

    void stamp(UUID id, long now) {
        last.put(id, now);
    }

    void clear(UUID id) {
        last.remove(id);
    }
}
