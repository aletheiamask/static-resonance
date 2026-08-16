package com.staticresonance.command;

import com.mojang.brigadier.CommandDispatcher;
import com.staticresonance.mode.AllyService;
import com.staticresonance.mode.CastService;
import com.staticresonance.mode.ControlService;
import com.staticresonance.mode.DustService;
import com.staticresonance.mode.FaradayService;
import com.staticresonance.mode.ScanOutlineService;
import com.staticresonance.mode.SrScoreboards;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Internal commands for Origins {@code execute_command}.
 * Apoli runs these with config permission_level (default 2).
 * Players typing in chat (level 0) cannot invoke {@code sr_internal}.
 */
@Mod.EventBusSubscriber(modid = com.staticresonance.StaticResonanceMod.MOD_ID)
public final class ModCommands {
    private ModCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("sr_internal")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("faraday_manual")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            SrScoreboards.ensureAll(player.getServer());
                            boolean ok = FaradayService.tryActivate(player, FaradayService.ActivateReason.MANUAL);
                            if (!ok) {
                                player.displayClientMessage(
                                        Component.literal(
                                                "Faraday Cage unavailable (wrong mode, already active, or recharging)"),
                                        true);
                            }
                            return ok ? 1 : 0;
                        }))
                .then(Commands.literal("faraday_emergency")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            SrScoreboards.ensureAll(player.getServer());
                            return FaradayService.tryActivate(player, FaradayService.ActivateReason.EMERGENCY) ? 1 : 0;
                        }))
                .then(Commands.literal("field_start")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            return ControlService.tryStartField(player) ? 1 : 0;
                        }))
                .then(Commands.literal("field_stop")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            ControlService.stopField(player, ControlService.StopReason.MANUAL);
                            return 1;
                        }))
                .then(Commands.literal("lock_target")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            return ControlService.lockLookTarget(player) ? 1 : 0;
                        }))
                .then(Commands.literal("cast")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            return CastService.castPrimary(player) ? 1 : 0;
                        }))
                .then(Commands.literal("dust_cast")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            return DustService.cast(player) ? 1 : 0;
                        }))
                .then(Commands.literal("scan_pulse")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            ScanOutlineService.pulse(player);
                            return 1;
                        }))
                .then(Commands.literal("scan_mark")
                        .executes(ctx -> {
                            ServerPlayer scanner = ctx.getSource().getPlayer();
                            Entity target = ctx.getSource().getEntity();
                            if (scanner == null || target == null) {
                                return 0;
                            }
                            ScanOutlineService.markFor(scanner, target);
                            return 1;
                        }))
                .then(Commands.literal("scan_clear")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            ScanOutlineService.clear(player);
                            return 1;
                        })));

        d.register(Commands.literal("sr")
                .then(Commands.literal("ally")
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer self = ctx.getSource().getPlayerOrException();
                                            ServerPlayer other = EntityArgument.getPlayer(ctx, "player");
                                            boolean ok = AllyService.add(self, other.getUUID());
                                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                                    ok ? "ui.static_resonance.ally_added"
                                                            : "ui.static_resonance.ally_already",
                                                    other.getGameProfile().getName()), false);
                                            return ok ? 1 : 0;
                                        })))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            ServerPlayer self = ctx.getSource().getPlayerOrException();
                                            ServerPlayer other = EntityArgument.getPlayer(ctx, "player");
                                            boolean ok = AllyService.remove(self, other.getUUID());
                                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                                    ok ? "ui.static_resonance.ally_removed"
                                                            : "ui.static_resonance.ally_missing",
                                                    other.getGameProfile().getName()), false);
                                            return ok ? 1 : 0;
                                        })))
                        .then(Commands.literal("list")
                                .executes(ctx -> {
                                    ServerPlayer self = ctx.getSource().getPlayerOrException();
                                    var ids = AllyService.readAllies(self);
                                    if (ids.isEmpty()) {
                                        ctx.getSource().sendSuccess(() ->
                                                Component.translatable("ui.static_resonance.ally_empty"), false);
                                        return 0;
                                    }
                                    ids.forEach(id -> {
                                        ServerPlayer p = self.server.getPlayerList().getPlayer(id);
                                        String name = p != null ? p.getGameProfile().getName() : id.toString();
                                        ctx.getSource().sendSuccess(() -> Component.literal("- " + name), false);
                                    });
                                    return ids.size();
                                }))));
    }
}
