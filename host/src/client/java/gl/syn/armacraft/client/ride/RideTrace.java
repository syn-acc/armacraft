package gl.syn.armacraft.client.ride;

import java.util.ArrayList;
import java.util.List;

/**
 * Dev trace of what the rider sees, one row per rendered frame while riding: time, partial tick, camera eye and
 * rotation, bike render position, and the view-bob offset vanilla would add. Time is game time plus the frame's partial
 * tick (seconds), so it is smooth by construction and works at any frame pacing. Enabled with
 * {@code -Darmacraft.ride.trace=true} or by tests. Used to measure smoothness (second differences per frame).
 */
public final class RideTrace {

	/** One frame. */
	public record Frame(double t, String phase, float partial, double ex, double ey, double ez, float yaw, float pitch, double bx, double by,
		double bz, float bodyYaw, float bob, float walk, double rawY, boolean ground) {}

	public static volatile boolean enabled = Boolean.getBoolean("armacraft.ride.trace");
	public static volatile String phase = "";
	private static final List<Frame> FRAMES = new ArrayList<>();

	private RideTrace() {}

	public static synchronized void add(Frame f) {
		if (FRAMES.size() < 200_000) {
			FRAMES.add(f);
		}
	}

	public static synchronized List<Frame> drain() {
		List<Frame> out = new ArrayList<>(FRAMES);
		FRAMES.clear();
		return out;
	}

	/**
	 * Second differences per frame for one phase: eye position (blocks/s^2), camera yaw (deg/s^2), bike position
	 * (blocks/s^2), and the largest vanilla view-bob translation that frame would have had (blocks).
	 */
	public static String stats(List<Frame> frames, String phase) {
		List<Frame> f = new ArrayList<>();
		for (Frame x : frames) {
			if (x.phase().equals(phase) && (f.isEmpty() || x.t() > f.get(f.size() - 1).t() + 1e-6)) {
				f.add(x);
			}
		}
		if (f.size() < 4) {
			return phase + ": " + f.size() + " frames";
		}
		double eyeRms = 0;
		double eyeMax = 0;
		double eyeYMax = 0;
		double yawRms = 0;
		double yawMax = 0;
		double bikeRms = 0;
		double bikeMax = 0;
		double bobMax = 0;
		int n = 0;
		for (int i = 1; i + 1 < f.size(); i++) {
			Frame a = f.get(i - 1);
			Frame b = f.get(i);
			Frame c = f.get(i + 1);
			double dt1 = b.t() - a.t();
			double dt2 = c.t() - b.t();
			double dtm = (dt1 + dt2) / 2;
			double ax = ((c.ex() - b.ex()) / dt2 - (b.ex() - a.ex()) / dt1) / dtm;
			double ay = ((c.ey() - b.ey()) / dt2 - (b.ey() - a.ey()) / dt1) / dtm;
			double az = ((c.ez() - b.ez()) / dt2 - (b.ez() - a.ez()) / dt1) / dtm;
			double e = Math.sqrt(ax * ax + ay * ay + az * az);
			double yaw2 = (wrap(c.yaw() - b.yaw()) / dt2 - wrap(b.yaw() - a.yaw()) / dt1) / dtm;
			double bx = ((c.bx() - b.bx()) / dt2 - (b.bx() - a.bx()) / dt1) / dtm;
			double by = ((c.by() - b.by()) / dt2 - (b.by() - a.by()) / dt1) / dtm;
			double bz = ((c.bz() - b.bz()) / dt2 - (b.bz() - a.bz()) / dt1) / dtm;
			double k = Math.sqrt(bx * bx + by * by + bz * bz);
			eyeRms += e * e;
			eyeMax = Math.max(eyeMax, e);
			eyeYMax = Math.max(eyeYMax, Math.abs(ay));
			yawRms += yaw2 * yaw2;
			yawMax = Math.max(yawMax, Math.abs(yaw2));
			bikeRms += k * k;
			bikeMax = Math.max(bikeMax, k);
			double w = b.walk() * Math.PI;
			bobMax = Math.max(bobMax, Math.hypot(Math.sin(w) * b.bob() * 0.5, Math.abs(Math.cos(w) * b.bob())));
			n++;
		}
		double span = f.get(f.size() - 1).t() - f.get(0).t();
		return String.format(
			"%-8s frames %4d (%.0f fps)  eye d2: rms %7.1f max %7.1f (vertical max %7.1f) b/s^2  yaw d2: rms %8.0f max %8.0f deg/s^2  bike d2: rms %7.1f max %7.1f b/s^2  vanilla bob up to %.3f b",
			phase, f.size(), f.size() / Math.max(1e-6, span), Math.sqrt(eyeRms / n), eyeMax, eyeYMax, Math.sqrt(yawRms / n), yawMax,
			Math.sqrt(bikeRms / n), bikeMax, bobMax);
	}

