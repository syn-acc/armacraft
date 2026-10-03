package gl.syn.armacraft.ride;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side record of who is riding a bike, and the seam between the two halves of ride mode.
 *
 * <p>Written by the ride network handler (the client reports start / state / stop over the ride payloads).
 * Read by the server-side ride features in {@code gl.syn.armacraft.ride.server}: frost walker, fall-damage
 * immunity, relaxed movement checks, crash effects, mob hits. Server thread only.
 *
 * <p>Contract owned by the main session: signatures are fixed, additions must be additive.
 */
public final class RideRegistry {

	/** What the server knows about a rider; reported by the rider's client. */
	public record Status(Direction heading, double speedBlocksPerSecond, boolean braking, long gameTime) {}

	public enum StopReason { DISMOUNT, CRASH, DISCONNECT }

	/** Server-side ride features subscribe here. All callbacks run on the server thread. */
	public interface Listener {
		default void onStart(ServerPlayer player, Status status) {}

		default void onUpdate(ServerPlayer player, Status status) {}

		/** {@code at} is where the ride ended (the crash point for CRASH). */
		default void onStop(ServerPlayer player, StopReason reason, Vec3 at) {}
	}

	private static final Map<UUID, Status> RIDERS = new HashMap<>();
	private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

	private RideRegistry() {}

	public static void addListener(Listener listener) {
		LISTENERS.add(listener);
	}

	public static void start(ServerPlayer player, Status status) {
		RIDERS.put(player.getUUID(), status);
		for (Listener l : LISTENERS) l.onStart(player, status);
	}

	public static void update(ServerPlayer player, Status status) {
		if (!RIDERS.containsKey(player.getUUID())) {
			start(player, status);
			return;
		}
		RIDERS.put(player.getUUID(), status);
		for (Listener l : LISTENERS) l.onUpdate(player, status);
	}

	public static void stop(ServerPlayer player, StopReason reason, Vec3 at) {
		if (RIDERS.remove(player.getUUID()) == null) return;
		for (Listener l : LISTENERS) l.onStop(player, reason, at);
	}

	public static Optional<Status> status(Player player) {
		return Optional.ofNullable(RIDERS.get(player.getUUID()));
	}

	/** Usable from mixins that only see a {@link Player}; false on the client side of singleplayer. */
	public static boolean isRiding(Player player) {
		return player instanceof ServerPlayer && RIDERS.containsKey(player.getUUID());
	}

	public static int riderCount() {
		return RIDERS.size();
	}

	/** Additive (ride driving agent): number of registered server-side features; tests use it to know what to expect. */
	public static int listenerCount() {
		return LISTENERS.size();
	}
}
