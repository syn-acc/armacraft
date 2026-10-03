package gl.syn.armacraft.ride;

import gl.syn.armacraft.ride.core.Trail;
import gl.syn.armacraft.ride.net.RidePayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sends other riders (pose, colour, trail) to modded viewers over {@code armacraft:ride_sync}. Trails go
 * incrementally per viewer: only points from the last one already sent (which is resent, because the owner extends
 * it in place along straight runs). Viewers without the mod get nothing here; they see {@link RideFallback}.
 */
public final class RideSync {

	/** What a viewer holds of one ride: the ride's trail object and the end index already sent. */
	private record Sent(Trail trail, long end) {}

	/** Per viewer: ride key -> what was sent. */
	private final Map<UUID, Map<UUID, Sent>> sent = new HashMap<>();
	private int tick;

	public void forget(UUID viewer) {
		sent.remove(viewer);
	}

	public void clear() {
		sent.clear();
	}

	public void tick(MinecraftServer server, RideService ride) {
		if (++tick % 2 != 0) {
			return;
		}
		RideConfig cfg = ride.config();
		double range2 = cfg.syncRange * cfg.syncRange;
		double now = RideService.seconds(server.overworld());
		List<RideService.Rider> all = new ArrayList<>(ride.riders());
		all.addAll(ride.fadingTrails());
		Set<UUID> online = new HashSet<>();
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			online.add(viewer.getUUID());
			if (!ServerPlayNetworking.canSend(viewer, RidePayloads.Sync.TYPE)) {
				continue;
			}
			Map<UUID, Sent> known = sent.computeIfAbsent(viewer.getUUID(), k -> new HashMap<>());
			List<RidePayloads.Rider> views = new ArrayList<>();
			Set<UUID> seen = new HashSet<>();
			for (RideService.Rider r : all) {
				if (r.uuid.equals(viewer.getUUID()) || !inRange(viewer, r, range2)) {
					continue;
				}
				if (r.dimension != null && r.dimension != viewer.level().dimension()) {
					continue;
				}
				seen.add(r.key);
				Trail t = r.trail;
				Sent prev = known.get(r.key);
				boolean reset = prev == null || prev.trail() != t || prev.end() > t.endIndex();
				long from = reset ? t.firstIndex() : Math.max(t.firstIndex(), prev.end() - 1);
				double[] pts = t.pointsFrom(from);
				int[] segs = t.segsFrom(from);
				known.put(r.key, new Sent(t, t.endIndex()));
				int flags = (r.riding ? 1 : 0) | (r.crashed ? 2 : 0);
				float stoppedFor = r.riding ? 0f : (float) Math.max(0, now - t.deadSince());
				views.add(new RidePayloads.Rider(r.key, r.entityId, r.rgb, flags, r.x, r.y, r.z, r.heading, r.speed, stoppedFor, reset,
					t.firstIndex(), from, pts, segs));
			}
			List<UUID> gone = new ArrayList<>();
			for (UUID u : new ArrayList<>(known.keySet())) {
				if (!seen.contains(u)) {
					gone.add(u);
					known.remove(u);
				}
			}
			if (!views.isEmpty() || !gone.isEmpty()) {
				ServerPlayNetworking.send(viewer, new RidePayloads.Sync(views, gone));
			}
		}
		sent.keySet().retainAll(online);
	}

	private static boolean inRange(ServerPlayer viewer, RideService.Rider r, double range2) {
		double dx = viewer.getX() - r.x;
		double dz = viewer.getZ() - r.z;
		if (dx * dx + dz * dz <= range2) {
			return true;
		}
		// a trail can reach the viewer even if its rider is far away
		Trail t = r.trail;
		for (int i = 0; i < t.size(); i += 4) {
			double tx = viewer.getX() - t.x(i);
			double tz = viewer.getZ() - t.z(i);
			if (tx * tx + tz * tz <= range2) {
				return true;
			}
		}
		return false;
	}
}
