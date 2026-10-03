package gl.syn.armacraft.ride;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gl.syn.armacraft.ride.core.ArmaSettings;
import gl.syn.armacraft.ride.core.CycleMovement;
import gl.syn.armacraft.ride.core.CycleParams;
import gl.syn.armacraft.ride.core.Heading;
import gl.syn.armacraft.ride.core.Surroundings;
import gl.syn.armacraft.ride.core.Trail;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential test of the physics port against the real Armagetron engine. tools/ride_diff.py records the
 * armacraft guest (patched 0.2.9.3.0 dedicated server) driving scripted bridge inputs; this replays the same inputs
 * through {@link CycleMovement} in Armagetron units (blocks_per_unit 1, zero-width cycle, the rim and the cycle's
 * own walls as the only walls) and compares turn times and positions, speed over time, braking and the time of death.
 *
 * <p>The recordings in src/test/resources/ride-diff run with every {@code gradlew test}
 * (build/ride-diff-bundled-report.txt); a fresh one with {@code -Parmacraft.ridediff=<recording.json>}
 * (build/ride-diff-report.txt, add {@code -Parmacraft.ridediff.verbose} for every sample).
 */
class RideDiffTest {

	private static final double DT = 0.002;
	/** Bridge input reaches the cycle on the guest's next frame; calibrated per recording from the first turn. */
	private static final double DEFAULT_INPUT_DELAY = 0.013;

	record Sample(double t, double x, double y, double speed, boolean brake, double rubber, boolean alive) {}

	record TurnEv(double t, double x, double y) {}

	/** Opt-in: a fresh recording, {@code gradlew test -Parmacraft.ridediff=<recording.json>}. */
	@Test
	void replayAgainstRecording() throws IOException {
		String file = System.getProperty("armacraft.ridediff");
		Assumptions.assumeTrue(file != null && !file.isBlank(), "set -Parmacraft.ridediff=<recording.json> to run");
		String report = replayAll(file, Files.readString(Path.of(file), StandardCharsets.UTF_8));
		Path out = Path.of("build", "ride-diff-report.txt");
		Files.createDirectories(out.getParent());
		Files.writeString(out, report, StandardCharsets.UTF_8);
	}

