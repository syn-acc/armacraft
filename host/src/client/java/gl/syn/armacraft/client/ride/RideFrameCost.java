package gl.syn.armacraft.client.ride;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/**
 * CPU time the ride client spends per rendered frame (chase camera + frame-time pose, trail/bike submits, HUD) and
 * per client tick (physics), measured on the render thread when {@link #enabled}. A frame opens at the camera hook
 * (the first ride work in a frame) and closes at the next one. Also counts the bytes the measured code allocates
 * (HotSpot's per-thread allocation counter). Preallocated; no allocation while measuring.
 */
public final class RideFrameCost {

	public static volatile boolean enabled;

	private static final int CAP = 1 << 15;
	private static final long[] FRAMES = new long[CAP];
	private static final long[] TICKS = new long[CAP];
	private static final long[] FRAME_BYTES = new long[CAP];
	private static final long[] TICK_BYTES = new long[CAP];
	private static final com.sun.management.ThreadMXBean MX = threadBean();
	private static long allocStart;
	private static long allocAcc;
	private static int frameCount;
	private static int tickCount;
	private static long acc;
	private static boolean open;
	private static int planFrames;
	/** Per section (0 pose, 1 camera, 2 world submits, 3 HUD): total ns and bytes since reset. */
	private static final String[] SECTIONS = {"pose", "camera", "submits", "hud"};
	private static final long[] SECTION_NS = new long[SECTIONS.length];
	private static final long[] SECTION_BYTES = new long[SECTIONS.length];

	private RideFrameCost() {}

	/** 0 when off; pass the result to {@link #add} or {@link #tick}. */
	public static long start() {
		if (!enabled) {
			return 0;
		}
		allocStart = allocated();
		return System.nanoTime();
	}

	private static long allocated() {
		return MX == null ? 0 : MX.getCurrentThreadAllocatedBytes();
	}

	private static com.sun.management.ThreadMXBean threadBean() {
		try {
			return ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean b && b.isThreadAllocatedMemorySupported()
				? b : null;
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** Called first thing in the camera hook: closes the previous frame. */
	public static void frameStart() {
		if (!enabled) {
			open = false;
			return;
		}
		if (open && frameCount < CAP) {
			FRAME_BYTES[frameCount] = allocAcc;
			FRAMES[frameCount++] = acc;
		}
		acc = 0;
		allocAcc = 0;
		open = true;
	}

	/** Ends a section begun with {@link #start()} (sections do not nest). */
	public static void add(long t0, int section) {
		if (t0 != 0 && open) {
			long ns = System.nanoTime() - t0;
			long bytes = allocated() - allocStart;
			acc += ns;
			allocAcc += bytes;
			SECTION_NS[section] += ns;
			SECTION_BYTES[section] += bytes;
		}
	}

	public static final int POSE = 0;
	public static final int CAMERA = 1;
	public static final int SUBMITS = 2;
	public static final int HUD = 3;

	public static void tick(long t0) {
		if (t0 != 0 && tickCount < CAP) {
			long ns = System.nanoTime() - t0;
			TICK_BYTES[tickCount] = allocated() - allocStart;
			TICKS[tickCount++] = ns;
		}
	}

	/** A frame that planned turns ahead of the physics (exact turn timing). */
	public static void planned() {
		if (enabled) {
			planFrames++;
		}
	}

	public static void reset() {
		frameCount = 0;
		tickCount = 0;
		planFrames = 0;
		acc = 0;
		allocAcc = 0;
		open = false;
		Arrays.fill(SECTION_NS, 0);
		Arrays.fill(SECTION_BYTES, 0);
	}

	/** "label: per frame n, avg / p99 / max ms, bytes allocated avg / max; per tick the same". */
	public static String summary(String label) {
		return String.format(Locale.ROOT,
			"%s: per frame n=%d avg %.4f p99 %.4f max %.4f ms, allocated avg %.0f max %d B (%d frames planned turns ahead);"
				+ " per tick n=%d avg %.4f p99 %.4f max %.4f ms, allocated avg %.0f B",
			label, frameCount, avg(FRAMES, frameCount), pct(FRAMES, frameCount, 0.99), max(FRAMES, frameCount), avgRaw(FRAME_BYTES, frameCount),
			maxRaw(FRAME_BYTES, frameCount), planFrames, tickCount, avg(TICKS, tickCount), pct(TICKS, tickCount, 0.99), max(TICKS, tickCount),
			avgRaw(TICK_BYTES, tickCount)) + sections();
	}

	private static String sections() {
		if (frameCount == 0) {
			return "";
		}
		StringBuilder b = new StringBuilder("; per frame by part:");
		for (int i = 0; i < SECTIONS.length; i++) {
			b.append(String.format(Locale.ROOT, " %s %.4f ms %.0f B", SECTIONS[i], SECTION_NS[i] / 1e6 / frameCount, SECTION_BYTES[i] / (double) frameCount));
		}
		return b.toString();
	}

	private static double avgRaw(long[] a, int n) {
		return n == 0 ? Double.NaN : avg(a, n) * 1e6;
	}

	private static long maxRaw(long[] a, int n) {
		long m = 0;
		for (int i = 0; i < n; i++) {
			m = Math.max(m, a[i]);
		}
		return m;
	}

	public static double frameAvgMs() {
		return avg(FRAMES, frameCount);
	}

	public static double frameMaxMs() {
		return max(FRAMES, frameCount);
	}

	private static double avg(long[] a, int n) {
		if (n == 0) {
			return Double.NaN;
		}
		long s = 0;
		for (int i = 0; i < n; i++) {
			s += a[i];
		}
		return s / (double) n / 1e6;
	}

	private static double max(long[] a, int n) {
		long m = 0;
		for (int i = 0; i < n; i++) {
			m = Math.max(m, a[i]);
		}
		return n == 0 ? Double.NaN : m / 1e6;
	}

	private static double pct(long[] a, int n, double q) {
		if (n == 0) {
			return Double.NaN;
		}
		long[] c = Arrays.copyOf(a, n);
		Arrays.sort(c);
		return c[Math.min(n - 1, (int) Math.floor(q * (n - 1)))] / 1e6;
	}
}
