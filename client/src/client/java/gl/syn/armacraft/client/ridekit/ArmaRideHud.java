package gl.syn.armacraft.client.ridekit;

import gl.syn.armacraft.client.kit.ArmaGauges;
import gl.syn.armacraft.client.kit.ArmaScreen;
import gl.syn.armacraft.client.ride.api.RideHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.function.DoubleSupplier;

/**
 * Ride mode's HUD: Armagetron's three gauges (gHud.cpp: Speed, Rubber Used, Brakes) in Armagetron's font and
 * layout. Speed is shown in Armagetron units per second (blocks/s divided by the ride's blocks-per-unit, so the
 * start speed reads 30 like in Armagetron). Values arrive per tick and are interpolated per frame, so the needles
 * move smoothly.
 */
public final class ArmaRideHud implements RideHud {

	private final ArmaGauges gauges = new ArmaGauges();
	private final DoubleSupplier blocksPerUnit;
	private State prev;
	private State cur;
	private boolean fresh = true;
	private volatile long lastExtract;
	/** Cycle-mode death cam (host: every tick while dead): cause and seconds to the automatic redeploy (NaN = off). */
	private volatile String deadCause;
	private volatile double deadRedeployIn = Double.NaN;
	private volatile long deadStamp;
	/** wallIn when the current warning started: the host's warning threshold, used to scale urgency. */
	private double warnFrom = Double.NaN;

	public ArmaRideHud(DoubleSupplier blocksPerUnit) {
		this.blocksPerUnit = blocksPerUnit;
	}

	@Override
	public void update(State state) {
		if (fresh) {
			gauges.reset();
			prev = state;
			fresh = false;
		} else {
			prev = cur;
		}
		cur = state;
		if (state.warning()) {
			if (Double.isNaN(warnFrom) && Double.isFinite(state.wallIn())) {
				warnFrom = Math.max(0.05, state.wallIn());
			}
		} else {
			warnFrom = Double.NaN;
		}
	}

	/** The last state received has the wall warning on (tests). */
	public boolean warningOn() {
		State s = cur;
		return s != null && s.warning();
	}

	/** 0 when the warning starts .. 1 at the wall. */
	static double urgency(double wallIn, double warnFrom) {
		double ref = Double.isFinite(warnFrom) && warnFrom > 0 ? warnFrom : 0.5;
		if (!Double.isFinite(wallIn)) {
			return 0;
		}
		return Math.max(0, Math.min(1, 1 - wallIn / ref));
	}

	@Override
	public void dead(String cause, double redeployIn) {
		deadCause = cause == null ? "" : cause;
		deadRedeployIn = redeployIn;
		deadStamp = System.nanoTime();
	}

	/** dead() called within the last quarter second. */
	boolean deadShown() {
		return deadStamp != 0 && System.nanoTime() - deadStamp < 250_000_000L;
	}

