package com.staticresonance.client.ui;

import com.staticresonance.client.ModKeyBindings;
import com.staticresonance.client.AllyListClient;
import com.staticresonance.mode.ControlService;
import com.staticresonance.mode.DustService;
import com.staticresonance.mode.FaradayService;
import com.staticresonance.mode.LookTargets;
import com.staticresonance.mode.ModeService;
import com.staticresonance.mode.ResonanceMode;
import com.staticresonance.mode.ScanOutlineService;
import com.staticresonance.network.ModNetwork;
import com.staticresonance.network.SetControlStylePacket;
import com.staticresonance.network.SetDustStylePacket;
import com.staticresonance.network.SetModePacket;
import com.staticresonance.network.SetScanFilterPacket;
import com.staticresonance.network.ToggleAllyPacket;
import com.staticresonance.network.ToggleFaradayEmergencyPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

/**
 * Client controller: hold R → radial, tap R → cycle modes.
 * Normal play: release the mouse and aim with the pointer (old feel).
 * If the cursor is already free (Axiom editor), do not open — tap R still cycles.
 * LMB / release R: <b>select only</b> — never cast.
 */
@Mod.EventBusSubscriber(modid = com.staticresonance.StaticResonanceMod.MOD_ID, value = Dist.CLIENT)
public final class RadialModeMenu {
    public enum Phase {
        IDLE,
        PRESSING,
        OPEN
    }

    public enum Layer {
        MAIN,
        SUB_FARADAY,
        SUB_CONTROL,
        SUB_DUST,
        SUB_SENSE
    }

    public enum SubHover {
        NONE,
        BACK,
        FARADAY_EMERGENCY,
        CONTROL_TARGET,
        CONTROL_FIELD,
        CONTROL_ALLY,
        DUST_BURST,
        DUST_ZONE,
        SCAN_PASSIVE,
        SCAN_HOSTILE,
        SCAN_PLAYERS
    }

    public static final long HOLD_OPEN_MS = 120L;
    private static final long OPEN_ANIM_MS = 140L;
    private static final long SUB_ANIM_MS = 120L;
    private static final double MOUSE_MOVE_EPS = 0.5;
    /** Sentinel when Apoli charge resource is not readable on client. */
    private static final int CHARGE_UNKNOWN = Integer.MIN_VALUE;

    private static Phase phase = Phase.IDLE;
    private static Layer layer = Layer.MAIN;
    private static long pressStartedMs;
    private static long openStartedMs;
    private static long subOpenedMs;
    private static boolean wasDown;
    private static boolean mouseReleasedByMenu;
    /** True after LMB applied something on main this open session — skip re-apply on release R. */
    private static boolean mainCommitted;
    @Nullable
    private static java.util.UUID lookedPlayerId;
    @Nullable
    private static String lookedPlayerName;

    private static double aimDx;
    private static double aimDy;
    private static double lastPointerX;
    private static double lastPointerY;
    private static boolean hasPointerSample;

    private RadialModeMenu() {
    }

    public static Phase phase() {
        return phase;
    }

    public static Layer layer() {
        return layer;
    }

    public static boolean isOpen() {
        return phase == Phase.OPEN;
    }

    public static boolean isSubLayer() {
        return isOpen() && layer != Layer.MAIN;
    }

    @Nullable
    public static RadialWedge hoveredWedge() {
        if (phase != Phase.OPEN || layer != Layer.MAIN) {
            return null;
        }
        return resolveMainHover(Minecraft.getInstance());
    }

    public static SubHover subHovered() {
        if (phase != Phase.OPEN || layer == Layer.MAIN) {
            return SubHover.NONE;
        }
        return resolveSubHover(Minecraft.getInstance());
    }

    public static float openAnim() {
        if (phase != Phase.OPEN) {
            return 0f;
        }
        float t = (System.currentTimeMillis() - openStartedMs) / (float) OPEN_ANIM_MS;
        return Math.min(1f, Math.max(0f, t));
    }

