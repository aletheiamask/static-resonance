package com.staticresonance.mode;

import com.staticresonance.StaticResonanceMod;
import com.staticresonance.network.ModNetwork;
import com.staticresonance.network.PacketLimits;
import com.staticresonance.network.ScanOutlinePacket;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-scanner private outlines. One S2C packet per pulse with the full UUID set.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID)
public final class ScanOutlineService {
    public static final ResourceLocation ACTIVE_POWER =
            new ResourceLocation(StaticResonanceMod.MOD_ID, "systems/scan_active");
    public static final ResourceLocation RADIUS_POWER =
            new ResourceLocation(StaticResonanceMod.MOD_ID, "systems/scan_radius");
    public static final ResourceLocation FILTER_POWER =
            new ResourceLocation(StaticResonanceMod.MOD_ID, "systems/scan_filter");
    public static final int FILTER_PASSIVE = 1;
    public static final int FILTER_HOSTILE = 2;
    public static final int FILTER_PLAYERS = 4;
    public static final int FILTER_ALL = FILTER_PASSIVE | FILTER_HOSTILE | FILTER_PLAYERS;
    public static final int SCAN_COST = 8;
    public static final int SCAN_RADIUS = 32;

    private static final double MIN_DISTANCE = 0.5;
    private static final int MAX_MARKS = PacketLimits.MAX_OUTLINE_MARKS;
    private static final int PRUNE_INTERVAL = 20;

    private static final Map<UUID, Set<UUID>> MARKED = new ConcurrentHashMap<>();

    private ScanOutlineService() {
    }

    public static boolean isActive(Player player) {
        return ApoliResources.getValue(player, ACTIVE_POWER).orElse(0) != 0;
    }

    public static boolean toggle(ServerPlayer player) {
        if (isActive(player)) {
            stop(player);
            return true;
        }
        int charge = ModeService.getResourceValue(player, ModeService.CHARGE_POWER).orElse(0);
        if (charge < SCAN_COST) {
            ActionBars.show(player,
                    net.minecraft.network.chat.Component.translatable("ui.static_resonance.charge_too_low"));
            return false;
        }
        if (!ApoliResources.setValue(player, ModeService.CHARGE_POWER, charge - SCAN_COST)) {
            return false;
        }
        ApoliResources.setValue(player, ACTIVE_POWER, 1);
        ApoliResources.setValue(player, RADIUS_POWER, SCAN_RADIUS);
        ApoliResources.sync(player);
        runFunction(player, "function static_resonance:sense/scan_start");
        pulse(player);
        return true;
    }

    public static void stop(ServerPlayer player) {
        ApoliResources.setValue(player, ACTIVE_POWER, 0);
        ApoliResources.setValue(player, RADIUS_POWER, 0);
        ApoliResources.sync(player);
        runFunction(player, "function static_resonance:sense/scan_stop");
    }

