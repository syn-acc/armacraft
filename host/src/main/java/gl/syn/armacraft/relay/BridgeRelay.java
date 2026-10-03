package gl.syn.armacraft.relay;

import com.google.gson.JsonObject;
import gl.syn.armacraft.ArmaController;
import gl.syn.armacraft.Armacraft;
import gl.syn.armacraft.bridge.BridgeClient;
import gl.syn.armacraft.bridge.Msg;
import gl.syn.armacraft.config.ArmaConfig;
import gl.syn.armacraft.logic.Colors;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ClientboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Server side of the {@code armacraft:bridge} relay (client/RELAY.md): feeds the optional client renderer
 * ({@code armacraft_client}) with the guest's own lines over the Minecraft connection. Registered as a second
 * {@code main} entrypoint of the armacraft mod; nothing here runs for vanilla clients (they never register the
 * channel, so {@code canSend} is false and they are never subscribed).
 *
 * <p>Relay riders vs. vanilla sessions: a player who joins through the relay gets <b>no</b> {@code SessionManager}
 * session (no spectator mode, no server chase cam, no teleport): the client renders and drives the camera itself.
 * A relay {@code join} from a player who has a vanilla session ends that session first (restoring them), and
 * {@code /arma join} is refused while relay-riding ({@link #refuseVanillaJoin}). Relay riders steer only through
 * relay {@code input} lines; vanilla movement packets do nothing for them.
 */
public final class BridgeRelay implements ModInitializer {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/relay");

	private static volatile @Nullable RelayHub hub;
	private static long tapErrors;

	@Override
	public void onInitialize() {
		BridgePayload.registerTypes();
		RelayHub h = new RelayHub(new ControllerGuest(), System::nanoTime);
		hub = h;

		// Registering the receiver is also what advertises the relay: Fabric syncs it to modded clients.
		ServerPlayNetworking.registerGlobalReceiver(BridgePayload.TYPE, (payload, ctx) -> onClientLine(ctx.player(), payload.line()));

		ServerLifecycleEvents.SERVER_STARTING.register(BridgeRelay::install);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> h.clear());
		ServerTickEvents.END_SERVER_TICK.register(server -> updateRelayLine());

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> trySubscribe(handler));
		ClientboundPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> {
			if (channels.contains(BridgePayload.ID)) {
				server.execute(() -> trySubscribe(handler));
			}
		});
		ClientboundPlayChannelEvents.UNREGISTER.register((handler, sender, server, channels) -> {
			if (channels.contains(BridgePayload.ID)) {
				server.execute(() -> h.unsubscribe(handler.player.getUUID()));
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> h.disconnected(handler.player.getUUID()));
	}

	/** The relay state (null before the mod initialised). Tests and diagnostics. */
	public static @Nullable RelayHub hub() {
		return hub;
	}

	/** Hook for {@code /arma join}: a relay rider must not also get the spectator chase-cam session. */
	public static boolean refuseVanillaJoin(ServerPlayer player, CommandSourceStack source) {
		RelayHub h = hub;
		if (h != null && h.isRider(player.getUUID())) {
			source.sendFailure(Component.literal("[arma] you are riding through the armacraft client; /armac leave first"));
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------------------------------------ wiring

	private static void install(MinecraftServer server) {
		ArmaController c = Armacraft.controller();
		if (c == null) {
			LOG.warn("relay: armacraft controller missing, relay disabled");
			return;
		}
		BridgeClient bridge = c.bridge();
		bridge.lineTap = (line, msg) -> onGuestLine(bridge, line, msg);
		updateRelayLine();
	}

	/** Bridge reader thread. Never throws into the reader loop. */
	private static void onGuestLine(BridgeClient bridge, String line, Msg msg) {
		RelayHub h = hub;
		if (h == null) {
			return;
		}
		try {
			Msg.State folded = msg instanceof Msg.State s ? s : msg instanceof Msg.Delta ? bridge.latestState() : null;
			h.guestLine(line, msg, folded);
		} catch (RuntimeException e) {
			if (tapErrors++ % 100 == 0) {
				LOG.warn("relay: guest line handling failed ({} so far)", tapErrors, e);
			}
		}
	}

	private static void updateRelayLine() {
		RelayHub h = hub;
		ArmaController c = Armacraft.controller();
		if (h == null || c == null) {
			return;
		}
		ArmaConfig cfg = c.config();
		h.setRelayLine(RelayLines.relay(cfg.anchor.x, cfg.anchor.y, cfg.anchor.z, cfg.scale, cfg.anchor.dimension));
	}

	/** Server thread. Subscribes the player if their client registered {@code armacraft:bridge}. */
	private static void trySubscribe(ServerGamePacketListenerImpl handler) {
		RelayHub h = hub;
		if (h == null || !handler.isAcceptingMessages() || !ServerPlayNetworking.canSend(handler, BridgePayload.TYPE)) {
			return;
		}
		updateRelayLine();
		if (h.subscribe(handler.player.getUUID(), line -> handler.send(ServerPlayNetworking.createClientboundPacket(new BridgePayload(line))))) {
			LOG.info("relay: streaming to {} (armacraft_client)", handler.player.getPlainTextName());
		}
	}

	/** Server thread (Fabric runs play payload handlers there). */
	private static void onClientLine(ServerPlayer player, String line) {
		RelayHub h = hub;
		ArmaController c = Armacraft.controller();
		if (h == null || c == null) {
			return;
		}
		UUID id = player.getUUID();
		RelayRules.Inbound in = h.check(id, player.getGameProfile().name(), line, Colors.fromUuid(id));
		if (!in.accepted()) {
			LOG.debug("relay: dropped {} from {}: {}", in.type(), player.getPlainTextName(), in.dropped());
			return;
		}
		switch (in.type()) {
			case "chat" -> {
				if (!c.config().forwardChat) {
					return;
				}
			}
			case "join" -> {
				if (c.sessions().get(id) != null) {
					// one mechanism per player: the client renderer replaces the spectator chase cam
					c.sessions().leave(player, c.bridge());
					player.sendSystemMessage(Component.literal("[arma] switched to the armacraft client renderer").withStyle(ChatFormatting.GRAY));
				}
				LOG.info("relay: {} joins the arena through the relay", player.getPlainTextName());
			}
			case "leave" -> LOG.info("relay: {} leaves the arena through the relay", player.getPlainTextName());
			default -> {
			}
		}
		h.forward(id, in);
	}

	/** Guest link through the host's single bridge connection. */
	private static final class ControllerGuest implements RelayHub.Guest {
		@Override
		public boolean connected() {
			ArmaController c = Armacraft.controller();
			return c != null && c.bridge().isConnected();
		}

		@Override
		public boolean send(JsonObject msg) {
			ArmaController c = Armacraft.controller();
			return c != null && c.bridge().send(msg);
		}
	}
}
