package com.staticresonance.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.staticresonance.client.ui.RadialModeMenu.Layer;
import com.staticresonance.client.ui.RadialModeMenu.SubHover;
import com.staticresonance.mode.ControlService;
import com.staticresonance.mode.DustService;
import com.staticresonance.mode.ResonanceMode;
import com.staticresonance.mode.ScanOutlineService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.EnumMap;

/**
 * Grey glass radial: main 5-wedge wheel + skill sub-wheels. Minecraft item icons.
 */
@Mod.EventBusSubscriber(modid = com.staticresonance.StaticResonanceMod.MOD_ID, value = Dist.CLIENT)
public final class RadialModeMenuRenderer {
    private static final int SEGMENTS_PER_WEDGE = 12;
    private static final EnumMap<RadialWedge, ItemStack> WEDGE_ICONS = new EnumMap<>(RadialWedge.class);

    static {
        for (RadialWedge wedge : RadialWedge.values()) {
            WEDGE_ICONS.put(wedge, new ItemStack(wedge.iconItem()));
        }
    }

    private static final int COL_UNSELECTED = 0x3A3A3A;
    private static final int COL_SELECTED = 0xC8C8C8;
    private static final int COL_GREY = 0x4A4A4A;
    private static final int COL_GREY_HOVER = 0x626262;
    private static final int COL_ON = 0x2E7D32;
    private static final int COL_ON_HOVER = 0x66BB6A;
    private static final int COL_OFF = 0x3A3A3A;
    private static final int COL_BORDER_LIGHT = 0xA0A0A0;
    private static final int COL_BORDER_DARK = 0x2C2C2C;
    private static final int COL_CENTER_FILL = 0x2A2A2A;
    private static final int COL_HINT = 0xB0B0B0;
    private static final int COL_SCRIM = 0x2A2A2A;

    private static final float HOVER_SCALE = 0.05f;
    private static final float HOVER_ANIM_SPEED = 14f;
    private static final float HOVER_RIM_THRESHOLD = 0.02f;
    private static final float LABEL_SHADOW_THRESHOLD = 0.35f;
    private static final float SUB_OUTER_SCALE = 0.85f;

    private static final float[] HOVER_ANIM = new float[RadialWedge.values().length];
    private static float subEmergencyHover;
    private static float subTargetHover;
    private static float subFieldHover;
    private static float subAllyHover;
    private static float subDustBurstHover;
    private static float subDustZoneHover;
    private static float subScanPassiveHover;
    private static float subScanHostileHover;
    private static float subScanPlayersHover;
    private static long lastAnimMs = System.currentTimeMillis();
    private static boolean animActive;

