package gl.syn.armacraft.client.ridekit;

import gl.syn.armacraft.client.ClientConfig;
import gl.syn.armacraft.client.ride.api.ChaseCamera;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Replays camera targets recorded from real rides (client gametest {@code -Parmacraft_client.gametest=camrec}, files in
 * build/clientGametest/camrec) through camera variants on the recorded heightmap, and writes vertical smoothness
 * numbers to build/camreplay.txt. Skipped when no recording exists.
 */
class ArmaChaseCameraReplayTest {

	private static final Path DIR = Path.of(System.getProperty("camrec.dir", System.getenv().getOrDefault("CAMREC_DIR", "build/clientGametest/camrec")));


	/** {@code arma} null = the 0.4 z5693 settings (fallback); otherwise the 0.1.2 camera: back/rise/pitch/turn, no speed terms. */
	record Variant(String name, String mode, double[] arma, double follow) {}

	@Test
	void replay() throws IOException {
		Assumptions.assumeTrue(Files.isDirectory(DIR), "no camera recording");
		List<Variant> variants = List.of(
			new Variant("0.1.2 cam (b11 r6 p-.45 t25) hf3", "lift", new double[] {11, 6, -.45, 25}, 3),
			new Variant("0.4 z5693 hf2", "lift", null, 2),
			new Variant("0.4 z5693 hf3", "lift", null, 3),
			new Variant("0.4 z5693 hf4", "lift", null, 4),
			new Variant("0.4 z5693 hf6", "lift", null, 6));
		StringBuilder out = new StringBuilder();
		for (String biome : new String[] {"plains", "hills"}) {
			Path frames = DIR.resolve(biome + "-frames.csv");
			if (!Files.isRegularFile(frames)) {
				continue;
			}
			List<double[]> rec = readCsv(frames);
			Map<Long, Double> heights = new HashMap<>();
			for (double[] h : readCsv(DIR.resolve(biome + "-heights.csv"))) {
				heights.put(key((int) h[0], (int) h[1]), h[2]);
			}
			double rise = 0;
			for (int i = 1; i < rec.size(); i++) {
				rise += Math.abs(rec.get(i)[2] - rec.get(i - 1)[2]);
			}
			double sq = 0, mx = 0;
			int nn = 0;
			int at = -1;
			for (int i = 2; i < rec.size(); i++) {
				if (rec.get(i)[11] < rec.get(i - 1)[11] || rec.get(i - 1)[11] < rec.get(i - 2)[11] || rec.get(i - 2)[11] < 0.5) {
					continue;
				}
				double d2 = rec.get(i)[2] - 2 * rec.get(i - 1)[2] + rec.get(i - 2)[2];
				sq += d2 * d2;
				nn++;
				if (Math.abs(d2) > mx) {
					mx = Math.abs(d2);
					at = i;
				}
			}
			out.append(String.format(Locale.ROOT, "%s: %d frames, bike vertical travel %.1f blocks; input bike y d2 rms %.4f max %.4f (frame %d, y %.2f %.2f %.2f, dt %.4f %.4f)%n",
				biome, rec.size(), rise, Math.sqrt(sq / Math.max(1, nn)), mx, at, at > 1 ? rec.get(at - 2)[2] : 0, at > 1 ? rec.get(at - 1)[2] : 0, at > 1 ? rec.get(at)[2] : 0,
				at > 1 ? rec.get(at - 1)[0] : 0, at > 1 ? rec.get(at)[0] : 0));
			for (Variant v : variants) {
				out.append(String.format(Locale.ROOT, "  %-28s %s%n", v.name(), run(v, rec, heights)));
			}
		}
		Files.writeString(Path.of("build/camreplay.txt"), out);
		System.out.println(out);
	}