	/** Writes the frames as CSV (for plotting / inspection). */
	public static void writeCsv(List<Frame> frames, java.nio.file.Path file) {
		StringBuilder sb = new StringBuilder("t,phase,partial,ex,ey,ez,yaw,pitch,bx,by,bz,bodyYaw,bob,walk,rawY,ground").append(System.lineSeparator());
		for (Frame f : frames) {
			sb.append(String.format(java.util.Locale.ROOT, "%.5f,%s,%.3f,%.4f,%.4f,%.4f,%.3f,%.3f,%.4f,%.4f,%.4f,%.3f,%.4f,%.4f,%.4f,%b%n", f.t(),
				f.phase(), f.partial(), f.ex(), f.ey(), f.ez(), f.yaw(), f.pitch(), f.bx(), f.by(), f.bz(), f.bodyYaw(), f.bob(), f.walk(), f.rawY(),
				f.ground()));
		}
		try {
			java.nio.file.Files.writeString(file, sb.toString());
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/**
	 * Vertical smoothness of the rendered bike for one phase: second difference of its y (blocks/s^2), largest per-frame
	 * y jump, the same for the raw interpolated physics y (what you would see without easing), and frames off the ground.
	 */
	public static String verticalStats(List<Frame> frames, String phase) {
		List<Frame> f = new ArrayList<>();
		for (Frame x : frames) {
			if (x.phase().equals(phase) && (f.isEmpty() || x.t() > f.get(f.size() - 1).t() + 1e-6)) {
				f.add(x);
			}
		}
		if (f.size() < 4) {
			return phase + ": " + f.size() + " frames";
		}
		double rms = 0;
		double max = 0;
		double rawMax = 0;
		double rawRms = 0;
		double jump = 0;
		double rawJump = 0;
		int air = 0;
		int n = 0;
		for (int i = 1; i + 1 < f.size(); i++) {
			Frame a = f.get(i - 1);
			Frame b = f.get(i);
			Frame c = f.get(i + 1);
			double dt1 = b.t() - a.t();
			double dt2 = c.t() - b.t();
			double dtm = (dt1 + dt2) / 2;
			double d2 = ((c.by() - b.by()) / dt2 - (b.by() - a.by()) / dt1) / dtm;
			double r2 = ((c.rawY() - b.rawY()) / dt2 - (b.rawY() - a.rawY()) / dt1) / dtm;
			rms += d2 * d2;
			rawRms += r2 * r2;
			max = Math.max(max, Math.abs(d2));
			rawMax = Math.max(rawMax, Math.abs(r2));
			jump = Math.max(jump, Math.abs(c.by() - b.by()));
			rawJump = Math.max(rawJump, Math.abs(c.rawY() - b.rawY()));
			air += b.ground() ? 0 : 1;
			n++;
		}
		double yLo = Double.POSITIVE_INFINITY;
		double yHi = Double.NEGATIVE_INFINITY;
		for (Frame x : f) {
			yLo = Math.min(yLo, x.rawY());
			yHi = Math.max(yHi, x.rawY());
		}
		return String.format(java.util.Locale.ROOT,
			"%-9s frames %4d  y %.1f..%.1f  bike y d2: rms %6.1f max %6.1f b/s^2, max jump %.3f b/frame | raw physics y d2: rms %6.1f max %6.1f, max jump %.3f | airborne frames %d",
			phase, f.size(), yLo, yHi, Math.sqrt(rms / n), max, jump, Math.sqrt(rawRms / n), rawMax, rawJump, air);
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