    private RadialModeMenuRenderer() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!RadialModeMenu.isOpen()) {
            if (animActive) {
                resetHoverAnim();
            }
            return;
        }
        animActive = true;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        float dt = tickAnimDelta();
        Layer layer = RadialModeMenu.layer();

        GuiGraphics graphics = event.getGuiGraphics();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        float cx = sw / 2f;
        float cy = sh / 2f;
        float anim = easeOutBack(RadialModeMenu.openAnim());

        // Soft grey glass scrim (layered for faux-blur)
        int a0 = (int) (90 * anim);
        int a1 = (int) (70 * anim);
        int a2 = (int) (50 * anim);
        graphics.fill(0, 0, sw, sh, withAlpha(COL_SCRIM, a0));
        graphics.fill(sw / 8, sh / 8, sw - sw / 8, sh - sh / 8, withAlpha(0x3A3A3A, a1));
        graphics.fill(sw / 5, sh / 5, sw - sw / 5, sh - sh / 5, withAlpha(0x484848, a2));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        if (layer == Layer.MAIN) {
            drawMainWheel(graphics, mc, sw, sh, cx, cy, anim, dt);
        } else {
            drawSubWheel(graphics, mc, sw, sh, cx, cy, anim, dt, layer);
        }

        Component hint = Component.translatable(layer == Layer.MAIN
                ? "ui.static_resonance.radial_hint"
                : "ui.static_resonance.radial_sub_hint");
        graphics.drawCenteredString(mc.font, hint, (int) cx, sh - 28, withAlpha(COL_HINT, (int) (220 * anim)));

        RenderSystem.disableBlend();
    }

    private static void drawMainWheel(GuiGraphics graphics, Minecraft mc, int sw, int sh,
                                      float cx, float cy, float anim, float dt) {
        RadialWedge hovered = RadialModeMenu.hoveredWedge();
        for (RadialWedge wedge : RadialWedge.values()) {
            float target = wedge == hovered ? 1f : 0f;
            int i = wedge.ordinal();
            HOVER_ANIM[i] = approach(HOVER_ANIM[i], target, dt * HOVER_ANIM_SPEED);
        }

        float outerBase = Math.min(sw, sh) * RadialWedge.OUTER_FRAC * anim;
        float inner = outerBase * RadialWedge.INNER_FRAC;
        if (outerBase < 4f) {
            return;
        }

        ResonanceMode current = RadialModeMenu.liveMode(mc);
        if (current == null) {
            current = ResonanceMode.DUST;
        }
        Font font = mc.font;
        // Radial is select-only — never grey wedges by G-cast cost
        try {
            for (RadialWedge wedge : RadialWedge.values()) {
                float h = HOVER_ANIM[wedge.ordinal()];
                float outer = outerBase * (1f + HOVER_SCALE * h);
                boolean selected = wedge.mode() == current;
                int fill = selected
                        ? lerpRgb(COL_ON, COL_ON_HOVER, h)
                        : lerpRgb(COL_UNSELECTED, COL_SELECTED, h);
                drawWedge(graphics, cx, cy, inner, outer, wedge.startDeg(), wedge.endDeg(),
                        fill, h, selected);
            }
            drawCenterDiscs(graphics, cx, cy, inner);
        } finally {
            flushSectors();
        }

        drawCenterSkillText(graphics, mc, cx, cy, current);

        for (RadialWedge wedge : RadialWedge.values()) {
            float h = HOVER_ANIM[wedge.ordinal()];
            float outer = outerBase * (1f + HOVER_SCALE * h);
            drawItemIcon(graphics, wedge, cx, cy, inner, outer, h);
        }

        for (RadialWedge wedge : RadialWedge.values()) {
            float h = HOVER_ANIM[wedge.ordinal()];
            float outer = outerBase * (1f + HOVER_SCALE * h);
            drawMainLabel(graphics, font, wedge, cx, cy, outer, h);
        }
    }

    private static void drawItemIcon(GuiGraphics graphics, RadialWedge wedge,
                                     float cx, float cy, float inner, float outer, float hoverT) {
        float mid = wedge.midDeg() * Mth.DEG_TO_RAD;
        float r = (inner + outer) * 0.5f;
        int ix = (int) (cx + Mth.cos(mid) * r) - 8;
        int iy = (int) (cy + Mth.sin(mid) * r) - 8;
        ItemStack stack = WEDGE_ICONS.get(wedge);
        float b = 0.85f + 0.15f * hoverT;
        RenderSystem.setShaderColor(b, b, b, 1f);
        graphics.renderItem(stack, ix, iy);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    private static void drawSubWheel(GuiGraphics graphics, Minecraft mc, int sw, int sh,
                                     float cx, float cy, float anim, float dt, Layer layer) {
        float subT = easeOutBack(RadialModeMenu.subAnim());
        float outerBase = Math.min(sw, sh) * RadialWedge.OUTER_FRAC * SUB_OUTER_SCALE * anim * (0.85f + 0.15f * subT);
        float inner = outerBase * RadialWedge.INNER_FRAC;
        if (outerBase < 4f) {
            return;
        }

        SubHover hover = RadialModeMenu.subHovered();
        Font font = mc.font;

        if (layer == Layer.SUB_FARADAY) {
            boolean armed = RadialModeMenu.liveFaradayEmergency(mc);
            float target = hover == SubHover.FARADAY_EMERGENCY ? 1f : 0f;
            subEmergencyHover = approach(subEmergencyHover, target, dt * HOVER_ANIM_SPEED);
            float h = subEmergencyHover;
            int fill = armed
                    ? lerpRgb(COL_ON, COL_ON_HOVER, h)
                    : lerpRgb(COL_OFF, COL_GREY, h);
            float outer = outerBase * (1f + HOVER_SCALE * h);
            try {
                drawWedge(graphics, cx, cy, inner, outer, -180f, 180f, fill, h, armed);
                drawOnOffRim(graphics, cx, cy, inner, outer, -180f, 180f, armed, h);
                drawCenterDiscs(graphics, cx, cy, inner);
            } finally {
                flushSectors();
            }
            graphics.drawCenteredString(font, Component.translatable("ui.static_resonance.sub_back"),
                    (int) cx, (int) (cy - 4), hover == SubHover.BACK ? 0xFFFFFFFF : 0xFFB0B0B0);

            Component title = Component.translatable("ui.static_resonance.faraday_panel_title");
            graphics.drawCenteredString(font, title, (int) cx, (int) (cy - outerBase - 22), 0xFFE0F7FF);

            Component state = Component.translatable(armed
                    ? "ui.static_resonance.faraday_emergency_armed"
                    : "ui.static_resonance.faraday_emergency_disarmed");
            int sc = armed ? 0xFFAAFFAA : 0xFFAAAAAA;
            graphics.drawCenteredString(font, state, (int) cx, (int) (cy + outerBase + 10), sc);
            graphics.drawCenteredString(font,
                    Component.translatable("ui.static_resonance.faraday_g_hint"),
                    (int) cx, (int) (cy + outerBase + 22), 0xFFE0F7FF);
            graphics.drawCenteredString(font,
                    Component.translatable("ui.static_resonance.sub_click_toggle"),
                    (int) cx, (int) (cy + outerBase + 34), 0xFF90A4AE);
        } else if (layer == Layer.SUB_CONTROL) {
            int style = RadialModeMenu.liveControlStyle(mc);
            boolean targetOn = style == ControlService.STYLE_TARGET;
            boolean fieldOn = style == ControlService.STYLE_FIELD;

            float tTarget = hover == SubHover.CONTROL_TARGET ? 1f : 0f;
            float tField = hover == SubHover.CONTROL_FIELD ? 1f : 0f;
            subTargetHover = approach(subTargetHover, tTarget, dt * HOVER_ANIM_SPEED);
            subFieldHover = approach(subFieldHover, tField, dt * HOVER_ANIM_SPEED);

            float ht = subTargetHover;
            float hf = subFieldHover;
            float outerT = outerBase * (1f + HOVER_SCALE * ht);
            float outerF = outerBase * (1f + HOVER_SCALE * hf);

            int fillT = targetOn
                    ? lerpRgb(COL_ON, COL_ON_HOVER, ht)
                    : lerpRgb(COL_OFF, COL_GREY, ht);
            int fillF = fieldOn
                    ? lerpRgb(COL_ON, COL_ON_HOVER, hf)
                    : lerpRgb(COL_OFF, COL_GREY, hf);

            try {
                drawWedge(graphics, cx, cy, inner, outerT, 90f, 270f, fillT, ht, targetOn);
                drawOnOffRim(graphics, cx, cy, inner, outerT, 90f, 270f, targetOn, ht);
                drawWedge(graphics, cx, cy, inner, outerF, -90f, 90f, fillF, hf, fieldOn);
                drawOnOffRim(graphics, cx, cy, inner, outerF, -90f, 90f, fieldOn, hf);
                drawCenterDiscs(graphics, cx, cy, inner);
            } finally {
                flushSectors();
            }
            graphics.drawCenteredString(font, Component.translatable("ui.static_resonance.sub_back"),
                    (int) cx, (int) (cy - 4), hover == SubHover.BACK ? 0xFFFFFFFF : 0xFFB0B0B0);

            graphics.drawCenteredString(font,
                    Component.translatable("skill.static_resonance.magnetic_lock"),
                    (int) cx, (int) (cy - outerBase - 22), 0xFFE1BEE7);

            int leftColor = targetOn ? 0xFFAAFFAA : 0xFF888888;
            int rightColor = fieldOn ? 0xFFAAFFAA : 0xFF888888;
            if (ht > 0.3f) {
                leftColor = 0xFFFFFFFF;
            }
            if (hf > 0.3f) {
                rightColor = 0xFFFFFFFF;
            }
            Component left = Component.translatable("ui.static_resonance.control_target");
            Component right = Component.translatable("ui.static_resonance.control_field");
            int lw = font.width(left);
            graphics.drawString(font, left, (int) (cx - outerBase - 10 - lw), (int) (cy - 4), leftColor, ht > 0.35f);
            graphics.drawString(font, right, (int) (cx + outerBase + 10), (int) (cy - 4), rightColor, hf > 0.35f);

            Component hint = Component.translatable(targetOn
                    ? "ui.static_resonance.control_target_hint"
                    : "ui.static_resonance.control_field_hint");
            graphics.drawCenteredString(font, hint, (int) cx, (int) (cy + outerBase + 12), 0xFFCE93D8);

            float tAlly = hover == SubHover.CONTROL_ALLY ? 1f : 0f;
            subAllyHover = approach(subAllyHover, tAlly, dt * HOVER_ANIM_SPEED);
            boolean allyOn = RadialModeMenu.lookedPlayerIsAlly();
            String allyName = RadialModeMenu.lookedPlayerName();
            Component ally = allyName == null
                    ? Component.translatable("ui.static_resonance.ally_look_hint")
                    : Component.translatable(allyOn
                            ? "ui.static_resonance.ally_revoke"
                            : "ui.static_resonance.ally_trust", allyName);
            int allyColor = allyName == null ? 0xFF888888 : (allyOn ? 0xFFAAFFAA : 0xFFFFCC80);
            if (subAllyHover > 0.3f) {
                allyColor = 0xFFFFFFFF;
            }
            graphics.drawCenteredString(font, ally, (int) cx, (int) (cy + outerBase + 24), allyColor);
        } else if (layer == Layer.SUB_DUST) {
            drawDustSub(graphics, mc, font, cx, cy, outerBase, inner, hover, dt);
        } else if (layer == Layer.SUB_SENSE) {
            drawScanSub(graphics, mc, font, cx, cy, outerBase, inner, hover, dt);
        }
    }

    private static void drawDustSub(GuiGraphics graphics, Minecraft mc, Font font,
                                    float cx, float cy, float outerBase, float inner,
                                    SubHover hover, float dt) {
        int style = RadialModeMenu.liveDustStyle(mc);
        boolean burstOn = style == DustService.STYLE_BURST;
        boolean zoneOn = style == DustService.STYLE_ZONE;
        float tBurst = hover == SubHover.DUST_BURST ? 1f : 0f;
        float tZone = hover == SubHover.DUST_ZONE ? 1f : 0f;
        subDustBurstHover = approach(subDustBurstHover, tBurst, dt * HOVER_ANIM_SPEED);
        subDustZoneHover = approach(subDustZoneHover, tZone, dt * HOVER_ANIM_SPEED);
        float hb = subDustBurstHover;
        float hz = subDustZoneHover;
        int fillB = burstOn ? lerpRgb(COL_ON, COL_ON_HOVER, hb) : lerpRgb(COL_OFF, COL_GREY, hb);
        int fillZ = zoneOn ? lerpRgb(COL_ON, COL_ON_HOVER, hz) : lerpRgb(COL_OFF, COL_GREY, hz);
        float outerB = outerBase * (1f + HOVER_SCALE * hb);
        float outerZ = outerBase * (1f + HOVER_SCALE * hz);
        try {
            drawWedge(graphics, cx, cy, inner, outerB, 90f, 270f, fillB, hb, burstOn);
            drawOnOffRim(graphics, cx, cy, inner, outerB, 90f, 270f, burstOn, hb);
            drawWedge(graphics, cx, cy, inner, outerZ, -90f, 90f, fillZ, hz, zoneOn);
            drawOnOffRim(graphics, cx, cy, inner, outerZ, -90f, 90f, zoneOn, hz);
            drawCenterDiscs(graphics, cx, cy, inner);
        } finally {
            flushSectors();
        }
        graphics.drawCenteredString(font, Component.translatable("ui.static_resonance.sub_back"),
                (int) cx, (int) (cy - 4), hover == SubHover.BACK ? 0xFFFFFFFF : 0xFFB0B0B0);
        graphics.drawCenteredString(font, Component.translatable("skill.static_resonance.dust_curtain"),
                (int) cx, (int) (cy - outerBase - 22), 0xFFE6CC8A);
        Component left = Component.translatable("ui.static_resonance.dust_burst");
        Component right = Component.translatable("ui.static_resonance.dust_zone");
        graphics.drawString(font, left, (int) (cx - outerBase - 10 - font.width(left)), (int) (cy - 4),
                burstOn ? 0xFFAAFFAA : 0xFF888888, hb > 0.35f);
        graphics.drawString(font, right, (int) (cx + outerBase + 10), (int) (cy - 4),
                zoneOn ? 0xFFAAFFAA : 0xFF888888, hz > 0.35f);
        graphics.drawCenteredString(font, Component.translatable(burstOn
                        ? "ui.static_resonance.dust_burst_hint"
                        : "ui.static_resonance.dust_zone_hint"),
                (int) cx, (int) (cy + outerBase + 12), 0xFFD7CCC8);
    }

    private static void drawScanSub(GuiGraphics graphics, Minecraft mc, Font font,
                                    float cx, float cy, float outerBase, float inner,
                                    SubHover hover, float dt) {
        int filter = RadialModeMenu.liveScanFilter(mc);
        boolean passiveOn = (filter & ScanOutlineService.FILTER_PASSIVE) != 0;
        boolean hostileOn = (filter & ScanOutlineService.FILTER_HOSTILE) != 0;
        boolean playersOn = (filter & ScanOutlineService.FILTER_PLAYERS) != 0;
        float tp = hover == SubHover.SCAN_PASSIVE ? 1f : 0f;
        float th = hover == SubHover.SCAN_HOSTILE ? 1f : 0f;
        float tu = hover == SubHover.SCAN_PLAYERS ? 1f : 0f;
        subScanPassiveHover = approach(subScanPassiveHover, tp, dt * HOVER_ANIM_SPEED);
        subScanHostileHover = approach(subScanHostileHover, th, dt * HOVER_ANIM_SPEED);
        subScanPlayersHover = approach(subScanPlayersHover, tu, dt * HOVER_ANIM_SPEED);
        float hp = subScanPassiveHover;
        float hh = subScanHostileHover;
        float hu = subScanPlayersHover;
        float outerP = outerBase * (1f + HOVER_SCALE * hp);
        float outerH = outerBase * (1f + HOVER_SCALE * hh);
        float outerU = outerBase * (1f + HOVER_SCALE * hu);
        try {
            drawWedge(graphics, cx, cy, inner, outerP, 90f, 210f,
                    passiveOn ? lerpRgb(COL_ON, COL_ON_HOVER, hp) : lerpRgb(COL_OFF, COL_GREY, hp), hp, passiveOn);
            drawOnOffRim(graphics, cx, cy, inner, outerP, 90f, 210f, passiveOn, hp);
            drawWedge(graphics, cx, cy, inner, outerH, -90f, 30f,
                    hostileOn ? lerpRgb(COL_ON, COL_ON_HOVER, hh) : lerpRgb(COL_OFF, COL_GREY, hh), hh, hostileOn);
            drawOnOffRim(graphics, cx, cy, inner, outerH, -90f, 30f, hostileOn, hh);
            drawWedge(graphics, cx, cy, inner, outerU, 30f, 90f,
                    playersOn ? lerpRgb(COL_ON, COL_ON_HOVER, hu) : lerpRgb(COL_OFF, COL_GREY, hu), hu, playersOn);
            drawOnOffRim(graphics, cx, cy, inner, outerU, 30f, 90f, playersOn, hu);
            drawCenterDiscs(graphics, cx, cy, inner);
        } finally {
            flushSectors();
        }
        graphics.drawCenteredString(font, Component.translatable("ui.static_resonance.sub_back"),
                (int) cx, (int) (cy - 4), hover == SubHover.BACK ? 0xFFFFFFFF : 0xFFB0B0B0);
        graphics.drawCenteredString(font, Component.translatable("skill.static_resonance.static_scan"),
                (int) cx, (int) (cy - outerBase - 22), 0xFF81D4FA);
        graphics.drawString(font, Component.translatable("ui.static_resonance.scan_passive"),
                (int) (cx - outerBase - 10 - font.width(Component.translatable("ui.static_resonance.scan_passive"))),
                (int) (cy - 4), passiveOn ? 0xFFAAFFAA : 0xFF888888, hp > 0.35f);
        graphics.drawString(font, Component.translatable("ui.static_resonance.scan_hostile"),
                (int) (cx + outerBase + 10), (int) (cy - 4),
                hostileOn ? 0xFFAAFFAA : 0xFF888888, hh > 0.35f);
        graphics.drawCenteredString(font, Component.translatable("ui.static_resonance.scan_players"),
                (int) cx, (int) (cy + outerBase + 10), playersOn ? 0xFFAAFFAA : 0xFF888888);
        graphics.drawCenteredString(font, Component.translatable("ui.static_resonance.scan_filter_hint"),
                (int) cx, (int) (cy + outerBase + 22), 0xFF90A4AE);
    }

    private static void drawCenterDiscs(GuiGraphics graphics, float cx, float cy, float inner) {
        drawDisc(graphics, cx, cy, inner * 0.92f, withAlpha(COL_CENTER_FILL, 200));
        drawRing(graphics, cx, cy, inner * 0.92f, withAlpha(COL_BORDER_DARK, 200), 2.2f);
        drawRing(graphics, cx, cy, inner * 0.92f - 2.2f, withAlpha(COL_BORDER_LIGHT, 140), 1.2f);
    }

    private static void drawCenterSkillText(GuiGraphics graphics, Minecraft mc, float cx, float cy,
                                            ResonanceMode current) {
        Font font = mc.font;
        Component centerSkill = Component.translatable(current.skillLangKey());
        graphics.drawCenteredString(font, centerSkill, (int) cx, (int) (cy - 6), 0xFFFFFFFF);
        int charge = RadialModeMenu.liveCharge(mc);
        if (charge != Integer.MIN_VALUE) {
            Component chargeText = Component.translatable("ui.static_resonance.charge_pip", charge);
            graphics.drawCenteredString(font, chargeText, (int) cx, (int) (cy + 6), 0xFFB0B0B0);
        }
    }

    private static void drawOnOffRim(GuiGraphics graphics, float cx, float cy,
                                     float inner, float outer,
                                     float startDeg, float endDeg, boolean on, float hoverT) {
        if (on) {
            int rimA = (int) (160 + 60 * hoverT);
            fillAnnularSector(graphics, cx, cy, outer - 2.5f, outer + 2f, startDeg, endDeg,
                    withAlpha(0x81C784, rimA));
        } else if (hoverT > HOVER_RIM_THRESHOLD) {
            fillAnnularSector(graphics, cx, cy, outer - 2f, outer + 1.5f, startDeg, endDeg,
                    withAlpha(0xFFFFFF, (int) (120 * hoverT)));
        }
        fillAnnularSector(graphics, cx, cy, outer + 1.2f, outer + 2.8f, startDeg, endDeg,
                withAlpha(COL_BORDER_LIGHT, 100));
        fillAnnularSector(graphics, cx, cy, inner, inner + 1.5f, startDeg, endDeg,
                withAlpha(COL_BORDER_DARK, 120));
    }

    private static void drawMainLabel(GuiGraphics graphics, Font font, RadialWedge wedge,
                                      float cx, float cy, float outer, float hoverT) {
        int titleColor = lerpArgb(withAlpha(0x3A3A3A, 220), withAlpha(0xFFFFFF, 255), hoverT);
        int hintColor = lerpArgb(withAlpha(0x2A2A2A, 180), withAlpha(0xE0E0E0, 230), hoverT);

        Component skill = Component.translatable(wedge.skillLangKey());
        Component hint = Component.translatable(wedge.hintLangKey());
        Component subHint = null;
        if (wedge.hasSubMenu()) {
            subHint = Component.translatable("ui.static_resonance.skill_has_sub");
        }
        int tw = font.width(skill);
        int hw = font.width(hint);

        float pad = 10f + 3f * hoverT;
        int sx;
        int sy;
        int hx;
        int hy;
        switch (wedge.labelAnchor()) {
            case TOP -> {
                sx = (int) (cx - tw / 2f);
                sy = (int) (cy - outer - 18 - 2f * hoverT);
                hx = (int) (cx - hw / 2f);
                hy = sy + 10;
            }
            case BOTTOM -> {
                sx = (int) (cx - tw / 2f);
                sy = (int) (cy + outer + 6 + 2f * hoverT);
                hx = (int) (cx - hw / 2f);
                hy = sy + 10;
            }
            case RIGHT -> {
                sx = (int) (cx + outer + pad);
                sy = (int) (cy - 10);
                hx = sx;
                hy = sy + 10;
            }
            case LEFT -> {
                sx = (int) (cx - outer - pad - tw);
                sy = (int) (cy - 10);
                hx = (int) (cx - outer - pad - hw);
                hy = sy + 10;
            }
            case TOP_LEFT -> {
                sx = (int) (cx - outer - pad - tw);
                sy = (int) (cy - outer * 0.55f);
                hx = (int) (cx - outer - pad - hw);
                hy = sy + 10;
            }
            default -> {
                sx = (int) cx;
                sy = (int) cy;
                hx = sx;
                hy = sy + 10;
            }
        }
        graphics.drawString(font, skill, sx, sy, titleColor, hoverT > LABEL_SHADOW_THRESHOLD);
        graphics.drawString(font, hint, hx, hy, hintColor, false);
        if (subHint != null && hoverT > 0.2f) {
            int shw = font.width(subHint);
            int shx = switch (wedge.labelAnchor()) {
                case RIGHT -> sx;
                case LEFT, TOP_LEFT -> (int) (cx - outer - pad - shw);
                default -> (int) (cx - shw / 2f);
            };
            int shy = hy + 10;
            graphics.drawString(font, subHint, shx, shy, withAlpha(0xFFE082, (int) (200 * hoverT)), false);
        }
    }

    private static void drawWedge(GuiGraphics graphics, float cx, float cy, float inner, float outer,
                                  float startDeg, float endDeg, int fillRgb, float hoverT, boolean isActive) {
        int alpha = (int) Mth.lerp(hoverT, isActive ? 170 : 120, 210);
        fillAnnularSector(graphics, cx, cy, inner, outer, startDeg, endDeg, withAlpha(fillRgb, alpha));

        if (isActive && hoverT < 0.5f) {
            float edgeA = (1f - hoverT * 2f) * 90f;
            fillAnnularSector(graphics, cx, cy, outer - 2.5f, outer + 1f, startDeg, endDeg,
                    withAlpha(COL_BORDER_LIGHT, (int) edgeA));
        }
        if (hoverT > HOVER_RIM_THRESHOLD && !isActive) {
            int rimA = (int) (140 * hoverT);
            fillAnnularSector(graphics, cx, cy, outer - 2.5f, outer + 2f, startDeg, endDeg,
                    withAlpha(0xFFFFFF, rimA));
        }
    }

    private static boolean sectorBatchOpen;

    private static void ensureSectorBatch() {
        if (sectorBatchOpen) {
            return;
        }
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        sectorBatchOpen = true;
    }

    private static void flushSectors() {
        if (!sectorBatchOpen) {
            return;
        }
        Tesselator.getInstance().end();
        sectorBatchOpen = false;
    }

    private static void fillAnnularSector(GuiGraphics graphics, float cx, float cy,
                                          float inner, float outer,
                                          float startDeg, float endDeg, int argb) {
        Matrix4f matrix = graphics.pose().last().pose();
        float start = startDeg * Mth.DEG_TO_RAD;
        float end = endDeg * Mth.DEG_TO_RAD;
        if (end < start) {
            end += Mth.TWO_PI;
        }
        float a = ((argb >> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float innerA = a * 0.85f;

        ensureSectorBatch();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        float prevCos = Mth.cos(start);
        float prevSin = Mth.sin(start);
        for (int i = 1; i <= SEGMENTS_PER_WEDGE; i++) {
            float t = i / (float) SEGMENTS_PER_WEDGE;
            float ang = start + (end - start) * t;
            float cos = Mth.cos(ang);
            float sin = Mth.sin(ang);
            float ox0 = cx + prevCos * outer;
            float oy0 = cy + prevSin * outer;
            float ix0 = cx + prevCos * inner;
            float iy0 = cy + prevSin * inner;
            float ox1 = cx + cos * outer;
            float oy1 = cy + sin * outer;
            float ix1 = cx + cos * inner;
            float iy1 = cy + sin * inner;
            buffer.vertex(matrix, ox0, oy0, 0).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, ix0, iy0, 0).color(r, g, b, innerA).endVertex();
            buffer.vertex(matrix, ox1, oy1, 0).color(r, g, b, a).endVertex();
            buffer.vertex(matrix, ix0, iy0, 0).color(r, g, b, innerA).endVertex();
            buffer.vertex(matrix, ix1, iy1, 0).color(r, g, b, innerA).endVertex();
            buffer.vertex(matrix, ox1, oy1, 0).color(r, g, b, a).endVertex();
            prevCos = cos;
            prevSin = sin;
        }
    }

    private static void drawDisc(GuiGraphics graphics, float cx, float cy, float radius, int argb) {
        fillAnnularSector(graphics, cx, cy, 0.01f, radius, 0, 360, argb);
    }

    private static void drawRing(GuiGraphics graphics, float cx, float cy, float radius, int argb, float thickness) {
        fillAnnularSector(graphics, cx, cy, Math.max(0.5f, radius - thickness), radius + thickness * 0.25f,
                0, 360, argb);
    }

    private static int withAlpha(int rgb, int alpha) {
        return ((alpha & 0xFF) << 24) | (rgb & 0xFFFFFF);
    }

    private static int lerpRgb(int a, int b, float t) {
        return lerpArgb(a & 0xFFFFFF, b & 0xFFFFFF, t) & 0xFFFFFF;
    }

    private static int lerpArgb(int a, int b, float t) {
        t = Mth.clamp(t, 0f, 1f);
        int aa = (a >> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int oA = (int) Mth.lerp(t, aa, ba);
        int oR = (int) Mth.lerp(t, ar, br);
        int oG = (int) Mth.lerp(t, ag, bg);
        int oB = (int) Mth.lerp(t, ab, bb);
        return (oA << 24) | (oR << 16) | (oG << 8) | oB;
    }

    private static float approach(float current, float target, float maxDelta) {
        float d = target - current;
        if (Math.abs(d) <= maxDelta) {
            return target;
        }
        return current + Math.signum(d) * maxDelta;
    }

    private static float tickAnimDelta() {
        long now = System.currentTimeMillis();
        float dt = (now - lastAnimMs) / 1000f;
        lastAnimMs = now;
        return Mth.clamp(dt, 0f, 0.05f);
    }

    private static void resetHoverAnim() {
        for (int i = 0; i < HOVER_ANIM.length; i++) {
            HOVER_ANIM[i] = 0f;
        }
        subEmergencyHover = 0f;
        subTargetHover = 0f;
        subFieldHover = 0f;
        subAllyHover = 0f;
        subDustBurstHover = 0f;
        subDustZoneHover = 0f;
        subScanPassiveHover = 0f;
        subScanHostileHover = 0f;
        subScanPlayersHover = 0f;
        lastAnimMs = System.currentTimeMillis();
        animActive = false;
    }

    private static float easeOutBack(float t) {
        t = Mth.clamp(t, 0f, 1f);
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(t - 1f, 3) + c1 * (float) Math.pow(t - 1f, 2);
    }
}
