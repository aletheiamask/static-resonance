package com.staticresonance.mode;

import net.minecraft.core.particles.DustParticleOptions;
import org.joml.Vector3f;

/**
 * Shared white-static stand-in palette. Faraday Cage keeps its own FX.
 */
public final class SrFx {
    public static final Vector3f WHITE = new Vector3f(0.94f, 0.97f, 1.00f);
    public static final Vector3f WHITE_CORE = new Vector3f(1.00f, 1.00f, 1.00f);
    public static final DustParticleOptions CHARGE_DUST = new DustParticleOptions(WHITE, 1.0f);
    public static final DustParticleOptions CHARGE_DUST_LARGE = new DustParticleOptions(WHITE, 1.15f);
    public static final int TEXT_WHITE = 0xE8F4FF;

    private SrFx() {
    }
}