	private static String run(Variant v, List<double[]> rec, Map<Long, Double> heights) {
		ClientConfig.Ride c = new ClientConfig.Ride();
		c.heightMode = v.mode();
		c.heightFollow = v.follow();
		ArmaCamSettings.Resolved base = ArmaCamSettings.fallback();
		ArmaCamSettings.Resolved res = base;
		if (v.arma() != null) {
			gl.syn.armacraft.client.core.ArmaCam04.Settings old = new gl.syn.armacraft.client.core.ArmaCam04.Settings();
			old.back = v.arma()[0];
			old.rise = v.arma()[1];
			old.pitch = v.arma()[2];
			old.turnSpeed = v.arma()[3];
			old.turnSpeed180 = 2;
			old.zoom = 0;
			res = new ArmaCamSettings.Resolved(old, 90, base.lowerWall(), 1, false, false, base.values(), base.sources());
		}
		final ArmaCamSettings.Resolved fres = res;
		ArmaChaseCamera.World world = new ArmaChaseCamera.World() {
			@Override
			public double groundY(double x, double z) {
				Double h = heights.get(key((int) Math.floor(x), (int) Math.floor(z)));
				return h == null ? Double.NaN : h;
			}

			@Override
			public double clearFraction(Vec3 pivot, Vec3 want) {
				// terrain only (heightmap; no trees): first sample under the ground, minus the margin
				Vec3 d = want.subtract(pivot);
				double len = d.length();
				int n = Math.max(2, (int) Math.ceil(len / 0.2));
				for (int i = 1; i <= n; i++) {
					double f = i / (double) n;
					Vec3 p = pivot.add(d.scale(f));
					double g = groundY(p.x, p.z);
					// a heightmap cannot represent overhangs: a column far above the bike next to it is open space below
					boolean overhang = g > pivot.y + 2 && Math.hypot(p.x - pivot.x, p.z - pivot.z) < 1.5;
					if (Double.isFinite(g) && p.y < g && !overhang) {
						return Math.max(0, f - 0.3 / len);
					}
				}
				return 1;
			}
		};
		ArmaChaseCamera cam = new ArmaChaseCamera(() -> c, world, () -> 16 / 9.0);
		cam.settings(() -> fres);
		List<double[]> eyes = new ArrayList<>(); // eyeY, screen vertical angle of the bike (deg), pull, dt
		double lastRide = Double.NaN;
		for (double[] r : rec) {
			ChaseCamera.Target t = new ChaseCamera.Target(new Vec3(r[1], r[2], r[3]), (float) r[4], (float) r[5], r[6], r[7], (float) r[8], (float) r[9], r[10],
				r[11], r[12] > 0, r[13] > 0, false);
			if (Double.isNaN(lastRide) || r[11] < lastRide) {
				cam.reset(t); // (re)deploy
				eyes.add(null);
			} else {
				cam.update(r[0], t);
			}
			lastRide = r[11];
			Vec3 e = cam.eye();
			double h = Math.hypot(r[1] - e.x, r[3] - e.z);
			double elev = -Math.toDegrees(Math.atan2(r[2] + 0.45 - e.y, h)); // MC pitch convention of the direction to the bike
			// the first 0.5 s after a (re)deploy is the deploy transient, not riding
			eyes.add(r[11] < 0.5 ? null : new double[] {e.y, elev - cam.pitch(), cam.pull(), r[0]});
		}
		double sumD2 = 0, maxD2 = 0, sumS2 = 0, maxS2 = 0;
		int n = 0;
		int pulledFrames = 0;
		int snaps = 0;
		double maxSnap = 0;
		for (int i = 2; i < eyes.size(); i++) {
			double[] a = eyes.get(i - 2), b = eyes.get(i - 1), x = eyes.get(i);
			if (a == null || b == null || x == null || !(x[3] > 0) || !(b[3] > 0)) {
				continue;
			}
			if (x[2] < 0.999) {
				pulledFrames++;
			}
			if (b[2] - x[2] > 0.05) {
				snaps++;
				maxSnap = Math.max(maxSnap, b[2] - x[2]);
			}
			double d2 = x[0] - 2 * b[0] + a[0];
			double s2 = x[1] - 2 * b[1] + a[1];
			sumD2 += d2 * d2;
			maxD2 = Math.max(maxD2, Math.abs(d2));
			sumS2 += s2 * s2;
			maxS2 = Math.max(maxS2, Math.abs(s2));
			n++;
		}
		return String.format(Locale.ROOT, "eye y d2 rms %.4f max %.4f | bike screen-vert d2 rms %.3f max %.3f deg | pulled %d, snaps %d (max %.2f) | n %d",
			Math.sqrt(sumD2 / Math.max(1, n)), maxD2, Math.sqrt(sumS2 / Math.max(1, n)), maxS2, pulledFrames, snaps, maxSnap, n);
	}

	private static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	private static List<double[]> readCsv(Path p) throws IOException {
		List<double[]> out = new ArrayList<>();
		for (String line : Files.readAllLines(p)) {
			if (line.isBlank()) {
				continue;
			}
			String[] f = line.split(",");
			double[] v = new double[f.length];
			for (int i = 0; i < f.length; i++) {
				v[i] = Double.parseDouble(f[i]);
			}
			out.add(v);
		}
		return out;
	}
}
