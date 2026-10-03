package gl.syn.armacraft.ride;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Side-aware "is this player riding" registry for the common mixins. Singleplayer runs the integrated server and
 * the client in one JVM, so the two sides keep separate sets, picked by {@code level().isClientSide()}.
 */
public final class RideHooks {

	private static final Set<UUID> SERVER_RIDERS = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> CLIENT_RIDERS = ConcurrentHashMap.newKeySet();

	/** Client-side movement override for the local rider, installed by the client entrypoint. */
	public interface TravelOverride {
		/** @return true if the ride code moved the player this tick (vanilla travel is skipped) */
		boolean travel(Player player, Vec3 input);
	}

	private static volatile @Nullable TravelOverride clientTravel;
	/** Width of a rider's collision box (blocks); see {@code RideConfig.bikeWidth}. */
	private static volatile float rideWidth = 0.2f;

	private RideHooks() {}

	public static boolean isRiding(Entity e) {
		if (!(e instanceof Player)) {
			return false;
		}
		return (e.level().isClientSide() ? CLIENT_RIDERS : SERVER_RIDERS).contains(e.getUUID());
	}

	public static float rideWidth() {
		return rideWidth;
	}

	public static void setRideWidth(double width) {
		rideWidth = (float) Math.max(0.05, Math.min(0.6, width));
	}

	public static void setServerRiding(UUID id, boolean riding) {
		if (riding) {
			SERVER_RIDERS.add(id);
		} else {
			SERVER_RIDERS.remove(id);
		}
	}

	public static void setClientRiding(UUID id, boolean riding) {
		if (riding) {
			CLIENT_RIDERS.add(id);
		} else {
			CLIENT_RIDERS.remove(id);
		}
	}

	public static void clearClient() {
		CLIENT_RIDERS.clear();
	}

	public static void clearServer() {
		SERVER_RIDERS.clear();
	}

	public static void installClientTravel(@Nullable TravelOverride o) {
		clientTravel = o;
	}

	public static @Nullable TravelOverride clientTravel() {
		return clientTravel;
	}
}
