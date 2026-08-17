package com.staticresonance.mode;

import com.staticresonance.StaticResonanceMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.OptionalInt;

/**
 * Read/write Apoli resource powers for both:
 * <ul>
 *   <li>Forge Apoli ({@code io.github.edwinmindcraft.apoli})</li>
 *   <li>Fabric Apoli via Sinytra Connector ({@code io.github.apace100.apoli})</li>
 * </ul>
 * ModeService must never hard-link either API at class-load time — the pack may only have one.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID)
public final class ApoliResources {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final Backend BACKEND = detectBackend();

    private ApoliResources() {
    }

    public static boolean isAvailable() {
        return BACKEND != null;
    }

    public static boolean hasPower(Player player, ResourceLocation powerId) {
        return BACKEND != null && BACKEND.hasPower(player, powerId);
    }

    public static OptionalInt getValue(Player player, ResourceLocation powerId) {
        return BACKEND == null ? OptionalInt.empty() : BACKEND.getValue(player, powerId);
    }

    /**
     * @return true if the value was written (backend present and power exists)
     */
    public static boolean setValue(Player player, ResourceLocation powerId, int value) {
        return BACKEND != null && BACKEND.setValue(player, powerId, value);
    }

    public static void sync(Player player) {
        if (BACKEND != null) {
            BACKEND.sync(player);
        }
    }

    @SubscribeEvent
    public static void onReload(AddReloadListenerEvent event) {
        if (BACKEND != null) {
            BACKEND.invalidate();
        }
    }

    @Nullable
    private static Backend detectBackend() {
        if (classPresent("io.github.edwinmindcraft.apoli.api.ApoliAPI")) {
            try {
                Backend forge = new ForgeBackend();
                LOGGER.info("Static Resonance: using Forge Apoli API");
                return forge;
            } catch (Throwable t) {
                LOGGER.error("Static Resonance: Forge Apoli present but backend failed to init", t);
            }
        }
        if (classPresent("io.github.apace100.apoli.component.PowerHolderComponent")) {
            try {
                Backend fabric = new FabricBackend();
                LOGGER.info("Static Resonance: using Fabric Apoli API (Connector)");
                return fabric;
            } catch (Throwable t) {
                LOGGER.error("Static Resonance: Fabric Apoli present but backend failed to init", t);
            }
        }
        LOGGER.error(
                "Static Resonance: no Apoli API found (mod list has origins={}). Mode wheel disabled.",
                ModList.get().isLoaded("origins")
        );
        return null;
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, ApoliResources.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        }
    }

    private interface Backend {
        boolean hasPower(Player player, ResourceLocation powerId);

        OptionalInt getValue(Player player, ResourceLocation powerId);

        boolean setValue(Player player, ResourceLocation powerId, int value);

        void sync(Player player);

        default void invalidate() {
        }
    }

    /**
     * Direct calls to Forge Apoli. Only instantiated after class presence check.
     */
    private static final class ForgeBackend implements Backend {
        @Override
        public boolean hasPower(Player player, ResourceLocation powerId) {
            var container = io.github.edwinmindcraft.apoli.api.ApoliAPI.getPowerContainer(player);
            return container != null && container.hasPower(powerId);
        }

        @Override
        public OptionalInt getValue(Player player, ResourceLocation powerId) {
            var container = io.github.edwinmindcraft.apoli.api.ApoliAPI.getPowerContainer(player);
            if (container == null || !container.hasPower(powerId)) {
                return OptionalInt.empty();
            }
            var holder = container.getPower(powerId);
            if (holder == null || !holder.isBound()) {
                return OptionalInt.empty();
            }
            return holder.value().getValue(player);
        }

        @Override
        public boolean setValue(Player player, ResourceLocation powerId, int value) {
            var container = io.github.edwinmindcraft.apoli.api.ApoliAPI.getPowerContainer(player);
            if (container == null || !container.hasPower(powerId)) {
                return false;
            }
            var holder = container.getPower(powerId);
            if (holder == null || !holder.isBound()) {
                return false;
            }
            return holder.value().assign(player, value).isPresent();
        }

        @Override
        public void sync(Player player) {
            io.github.edwinmindcraft.apoli.api.ApoliAPI.synchronizePowerContainer(player);
        }
    }

    /**
     * Reflection against Fabric Apoli (works under Connector remapping).
     * Minecraft types are ResourceLocation/Entity at runtime; Apoli method names stay intermediary-free.
     */
    private static final class FabricBackend implements Backend {
        private final Class<?> powerHolderClass;
        private final Object componentKey;
        private final java.lang.reflect.Method keyMaybeGet;
        private final java.lang.reflect.Method holderHasPower;
        private final java.lang.reflect.Method holderGetPower;
        private final java.lang.reflect.Method registryContains;
        private final java.lang.reflect.Method registryGet;
        private final java.lang.reflect.Method getValue;
        private final java.lang.reflect.Method setValue;
        private final java.lang.reflect.Method syncEntity;
        private final Class<?> powerTypeClass;
        private final Class<?> variableIntPowerClass;
        private final java.util.Map<ResourceLocation, Object> powerTypes = new java.util.concurrent.ConcurrentHashMap<>();

        FabricBackend() throws Exception {
            powerHolderClass = Class.forName("io.github.apace100.apoli.component.PowerHolderComponent");
            powerTypeClass = Class.forName("io.github.apace100.apoli.power.PowerType");
            variableIntPowerClass = Class.forName("io.github.apace100.apoli.power.VariableIntPower");
            Class<?> registryClass = Class.forName("io.github.apace100.apoli.power.PowerTypeRegistry");

            componentKey = powerHolderClass.getField("KEY").get(null);
            keyMaybeGet = componentKey.getClass().getMethod("maybeGet", Object.class);
            holderHasPower = powerHolderClass.getMethod("hasPower", powerTypeClass);
            holderGetPower = powerHolderClass.getMethod("getPower", powerTypeClass);
            registryContains = registryClass.getMethod("contains", ResourceLocation.class);
            registryGet = registryClass.getMethod("get", ResourceLocation.class);
            getValue = variableIntPowerClass.getMethod("getValue");
            setValue = variableIntPowerClass.getMethod("setValue", int.class);
            syncEntity = powerHolderClass.getMethod("sync", net.minecraft.world.entity.Entity.class);
        }

        @Nullable
        private Object component(Player player) {
            try {
                @SuppressWarnings("unchecked")
                java.util.Optional<Object> opt =
                        (java.util.Optional<Object>) keyMaybeGet.invoke(componentKey, player);
                return opt.orElse(null);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        @Nullable
        private Object powerType(ResourceLocation powerId) {
            Object cached = powerTypes.get(powerId);
            if (cached != null) {
                return cached;
            }
            try {
                if (!(boolean) registryContains.invoke(null, powerId)) {
                    return null;
                }
                Object type = registryGet.invoke(null, powerId);
                if (type != null) {
                    powerTypes.put(powerId, type);
                }
                return type;
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        @Override
        public void invalidate() {
            powerTypes.clear();
        }

        @Override
        public boolean hasPower(Player player, ResourceLocation powerId) {
            Object component = component(player);
            Object type = powerType(powerId);
            if (component == null || type == null) {
                return false;
            }
            try {
                return (boolean) holderHasPower.invoke(component, type);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }

        @Override
        public OptionalInt getValue(Player player, ResourceLocation powerId) {
            Object power = resolveVariableInt(player, powerId);
            if (power == null) {
                return OptionalInt.empty();
            }
            try {
                return OptionalInt.of((int) getValue.invoke(power));
            } catch (ReflectiveOperationException e) {
                return OptionalInt.empty();
            }
        }

        @Override
        public boolean setValue(Player player, ResourceLocation powerId, int value) {
            Object power = resolveVariableInt(player, powerId);
            if (power == null) {
                return false;
            }
            try {
                setValue.invoke(power, value);
                return true;
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }

        @Override
        public void sync(Player player) {
            try {
                syncEntity.invoke(null, player);
            } catch (ReflectiveOperationException ignored) {
            }
        }

        @Nullable
        private Object resolveVariableInt(Player player, ResourceLocation powerId) {
            Object component = component(player);
            Object type = powerType(powerId);
            if (component == null || type == null) {
                return null;
            }
            try {
                if (!(boolean) holderHasPower.invoke(component, type)) {
                    return null;
                }
                Object power = holderGetPower.invoke(component, type);
                if (power == null || !variableIntPowerClass.isInstance(power)) {
                    return null;
                }
                return power;
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
    }
}
