package gl.syn.armacraft.ride.server;

import gl.syn.armacraft.ride.RideRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Entrypoint for the server-side ride features. Loads config/armacraft-ride-server.json, subscribes to
 * {@link RideRegistry} and ticks at START_SERVER_TICK: block and velocity changes made there go out with the same
 * tick's chunk/entity broadcast (inside the level tick) instead of the next one.
 */
public final class RideServerInit implements ModInitializer {

	@Override
	public void onInitialize() {
		if (RideServer.get() != null) {
			return;
		}
		java.nio.file.Path file = FabricLoader.getInstance().getConfigDir().resolve(RideServerConfig.FILE_NAME);
		RideServer server = new RideServer(RideServerConfig.load(file), file);
		RideServer.install(server);
		RideRegistry.addListener(server);
		ServerTickEvents.START_SERVER_TICK.register(s -> {
			try {
				server.tick(s);
			} catch (RuntimeException e) {
				RideServer.logError("tick", e);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> server.reset());
	}
}
