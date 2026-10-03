package gl.syn.armacraft;

import gl.syn.armacraft.command.ArmaCommands;
import gl.syn.armacraft.render.EntitySet;
import gl.syn.armacraft.ride.RideService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Armacraft implements ModInitializer {

	public static final String MOD_ID = "armacraft";
	private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	private static ArmaController controller;
	private static RideService ride;
	/** Count of server ticks where the controller threw (logged and swallowed). Exposed for tests. */
	public static final java.util.concurrent.atomic.AtomicInteger TICK_ERRORS = new java.util.concurrent.atomic.AtomicInteger();

	public static ArmaController controller() {
		return controller;
	}

	/** Ride mode (server half of the driving: handshake, rider state, trails). */
	public static RideService ride() {
		return ride;
	}

	@Override
	public void onInitialize() {
		controller = new ArmaController(FabricLoader.getInstance().getConfigDir());
		ride = new RideService(FabricLoader.getInstance().getConfigDir());
		ride.register();

		// Leak guard: tagged entities are never saved (EntityMixin), and anything that slipped through
		// from an older build or a crash is refused when loaded from disk.
		ServerEntityEvents.ALLOW_LOAD.register((entity, level, reason, fromDisk) ->
			!(fromDisk && entity.entityTags().contains(EntitySet.TAG)));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			controller.onServerStarted(server);
			ride.onServerStarted(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			ride.onServerStopping(server);
			controller.onServerStopping(server);
		});
		// START, not END: 26.3 sends entity changes from ChunkMap.tick() inside the level tick, which runs
		// after START_SERVER_TICK (injected right before tickChildren) but before END_SERVER_TICK (tickServer
		// TAIL). Rendering at END left every display update queued for the *next* tick: +50 ms on every hop.
		ServerTickEvents.START_SERVER_TICK.register(server -> {
			try {
				controller.tick(server);
			} catch (RuntimeException e) {
				TICK_ERRORS.incrementAndGet();
				LOG.error("armacraft tick failed", e);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> controller.onPlayerJoin(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ride.onDisconnect(handler.player);
			controller.onPlayerDisconnect(handler.player);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			try {
				ride.tick(server);
			} catch (RuntimeException e) {
				TICK_ERRORS.incrementAndGet();
				LOG.error("armacraft ride tick failed", e);
			}
		});
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> controller.onChat(sender, message.signedContent()));
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> ArmaCommands.register(dispatcher));
		LOG.info("armacraft loaded (server-side bridge, protocol v1; ride mode)");
	}
}
