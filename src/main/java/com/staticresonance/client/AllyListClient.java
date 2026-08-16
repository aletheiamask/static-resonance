package com.staticresonance.client;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Client cache of the local player's ally UUIDs. */
public final class AllyListClient {
    private static final Set<UUID> ALLIES = new HashSet<>();

    private AllyListClient() {
    }

    public static void replace(Collection<UUID> next) {
        ALLIES.clear();
        ALLIES.addAll(next);
    }

    public static boolean isAlly(UUID id) {
        return ALLIES.contains(id);
    }

    public static void clear() {
        ALLIES.clear();
    }
}