    private static void runFunction(ServerPlayer player, String command) {
        var server = player.getServer();
        if (server == null) {
            return;
        }
        server.getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withSuppressedOutput().withPermission(2),
                command);
    }

    public static void pulse(ServerPlayer scanner) {
        Set<UUID> marked = MARKED.computeIfAbsent(scanner.getUUID(), id -> new LinkedHashSet<>());
        ServerLevel level = scanner.serverLevel();
        pruneDead(level, marked);
        Vec3 pos = scanner.position();
        int filter = getFilter(scanner);
        double r = SCAN_RADIUS;
        double minSq = MIN_DISTANCE * MIN_DISTANCE;
        double maxSq = r * r;
        AABB box = new AABB(pos.x - r, pos.y - r, pos.z - r, pos.x + r, pos.y + r, pos.z + r);

        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, e -> {
            if (!e.isAlive() || e == scanner || e instanceof ArmorStand || e.isSpectator()
                    || !matchesFilter(e, filter)) {
                return false;
            }
            double distSq = e.distanceToSqr(pos);
            return distSq >= minSq && distSq <= maxSq;
        })) {
            boolean first = marked.add(living.getUUID());
            if (first && living instanceof ServerPlayer pinged) {
                pingScanner(scanner, pinged);
            }
        }
        evictOldest(marked);
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> scanner),
                new ScanOutlinePacket(true, new ArrayList<>(marked)));
    }

    /** Legacy single-target mark used only if a player is the command source. */
    public static void markFor(ServerPlayer scanner, Entity target) {
        if (scanner == null || target == null || scanner == target) {
            return;
        }
        Set<UUID> marked = MARKED.computeIfAbsent(scanner.getUUID(), id -> new LinkedHashSet<>());
        if (!marked.add(target.getUUID())) {
            return;
        }
        evictOldest(marked);
        if (target instanceof ServerPlayer seen) {
            pingScanner(scanner, seen);
        }
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> scanner),
                new ScanOutlinePacket(false, List.of(target.getUUID())));
    }

    public static int getFilter(Player player) {
        int raw = ApoliResources.getValue(player, FILTER_POWER).orElse(FILTER_ALL);
        return raw == 0 ? FILTER_ALL : raw;
    }

    /**
     * Flip one bit. Refuses to turn off the last remaining filter.
     *
     * @return the mask after the attempt
     */
    public static int toggleFilter(ServerPlayer player, int bit) {
        if (bit != FILTER_PASSIVE && bit != FILTER_HOSTILE && bit != FILTER_PLAYERS) {
            return getFilter(player);
        }
        int cur = getFilter(player);
        int next = cur ^ bit;
        if (next == 0) {
            return cur;
        }
        ApoliResources.setValue(player, FILTER_POWER, next);
        ApoliResources.sync(player);
        return next;
    }

    public static boolean filterOn(Player player, int bit) {
        return (getFilter(player) & bit) != 0;
    }

    private static boolean matchesFilter(LivingEntity living, int filter) {
        if (living instanceof Player) {
            return (filter & FILTER_PLAYERS) != 0;
        }
        if (living.getType().getCategory() == MobCategory.MONSTER) {
            return (filter & FILTER_HOSTILE) != 0;
        }
        return (filter & FILTER_PASSIVE) != 0;
    }

    public static void clear(ServerPlayer scanner) {
        MARKED.remove(scanner.getUUID());
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> scanner),
                new ScanOutlinePacket(true, List.of()));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || MARKED.isEmpty()) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.overworld().getGameTime() % PRUNE_INTERVAL != 0L) {
            return;
        }
        for (UUID scannerId : MARKED.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(scannerId);
            if (player == null) {
                MARKED.remove(scannerId);
                continue;
            }
            pruneDead(player.serverLevel(), MARKED.get(scannerId));
        }
    }

    private static void pruneDead(ServerLevel level, Set<UUID> marked) {
        if (marked == null || marked.isEmpty()) {
            return;
        }
        marked.removeIf(id -> {
            Entity entity = level.getEntity(id);
            return entity != null && !entity.isAlive();
        });
    }

    private static void evictOldest(Set<UUID> marked) {
        while (marked.size() > MAX_MARKS) {
            Iterator<UUID> it = marked.iterator();
            if (!it.hasNext()) {
                return;
            }
            it.next();
            it.remove();
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MARKED.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isActive(player)) {
            MARKED.remove(player.getUUID());
            pulse(player);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isActive(player)) {
            MARKED.remove(player.getUUID());
            pulse(player);
        }
    }

    private static void pingScanner(ServerPlayer scanner, Entity at) {
        long seed = scanner.getRandom().nextLong();
        double x = at.getX();
        double y = at.getY();
        double z = at.getZ();
        scanner.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_BELL, SoundSource.PLAYERS, x, y, z, 0.7f, 1.35f, seed));
        scanner.connection.send(new ClientboundSoundPacket(
                Holder.direct(SoundEvents.AMETHYST_BLOCK_CHIME), SoundSource.PLAYERS, x, y, z, 0.45f, 1.6f, seed + 1L));
    }
}
