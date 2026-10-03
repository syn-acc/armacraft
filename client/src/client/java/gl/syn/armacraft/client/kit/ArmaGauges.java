package gl.syn.armacraft.client.kit;

/**
 * Armagetron's three HUD gauges (gHud.cpp GLmeter_subby / display_hud_subby), fed from a plain model. Speed at
 * (0,-.9), "Rubber Used" at (-.48,-.9), "Brakes" at (.48,-.9), size .175; the speed scale starts at 50 and grows
 * by 10 whenever the speed exceeds it. Needle (.5,.5,1), min/max labels 0xcccccc, value 0xccffff, title 0xff3333.
 * No bridge types: ride mode can feed it directly.
 */
public final class ArmaGauges {

	/** What the gauges show. {@code rubber} = fraction of the rubber budget used, {@code brake} = reservoir left, both 0..1. */
	public record Model(double speed, double rubber, double brake) {}

	private float maxMeterSpeed = 50;
	/** Rubber gauge maximum (CYCLE_RUBBER, settings.cfg 1.0); the gauge shows {@code rubber * rubberMax}. */
	public double rubberMax = 1.0;

	/** Reset the growing speed scale (Armagetron keeps it per player across rounds; call on a new session). */
	public void reset() {
		maxMeterSpeed = 50;
	}

	/** Armagetron's gauge row: size, distance between gauge centres, baseline. */
	public static final double SIZE = .175, SPACING = .48, ROW_Y = -.9;
	/** Widest value label ("0.9", "29.8"): glyphs drawn right of the point the needle aims at. */
	private static final int VALUE_CHARS = 3;
	private static final double MARGIN = .02;
	/**
	 * Half width (x units from the screen centre) a window needs for the wide ride row: the right end of a Boost gauge
	 * one spacing right of Brakes, with its value label out to the right as it is while the reservoir refills.
	 */
	static final double WIDE_HALF_WIDTH = meterExtent(2 * SPACING, SIZE)[1] + MARGIN;
	/** Half width of the narrow ride row at Armagetron's size: four gauges centred as a group. */
	static final double NARROW_HALF_WIDTH = meterExtent(1.5 * SPACING, SIZE)[1] + MARGIN;

	/** Armagetron's three gauges where Armagetron draws them (arena mode). */
	public void draw(ArmaScreen s, Model m) {
		draw(s, m, new RideRow(SIZE, ROW_Y, -SPACING, 0, SPACING, Double.NaN, false), 0, false);
	}

	/**
	 * Where ride mode's row is drawn: one baseline {@code y} and one {@code size} for all gauges, the gauge centres,
	 * and whether Boost is drawn all the time. Depends on the window shape only (see {@link #rideRow}), never on
	 * whether Boost is in use, so no gauge ever moves.
	 */
	public record RideRow(double size, double y, double rubberX, double speedX, double brakesX, double boostX, boolean boostAlways) {}

	/**
	 * Ride mode's row for a window whose half width is {@code windowHalfWidth} x units ({@link ArmaScreen#windowToX}:
	 * 1 for a 4:3 or narrower window, 1.33 for 16:9). armacraft's Boost is a fourth gauge in the row: same baseline,
	 * size and spacing as Armagetron's three.
	 * <ul>
	 *   <li>Wide enough for it (16:9 and wider): Armagetron's three exactly where Armagetron draws them, centred, and
	 *       Boost one spacing right of Brakes, drawn only while in use or refilling.</li>
	 *   <li>Narrower (4:3, 16:10, a portrait window): there is no room right of Brakes, so the four are centred as a
	 *       group and Boost is always drawn (a full reservoir reads 1, like Brakes): the row is always whole and
	 *       centred. If even that does not fit (4:3 and narrower) the row shrinks towards the bottom centre until
	 *       nothing reaches past the window's side.</li>
	 * </ul>
	 */
	public static RideRow rideRow(double windowHalfWidth) {
		if (!(windowHalfWidth < WIDE_HALF_WIDTH)) { // also NaN: Armagetron's own row
			return new RideRow(SIZE, ROW_Y, -SPACING, 0, SPACING, 2 * SPACING, false);
		}
		double k = Math.min(1, Math.max(0, windowHalfWidth) / NARROW_HALF_WIDTH);
		double sp = SPACING * k;
		return new RideRow(SIZE * k, -1 + (ROW_Y + 1) * k, -1.5 * sp, -.5 * sp, .5 * sp, 1.5 * sp, true);
	}

	/**
	 * Ride mode's row (see {@link #rideRow}). {@code boost} is the reservoir 0..1, {@code boostInUse} whether it is
	 * being used or refilling (when the wide row shows it).
	 */
	public void drawRide(ArmaScreen s, Model m, double boost, boolean boostInUse) {
		draw(s, m, rideRow(s.windowToX()), boost, boostInUse);
	}

	private void draw(ArmaScreen s, Model m, RideRow row, double boost, boolean boostInUse) {
		if (m.speed() > maxMeterSpeed) {
			maxMeterSpeed += 10;
		}
		meter(s, m.speed(), maxMeterSpeed, row.speedX(), row.y(), row.size(), "Speed");
		meter(s, clamp01(m.rubber()) * rubberMax, rubberMax, row.rubberX(), row.y(), row.size(), " Rubber Used");
		meter(s, clamp01(m.brake()), 1.0, row.brakesX(), row.y(), row.size(), " Brakes");
		if (!Double.isNaN(row.boostX()) && (row.boostAlways() || boostInUse)) {
			meter(s, clamp01(boost), 1.0, row.boostX(), row.y(), row.size(), " Boost");
		}
	}

	/**
	 * Left and right end of everything {@link #meter} can draw for a gauge at {@code locx}: the value label swings
	 * out to 1.45 sizes either side of the centre (further than the dial and the min/max labels) and is drawn to the
	 * right of that point.
	 */
	public static double[] meterExtent(double locx, double size) {
		return new double[] {locx - 1.45 * size, locx + 1.45 * size + .12 * size * VALUE_CHARS};
	}

	private static double clamp01(double v) {
		return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
	}

	/** gHud.cpp GLmeter_subby. */
	public static void meter(ArmaScreen s, double value, double max, double locx, double locy, double size, String title) {
		boolean displayValue = true;
		if (value > max) {
			value = max;
		}
		if (value < 0) {
			value = 0;
		}
		double x = Math.cos(Math.PI * value / max);
		double y = Math.sin(Math.PI * value / max);
		if (y < size * .24) {
			displayValue = false;
		}
		s.line(-.1 * x * size + locx, .1 * y * size + locy, -x * size + locx, y * size + locy, 0xFF8080FF, Math.max(1f, s.unitY * 0.004f));
		s.text("0", -size - (0.1 * size) + locx, locy, .12 * size, .24 * size, 0xcccccc, 1);
		s.text(fmtMax(max), +size + (0.1 * size) + locx, locy, .12 * size, .24 * size, 0xcccccc, 1);
		if (displayValue) {
			s.text(String.format("%.1f", value), -x * 1.45 * size + locx, y * 1.35 * size + locy, .12 * size, .24 * size, 0xccffff, 1);
		}
		int length = title.length() + 1;
		if (length > 1) {
			s.text(title, locx - ((.15 * size * (length - 1.5)) / 2.0), locy, .12 * size, .24 * size, 0xff3333, 1);
		}
	}

	private static String fmtMax(double max) {
		return max == Math.rint(max) ? Integer.toString((int) max) : String.format("%.1f", max);
	}
}
