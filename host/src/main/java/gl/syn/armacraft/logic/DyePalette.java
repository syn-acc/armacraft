package gl.syn.armacraft.logic;

import java.util.List;

/**
 * Picks the Minecraft dye colour (used for concrete / stained glass) closest to an Armagetron RGB colour.
 *
 * <p>Plain RGB distance is a poor fit here: Armagetron colours are fully saturated and bright, while
 * concrete textures are darker, so pure red lands nearer orange than red concrete. Instead each colour
 * is mapped into an HSV cylinder (x = S*cos H, y = S*sin H, z = V; the hue radius fades out for very dark
 * colours) and compared there with value down-weighted, which keeps hue dominant for saturated colours and
 * value dominant for greys.
 */
public final class DyePalette {

	/** Dye name (as in {@code minecraft:<name>_concrete}) with its approximate concrete texture colour. */
	public record Dye(String name, int r, int g, int b) {}

	public static final List<Dye> DYES = List.of(
		new Dye("white", 207, 213, 214),
		new Dye("orange", 224, 97, 1),
		new Dye("magenta", 169, 48, 159),
		new Dye("light_blue", 36, 137, 199),
		new Dye("yellow", 241, 175, 21),
		new Dye("lime", 94, 169, 24),
		new Dye("pink", 214, 101, 143),
		new Dye("gray", 55, 58, 62),
		new Dye("light_gray", 125, 125, 115),
		new Dye("cyan", 21, 119, 136),
		new Dye("purple", 100, 32, 156),
		new Dye("blue", 45, 47, 143),
		new Dye("brown", 96, 60, 32),
		new Dye("green", 73, 91, 36),
		new Dye("red", 142, 33, 33),
		new Dye("black", 8, 10, 15)
	);

	private static final double VALUE_WEIGHT = 0.5;
	private static final double DARK_FADE = 0.25;

	private DyePalette() {}

	/** Closest dye for a colour given as 0..1 floats (out-of-range values are clamped). */
	public static Dye closest(double r, double g, double b) {
		double[] p = cone(clamp01(r), clamp01(g), clamp01(b));
		Dye best = DYES.get(0);
		double bestD = Double.MAX_VALUE;
		for (Dye d : DYES) {
			double[] q = cone(d.r / 255.0, d.g / 255.0, d.b / 255.0);
			double dx = p[0] - q[0];
			double dy = p[1] - q[1];
			double dz = (p[2] - q[2]) * VALUE_WEIGHT;
			double dist = dx * dx + dy * dy + dz * dz;
			if (dist < bestD) {
				bestD = dist;
				best = d;
			}
		}
		return best;
	}

	/** 0..1 floats to a packed 0xRRGGBB int (clamped). */
	public static int packRgb(double r, double g, double b) {
		int ri = (int) Math.round(clamp01(r) * 255.0);
		int gi = (int) Math.round(clamp01(g) * 255.0);
		int bi = (int) Math.round(clamp01(b) * 255.0);
		return (ri << 16) | (gi << 8) | bi;
	}

	static double[] cone(double r, double g, double b) {
		double max = Math.max(r, Math.max(g, b));
		double min = Math.min(r, Math.min(g, b));
		double v = max;
		double s = max <= 0 ? 0 : (max - min) / max;
		double h;
		if (max == min) {
			h = 0;
		} else if (max == r) {
			h = 60.0 * (((g - b) / (max - min)) % 6.0);
		} else if (max == g) {
			h = 60.0 * (((b - r) / (max - min)) + 2.0);
		} else {
			h = 60.0 * (((r - g) / (max - min)) + 4.0);
		}
		double hr = Math.toRadians(h);
		// Very dark colours have unreliable saturation (tiny denominators); fade the hue radius out below V=0.25.
		double radius = s * Math.min(1.0, v / DARK_FADE);
		return new double[] {radius * Math.cos(hr), radius * Math.sin(hr), v};
	}

	static double clamp01(double v) {
		if (Double.isNaN(v)) {
			return 0;
		}
		return Math.max(0, Math.min(1, v));
	}
}
