package gl.syn.armacraft.client;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import gl.syn.armacraft.client.dev.DevTools;
import gl.syn.armacraft.client.input.ArmaKeys;
import gl.syn.armacraft.client.net.RelayBinding;
import gl.syn.armacraft.client.net.RelayPresence;
import gl.syn.armacraft.client.render.ArenaRenderer;
import gl.syn.armacraft.client.render.ArmaRenderTypes;
import gl.syn.armacraft.client.render.IrisCompat;
import gl.syn.armacraft.client.render.ArmaTextures;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Client entrypoint: wires config, transport, rendering, camera, input, sound, HUD and commands. */
public final class ArmacraftClient implements ClientModInitializer {

	private static DevTools dev;

	@Override
	public void onInitializeClient() {
		// armacraft:bridge is registered by the host mod (gl.syn.armacraft.relay.BridgeRelay, a main entrypoint)
		ClientConfig cfg = ClientConfig.load(FabricLoader.getInstance().getConfigDir().resolve("armacraft_client.json"));
		ArenaClient client = new ArenaClient(cfg);
		ArmaRenderTypes.init();
		IrisCompat.init(cfg.render);
		gl.syn.armacraft.client.render.ArmaSubmit.afterTerrain = cfg.render.wallsAfterWater;
		ArmaKeys.init();
		dev = new DevTools();
		ArenaRenderer renderer = new ArenaRenderer(client);

		if (RelayPresence.PRESENT) {
			RelayBinding.registerReceiver(client::relayLine);
		}
		ClientPlayConnectionEvents.JOIN.register((listener, sender, mc) -> client.onWorldJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> mc.execute(client::onWorldLeave));
		ServerboundPlayChannelEvents.REGISTER.register((listener, sender, mc, channels) -> {
			if (RelayPresence.PRESENT && RelayBinding.isChannel(channels)) {
				mc.execute(client::onRelayAdvertised);
			}
		});

		LevelRenderEvents.COLLECT_SUBMITS.register(renderer::collect);
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, outline) -> !client.camera().override());

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("armacraft_client", "hud"), client.hud()::extract);
		hideWhileRiding(client, VanillaHudElements.CROSSHAIR, true);
		for (Identifier id : List.of(VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR,
			VanillaHudElements.ARMOR_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.EXPERIENCE_LEVEL,
			VanillaHudElements.INFO_BAR, VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.HELD_ITEM_TOOLTIP)) {
			hideWhileRiding(client, id, false);
		}

		ClientTickEvents.END_CLIENT_TICK.register(mc -> dev.tick());
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> client.onWorldLeave());

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(literal("armac")
			.then(literal("join").executes(c -> {
				client.join();
				return feedback(c.getSource(), "joining via " + client.transportDescription());
			}))
			.then(literal("leave").executes(c -> {
				client.leave();
				return feedback(c.getSource(), "left the arena");
			}))
			.then(literal("status").executes(c -> feedback(c.getSource(), status(client))))
			.then(literal("mode").then(argument("mode", StringArgumentType.word()).executes(c -> {
				String m = StringArgumentType.getString(c, "mode");
				cfg.mode = m;
				cfg.sanitize();
				cfg.save();
				client.chooseTransport();
				return feedback(c.getSource(), "mode " + cfg.mode + ": " + client.transportDescription());
			})))
			.then(literal("connect").then(argument("host", StringArgumentType.string()).then(argument("port", IntegerArgumentType.integer(1, 65535)).executes(c -> {
				client.connectDirect(StringArgumentType.getString(c, "host"), IntegerArgumentType.getInteger(c, "port"));
				return feedback(c.getSource(), client.transportDescription());
			}))))
			.then(literal("anchor")
				.then(literal("here").executes(c -> {
					Vec3 p = c.getSource().getPosition();
					double[] b = client.model().bounds();
					// put the arena centre under the player
					double cx = b != null ? (b[0] + b[2]) / 2 : 0, cy = b != null ? (b[1] + b[3]) / 2 : 0;
					double s = cfg.render.scale;
					client.setAnchor(Math.floor(p.x) + 0.5 - cx * s, Math.floor(p.y), Math.floor(p.z) + 0.5 + cy * s);
					return feedback(c.getSource(), "anchor " + client.mapping());
				}))
				.then(argument("x", DoubleArgumentType.doubleArg()).then(argument("y", DoubleArgumentType.doubleArg()).then(argument("z", DoubleArgumentType.doubleArg()).executes(c -> {
					client.setAnchor(DoubleArgumentType.getDouble(c, "x"), DoubleArgumentType.getDouble(c, "y"), DoubleArgumentType.getDouble(c, "z"));
					return feedback(c.getSource(), "anchor " + client.mapping());
				})))))
			.then(literal("scale").then(argument("blocks_per_unit", DoubleArgumentType.doubleArg(0.05, 64)).executes(c -> {
				client.setScale(DoubleArgumentType.getDouble(c, "blocks_per_unit"));
				return feedback(c.getSource(), "scale " + client.mapping().scale());
			})))
			.then(literal("cam").then(argument("mode", StringArgumentType.word()).executes(c -> {
				cfg.camera.mode = StringArgumentType.getString(c, "mode");
				cfg.sanitize();
				cfg.save();
				client.applyConfig();
				return feedback(c.getSource(), "camera " + cfg.camera.mode);
			})))
			.then(literal("fallback").then(argument("hide", StringArgumentType.word()).executes(c -> {
				cfg.render.hideFallbackDisplays = Boolean.parseBoolean(StringArgumentType.getString(c, "hide"));
				cfg.save();
				return feedback(c.getSource(), "hide fallback displays: " + cfg.render.hideFallbackDisplays);
			})))
			.then(literal("reload").executes(c -> {
				ClientConfig fresh = ClientConfig.load(FabricLoader.getInstance().getConfigDir().resolve("armacraft_client.json"));
				copy(fresh, cfg);
				client.applyConfig();
				gl.syn.armacraft.client.render.ArmaSubmit.afterTerrain = cfg.render.wallsAfterWater;
				ArmaTextures.invalidate();
				gl.syn.armacraft.client.ridekit.ArmaCamSettings.invalidate();
				client.chooseTransport();
				return feedback(c.getSource(), "reloaded config");
			}))
			.then(literal("shot").executes(c -> {
				dev.requestShot("cmd");
				return feedback(c.getSource(), "screenshot queued");
			}).then(argument("label", StringArgumentType.word()).executes(c -> {
				dev.requestShot(StringArgumentType.getString(c, "label"));
				return feedback(c.getSource(), "screenshot queued");
			})))
			.then(literal("chat").then(argument("text", StringArgumentType.greedyString()).executes(c -> {
				client.sendChat(StringArgumentType.getString(c, "text"));
				return 1;
			})))));
	}

	private static void copy(ClientConfig from, ClientConfig to) {
		to.mode = from.mode;
		to.anchor = from.anchor;
		to.direct = from.direct;
		to.camera = from.camera;
		to.render = from.render;
		to.sound = from.sound;
		to.hud = from.hud;
		to.ride = from.ride;
		to.name = from.name;
		to.color = from.color;
	}

	private static String status(ArenaClient client) {
		var own = client.ownCycle();
		return "transport: " + client.transportDescription()
			+ " | relay offered: " + client.relayOffered()
			+ " | arena: " + (client.model().hasArena() ? "round " + client.model().round() : "unknown")
			+ " | cycles: " + client.model().cycles().size()
			+ " | riding: " + client.riding()
			+ " | own: " + (own == null ? "none" : (own.alive ? "alive" : "dead"))
			+ " | mapping: " + client.mapping();
	}

	private static int feedback(FabricClientCommandSource src, String msg) {
		src.sendFeedback(Component.literal("[armacraft] " + msg));
		return 1;
	}

	private static void hideWhileRiding(ArenaClient client, Identifier id, boolean always) {
		HudElementRegistry.replaceElement(id, original -> (HudElement) (graphics, delta) -> {
			boolean riding = client.camera().override() || gl.syn.armacraft.client.ridekit.ArmaRideVisuals.HUD.active();
			boolean hide = riding && (always || client.config().hud.hideVanilla);
			if (!hide) {
				original.extractRenderState(graphics, delta);
			}
		});
	}

	public static DevTools dev() {
		return dev;
	}

	static Minecraft mc() {
		return Minecraft.getInstance();
	}
}
