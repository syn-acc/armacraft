package gl.syn.armacraft.client.dev;

import gl.syn.armacraft.client.core.CycleTrack;
import gl.syn.armacraft.client.core.V2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * {@code -Darmacraft.devStats=true}: logs every 5 s how smooth the rendered own cycle is and how fast turns
 * come back from the guest. Evidence for "rendered every frame with extrapolation" and "immediate input".
 * <ul>
 *   <li>fps and frames;</li>
 *   <li>per-frame motion of the own cycle: rendered displacement vs speed*dt on frames without a turn
 *       (mean/max absolute error, units) and the count of corrections &gt; 0.05 units (snapshot snaps);</li>
 *   <li>turn key to guest {@code turn} event latency (ms) and predicted-vs-guest corner error (units).</li>
 * </ul>
 */
public final class DevStats {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft_client/stats");
	public static final boolean ENABLED = Boolean.getBoolean("armacraft.devStats");

	private double windowStart = Double.NaN;
	private int frames, motionFrames, corrections;
	private double errSum, errMax, corrMax;
	private V2 lastPos, lastDir;
	private double lastT = Double.NaN;
	private final Deque<double[]> pendingPresses = new ArrayDeque<>();   // {local, px, py}
	private int turns;
	private double latSum, latMax, predErrSum, predErrMax;

	public void keyTurn(double local, CycleTrack.Turn predicted) {
		if (!ENABLED) {
			return;
		}
		pendingPresses.addLast(new double[] {local, predicted != null ? predicted.x() : Double.NaN, predicted != null ? predicted.y() : Double.NaN});
		while (pendingPresses.size() > 8) {
			pendingPresses.removeFirst();
		}
	}

	public void ownTurnEvent(double local, double x, double y) {
		if (!ENABLED) {
			return;
		}
		// presses the guest never turned on (dead, ignored) must not pair with later turns
		while (!pendingPresses.isEmpty() && local - pendingPresses.peekFirst()[0] > 0.3) {
			pendingPresses.removeFirst();
		}
		if (pendingPresses.isEmpty()) {
			return;
		}
		double[] p = pendingPresses.removeFirst();
		double lat = (local - p[0]) * 1000;
		if (lat < 0 || lat > 1000) {
			return;
		}
		turns++;
		latSum += lat;
		latMax = Math.max(latMax, lat);
		if (Double.isFinite(p[1])) {
			double e = Math.hypot(x - p[1], y - p[2]);
			predErrSum += e;
			predErrMax = Math.max(predErrMax, e);
		}
	}

	private final java.util.Map<String, Integer> views = new java.util.TreeMap<>();
	private String soundInfo = "";

	public void sound(String info) {
		soundInfo = info;
	}

	public void frame(double local, double gameTime, V2 ownPos, V2 ownDir, double speed, boolean alive, String view) {
		if (!ENABLED) {
			return;
		}
		views.merge(view, 1, Integer::sum);
		if (Double.isNaN(windowStart)) {
			windowStart = local;
		}
		frames++;
		if (alive && ownPos != null && lastPos != null && Double.isFinite(lastT) && gameTime > lastT && gameTime > 0.5
			&& ownDir.equals(lastDir)) {
			double dt = gameTime - lastT;
			V2 expected = lastPos.add(ownDir.mul(speed * dt));
			double err = expected.dist(ownPos);
			motionFrames++;
			errSum += err;
			errMax = Math.max(errMax, err);
			if (err > 0.05) {
				corrections++;
				corrMax = Math.max(corrMax, err);
			}
		}
		lastPos = alive ? ownPos : null;
		lastDir = ownDir;
		lastT = gameTime;
		if (local - windowStart >= 5) {
			double secs = local - windowStart;
			LOG.info(String.format("fps %.1f (%d frames) | own motion: %d frames, mean err %.4f, max err %.3f, corrections>0.05: %d (max %.3f) | turns: %d, key->guest %.1f ms mean, %.1f max, corner err %.3f mean, %.3f max",
				frames / secs, frames, motionFrames, motionFrames > 0 ? errSum / motionFrames : 0, errMax, corrections, corrMax,
				turns, turns > 0 ? latSum / turns : 0, latMax, turns > 0 ? predErrSum / turns : 0, predErrMax) + " | views " + views + " | sound " + soundInfo);
			views.clear();
			windowStart = local;
			frames = motionFrames = corrections = turns = 0;
			errSum = errMax = corrMax = latSum = latMax = predErrSum = predErrMax = 0;
		}
	}
}