    public static float subAnim() {
        if (phase != Phase.OPEN || layer == Layer.MAIN) {
            return 0f;
        }
        float t = (System.currentTimeMillis() - subOpenedMs) / (float) SUB_ANIM_MS;
        return Math.min(1f, Math.max(0f, t));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.screen != null) {
            reset(mc);
            wasDown = false;
            return;
        }
        boolean down = ModKeyBindings.MODE_MENU.isDown();
        if (!down && !wasDown) {
            return;
        }
        if (!ModeService.hasModePower(player)) {
            reset(mc);
            wasDown = false;
            return;
        }
        if (down && !wasDown) {
            onPress();
        } else if (down && wasDown) {
            onHold(mc);
        } else if (!down && wasDown) {
            onRelease(mc);
        }
        wasDown = down;
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!isOpen()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }
        double scrollDelta = event.getScrollDelta();
        if (scrollDelta == 0.0) {
            return;
        }
        if (layer == Layer.MAIN) {
            RadialWedge base = resolveMainHover(mc);
            if (base == null) {
                ResonanceMode live = liveMode(mc);
                base = live != null ? RadialWedge.ofMode(live) : RadialWedge.DUST;
                if (base == null) {
                    base = RadialWedge.DUST;
                }
            }
            RadialWedge[] all = RadialWedge.values();
            int idx = base.ordinal();
            int next = scrollDelta > 0
                    ? (idx - 1 + all.length) % all.length
                    : (idx + 1) % all.length;
            setAimToWedge(mc, all[next]);
        } else if (layer == Layer.SUB_CONTROL || layer == Layer.SUB_SENSE || layer == Layer.SUB_DUST) {
            cycleSubAim(mc, scrollDelta > 0);
        } else if (layer == Layer.SUB_FARADAY) {
            setAimToSubSide(mc, false);
        }
        GuiPos mouse = guiMouse(mc);
        lastPointerX = mouse.x;
        lastPointerY = mouse.y;
        hasPointerSample = true;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!isOpen()) {
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }

        int button = event.getButton();
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (layer != Layer.MAIN) {
                enterMain(mc);
            }
            event.setCanceled(true);
            return;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            handleLeftClick(mc);
            // Always cancel while menu open — prevents attack / use
            event.setCanceled(true);
        }
    }

    /**
     * Block attack / use / pick while radial is open so LMB cannot fire Origins primary
     * or swing if those keys share mouse buttons.
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (isOpen()) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /**
     * LMB on main: select mode only (never cast). Sub: options / back.
     * Marks {@link #mainCommitted} only after a real {@code sendMode}.
     */
    private static void handleLeftClick(Minecraft mc) {
        if (layer == Layer.MAIN) {
            RadialWedge hover = resolveMainHover(mc);
            if (hover == null) {
                return;
            }
            ResonanceMode mode = hover.mode();
            sendMode(mode);
            mainCommitted = true;
            switch (mode.subLayer()) {
                case FARADAY -> enterSub(Layer.SUB_FARADAY, mc);
                case CONTROL -> enterSub(Layer.SUB_CONTROL, mc);
                case DUST -> enterSub(Layer.SUB_DUST, mc);
                case SENSE -> enterSub(Layer.SUB_SENSE, mc);
                case NONE -> {
                }
            }
            return;
        }
        SubHover hover = resolveSubHover(mc);
        if (hover == SubHover.BACK || hover == SubHover.NONE) {
            enterMain(mc);
            return;
        }
        if (layer == Layer.SUB_FARADAY && hover == SubHover.FARADAY_EMERGENCY) {
            ModNetwork.CHANNEL.sendToServer(new ToggleFaradayEmergencyPacket());
            return;
        }
        if (layer == Layer.SUB_CONTROL) {
            if (hover == SubHover.CONTROL_TARGET) {
                ModNetwork.CHANNEL.sendToServer(new SetControlStylePacket(ControlService.STYLE_TARGET));
            } else if (hover == SubHover.CONTROL_FIELD) {
                ModNetwork.CHANNEL.sendToServer(new SetControlStylePacket(ControlService.STYLE_FIELD));
            } else if (hover == SubHover.CONTROL_ALLY && lookedPlayerId != null) {
                ModNetwork.CHANNEL.sendToServer(new ToggleAllyPacket(lookedPlayerId));
            }
        }
        if (layer == Layer.SUB_DUST) {
            if (hover == SubHover.DUST_BURST) {
                ModNetwork.CHANNEL.sendToServer(new SetDustStylePacket(DustService.STYLE_BURST));
            } else if (hover == SubHover.DUST_ZONE) {
                ModNetwork.CHANNEL.sendToServer(new SetDustStylePacket(DustService.STYLE_ZONE));
            }
        }
        if (layer == Layer.SUB_SENSE) {
            int bit = switch (hover) {
                case SCAN_PASSIVE -> ScanOutlineService.FILTER_PASSIVE;
                case SCAN_HOSTILE -> ScanOutlineService.FILTER_HOSTILE;
                case SCAN_PLAYERS -> ScanOutlineService.FILTER_PLAYERS;
                default -> 0;
            };
            if (bit != 0) {
                ModNetwork.CHANNEL.sendToServer(new SetScanFilterPacket(bit));
            }
        }
    }

    private static void enterSub(Layer sub, Minecraft mc) {
        layer = sub;
        subOpenedMs = System.currentTimeMillis();
        if (sub == Layer.SUB_CONTROL) {
            int style = liveControlStyle(mc);
            setAimToSubSide(mc, style == ControlService.STYLE_FIELD);
        } else if (sub == Layer.SUB_DUST) {
            setAimToSubSide(mc, liveDustStyle(mc) == DustService.STYLE_ZONE);
        } else if (sub == Layer.SUB_SENSE) {
            setAimToScanFilter(mc, liveScanFilter(mc));
        } else {
            setAimToSubSide(mc, false);
        }
        rememberPointer(mc);
    }

    private static void enterMain(Minecraft mc) {
        layer = Layer.MAIN;
        ResonanceMode current = liveMode(mc);
        RadialWedge w = current != null ? RadialWedge.ofMode(current) : RadialWedge.DUST;
        if (w == null) {
            w = RadialWedge.DUST;
        }
        setAimToWedge(mc, w);
        rememberPointer(mc);
    }

    private static void onPress() {
        phase = Phase.PRESSING;
        pressStartedMs = System.currentTimeMillis();
        clearAim();
    }

    private static void onHold(Minecraft mc) {
        if (phase == Phase.PRESSING
                && System.currentTimeMillis() - pressStartedMs >= HOLD_OPEN_MS) {
            openMenu(mc);
        }
        if (phase == Phase.OPEN) {
            samplePointer(mc);
        }
    }

    private static void openMenu(Minecraft mc) {
        if (mc.player == null || !mc.mouseHandler.isMouseGrabbed()) {
            return;
        }
        phase = Phase.OPEN;
        layer = Layer.MAIN;
        mainCommitted = false;
        openStartedMs = System.currentTimeMillis();
        ResonanceMode current = liveMode(mc);
        RadialWedge w = current != null ? RadialWedge.ofMode(current) : RadialWedge.DUST;
        if (w == null) {
            w = RadialWedge.DUST;
        }
        setAimToWedge(mc, w);
        rememberPointer(mc);
        snapshotLookedPlayer(mc);
        releaseMouseForMenu(mc);
    }

    private static void onRelease(Minecraft mc) {
        if (phase == Phase.PRESSING) {
            ResonanceMode current = liveMode(mc);
            if (current == null) {
                current = ResonanceMode.DUST;
            }
            sendMode(current.next());
        } else if (phase == Phase.OPEN) {
            // If LMB already selected this open, only close — no re-send
            if (layer == Layer.MAIN && !mainCommitted) {
                RadialWedge selected = resolveMainHover(mc);
                if (selected != null) {
                    sendMode(selected.mode());
                }
            }
        }
        reset(mc);
    }

    private static void samplePointer(Minecraft mc) {
        GuiPos mouse = guiMouse(mc);
        if (hasPointerSample
                && Math.hypot(mouse.x - lastPointerX, mouse.y - lastPointerY) < MOUSE_MOVE_EPS) {
            return;
        }
        lastPointerX = mouse.x;
        lastPointerY = mouse.y;
        hasPointerSample = true;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        aimDx = mouse.x - sw / 2.0;
        aimDy = mouse.y - sh / 2.0;
    }

    private static void rememberPointer(Minecraft mc) {
        GuiPos mouse = guiMouse(mc);
        lastPointerX = mouse.x;
        lastPointerY = mouse.y;
        hasPointerSample = true;
    }

    private static GuiPos guiMouse(Minecraft mc) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        double x = mc.mouseHandler.xpos() * sw / (double) mc.getWindow().getScreenWidth();
        double y = mc.mouseHandler.ypos() * sh / (double) mc.getWindow().getScreenHeight();
        return new GuiPos(x, y);
    }

    private static void releaseMouseForMenu(Minecraft mc) {
        if (!mouseReleasedByMenu && mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
            mouseReleasedByMenu = true;
        }
    }

    private static void restoreMouseIfNeeded(Minecraft mc) {
        if (!mouseReleasedByMenu) {
            return;
        }
        mouseReleasedByMenu = false;
        if (mc.player != null && mc.level != null && mc.screen == null) {
            mc.mouseHandler.grabMouse();
        }
    }

    private static void setAimToWedge(Minecraft mc, RadialWedge wedge) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        float outer = Math.min(sw, sh) * RadialWedge.OUTER_FRAC;
        float radius = outer * RadialWedge.AIM_RADIUS_FRAC;
        float mid = wedge.midDeg() * Mth.DEG_TO_RAD;
        aimDx = Mth.cos(mid) * radius;
        aimDy = Mth.sin(mid) * radius;
    }

    private static void setAimToSubSide(Minecraft mc, boolean right) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        float outer = Math.min(sw, sh) * RadialWedge.OUTER_FRAC * 0.85f;
        float radius = outer * RadialWedge.AIM_RADIUS_FRAC;
        if (layer == Layer.SUB_FARADAY) {
            aimDx = 0;
            aimDy = -radius;
        } else {
            aimDx = right ? radius : -radius;
            aimDy = 0;
        }
    }

    @Nullable
    private static RadialWedge resolveMainHover(Minecraft mc) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        double dist = Math.hypot(aimDx, aimDy);
        float radius = Math.min(sw, sh) * RadialWedge.OUTER_FRAC;
        if (dist < radius * RadialWedge.DEADZONE_FRACTION) {
            return null;
        }
        return RadialWedge.fromAngleRad(Math.atan2(aimDy, aimDx));
    }

    private static SubHover resolveSubHover(Minecraft mc) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        double dist = Math.hypot(aimDx, aimDy);
        float radius = Math.min(sw, sh) * RadialWedge.OUTER_FRAC * 0.85f;
        if (dist < radius * RadialWedge.DEADZONE_FRACTION) {
            return SubHover.BACK;
        }
        if (layer == Layer.SUB_FARADAY) {
            return SubHover.FARADAY_EMERGENCY;
        }
        boolean bottom = aimDy > Math.abs(aimDx);
        if (layer == Layer.SUB_CONTROL) {
            if (bottom) {
                return SubHover.CONTROL_ALLY;
            }
            return aimDx >= 0 ? SubHover.CONTROL_FIELD : SubHover.CONTROL_TARGET;
        }
        if (layer == Layer.SUB_DUST) {
            return aimDx >= 0 ? SubHover.DUST_ZONE : SubHover.DUST_BURST;
        }
        if (layer == Layer.SUB_SENSE) {
            if (bottom) {
                return SubHover.SCAN_PLAYERS;
            }
            return aimDx >= 0 ? SubHover.SCAN_HOSTILE : SubHover.SCAN_PASSIVE;
        }
        return SubHover.NONE;
    }

    @Nullable
    public static ResonanceMode liveMode(Minecraft mc) {
        if (mc.player == null) {
            return null;
        }
        return ModeService.getMode(mc.player);
    }

    public static int liveCharge(Minecraft mc) {
        if (mc.player == null) {
            return CHARGE_UNKNOWN;
        }
        return ModeService.getResourceValue(mc.player, ModeService.CHARGE_POWER).orElse(CHARGE_UNKNOWN);
    }

    public static boolean liveFaradayEmergency(Minecraft mc) {
        if (mc.player == null) {
            return true;
        }
        return ModeService.getResourceValue(mc.player, FaradayService.EMERGENCY_POWER).orElse(1) != 0;
    }

    public static int liveControlStyle(Minecraft mc) {
        if (mc.player == null) {
            return ControlService.STYLE_TARGET;
        }
        return ModeService.getResourceValue(mc.player, ControlService.STYLE_POWER)
                .orElse(ControlService.STYLE_TARGET);
    }

    public static int liveDustStyle(Minecraft mc) {
        if (mc.player == null) {
            return DustService.STYLE_BURST;
        }
        return ModeService.getResourceValue(mc.player, DustService.STYLE_POWER)
                .orElse(DustService.STYLE_BURST);
    }

    public static int liveScanFilter(Minecraft mc) {
        if (mc.player == null) {
            return ScanOutlineService.FILTER_ALL;
        }
        int raw = ModeService.getResourceValue(mc.player, ScanOutlineService.FILTER_POWER)
                .orElse(ScanOutlineService.FILTER_ALL);
        return raw == 0 ? ScanOutlineService.FILTER_ALL : raw;
    }

    @Nullable
    public static java.util.UUID lookedPlayerId() {
        return lookedPlayerId;
    }

    @Nullable
    public static String lookedPlayerName() {
        return lookedPlayerName;
    }

    public static boolean lookedPlayerIsAlly() {
        return lookedPlayerId != null && AllyListClient.isAlly(lookedPlayerId);
    }

    private static void snapshotLookedPlayer(Minecraft mc) {
        lookedPlayerId = null;
        lookedPlayerName = null;
        if (mc.player == null) {
            return;
        }
        var living = LookTargets.findLookLiving(mc.player, e -> e instanceof net.minecraft.world.entity.player.Player);
        if (living instanceof net.minecraft.client.player.AbstractClientPlayer other) {
            lookedPlayerId = other.getUUID();
            lookedPlayerName = other.getGameProfile().getName();
        }
    }

    private static void setAimToScanFilter(Minecraft mc, int filter) {
        if ((filter & ScanOutlineService.FILTER_HOSTILE) != 0) {
            setAimToSubSide(mc, true);
        } else if ((filter & ScanOutlineService.FILTER_PASSIVE) != 0) {
            setAimToSubSide(mc, false);
        } else {
            setAimBottom(mc);
        }
    }

    private static void setAimBottom(Minecraft mc) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        float outer = Math.min(sw, sh) * RadialWedge.OUTER_FRAC * 0.85f;
        float radius = outer * RadialWedge.AIM_RADIUS_FRAC;
        aimDx = 0;
        aimDy = radius;
    }

    private static void cycleSubAim(Minecraft mc, boolean prev) {
        SubHover h = resolveSubHover(mc);
        if (layer == Layer.SUB_DUST) {
            setAimToSubSide(mc, h != SubHover.DUST_ZONE);
            return;
        }
        if (layer == Layer.SUB_CONTROL) {
            if (prev) {
                if (h == SubHover.CONTROL_FIELD) {
                    setAimToSubSide(mc, false);
                } else if (h == SubHover.CONTROL_ALLY) {
                    setAimToSubSide(mc, true);
                } else {
                    setAimBottom(mc);
                }
            } else {
                if (h == SubHover.CONTROL_TARGET) {
                    setAimToSubSide(mc, true);
                } else if (h == SubHover.CONTROL_FIELD) {
                    setAimBottom(mc);
                } else {
                    setAimToSubSide(mc, false);
                }
            }
            return;
        }
        if (layer == Layer.SUB_SENSE) {
            if (prev) {
                if (h == SubHover.SCAN_HOSTILE) {
                    setAimToSubSide(mc, false);
                } else if (h == SubHover.SCAN_PLAYERS) {
                    setAimToSubSide(mc, true);
                } else {
                    setAimBottom(mc);
                }
            } else {
                if (h == SubHover.SCAN_PASSIVE) {
                    setAimToSubSide(mc, true);
                } else if (h == SubHover.SCAN_HOSTILE) {
                    setAimBottom(mc);
                } else {
                    setAimToSubSide(mc, false);
                }
            }
        }
    }

    private static void sendMode(ResonanceMode mode) {
        ModNetwork.CHANNEL.sendToServer(new SetModePacket(mode.index()));
    }

    private static void clearAim() {
        aimDx = 0;
        aimDy = 0;
        hasPointerSample = false;
    }

    private static void reset(Minecraft mc) {
        phase = Phase.IDLE;
        layer = Layer.MAIN;
        mainCommitted = false;
        lookedPlayerId = null;
        lookedPlayerName = null;
        clearAim();
        restoreMouseIfNeeded(mc);
    }

    private record GuiPos(double x, double y) {
    }
}
