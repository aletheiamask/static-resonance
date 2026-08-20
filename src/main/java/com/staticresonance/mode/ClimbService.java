package com.staticresonance.mode;

import com.staticresonance.StaticResonanceMod;
import com.staticresonance.config.SrConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Always-on wall climb (spider-like). Java so it works on Forge Origins and Connector.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID)
public final class ClimbService {
    private static final int SPARK_INTERVAL = 8;

    private ClimbService() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        if (!ModeService.hasModePower(player)) {
            return;
        }
        if (player.isSpectator() || player.isInWater() || !player.horizontalCollision) {
            return;
        }
        Vec3 m = player.getDeltaMovement();
        double climb = SrConfig.COMMON.climb.climbSpeed.get();
        if (m.y < climb) {
            player.setDeltaMovement(m.x, climb, m.z);
            player.hurtMarked = true;
        }
        player.fallDistance = 0.0f;
        if (!player.level().isClientSide
                && player.tickCount % SPARK_INTERVAL == 0
                && player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    player.getX(), player.getY() + 0.9, player.getZ(),
                    2, 0.15, 0.2, 0.15, 0.01);
        }
    }
}
