package com.staticresonance.config;

import com.staticresonance.StaticResonanceMod;
import com.staticresonance.logic.GameplayDefaults;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * COMMON gameplay knobs. Mods → Config opens when Cloth Config is present.
 * The dedicated server's file is authoritative for spend/apply.
 * Faraday duration, cooldown, and holdTicks snapshot when a cage or shot starts;
 * repel / hard / mid radii stay live each aura tick. Field and dust-zone
 * sessions keep the values they started with.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SrConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(SrConfig.class);

    public static final int CHARGE_MAX = GameplayDefaults.CHARGE_MAX;

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Common COMMON;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        COMMON = new Common(builder);
        COMMON_SPEC = builder.build();
    }

    private SrConfig() {
    }

    @SubscribeEvent
    public static void onLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == COMMON_SPEC) {
            LOGGER.debug("Static Resonance common config loaded");
        }
    }

    @SubscribeEvent
    public static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == COMMON_SPEC) {
            LOGGER.info("Static Resonance config reloaded; next casts and regen ticks use the new values");
        }
    }

    public static final class Common {
        public final Charge charge;
        public final Dust dust;
        public final Sense sense;
        public final Control control;
        public final Faraday faraday;
        public final Circuit circuit;
        public final Climb climb;
        public final Visuals visuals;

        private Common(ForgeConfigSpec.Builder builder) {
            charge = new Charge(builder);
            dust = new Dust(builder);
            sense = new Sense(builder);
            control = new Control(builder);
            faraday = new Faraday(builder);
            circuit = new Circuit(builder);
            climb = new Climb(builder);
            visuals = new Visuals(builder);
        }
    }

    public static final class Charge {
        public final ForgeConfigSpec.IntValue regenIntervalTicks;
        public final ForgeConfigSpec.IntValue dryGain;
        public final ForgeConfigSpec.IntValue wetDrain;
        public final ForgeConfigSpec.IntValue motionIntervalTicks;
        public final ForgeConfigSpec.DoubleValue motionMinDist;
        public final ForgeConfigSpec.DoubleValue motionMaxDist;
        public final ForgeConfigSpec.IntValue motionCap;

        private Charge(ForgeConfigSpec.Builder builder) {
            builder.push("charge");
            regenIntervalTicks = builder
                    .comment("Ticks between dry/wet charge ticks. Next tick uses the new interval.")
                    .translation("config.static_resonance.charge.regenIntervalTicks")
                    .defineInRange("regenIntervalTicks", GameplayDefaults.CHARGE_REGEN_INTERVAL_TICKS, 1, 1200);
            dryGain = builder
                    .comment("Charge gained when dry (not in water or rain).")
                    .translation("config.static_resonance.charge.dryGain")
                    .defineInRange("dryGain", GameplayDefaults.CHARGE_DRY_GAIN, 0, CHARGE_MAX);
            wetDrain = builder
                    .comment("Charge lost when in water or rain.")
                    .translation("config.static_resonance.charge.wetDrain")
                    .defineInRange("wetDrain", GameplayDefaults.CHARGE_WET_DRAIN, 0, CHARGE_MAX);
            motionIntervalTicks = builder
                    .comment("Ticks between motion-harvest scans.")
                    .translation("config.static_resonance.charge.motionIntervalTicks")
                    .defineInRange("motionIntervalTicks", GameplayDefaults.CHARGE_MOTION_INTERVAL_TICKS, 1, 1200);
            motionMinDist = builder
                    .comment("Closest moving life that still grants charge.")
                    .translation("config.static_resonance.charge.motionMinDist")
                    .defineInRange("motionMinDist", GameplayDefaults.CHARGE_MOTION_MIN_DIST, 0.0, 16.0);
            motionMaxDist = builder
                    .comment("Farthest moving life that still grants charge.")
                    .translation("config.static_resonance.charge.motionMaxDist")
                    .defineInRange("motionMaxDist", GameplayDefaults.CHARGE_MOTION_MAX_DIST, 0.5, 32.0);
            motionCap = builder
                    .comment("Max moving sources counted per harvest tick. Reward steps stay 1/2/3/4 charge for 1/3/5/7+ movers.")
                    .translation("config.static_resonance.charge.motionCap")
                    .defineInRange("motionCap", GameplayDefaults.CHARGE_MOTION_CAP, 1, 32);
            builder.pop();
        }
    }

    public static final class Dust {
        public final ForgeConfigSpec.IntValue burstCost;
        public final ForgeConfigSpec.IntValue zoneCost;
        public final ForgeConfigSpec.DoubleValue zoneRadius;
        public final ForgeConfigSpec.IntValue zoneDurationTicks;

        private Dust(ForgeConfigSpec.Builder builder) {
            builder.push("dust");
            burstCost = builder
                    .comment("Charge spent on a single-target Dust hit.")
                    .translation("config.static_resonance.dust.burstCost")
                    .defineInRange("burstCost", GameplayDefaults.DUST_BURST_COST, 0, CHARGE_MAX);
            zoneCost = builder
                    .comment("Charge spent to plant a smoke zone.")
                    .translation("config.static_resonance.dust.zoneCost")
                    .defineInRange("zoneCost", GameplayDefaults.DUST_ZONE_COST, 0, CHARGE_MAX);
            zoneRadius = builder
                    .comment("Smoke zone radius. Snapshotted when the zone is placed.")
                    .translation("config.static_resonance.dust.zoneRadius")
                    .defineInRange("zoneRadius", GameplayDefaults.DUST_ZONE_RADIUS, 0.5, 32.0);
            zoneDurationTicks = builder
                    .comment("Smoke zone lifetime in ticks. Snapshotted when placed.")
                    .translation("config.static_resonance.dust.zoneDurationTicks")
                    .defineInRange("zoneDurationTicks", GameplayDefaults.DUST_ZONE_DURATION_TICKS, 1, 72000);
            builder.pop();
        }
    }

    public static final class Sense {
        public final ForgeConfigSpec.IntValue scanCost;
        public final ForgeConfigSpec.IntValue scanRadius;

        private Sense(ForgeConfigSpec.Builder builder) {
            builder.push("sense");
            scanCost = builder
                    .comment("Charge spent when Static Scan starts.")
                    .translation("config.static_resonance.sense.scanCost")
                    .defineInRange("scanCost", GameplayDefaults.SCAN_COST, 0, CHARGE_MAX);
            scanRadius = builder
                    .comment("Static Scan pulse radius in blocks.")
                    .translation("config.static_resonance.sense.scanRadius")
                    .defineInRange("scanRadius", GameplayDefaults.SCAN_RADIUS, 1, 128);
            builder.pop();
        }
    }

    public static final class Control {
        public final ForgeConfigSpec.IntValue targetCost;
        public final ForgeConfigSpec.IntValue fieldCost;
        public final ForgeConfigSpec.IntValue fieldMaxRadius;
        public final ForgeConfigSpec.IntValue fieldDurationTicks;
        public final ForgeConfigSpec.DoubleValue targetRange;
        public final ForgeConfigSpec.IntValue lockEffectTicks;
        public final ForgeConfigSpec.DoubleValue moveAbortThreshold;

        private Control(ForgeConfigSpec.Builder builder) {
            builder.push("control");
            targetCost = builder
                    .comment("Charge spent on a successful Magnetic Lock hit.")
                    .translation("config.static_resonance.control.targetCost")
                    .defineInRange("targetCost", GameplayDefaults.CONTROL_TARGET_COST, 0, CHARGE_MAX);
            fieldCost = builder
                    .comment("Charge spent to start Control Field.")
                    .translation("config.static_resonance.control.fieldCost")
                    .defineInRange("fieldCost", GameplayDefaults.CONTROL_FIELD_COST, 0, CHARGE_MAX);
            fieldMaxRadius = builder
                    .comment("Control Field max radius. Snapshotted when the field starts.")
                    .translation("config.static_resonance.control.fieldMaxRadius")
                    .defineInRange("fieldMaxRadius", GameplayDefaults.CONTROL_FIELD_MAX_RADIUS, 1, 64);
            fieldDurationTicks = builder
                    .comment("Control Field lifetime in ticks. Snapshotted when the field starts.")
                    .translation("config.static_resonance.control.fieldDurationTicks")
                    .defineInRange("fieldDurationTicks", GameplayDefaults.CONTROL_FIELD_DURATION_TICKS, 1, 72000);
            targetRange = builder
                    .comment("Magnetic Lock look range in blocks.")
                    .translation("config.static_resonance.control.targetRange")
                    .defineInRange("targetRange", GameplayDefaults.CONTROL_TARGET_RANGE, 1.0, 128.0);
            lockEffectTicks = builder
                    .comment("Lock slowness / glow duration in ticks. Field sessions snapshot this.")
                    .translation("config.static_resonance.control.lockEffectTicks")
                    .defineInRange("lockEffectTicks", GameplayDefaults.CONTROL_LOCK_EFFECT_TICKS, 1, 72000);
            moveAbortThreshold = builder
                    .comment("How far the caster may move before Control Field aborts. Snapshotted on start.")
                    .translation("config.static_resonance.control.moveAbortThreshold")
                    .defineInRange("moveAbortThreshold", GameplayDefaults.CONTROL_MOVE_ABORT, 0.0, 8.0);
            builder.pop();
        }
    }

    public static final class Faraday {
        public final ForgeConfigSpec.IntValue manualCost;
        public final ForgeConfigSpec.IntValue durationTicks;
        public final ForgeConfigSpec.IntValue cooldownTicks;
        public final ForgeConfigSpec.DoubleValue repelRadius;
        public final ForgeConfigSpec.DoubleValue hardRadius;
        public final ForgeConfigSpec.DoubleValue midRadius;
        public final ForgeConfigSpec.IntValue holdTicks;
        public final ForgeConfigSpec.DoubleValue emergencyMinHealth;

        private Faraday(ForgeConfigSpec.Builder builder) {
            builder.push("faraday");
            manualCost = builder
                    .comment("Charge spent to raise Faraday Cage manually. Emergency raise is free.")
                    .translation("config.static_resonance.faraday.manualCost")
                    .defineInRange("manualCost", GameplayDefaults.FARADAY_MANUAL_COST, 0, CHARGE_MAX);
            durationTicks = builder
                    .comment("Cage lifetime in ticks. Written onto the cage when it is raised.")
                    .translation("config.static_resonance.faraday.durationTicks")
                    .defineInRange("durationTicks", GameplayDefaults.FARADAY_DURATION_TICKS, 1, 72000);
            cooldownTicks = builder
                    .comment("Ticks before the cage can be raised again after it drops.")
                    .translation("config.static_resonance.faraday.cooldownTicks")
                    .defineInRange("cooldownTicks", GameplayDefaults.FARADAY_COOLDOWN_TICKS, 0, 72000);
            repelRadius = builder
                    .comment("Outer living-repel radius. Next cage tick uses the new value.")
                    .translation("config.static_resonance.faraday.repelRadius")
                    .defineInRange("repelRadius", GameplayDefaults.FARADAY_REPEL_RADIUS, 0.5, 32.0);
            hardRadius = builder
                    .comment("Hard push wall radius.")
                    .translation("config.static_resonance.faraday.hardRadius")
                    .defineInRange("hardRadius", GameplayDefaults.FARADAY_HARD_RADIUS, 0.5, 32.0);
            midRadius = builder
                    .comment("Soft push radius between hard wall and outer edge.")
                    .translation("config.static_resonance.faraday.midRadius")
                    .defineInRange("midRadius", GameplayDefaults.FARADAY_MID_RADIUS, 0.5, 32.0);
            holdTicks = builder
                    .comment("How long a caught projectile hangs. Snapshotted per shot.")
                    .translation("config.static_resonance.faraday.holdTicks")
                    .defineInRange("holdTicks", GameplayDefaults.FARADAY_HOLD_TICKS, 1, 200);
            emergencyMinHealth = builder
                    .comment("Emergency cage raises if a hit would leave health + absorption below this.")
                    .translation("config.static_resonance.faraday.emergencyMinHealth")
                    .defineInRange("emergencyMinHealth", GameplayDefaults.FARADAY_EMERGENCY_MIN_HEALTH, 0.0, 20.0);
            builder.pop();
        }
    }

    public static final class Circuit {
        public final ForgeConfigSpec.IntValue toggleCost;
        public final ForgeConfigSpec.IntValue lookRange;
        public final ForgeConfigSpec.IntValue toggleTicks;

        private Circuit(ForgeConfigSpec.Builder builder) {
            builder.push("circuit");
            toggleCost = builder
                    .comment("Charge spent to force a looked-at circuit piece.")
                    .translation("config.static_resonance.circuit.toggleCost")
                    .defineInRange("toggleCost", GameplayDefaults.CIRCUIT_TOGGLE_COST, 0, CHARGE_MAX);
            lookRange = builder
                    .comment("How far F may aim to toggle a circuit piece. Raycast only; never a volume scan.")
                    .translation("config.static_resonance.circuit.lookRange")
                    .defineInRange("lookRange", GameplayDefaults.CIRCUIT_LOOK_RANGE, 1, 32);
            toggleTicks = builder
                    .comment("How long a forced circuit stays flipped. Snapshotted on toggle.")
                    .translation("config.static_resonance.circuit.toggleTicks")
                    .defineInRange("toggleTicks", GameplayDefaults.CIRCUIT_TOGGLE_TICKS, 1, 72000);
            builder.pop();
        }
    }

    public static final class Climb {
        public final ForgeConfigSpec.DoubleValue climbSpeed;

        private Climb(ForgeConfigSpec.Builder builder) {
            builder.push("climb");
            climbSpeed = builder
                    .comment("Vertical speed while wall-climbing. The dedicated server file is authoritative. A remote client's file only predicts motion; singleplayer and the LAN host share one file.")
                    .translation("config.static_resonance.climb.climbSpeed")
                    .defineInRange("climbSpeed", GameplayDefaults.CLIMB_SPEED, 0.01, 1.0);
            builder.pop();
        }
    }

    public static final class Visuals {
        public final ForgeConfigSpec.BooleanValue seeThroughWalls;

        private Visuals(ForgeConfigSpec.Builder builder) {
            builder.push("visuals");
            seeThroughWalls = builder
                    .comment("If true, Sense scan boxes draw through walls for the caster. Default false (server-side opt-in). Origin skill only; not a cheat client. Dedicated servers: this COMMON file is authoritative.")
                    .translation("config.static_resonance.visuals.seeThroughWalls")
                    .define("seeThroughWalls", GameplayDefaults.SEE_THROUGH_WALLS);
            builder.pop();
        }
    }
}
