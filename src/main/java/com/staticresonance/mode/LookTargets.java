package com.staticresonance.mode;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Shared look-ray + view-cone pick used by Control lock, Dust zone, and ally mark.
 */
public final class LookTargets {
    public static final double DEFAULT_RANGE = 64.0;
    public static final double DEFAULT_CONE_DEG = 40.0;
    private static final int CONE_CANDIDATE_CAP = 32;

    private LookTargets() {
    }

    @Nullable
    public static LivingEntity findLookLiving(Player player) {
        return findLookLiving(player, DEFAULT_RANGE, DEFAULT_CONE_DEG, e -> true);
    }

    @Nullable
    public static LivingEntity findLookLiving(Player player, double range, double coneDeg,
                                              Predicate<LivingEntity> extra) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getViewVector(1.0f);
        double rangeSq = range * range;

        Vec3 end = eye.add(look.scale(range));
        AABB rayBox = player.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player,
                eye,
                end,
                rayBox,
                e -> e instanceof LivingEntity living
                        && living.isAlive()
                        && living != player
                        && !living.isSpectator()
                        && extra.test(living),
                rangeSq
        );
        if (hit != null && hit.getEntity() instanceof LivingEntity direct) {
            return direct;
        }

        double minDot = Math.cos(Math.toRadians(coneDeg));
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        double fallback = Math.min(range, 32.0);
        double fallbackSq = fallback * fallback;
        AABB around = player.getBoundingBox().inflate(fallback);
        List<LivingEntity> candidates = new ArrayList<>(player.level().getEntitiesOfClass(
                LivingEntity.class, around, e ->
                        e.isAlive()
                                && e != player
                                && !e.isSpectator()
                                && extra.test(e)
                                && e.distanceToSqr(player) <= fallbackSq));
        if (candidates.size() > CONE_CANDIDATE_CAP) {
            candidates.sort(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
            candidates = candidates.subList(0, CONE_CANDIDATE_CAP);
        }
        for (LivingEntity living : candidates) {
            Vec3 toCenter = living.getEyePosition().subtract(eye);
            double dist = toCenter.length();
            if (dist < 0.25) {
                continue;
            }
            Vec3 dir = toCenter.scale(1.0 / dist);
            double dot = look.dot(dir);
            if (dot < minDot || !player.hasLineOfSight(living)) {
                continue;
            }
            double score = (1.0 - dot) * 1000.0 + dist;
            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    @Nullable
    public static LivingEntity findLookLiving(Player player, Predicate<LivingEntity> extra) {
        return findLookLiving(player, DEFAULT_RANGE, DEFAULT_CONE_DEG, extra);
    }
}
