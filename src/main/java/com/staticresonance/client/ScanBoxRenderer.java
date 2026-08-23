package com.staticresonance.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.List;

/**
 * World-space scan lock corners drawn after the level (and any shader composite).
 * Immediate tessellator, not {@code renderBuffers()}, so Oculus/ImmediatelyFast cannot
 * feed the lines into {@code gbuffers_line} during the deferred pass.
 */
final class ScanBoxRenderer {
    private ScanBoxRenderer() {
    }

    record Box(AABB aabb, float r, float g, float b, float a) {
    }

    static void draw(Camera camera, Matrix4f projection, Matrix4f view, boolean seeThrough, List<Box> boxes) {
        if (boxes.isEmpty()) {
            return;
        }
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        try {
            // Vertices are camera-relative world; captured view (bob/hurt included) is ModelViewMat.
            modelView.last().pose().set(view);
            modelView.last().normal().identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.backupProjectionMatrix();
            float fogStart = RenderSystem.getShaderFogStart();
            float fogEnd = RenderSystem.getShaderFogEnd();
            FogRenderer.setupNoFog();
            try {
                RenderSystem.setProjectionMatrix(new Matrix4f(projection), VertexSorting.DISTANCE_TO_ORIGIN);
                RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableCull();
                RenderSystem.lineWidth(lineWidth());
                RenderSystem.depthMask(!seeThrough);
                if (seeThrough) {
                    RenderSystem.disableDepthTest();
                } else {
                    RenderSystem.enableDepthTest();
                }

                PoseStack pose = new PoseStack();
                Vec3 cam = camera.getPosition();
                pose.translate(-cam.x, -cam.y, -cam.z);

                Tesselator tess = Tesselator.getInstance();
                BufferBuilder buffer = tess.getBuilder();
                buffer.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
                try {
                    for (Box box : boxes) {
                        corners(pose, buffer, camera, box);
                    }
                } finally {
                    tess.end();
                }
            } finally {
                RenderSystem.setShaderFogStart(fogStart);
                RenderSystem.setShaderFogEnd(fogEnd);
                RenderSystem.restoreProjectionMatrix();
                RenderSystem.depthMask(true);
                RenderSystem.enableDepthTest();
                RenderSystem.enableCull();
                RenderSystem.disableBlend();
                RenderSystem.lineWidth(1.0f);
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            }
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /**
     * Four yaw-facing L-brackets on a vertical quad around the AABB.
     * Eight segments instead of a 24-edge hitbox box.
     */
    private static void corners(PoseStack pose, VertexConsumer lines, Camera camera, Box box) {
        AABB aabb = box.aabb;
        Vec3 cam = camera.getPosition();
        double cx = (aabb.minX + aabb.maxX) * 0.5;
        double cz = (aabb.minZ + aabb.maxZ) * 0.5;
        double dx = cx - cam.x;
        double dz = cz - cam.z;
        double hyp = Math.sqrt(dx * dx + dz * dz);
        double rx = hyp < 1.0e-4 ? 1.0 : -dz / hyp;
        double rz = hyp < 1.0e-4 ? 0.0 : dx / hyp;
        double halfW = Math.max(aabb.getXsize(), aabb.getZsize()) * 0.5;
        double y0 = aabb.minY;
        double y1 = aabb.maxY;
        double shorter = Math.min(halfW * 2.0, y1 - y0);
        if (shorter < 1.0e-4) {
            return;
        }
        double arm = Math.min(Math.max(0.08, shorter * 0.28), shorter * 0.42);
        double lx = cx - rx * halfW;
        double lz = cz - rz * halfW;
        double px = cx + rx * halfW;
        double pz = cz + rz * halfW;
        float r = box.r;
        float g = box.g;
        float b = box.b;
        float a = box.a;
        line(pose, lines, lx, y0, lz, lx + rx * arm, y0, lz + rz * arm, r, g, b, a);
        line(pose, lines, lx, y0, lz, lx, y0 + arm, lz, r, g, b, a);
        line(pose, lines, px, y0, pz, px - rx * arm, y0, pz - rz * arm, r, g, b, a);
        line(pose, lines, px, y0, pz, px, y0 + arm, pz, r, g, b, a);
        line(pose, lines, lx, y1, lz, lx + rx * arm, y1, lz + rz * arm, r, g, b, a);
        line(pose, lines, lx, y1, lz, lx, y1 - arm, lz, r, g, b, a);
        line(pose, lines, px, y1, pz, px - rx * arm, y1, pz - rz * arm, r, g, b, a);
        line(pose, lines, px, y1, pz, px, y1 - arm, pz, r, g, b, a);
    }

    private static void line(
            PoseStack pose,
            VertexConsumer consumer,
            double x1, double y1, double z1,
            double x2, double y2, double z2,
            float r, float g, float b, float a) {
        Matrix4f mat = pose.last().pose();
        Matrix3f norm = pose.last().normal();
        float nx = (float) (x2 - x1);
        float ny = (float) (y2 - y1);
        float nz = (float) (z2 - z1);
        consumer.vertex(mat, (float) x1, (float) y1, (float) z1).color(r, g, b, a).normal(norm, nx, ny, nz).endVertex();
        consumer.vertex(mat, (float) x2, (float) y2, (float) z2).color(r, g, b, a).normal(norm, nx, ny, nz).endVertex();
    }

    private static float lineWidth() {
        return Math.max(2.5f, Minecraft.getInstance().getWindow().getWidth() / 1920.0f * 2.5f);
    }
}
