package com.staticresonance.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.staticresonance.StaticResonanceMod;
import com.staticresonance.mode.CircuitService;
import com.staticresonance.network.CircuitOutlinePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Local-only boxes on nearby circuit blocks. See-through so walls do not hide wiring.
 */
@Mod.EventBusSubscriber(modid = StaticResonanceMod.MOD_ID, value = Dist.CLIENT)
public final class CircuitOutlineClient {
    private static final Map<BlockPos, Mark> MARKS = new HashMap<>();
    @Nullable
    private static BlockPos looked;

    private CircuitOutlineClient() {
    }

    public static void handle(CircuitOutlinePacket packet) {
        if (packet.clearAll()) {
            MARKS.clear();
            return;
        }
        MARKS.clear();
        for (CircuitOutlinePacket.Entry entry : packet.entries()) {
            BlockPos pos = entry.pos().immutable();
            AABB base = new AABB(pos);
            MARKS.put(pos, new Mark(base.inflate(0.004), base.inflate(0.03), entry.working()));
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        looked = lookCircuit(Minecraft.getInstance().player);
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (MARKS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();

        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType type = SeeThroughRender.lines();
        VertexConsumer lines = buffers.getBuffer(type);
        float pulse = 0.70f + 0.30f * (float) Math.sin(System.currentTimeMillis() / 140.0);
        for (Map.Entry<BlockPos, Mark> entry : MARKS.entrySet()) {
            BlockPos pos = entry.getKey();
            Mark mark = entry.getValue();
            boolean aim = pos.equals(looked);
            float r;
            float g;
            float b;
            float a;
            if (aim) {
                r = 1.00f;
                g = 0.62f * pulse;
                b = 0.12f;
                a = 1.00f;
            } else if (mark.working) {
                r = 0.90f;
                g = 0.22f;
                b = 0.21f;
                a = 0.85f;
            } else {
                r = 0.96f;
                g = 0.96f;
                b = 0.96f;
                a = 0.75f;
            }
            LevelRenderer.renderLineBox(pose, lines, aim ? mark.aim : mark.idle, r, g, b, a);
        }
        buffers.endBatch(type);
        pose.popPose();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MARKS.clear();
    }

    @SubscribeEvent
    public static void onClone(ClientPlayerNetworkEvent.Clone event) {
        MARKS.clear();
    }

    private static BlockPos lookCircuit(LocalPlayer player) {
        if (player == null) {
            return null;
        }
        HitResult hit = player.pick(CircuitService.RADIUS, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return ((BlockHitResult) hit).getBlockPos();
    }

    private record Mark(AABB idle, AABB aim, boolean working) {
    }
}
