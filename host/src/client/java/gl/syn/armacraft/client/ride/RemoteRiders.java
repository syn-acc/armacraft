package gl.syn.armacraft.client.ride;

import gl.syn.armacraft.ride.core.Trail;
import gl.syn.armacraft.ride.net.RidePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Other riders as this client knows them from {@code armacraft:ride_sync}: pose, colour and trail. Their trails are
 * lethal walls for the local rider (the server has the final word on such kills). Client thread only.
 */
public final class RemoteRiders {

	public static final class Remote {
		public final UUID uuid;
		public int entityId;
		public int rgb;
		public boolean riding;
		public double x;
		public double y;
		public double z;
		public int heading;
		public float speed;
		/** Client time (s) the rider stopped, NaN while riding. */
		public double stoppedAt = Double.NaN;
		public final Trail trail = new Trail();
		public double wheelPhase;
		double lastX;
		double lastZ;

		Remote(UUID uuid) {
			this.uuid = uuid;
		}

		/** The rider's entity on this client if loaded (smooth, interpolated position). */
		public @Nullable Entity entity() {
			Minecraft mc = Minecraft.getInstance();
			return mc.level == null ? null : mc.level.getEntity(entityId);
		}

		public Vec3 head() {
			Entity e = entity();
			return e != null ? e.position() : new Vec3(x, y, z);
		}
	}

	private final Map<UUID, Remote> riders = new LinkedHashMap<>();
	private double trailHeight = 1.0;
	private double stayUp = 8.0;
	private double time;

	public Collection<Remote> all() {
		return riders.values();
	}

	public double now() {
		return time;
	}

	public double stayUp() {
		return stayUp;
	}

	public void settings(double trailHeight, double stayUp) {
		this.trailHeight = trailHeight;
		this.stayUp = stayUp;
	}

	public void tick() {
		time += 0.05;
		riders.values().removeIf(r -> !r.riding && !Double.isNaN(r.stoppedAt) && stayUp >= 0 && time - r.stoppedAt > stayUp + 1);
		for (Remote r : riders.values()) {
			Vec3 h = r.head();
			r.wheelPhase += Math.hypot(h.x - r.lastX, h.z - r.lastZ) / 0.38;
			r.lastX = h.x;
			r.lastZ = h.z;
		}
	}

	public void clear() {
		riders.clear();
	}

	/** Is the player entity with this id riding (its body is replaced by the bike)? */
	public boolean isRidingEntity(int entityId) {
		for (Remote r : riders.values()) {
			if (r.riding && r.entityId == entityId) {
				return true;
			}
		}
		return false;
	}

	public void apply(RidePayloads.Sync s) {
		Minecraft mc = Minecraft.getInstance();
		UUID self = mc.player == null ? null : mc.player.getUUID();
		for (RidePayloads.Rider v : s.riders()) {
			if (v.uuid().equals(self)) {
				continue;
			}
			Remote r = riders.computeIfAbsent(v.uuid(), Remote::new);
			r.entityId = v.entityId();
			r.rgb = v.rgb();
			boolean wasRiding = r.riding;
			r.riding = v.riding();
			r.x = v.x();
			r.y = v.y();
			r.z = v.z();
			r.heading = v.heading();
			r.speed = v.speed();
			if (r.riding) {
				r.stoppedAt = Double.NaN;
			} else if (wasRiding || Double.isNaN(r.stoppedAt)) {
				r.stoppedAt = time - v.stoppedFor();
			}
			if (v.reset()) {
				r.trail.clear();
			}
			if (v.points().length > 0) {
				r.trail.receive(v.trailFrom(), v.points(), v.segs());
			}
			r.trail.dropBefore(v.trailFirst());
		}
		for (UUID u : s.gone()) {
			riders.remove(u);
		}
	}

	/** Alpha of a remote trail: 1 while riding and for WALLS_STAY_UP_DELAY after, fading out at the end. */
	public double alpha(Remote r) {
		if (r.riding) {
			return 1;
		}
		return Trail.alphaAfter(time - r.stoppedAt, stayUp);
	}

	public double ahead(Trail.Point box, int heading, double height, double maxDist, Trail.Profile profile) {
		double best = Double.POSITIVE_INFINITY;
		for (Remote r : riders.values()) {
			if (alpha(r) <= 0) {
				continue;
			}
			Vec3 h = r.riding ? r.head() : null;
			double d = r.trail.ahead(box, heading, height, maxDist, Integer.MAX_VALUE, h == null ? Double.NaN : h.x, h == null ? 0 : h.y,
				h == null ? 0 : h.z, profile);
			best = Math.min(best, d);
		}
		return best;
	}

	public double side(Trail.Point box, int heading, int side, double height, double near) {
		double best = Double.POSITIVE_INFINITY;
		for (Remote r : riders.values()) {
			if (alpha(r) > 0) {
				best = Math.min(best, r.trail.side(box, heading, side, height, near, Integer.MAX_VALUE));
			}
		}
		return best;
	}

	public double trailHeight() {
		return trailHeight;
	}
}