	@Override
	public void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		lastExtract = System.nanoTime();
		if (deadShown()) {
			deathMessage(ArmaScreen.of(graphics));
			return;
		}
		State a = prev;
		State b = cur;
		if (b == null) {
			return;
		}
		if (a == null) {
			a = b;
		}
		double f = Math.max(0, Math.min(1, delta.getGameTimeDeltaPartialTick(false)));
		double s = blocksPerUnit.getAsDouble();
		double units = s > 0 ? 1 / s : 1;
		double speed = lerp(a.speed(), b.speed(), f) * units;
		double rubberUsed = 1 - lerp(a.rubberLeft(), b.rubberLeft(), f);
		double brake = lerp(a.brake(), b.brake(), f);
		ArmaScreen screen = ArmaScreen.of(graphics);
		// armacraft's boost (not an Armagetron gauge): a fourth gauge in the same row. The row's layout depends on the
		// window shape only, so the other three keep their places whether Boost is shown or not (ArmaGauges.rideRow)
		double boost = lerp(a.boost(), b.boost(), f);
		gauges.drawRide(screen, new ArmaGauges.Model(speed, rubberUsed, brake), boost, boostShown(b.boosting(), boost));
		if (b.warning()) {
			wallWarning(screen, lerp(Double.isFinite(a.wallIn()) ? a.wallIn() : b.wallIn(), b.wallIn(), f));
		}
	}

	/** Boost is in use: boosting, or the reservoir is not full yet (the wide gauge row shows Boost only then). */
	static boolean boostShown(boolean boosting, double boost) {
		return boosting || boost < 0.999;
	}

	/** Drawn within the last quarter second: armacraft is riding with this HUD (vanilla HUD and crosshair hide). */
	public boolean active() {
		return lastExtract != 0 && System.nanoTime() - lastExtract < 250_000_000L;
	}

	/**
	 * NOT Armagetron (armacraft's look-ahead warning), drawn in Armagetron's HUD style: its font, the gauge title red
	 * (0xff3333) shading in from yellow as the wall gets closer, centred in the upper half of the window, growing and
	 * pulsing faster with urgency, with a bar under it for the time left. Its size is in ArmaScreen units, which
	 * follow the window width in a narrow (portrait) window, so it keeps its proportions there.
	 */
	private void wallWarning(ArmaScreen s, double wallIn) {
		double u = urgency(wallIn, warnFrom);
		double cw = .05 + .03 * u;
		double ch = cw * 2;
		// upper half: the 0.4 custom camera frames the bike just below the screen centre, where it must stay visible
		double y = s.windowY(0.42) + ch;
		double pulse = 0.5 + 0.5 * Math.cos(System.nanoTime() / 1e9 * Math.PI * 2 * (2 + 6 * u));
		float alpha = (float) (0.65 + 0.35 * pulse);
		int rgb = lerpRgb(0xffff40, 0xff3333, u);
		s.text("WALL", -2 * cw, y, cw, ch, rgb, alpha);
		double half = 0.12 * (1 - u);
		if (half > 0.002) {
			s.line(-half, y - ch - 0.02, half, y - ch - 0.02, 0xFF000000 | rgb, Math.max(1f, s.unitY * 0.008f));
		}
	}

	/**
	 * Death cam text in Armagetron's HUD style (its font, centred like the "Winner"/console messages): the cause in
	 * the gauge-title red, the hint in the gauge-value cyan, the countdown whole seconds.
	 */
	private void deathMessage(ArmaScreen s) {
		String cause = deadCause == null || deadCause.isBlank() ? "crashed" : deadCause;
		// window heights (upper half of the window at any window shape); the hint keeps its distance in text units
		double y = s.windowY(0.30);
		centered(s, cause, y, .055, 0xff3333, 1f);
		double in = deadRedeployIn;
		centered(s, deadHint(in), y - 0.15, .035, 0xccffff, 1f);
	}

	static String deadHint(double redeployIn) {
		return Double.isFinite(redeployIn) ? "turn to ride again (auto in " + (int) Math.ceil(Math.max(0, redeployIn)) + " s)" : "turn to ride again";
	}

	private static void centered(ArmaScreen s, String text, double y, double cw, int rgb, float alpha) {
		double maxW = 1.8;
		double w = cw * text.length();
		if (w > maxW) {
			cw = maxW / text.length();
			w = maxW;
		}
		s.text(text, -w / 2, y, cw, cw * 2, rgb, alpha);
	}

	private static int lerpRgb(int a, int b, double f) {
		int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f);
		int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f);
		int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * f);
		return (r << 16) | (g << 8) | bl;
	}

	@Override
	public void clear() {
		deadStamp = 0;
		deadCause = null;
		warnFrom = Double.NaN;
		lastExtract = 0;
		prev = null;
		cur = null;
		fresh = true;
	}

	private static double lerp(double a, double b, double f) {
		return a + (b - a) * f;
	}
}
