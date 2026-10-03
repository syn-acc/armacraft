package gl.syn.armacraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import gl.syn.armacraft.ArmaController;
import gl.syn.armacraft.Armacraft;
import gl.syn.armacraft.bridge.Protocol;
import gl.syn.armacraft.config.ArmaConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * /arma connect [host] [port] | disconnect | reload | anchor | scale &lt;f&gt; | join | leave | status | cmd &lt;line...&gt;
 *       | ride [start|stop] | cycle [on|off] | preset [name] | whatsnew
 *       | debug turn &lt;player&gt; left|right | debug brake &lt;player&gt; on|off
 *
 * <p>connect, disconnect, anchor, scale, cmd and debug need gamemaster permission (op level 2): they move
 * world geometry for everyone, open outbound connections from the server, or drive another player cycle.
 * join, leave, status and ride are open.
 */
public final class ArmaCommands {

	private ArmaCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("arma")
			.then(Commands.literal("connect")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> connect(ctx, null, -1))
				.then(Commands.argument("host", StringArgumentType.string())
					.executes(ctx -> connect(ctx, StringArgumentType.getString(ctx, "host"), -1))
					.then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
						.executes(ctx -> connect(ctx, StringArgumentType.getString(ctx, "host"), IntegerArgumentType.getInteger(ctx, "port"))))))
			.then(Commands.literal("disconnect")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ArmaCommands::disconnect))
			.then(Commands.literal("anchor")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ArmaCommands::anchor))
			.then(Commands.literal("scale")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("f", DoubleArgumentType.doubleArg(0.05, 16.0))
					.executes(ctx -> scale(ctx, DoubleArgumentType.getDouble(ctx, "f")))))
			.then(Commands.literal("preset")
				.executes(ArmaCommands::presetShow)
				.then(Commands.argument("name", StringArgumentType.greedyString())
					.requires(ArmaCommands::mayChangeRidePhysics)
					.suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(gl.syn.armacraft.ride.RideConfig.PRESETS, b))
					.executes(ctx -> {
						ok(ctx, Armacraft.ride().setPreset(StringArgumentType.getString(ctx, "name")));
						return 1;
					})))
			.then(Commands.literal("cycle")
				.executes(ctx -> cycle(ctx, gl.syn.armacraft.ride.net.RidePayloads.Cycle.TOGGLE))
				.then(Commands.literal("on").executes(ctx -> cycle(ctx, gl.syn.armacraft.ride.net.RidePayloads.Cycle.ON)))
				.then(Commands.literal("off").executes(ctx -> cycle(ctx, gl.syn.armacraft.ride.net.RidePayloads.Cycle.OFF))))
			.then(Commands.literal("ride")
				.executes(ctx -> ride(ctx, null))
				.then(Commands.literal("stop").executes(ctx -> ride(ctx, false)))
				.then(Commands.literal("start").executes(ctx -> ride(ctx, true))))
			.then(Commands.literal("join").executes(ArmaCommands::join))
			.then(Commands.literal("leave").executes(ArmaCommands::leave))
			.then(Commands.literal("status").executes(ArmaCommands::status))
			.then(Commands.literal("reload")
				// gamemasters, or the owner of a singleplayer world even with cheats off
				.requires(ArmaCommands::mayChangeRidePhysics)
				.executes(ctx -> {
					// config/armacraft.json, armacraft-ride.json (pushed to clients, which re-read their keys) and
					// armacraft-ride-server.json: all live, no restart
					ok(ctx, c().reloadConfig());
					ok(ctx, Armacraft.ride().reloadConfig());
					gl.syn.armacraft.ride.server.RideServer rs = gl.syn.armacraft.ride.server.RideServer.get();
					if (rs != null) {
						ok(ctx, rs.reloadConfig());
					}
					return 1;
				}))
			.then(Commands.literal("whatsnew").executes(ArmaCommands::whatsNew))
			.then(Commands.literal("debug")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("turn")
					.then(Commands.argument("player", EntityArgument.player())
						.then(Commands.literal("left").executes(ctx -> debugTurn(ctx, true)))
						.then(Commands.literal("right").executes(ctx -> debugTurn(ctx, false)))))
				.then(Commands.literal("brake")
					.then(Commands.argument("player", EntityArgument.player())
						.then(Commands.literal("on").executes(ctx -> debugBrake(ctx, true)))
						.then(Commands.literal("off").executes(ctx -> debugBrake(ctx, false))))))
			.then(Commands.literal("cmd")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("line", StringArgumentType.greedyString())
					.executes(ctx -> cmd(ctx, StringArgumentType.getString(ctx, "line")))))
		);
	}

	private static ArmaController c() {
		return Armacraft.controller();
	}

	private static int connect(CommandContext<CommandSourceStack> ctx, String host, int port) {
		ArmaController c = c();
		String h = host != null ? host : c.config().host;
		int p = port > 0 ? port : c.config().port;
		c.connect(h, p);
		c.saveConfig();
		ok(ctx, "[arma] connecting to " + h + ":" + p + " (retries with backoff)");
		return 1;
	}

	private static int disconnect(CommandContext<CommandSourceStack> ctx) {
		c().disconnect();
		ok(ctx, "[arma] disconnected, arena entities removed");
		return 1;
	}

	private static int anchor(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		Vec3 pos = src.getPosition();
		ArmaConfig cfg = c().config();
		cfg.anchor.dimension = src.getLevel().dimension().identifier().toString();
		cfg.anchor.x = Math.floor(pos.x) + 0.5;
		cfg.anchor.y = Math.floor(pos.y);
		cfg.anchor.z = Math.floor(pos.z) + 0.5;
		c().saveConfig();
		c().markLayoutDirty();
		ok(ctx, String.format("[arma] anchor set to %s %.1f %.1f %.1f", cfg.anchor.dimension, cfg.anchor.x, cfg.anchor.y, cfg.anchor.z));
		return 1;
	}

	private static int scale(CommandContext<CommandSourceStack> ctx, double f) {
		c().config().scale = f;
		c().saveConfig();
		c().markLayoutDirty();
		ok(ctx, "[arma] scale set to " + f + " blocks per grid unit");
		return 1;
	}

	private static int join(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		if (gl.syn.armacraft.relay.BridgeRelay.refuseVanillaJoin(p, ctx.getSource())) { return 0; } // armacraft-relay
		ArmaController c = c();
		if (c.sessions().get(p.getUUID()) != null) {
			ok(ctx, "[arma] already joined");
			return 0;
		}
		if (Armacraft.ride().isRiding(p)) {
			Armacraft.ride().stop(p, gl.syn.armacraft.ride.RideRegistry.StopReason.DISMOUNT, p.position(), true, "joined the arena");
		}
		c.sessions().join(p, c.bridge());
		ok(ctx, c.bridge().isConnected()
			? "[arma] joining... you spawn at the next round start. /arma leave to exit"
			: "[arma] queued: will join when the guest connects. /arma leave to exit");
		return 1;
	}

	/** Ride physics affect everyone on the server: gamemasters, or the owner of a singleplayer world (cheats or not). */
	private static boolean mayChangeRidePhysics(CommandSourceStack src) {
		if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(src)) {
			return true;
		}
		ServerPlayer p = src.getPlayer();
		return p != null && src.getServer().isSingleplayerOwner(p.nameAndId());
	}

	private static int presetShow(CommandContext<CommandSourceStack> ctx) {
		var c = Armacraft.ride().config();
		ok(ctx, "[arma] ride preset " + c.preset + " (" + String.join(", ", gl.syn.armacraft.ride.RideConfig.PRESETS) + "); /arma preset <name>");
		return 1;
	}

	/** /arma cycle [on|off]: switch your client's cycle mode (the light cycle interface); toggles without an argument. */
	private static int cycle(CommandContext<CommandSourceStack> ctx, int action) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String err = Armacraft.ride().cycleCommand(p, action);
		if (err != null) {
			ctx.getSource().sendFailure(Component.literal("[arma] " + err));
			return 0;
		}
		return 1;
	}

	/** /arma whatsnew: the client prints its build stamp and config/armacraft-whatsnew.txt again. */
	private static int whatsNew(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String err = Armacraft.ride().whatsNewCommand(ctx.getSource().getPlayerOrException());
		if (err != null) {
			ctx.getSource().sendFailure(Component.literal("[arma] " + err));
			return 0;
		}
		return 1;
	}

	/** /arma ride [start|stop]: deploy the light cycle facing your nearest cardinal, or stow it. Toggles without an argument. */
	private static int ride(CommandContext<CommandSourceStack> ctx, Boolean start) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		gl.syn.armacraft.ride.RideService ride = Armacraft.ride();
		boolean riding = ride.isRiding(p);
		boolean wantStart = start != null ? start : !riding;
		if (wantStart) {
			if (riding) {
				ok(ctx, "[arma] already riding");
				return 0;
			}
			String err = ride.start(p, p.getYRot());
			if (err != null) {
				ctx.getSource().sendFailure(Component.literal("[arma] " + err));
				return 0;
			}
			ok(ctx, "[arma] riding: A/D turn, S/space brake, sneak or R to stow");
			return 1;
		}
		if (!riding) {
			ok(ctx, "[arma] not riding");
			return 0;
		}
		ride.stop(p, gl.syn.armacraft.ride.RideRegistry.StopReason.DISMOUNT, p.position(), true, "");
		ok(ctx, "[arma] bike stowed");
		return 1;
	}

	private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		boolean was = c().sessions().leave(p, c().bridge());
		ok(ctx, was ? "[arma] left the arena" : "[arma] you are not in the arena");
		return was ? 1 : 0;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		ok(ctx, c().status());
		return 1;
	}

	private static int cmd(CommandContext<CommandSourceStack> ctx, String line) {
		if (c().bridge().send(Protocol.cmd(line))) {
			ok(ctx, "[arma] sent: " + line);
			return 1;
		}
		ctx.getSource().sendFailure(Component.literal("[arma] not connected"));
		return 0;
	}

	/**
	 * Feeds synthetic key states into the same handler the ServerboundPlayerInputPacket mixin uses
	 * (edge detection included): press then release.
	 */
	private static int debugTurn(CommandContext<CommandSourceStack> ctx, boolean left) throws CommandSyntaxException {
		ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
		if (c().sessions().get(p.getUUID()) == null) {
			ctx.getSource().sendFailure(Component.literal("[arma] " + p.getPlainTextName() + " has not joined"));
			return 0;
		}
		c().onPlayerInput(p, new Input(false, false, left, !left, false, false, false));
		c().onPlayerInput(p, Input.EMPTY);
		ok(ctx, "[arma] debug: " + p.getPlainTextName() + " turn " + (left ? "left" : "right"));
		return 1;
	}

	private static int debugBrake(CommandContext<CommandSourceStack> ctx, boolean on) throws CommandSyntaxException {
		ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
		if (c().sessions().get(p.getUUID()) == null) {
			ctx.getSource().sendFailure(Component.literal("[arma] " + p.getPlainTextName() + " has not joined"));
			return 0;
		}
		c().onPlayerInput(p, new Input(false, false, false, false, on, false, false));
		ok(ctx, "[arma] debug: " + p.getPlainTextName() + " brake " + (on ? "on" : "off"));
		return 1;
	}

	private static void ok(CommandContext<CommandSourceStack> ctx, String msg) {
		ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
	}
}
