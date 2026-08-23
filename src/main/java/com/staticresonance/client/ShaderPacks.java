package com.staticresonance.client;

import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * Optional Iris/Oculus query. No compile dependency.
 */
final class ShaderPacks {
    private static final int CACHE_FRAMES = 20;

    private static boolean tried;
    private static Object api;
    private static Method inUseMethod;
    private static boolean cached;
    private static int cacheFrames;

    private ShaderPacks() {
    }

    static boolean inUse() {
        if (cacheFrames > 0) {
            cacheFrames--;
            return cached;
        }
        cached = query();
        cacheFrames = CACHE_FRAMES;
        return cached;
    }

    private static boolean query() {
        if (!tried) {
            resolve();
        }
        if (inUseMethod == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(inUseMethod.invoke(api));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static void resolve() {
        tried = true;
        if (!ModList.get().isLoaded("oculus") && !ModList.get().isLoaded("iris")) {
            return;
        }
        try {
            Class<?> cls = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            api = cls.getMethod("getInstance").invoke(null);
            inUseMethod = cls.getMethod("isShaderPackInUse");
        } catch (Throwable ignored) {
            inUseMethod = null;
        }
    }
}
