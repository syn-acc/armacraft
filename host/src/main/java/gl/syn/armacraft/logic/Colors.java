package gl.syn.armacraft.logic;

import java.util.UUID;

public final class Colors {

	private Colors() {}

	/** Stable bright colour for a player, as 0..1 floats. Hue from the UUID hash, fixed saturation/value. */
	public static double[] fromUuid(UUID uuid) {
		long h = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
		h ^= (h >>> 33);
		h *= 0xff51afd7ed558ccdL;
		h ^= (h >>> 33);
		double hue = Math.floorMod(h, 360L);
		return hsvToRgb(hue, 0.85, 1.0);
	}

	public static double[] hsvToRgb(double hueDeg, double s, double v) {
		double c = v * s;
		double hp = (((hueDeg % 360) + 360) % 360) / 60.0;
		double x = c * (1 - Math.abs(hp % 2 - 1));
		double r;
		double g;
		double b;
		if (hp < 1) {
			r = c; g = x; b = 0;
		} else if (hp < 2) {
			r = x; g = c; b = 0;
		} else if (hp < 3) {
			r = 0; g = c; b = x;
		} else if (hp < 4) {
			r = 0; g = x; b = c;
		} else if (hp < 5) {
			r = x; g = 0; b = c;
		} else {
			r = c; g = 0; b = x;
		}
		double m = v - c;
		return new double[] {r + m, g + m, b + m};
	}
}
