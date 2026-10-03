package gl.syn.armacraft.ride.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Armagetron settings by their console names, in Armagetron units, with the 0.2.9.3.0 defaults
 * (config/settings.cfg lines 116-188 and 684-685; where settings.cfg does not set one, the default in
 * src/tron/gCycleMovement.cpp / gCycle.cpp / ui/uInput.cpp). Parses Armagetron .cfg text ({@code NAME value}
 * lines, {@code #} comments) and converts to {@link CycleParams} with a blocks-per-unit scale.
 */
public final class ArmaSettings {

	private final Map<String, Double> values = new LinkedHashMap<>();

	public static ArmaSettings defaults() {
		ArmaSettings s = new ArmaSettings();
		Map<String, Double> v = s.values;
		v.put("CYCLE_SPEED", 30.0);
		v.put("CYCLE_SPEED_MIN", 0.25);
		v.put("CYCLE_SPEED_MAX", 0.0);
		v.put("CYCLE_SPEED_DECAY_BELOW", 5.0);
		v.put("CYCLE_SPEED_DECAY_ABOVE", 0.1);
		v.put("CYCLE_START_SPEED", 20.0);
		v.put("CYCLE_ACCEL", 10.0);
		v.put("CYCLE_ACCEL_SELF", 1.0);
		v.put("CYCLE_ACCEL_TEAM", 1.0);
		v.put("CYCLE_ACCEL_ENEMY", 1.0);
		v.put("CYCLE_ACCEL_RIM", 0.0);
		v.put("CYCLE_ACCEL_SLINGSHOT", 1.0);
		v.put("CYCLE_ACCEL_TUNNEL", 1.0);
		v.put("CYCLE_ACCEL_OFFSET", 2.0);
		v.put("CYCLE_WALL_NEAR", 6.0);
		v.put("CYCLE_BRAKE", 30.0);
		v.put("CYCLE_BRAKE_REFILL", 0.1);
		v.put("CYCLE_BRAKE_DEPLETE", 1.0);
		v.put("CYCLE_DELAY", 0.1);
		v.put("CYCLE_DELAY_TIMEBASED", 1.0);
		v.put("CYCLE_DELAY_DOUBLEBIND_BONUS", 1.0);
		v.put("CYCLE_TURN_SPEED_FACTOR", 0.95);
		v.put("CYCLE_TURN_MEMORY", 3.0);
		v.put("CYCLE_RUBBER", 1.0);
		v.put("CYCLE_RUBBER_SPEED", 40.0);
		v.put("CYCLE_RUBBER_TIME", 10.0);
		v.put("CYCLE_RUBBER_TIMEBASED", 0.0);
		v.put("CYCLE_RUBBER_MINDISTANCE", 0.001);
		v.put("CYCLE_RUBBER_MINDISTANCE_RATIO", 0.0001);
		v.put("CYCLE_RUBBER_MINDISTANCE_RESERVOIR", 0.005);
		v.put("CYCLE_RUBBER_MINDISTANCE_UNPREPARED", 0.005);
		v.put("CYCLE_RUBBER_MINDISTANCE_PREPARATION", 0.2);
		v.put("CYCLE_RUBBER_MINADJUST", 0.05);
		v.put("CYCLE_RUBBER_DELAY", 0.0);
		v.put("CYCLE_RUBBER_DELAY_BONUS", 0.5);
		v.put("CYCLE_RUBBER_MALUS_TURN", 0.0);
		v.put("CYCLE_RUBBER_MALUS_TIME", 5.0);
		v.put("CYCLE_RUBBER_WALL_SHRINK", 0.0);
		v.put("WALLS_LENGTH", -1.0);
		v.put("CYCLE_WALLS_STAY_UP_DELAY", 8.0);
		v.put("DOUBLEBIND_TIME", -10.0);
		return s;
	}

	public ArmaSettings copy() {
		ArmaSettings s = new ArmaSettings();
		s.values.putAll(values);
		return s;
	}

	public boolean has(String name) {
		return values.containsKey(name.toUpperCase(Locale.ROOT));
	}

	public double get(String name) {
		Double d = values.get(name.toUpperCase(Locale.ROOT));
		if (d == null) {
			throw new IllegalArgumentException("unknown Armagetron setting " + name);
		}
		return d;
	}

	/** Sets a known setting; returns false (and ignores it) for names this port does not use. */
	public boolean set(String name, double value) {
		String key = name.toUpperCase(Locale.ROOT);
		if (key.equals("CYCLE_WALLS_LENGTH")) {
			key = "WALLS_LENGTH";
		}
		if (key.equals("WALLS_STAY_UP_DELAY")) {
			key = "CYCLE_WALLS_STAY_UP_DELAY"; // the .cfg name (gGame.cpp:567) of the value synced as CYCLE_WALLS_STAY_UP_DELAY
		}
		if (!values.containsKey(key) || !Double.isFinite(value)) {
			return false;
		}
		values.put(key, value);
		return true;
	}

	public Map<String, Double> asMap() {
		return new LinkedHashMap<>(values);
	}

	/** Applies {@code NAME value} lines (Armagetron .cfg syntax); unknown or non-numeric lines are skipped. */
	public ArmaSettings apply(String cfgText) {
		for (String raw : cfgText.split("\\R")) {
			String line = raw.strip();
			int hash = line.indexOf('#');
			if (hash >= 0) {
				line = line.substring(0, hash).strip();
			}
			if (line.isEmpty()) {
				continue;
			}
			String[] parts = line.split("\\s+");
			if (parts.length < 2) {
				continue;
			}
			try {
				set(parts[0], Double.parseDouble(parts[1]));
			} catch (NumberFormatException ignored) {
				// string settings (MAP_FILE, ...) are not physics
			}
		}
		return this;
	}

	/**
	 * Converts to Minecraft units: every length dimension times {@code blocksPerUnit} (accelerations of the wall
	 * formula, units^2/s^2, times its square); rates, times, ratios unchanged.
	 *
	 * @param speedCap hard cap on speed as a ratio of CYCLE_SPEED (Minecraft: chunk loading); 0 = none
	 */
	public CycleParams params(double blocksPerUnit, double speedCap) {
		double s = blocksPerUnit;
		CycleParams p = new CycleParams();
		p.speed = get("CYCLE_SPEED") * s;
		p.speedMin = get("CYCLE_SPEED_MIN");
		double max = get("CYCLE_SPEED_MAX");
		if (speedCap > 0) {
			max = max > 0 ? Math.min(max, speedCap) : speedCap;
		}
		p.speedMax = max;
		p.decayBelow = get("CYCLE_SPEED_DECAY_BELOW");
		p.decayAbove = get("CYCLE_SPEED_DECAY_ABOVE");
		p.startSpeed = get("CYCLE_START_SPEED") * s;
		p.accel = get("CYCLE_ACCEL") * s * s;
		p.accelOffset = get("CYCLE_ACCEL_OFFSET") * s;
		p.wallNear = get("CYCLE_WALL_NEAR") * s;
		p.accelSelf = get("CYCLE_ACCEL_SELF");
		p.accelEnemy = get("CYCLE_ACCEL_ENEMY");
		p.accelRim = get("CYCLE_ACCEL_RIM");
		p.accelSlingshot = get("CYCLE_ACCEL_SLINGSHOT");
		p.accelTunnel = get("CYCLE_ACCEL_TUNNEL");
		p.brake = get("CYCLE_BRAKE") * s;
		p.brakeRefill = get("CYCLE_BRAKE_REFILL");
		p.brakeDeplete = get("CYCLE_BRAKE_DEPLETE");
		p.delay = get("CYCLE_DELAY");
		p.delayDoublebindBonus = get("CYCLE_DELAY_DOUBLEBIND_BONUS");
		p.delayTimebased = get("CYCLE_DELAY_TIMEBASED");
		p.turnSpeedFactor = get("CYCLE_TURN_SPEED_FACTOR");
		p.turnMemory = (int) Math.round(get("CYCLE_TURN_MEMORY"));
		p.rubber = get("CYCLE_RUBBER") * s;
		p.rubberSpeed = get("CYCLE_RUBBER_SPEED");
		p.rubberTime = get("CYCLE_RUBBER_TIME");
		p.rubberTimebased = get("CYCLE_RUBBER_TIMEBASED");
		p.rubberMinDistance = get("CYCLE_RUBBER_MINDISTANCE") * s;
		p.rubberMinDistanceRatio = get("CYCLE_RUBBER_MINDISTANCE_RATIO");
		p.rubberMinDistanceReservoir = get("CYCLE_RUBBER_MINDISTANCE_RESERVOIR") * s;
		p.rubberMinDistanceUnprepared = get("CYCLE_RUBBER_MINDISTANCE_UNPREPARED") * s;
		p.rubberMinDistancePreparation = get("CYCLE_RUBBER_MINDISTANCE_PREPARATION");
		p.rubberMinAdjust = get("CYCLE_RUBBER_MINADJUST");
		p.rubberDelay = get("CYCLE_RUBBER_DELAY");
		p.rubberDelayBonus = get("CYCLE_RUBBER_DELAY_BONUS");
		p.rubberMalusTurn = get("CYCLE_RUBBER_MALUS_TURN");
		p.rubberMalusTime = get("CYCLE_RUBBER_MALUS_TIME");
		return p;
	}
}
