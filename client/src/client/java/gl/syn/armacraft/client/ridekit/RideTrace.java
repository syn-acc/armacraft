package gl.syn.armacraft.client.ridekit;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Ring buffer of per-frame camera samples (eye, bike position, rotation, pull-in) for self-checks: jitter of the bike
 * on screen and of the eye relative to the bike. Render thread only.
 */
public final class RideTrace {

	public record Sample(long nanos, double ex, double ey, double ez, double bx, double by, double bz, float yaw, float pitch, double pull, double lift) {
		/** Eye-to-bike distance, blocks. */
		public double dist() {
			return Math.sqrt((ex - bx) * (ex - bx) + (ey - by) * (ey - by) + (ez - bz) * (ez - bz));
		}
	}

	private final Sample[] ring;
	private int next;
	private int size;

	public RideTrace(int capacity) {
		ring = new Sample[capacity];
	}

	public void add(long nanos, Vec3 eye, Vec3 bike, float yaw, float pitch, double pull, double lift) {
		ring[next] = new Sample(nanos, eye.x, eye.y, eye.z, bike.x, bike.y, bike.z, yaw, pitch, pull, lift);
		next = (next + 1) % ring.length;
		size = Math.min(size + 1, ring.length);
	}

	public void clear() {
		next = 0;
		size = 0;
	}

	/** Oldest first. */
	public List<Sample> samples() {
		List<Sample> out = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			out.add(ring[(next - size + i + ring.length) % ring.length]);
		}
		return out;
	}

	/**
	 * Jitter summary over steady frames (view not turning, camera not pulled in): first and second frame-to-frame
	 * differences of the eye-to-bike offset (blocks) and of the bike's direction as seen from the eye relative to the
	 * view (degrees). Second differences separate jitter from smooth drift (height follow). Also the bike's own
	 * per-frame speed spread, which is the host's interpolation.
	 */
	public String summary() {
		return "flat: " + summary(true) + " || all (incl. terrain steps): " + summary(false);
	}

	/** @param flatOnly only windows where the bike's height did not change (no terrain step easing) */
	public String summary(boolean flatOnly) {
		List<Sample> s = samples();
		int n = 0;
		double sumRel = 0, maxRel = 0, sumRel2 = 0, maxRel2 = 0;
		double sumAng = 0, maxAng = 0, sumAng2 = 0, maxAng2 = 0;
		double sumDt = 0, maxDt = 0;
		double sumV = 0, sumV2 = 0;
		for (int i = 3; i < s.size(); i++) {
			Sample y = s.get(i - 3), z = s.get(i - 2), a = s.get(i - 1), b = s.get(i);
			if (!(steady(y, z) && steady(z, a) && steady(a, b))) {
				continue;
			}
			if (flatOnly && !(y.by() == z.by() && z.by() == a.by() && a.by() == b.by() && Math.abs(b.ey() - y.ey()) < 1e-6)) {
				continue;
			}
			double[] d1 = relDelta(a, b);
			double[] d0 = relDelta(z, a);
			double rel = norm(d1);
			double rel2 = norm(new double[] {d1[0] - d0[0], d1[1] - d0[1], d1[2] - d0[2]});
			double ay = screenYaw(b) - screenYaw(a), ap = screenPitch(b) - screenPitch(a);
			double zy = screenYaw(a) - screenYaw(z), zp = screenPitch(a) - screenPitch(z);
			double ang = Math.hypot(ay, ap);
			double ang2 = Math.hypot(ay - zy, ap - zp);
			double dt = (b.nanos() - a.nanos()) / 1e9;
			double v = dt > 0 ? Math.sqrt(sq(b.bx() - a.bx()) + sq(b.bz() - a.bz())) / dt : 0;
			n++;
			sumRel += rel;
			maxRel = Math.max(maxRel, rel);
			sumRel2 += rel2;
			maxRel2 = Math.max(maxRel2, rel2);
			sumAng += ang;
			maxAng = Math.max(maxAng, ang);
			sumAng2 += ang2;
			maxAng2 = Math.max(maxAng2, ang2);
			sumDt += dt * 1e3;
			maxDt = Math.max(maxDt, dt * 1e3);
			sumV += v;
			sumV2 += v * v;
		}
		if (n == 0) {
			return "no steady frames (of " + s.size() + ")";
		}
		double meanV = sumV / n;
		double sdV = Math.sqrt(Math.max(0, sumV2 / n - meanV * meanV));
		return String.format("%d steady frames (of %d); frame %.1f ms avg / %.1f max | eye-bike offset: change/frame %.4f avg %.4f max, 2nd diff %.4f avg %.4f max blocks"
				+ " | bike on screen: change/frame %.4f avg %.4f max, 2nd diff %.4f avg %.4f max deg | bike speed per frame %.2f +- %.2f blocks/s",
			n, s.size(), sumDt / n, maxDt, sumRel / n, maxRel, sumRel2 / n, maxRel2, sumAng / n, maxAng, sumAng2 / n, maxAng2, meanV, sdV);
	}

	private static boolean steady(Sample a, Sample b) {
		long dt = b.nanos() - a.nanos();
		return Math.abs(b.yaw() - a.yaw()) < 0.01f && b.pull() > 0.999 && a.pull() > 0.999 && dt > 0 && dt < 100_000_000L;
	}

	private static double[] relDelta(Sample a, Sample b) {
		return new double[] {(b.ex() - b.bx()) - (a.ex() - a.bx()), (b.ey() - b.by()) - (a.ey() - a.by()), (b.ez() - b.bz()) - (a.ez() - a.bz())};
	}

	private static double norm(double[] v) {
		return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
	}

	private static double sq(double v) {
		return v * v;
	}

	/** Horizontal angle of eye -> bike relative to the view yaw (MC yaw convention). */
	private static double screenYaw(Sample x) {
		double dx = x.bx() - x.ex();
		double dz = x.bz() - x.ez();
		double yaw = Math.toDegrees(Math.atan2(-dx, dz));
		return wrap(yaw - x.yaw());
	}

	private static double screenPitch(Sample x) {
		double dx = x.bx() - x.ex();
		double dy = x.by() - x.ey();
		double dz = x.bz() - x.ez();
		return -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))) - x.pitch();
	}

	private static double wrap(double d) {
		d %= 360;
		if (d >= 180) {
			d -= 360;
		}
		if (d < -180) {
			d += 360;
		}
		return d;
	}
}
