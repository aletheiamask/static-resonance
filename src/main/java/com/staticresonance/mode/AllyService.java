package com.staticresonance.mode;

import com.staticresonance.StaticResonanceMod;
import com.staticresonance.network.AllySyncPacket;
import com.staticresonance.network.ModNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Team;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Friends skip Control lock / Dust zone. Same vanilla team, or a personal UUID list.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID)
public final class AllyService {
    private static final String ROOT = "StaticResonance";
    private static final String ALLIES = "Allies";
    private static final Map<UUID, Set<UUID>> CACHE = new ConcurrentHashMap<>();

    private AllyService() {
    }

    public static boolean isProtected(Player caster, Entity target) {
        if (!(target instanceof Player other) || caster == other) {
            return caster == target;
        }
        if (sameTeam(caster, other)) {
            return true;
        }
        return cachedAllies(caster).contains(other.getUUID());
    }

    public static boolean sameTeam(Player a, Player b) {
        Team team = a.getTeam();
        return team != null && team == b.getTeam();
    }

    public static Set<UUID> readAllies(Player player) {
        return new HashSet<>(cachedAllies(player));
    }

    public static boolean isAllied(Player caster, UUID other) {
        return cachedAllies(caster).contains(other);
    }

    private static Set<UUID> cachedAllies(Player player) {
        Set<UUID> cached = CACHE.get(player.getUUID());
        if (cached != null) {
            return cached;
        }
        Set<UUID> loaded = loadAllies(player);
        Set<UUID> frozen = Set.copyOf(loaded);
        CACHE.put(player.getUUID(), frozen);
        return frozen;
    }

    private static Set<UUID> loadAllies(Player player) {
        Set<UUID> out = new HashSet<>();
        CompoundTag root = player.getPersistentData().getCompound(ROOT);
        ListTag list = root.getList(ALLIES, Tag.TAG_INT_ARRAY);
        for (Tag tag : list) {
            out.add(NbtUtils.loadUUID(tag));
        }
        return out;
    }

    /**
     * @return true if {@code other} is now on the list
     */
    public static boolean toggle(ServerPlayer caster, UUID other) {
        if (other.equals(caster.getUUID())) {
            return false;
        }
        Set<UUID> allies = readAllies(caster);
        boolean added = allies.add(other);
        if (!added) {
            allies.remove(other);
        }
        writeAllies(caster, allies);
        sync(caster);
        return added;
    }

    public static boolean add(ServerPlayer caster, UUID other) {
        if (other.equals(caster.getUUID())) {
            return false;
        }
        Set<UUID> allies = readAllies(caster);
        if (!allies.add(other)) {
            return false;
        }
        writeAllies(caster, allies);
        sync(caster);
        return true;
    }

    public static boolean remove(ServerPlayer caster, UUID other) {
        Set<UUID> allies = readAllies(caster);
        if (!allies.remove(other)) {
            return false;
        }
        writeAllies(caster, allies);
        sync(caster);
        return true;
    }

    public static void sync(ServerPlayer player) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new AllySyncPacket(readAllies(player)));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CACHE.remove(player.getUUID());
            sync(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CACHE.remove(event.getEntity().getUUID());
    }

    private static void writeAllies(Player player, Set<UUID> allies) {
        ListTag list = new ListTag();
        for (UUID id : allies) {
            list.add(NbtUtils.createUUID(id));
        }
        CompoundTag root = player.getPersistentData().getCompound(ROOT);
        root.put(ALLIES, list);
        player.getPersistentData().put(ROOT, root);
        CACHE.put(player.getUUID(), Set.copyOf(allies));
    }
}