	/**
	 * The recordings kept in src/test/resources/ride-diff: the basic set (brake, double tap, turn spam, spiral, rim)
	 * and the tight maneuvers next to the own wall (double-bind 180 and back along the wall, adjust towards and away
	 * from an old wall, the tightest box, a box spiral inward), each under stock Armagetron and the RCL sumobar physics.
	 * The same key script must give the same turns, the same path and the same survive/die outcome at the same time.
	 */
	@Test
	void bundledEngineRecordings() throws IOException {
		StringBuilder all = new StringBuilder();
		for (String name : new String[] {"armagetron", "rcl", "tight-armagetron", "tight-rcl"}) {
			try (var in = RideDiffTest.class.getResourceAsStream("/ride-diff/" + name + ".json")) {
				assertTrue(in != null, "missing recording " + name);
				all.append(replayAll(name, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
			}
		}
		Path out = Path.of("build", "ride-diff-bundled-report.txt");
		Files.createDirectories(out.getParent());
		Files.writeString(out, all.toString(), StandardCharsets.UTF_8);
	}

	private static String replayAll(String file, String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		ArmaSettings settings = ArmaSettings.defaults();
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("settings").entrySet()) {
			settings.set(e.getKey(), e.getValue().getAsDouble());
		}
		CycleParams params = settings.params(1.0, 0);
		params.substeps = 1;

		StringBuilder report = new StringBuilder();
		report.append("ride differential test: ").append(file).append(", preset ").append(root.get("preset").getAsString()).append('\n');
		report.append(String.format(Locale.ROOT, "%-13s %9s %9s %9s %9s %9s %9s %10s %10s%n", "scenario", "turns", "max dt_t", "max dpos_t",
			"rms dv", "max dv", "max dpos", "death eng", "death core"));
		boolean ok = true;
		for (JsonElement se : root.getAsJsonArray("scenarios")) {
			JsonObject s = se.getAsJsonObject();
			String result = replay(s, params, report);
			ok &= result == null;
			if (result != null) {
				report.append("  FAIL ").append(result).append('\n');
			}
		}
		System.out.println(report);
		assertTrue(ok, report.toString());
		return report.toString();
	}

	/** @return null if within tolerance, else why not */
	private static String replay(JsonObject s, CycleParams params, StringBuilder report) {
		String name = s.get("name").getAsString();
		double[] b = toArray(s.getAsJsonArray("bounds"));
		// engine samples while alive and after the start (skip the countdown and the stray first t=0 line)
		List<Sample> eng = new ArrayList<>();
		double lastT = -1;
		boolean first = true;
		for (JsonElement je : s.getAsJsonArray("states")) {
			JsonObject o = je.getAsJsonObject();
			double t = o.get("t").getAsDouble();
			if (first) {
				first = false;
				continue;
			}
			if (t < 0 || t <= lastT) {
				continue;
			}
			lastT = t;
			eng.add(new Sample(t, o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("speed").getAsDouble(), o.get("brake").getAsBoolean(),
				o.get("rubber").getAsDouble(), o.get("alive").getAsBoolean()));
		}
		List<TurnEv> engTurns = new ArrayList<>();
		double engDeath = Double.NaN;
		for (JsonElement je : s.getAsJsonArray("events")) {
			JsonObject e = je.getAsJsonObject();
			switch (e.get("ev").getAsString()) {
				case "turn" -> engTurns.add(new TurnEv(e.get("t").getAsDouble(), e.get("x").getAsDouble(), e.get("y").getAsDouble()));
				case "death" -> engDeath = e.get("t").getAsDouble();
				default -> {
				}
			}
		}
		// inputs at the guest: sent after the state at t, applied on the guest's next frame
		JsonArray inputs = s.getAsJsonArray("inputs");
		double delay = DEFAULT_INPUT_DELAY;
		if (!engTurns.isEmpty() && !inputs.isEmpty()) {
			delay = engTurns.get(0).t() - inputs.get(0).getAsJsonObject().get("sent_after_state_t").getAsDouble();
		}
		// The bridge's latency varies from input to input (up to 0.2 s on a loaded machine), so each group of presses
		// (the script's "at once" presses) is aligned to the engine turn its first press made; the other presses of
		// the group keep their recorded offsets to it, so the turn queue (CYCLE_DELAY spacing, memory) is still the
		// core's own work.
		List<double[]> in = new ArrayList<>(); // {time, action: -1 left, +1 right, 2 brake on, 3 brake off}
		double groupAt = Double.NaN;
		double groupDelay = delay;
		int turnInputs = 0;
		for (JsonElement je : inputs) {
			JsonObject o = je.getAsJsonObject();
			String a = o.get("action").getAsString();
			double code = switch (a) {
				case "left" -> -1;
				case "right" -> 1;
				case "brake on" -> 2;
				default -> 3;
			};
			double sent = o.get("sent_after_state_t").getAsDouble();
			boolean turn = code == -1 || code == 1;
			double at = o.get("at").getAsDouble();
			if (turn && at != groupAt) {
				groupAt = at;
				groupDelay = turnInputs < engTurns.size() ? Math.max(0, engTurns.get(turnInputs).t() - sent) : delay;
			}
			if (turn) {
				turnInputs++;
			}
			in.add(new double[] {sent + (turn ? groupDelay : delay), code});
		}

		// the core, in Armagetron units on the same arena; z = -y so Heading's north (-z) is Armagetron's +y.
		// Start from the spawn: the last countdown state (t < 0); the cycle starts moving at t = 0.
		JsonObject spawn = null;
		for (JsonElement je : s.getAsJsonArray("states")) {
			JsonObject o = je.getAsJsonObject();
			if (o.get("t").getAsDouble() < 0) {
				spawn = o;
			}
		}
		Sample s0 = new Sample(0, spawn.get("x").getAsDouble(), spawn.get("y").getAsDouble(), spawn.get("speed").getAsDouble(), false, 0, true);
		int heading = headingOf(spawn.get("dx").getAsDouble(), spawn.get("dy").getAsDouble());
		CycleMovement m = new CycleMovement(params, heading);
		double x = s0.x();
		double z = -s0.y();
		Trail own = new Trail();
		own.append(x, 0, z, 0);
		List<TurnEv> coreTurns = new ArrayList<>();
		List<double[]> coreSamples = new ArrayList<>(); // t, x, y, speed
		double t = 0;
		int next = 0;
		double coreDeath = Double.NaN;
		double tEnd = Double.isNaN(engDeath) ? eng.get(eng.size() - 1).t() : engDeath + 1.0;
		while (t < tEnd) {
			while (next < in.size() && in.get(next)[0] <= t + 1e-9) {
				double code = in.get(next)[1];
				if (code == 2 || code == 3) {
					m.setBraking(code == 2);
				} else if (m.requestTurn((int) code)) {
					coreTurns.add(new TurnEv(t, x, -z));
				}
				next++;
			}
			if (m.processPendingTurns()) {
				coreTurns.add(new TurnEv(t, x, -z));
			}
			int h = m.heading();
			double hx = Heading.dx(h);
			double hz = Heading.dz(h);
			Trail.Point box = new Trail.Point(x, z, 0, 1);
			double rim = rimDistance(b, x, z, hx, hz);
			double ownAhead = own.ahead(box, h, 1, 50, m.turns() - 1, Double.NaN, 0, 0);
			Surroundings.Side left = side(own, box, h, -1, params.wallNear);
			Surroundings.Side right = side(own, box, h, +1, params.wallNear);
			CycleMovement.Step step = m.tick(DT, new Surroundings(Math.min(rim, ownAhead), 1, left, right, false));
			x += hx * step.distance();
			z += hz * step.distance();
			own.append(x, 0, z, m.turns());
			t += DT;
			coreSamples.add(new double[] {t, x, -z, m.speed()});
			if (step.crashed()) {
				coreDeath = t;
				break;
			}
		}

		// compare
		double maxTurnDt = 0;
		double maxTurnDpos = 0;
		int n = Math.min(engTurns.size(), coreTurns.size());
		for (int i = 0; i < n; i++) {
			maxTurnDt = Math.max(maxTurnDt, Math.abs(engTurns.get(i).t() - coreTurns.get(i).t()));
			maxTurnDpos = Math.max(maxTurnDpos, Math.hypot(engTurns.get(i).x() - coreTurns.get(i).x(), engTurns.get(i).y() - coreTurns.get(i).y()));
		}
		double sumDv = 0;
		double maxDv = 0;
		double maxDpos = 0;
		int k = 0;
		for (Sample e : eng) {
			if (!e.alive() || (!Double.isNaN(engDeath) && e.t() > engDeath - 0.05) || (!Double.isNaN(coreDeath) && e.t() > coreDeath - 0.05)) {
				continue;
			}
			// the first snapshot after the start can still show the spawn (taken before the engine's first step)
			if (Math.abs(e.x() - s0.x()) + Math.abs(e.y() - s0.y()) < 1e-9) {
				continue;
			}
			// a turn drops the speed by CYCLE_TURN_SPEED_FACTOR at once: a sample within a frame of one compares the
			// two sides of that step, not the physics
			if (nearTurn(engTurns, e.t()) || nearTurn(coreTurns, e.t())) {
				continue;
			}
			double[] c = at(coreSamples, e.t());
			if (c == null) {
				continue;
			}
			double dv = Math.abs(c[3] - e.speed());
			if (System.getProperty("armacraft.ridediff.verbose") != null) {
				report.append(String.format(Locale.ROOT, "      %s t=%.4f engine v %.3f rubber %.4f (%.3f,%.3f)  core v %.3f (%.3f,%.3f)%n", name, e.t(), e.speed(),
					e.rubber(), e.x(), e.y(), c[3], c[1], c[2]));
			}
			sumDv += dv * dv;
			maxDv = Math.max(maxDv, dv);
			maxDpos = Math.max(maxDpos, Math.hypot(c[1] - e.x(), c[2] - e.y()));
			k++;
		}
		double rmsDv = k > 0 ? Math.sqrt(sumDv / k) : Double.NaN;
		report.append(String.format(Locale.ROOT, "%-13s %4d/%-4d %9.4f %9.4f %9.4f %9.4f %9.4f %10.4f %10.4f%n", name, coreTurns.size(), engTurns.size(),
			maxTurnDt, maxTurnDpos, rmsDv, maxDv, maxDpos, engDeath, coreDeath));
		for (int i = 0; i < Math.max(engTurns.size(), coreTurns.size()); i++) {
			TurnEv e = i < engTurns.size() ? engTurns.get(i) : null;
			TurnEv c = i < coreTurns.size() ? coreTurns.get(i) : null;
			report.append(String.format(Locale.ROOT, "    turn %d: engine %s  core %s%n", i + 1,
				e == null ? "-" : String.format(Locale.ROOT, "t=%.4f (%.3f,%.3f)", e.t(), e.x(), e.y()),
				c == null ? "-" : String.format(Locale.ROOT, "t=%.4f (%.3f,%.3f)", c.t(), c.x(), c.y())));
		}
		// tolerances: one guest frame for timing (inputs are only known to ~1/30 s, turns quantise to frames),
		// 0.5 unit (17 ms of travel at 30 u/s) for positions, speed see below, 0.05 s for death
		if (engTurns.size() != coreTurns.size()) {
			return name + ": turn count " + coreTurns.size() + " vs engine " + engTurns.size();
		}
		if (maxTurnDt > 0.02) {
			return name + ": turn time off by " + maxTurnDt;
		}
		// speed: the bridge reports Armagetron's Speed() = verletSpeed + .5 * lastTimestep * acceleration
		// (gCycleMovement.cpp:989-993) with the dedicated server's coarse frame step, so single samples lead by up to
		// ~.5 lastTimestep * acceleration; the integrated motion (positions, death times) is what has to agree
		if (maxDv > 1.2 || rmsDv > 0.25) {
			return name + ": speed off (rms " + rmsDv + ", max " + maxDv + ")";
		}
		if (maxDpos > 0.5) {
			return name + ": position off by " + maxDpos;
		}
		if (Double.isNaN(engDeath) != Double.isNaN(coreDeath) || (!Double.isNaN(engDeath) && Math.abs(engDeath - coreDeath) > 0.05)) {
			return name + ": death " + coreDeath + " vs engine " + engDeath;
		}
		return null;
	}

	private static boolean nearTurn(List<TurnEv> turns, double t) {
		for (TurnEv e : turns) {
			if (Math.abs(e.t() - t) < 0.04) {
				return true;
			}
		}
		return false;
	}

	private static Surroundings.Side side(Trail own, Trail.Point box, int h, int side, double near) {
		double c = own.side(box, h, side, 1, near, Integer.MAX_VALUE);
		return Double.isFinite(c) ? new Surroundings.Side(c, Surroundings.Kind.SELF) : Surroundings.Side.NONE;
	}

	/** Distance from (x, z) along (hx, hz) to the rim (Armagetron bounds, z = -y). */
	private static double rimDistance(double[] b, double x, double z, double hx, double hz) {
		if (hx > 0) {
			return b[2] - x;
		}
		if (hx < 0) {
			return x - b[0];
		}
		double y = -z;
		return hz < 0 ? b[3] - y : y - b[1];
	}

	private static int headingOf(double dx, double dy) {
		if (Math.abs(dx) > Math.abs(dy)) {
			return dx > 0 ? Heading.EAST : Heading.WEST;
		}
		return dy > 0 ? Heading.NORTH : Heading.SOUTH;
	}

	private static double[] at(List<double[]> samples, double t) {
		for (int i = 1; i < samples.size(); i++) {
			double[] a = samples.get(i - 1);
			double[] c = samples.get(i);
			if (a[0] <= t && c[0] >= t) {
				double f = (t - a[0]) / Math.max(1e-12, c[0] - a[0]);
				return new double[] {t, a[1] + (c[1] - a[1]) * f, a[2] + (c[2] - a[2]) * f, a[3] + (c[3] - a[3]) * f};
			}
		}
		return null;
	}

	private static double[] toArray(JsonArray a) {
		double[] out = new double[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsDouble();
		}
		return out;
	}
}
