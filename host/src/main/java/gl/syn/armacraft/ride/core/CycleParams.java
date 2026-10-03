package gl.syn.armacraft.ride.core;

/**
 * Armagetron cycle physics settings, already converted to Minecraft units: distances in blocks, speeds in
 * blocks/s, accelerations in blocks/s^2, times in seconds. Built from {@code RideConfig} (Armagetron units plus
 * {@code blocks_per_unit}); every length dimension is multiplied by that scale, so all time-based behaviour
 * (turn delay, brake duration, rubber regeneration, speed decay) is exactly Armagetron's.
 *
 * <p>Field names follow the Armagetron setting they come from ({@code CYCLE_SPEED} -> {@link #speed}, ...).
 * Source: armagetronad tag v0.2.9.3.0, src/tron/gCycleMovement.cpp (declarations at lines 102-445) and the
 * shipped defaults in config/settings.cfg lines 116-188.
 */
public final class CycleParams {

	// ---- speed (CYCLE_SPEED, _MIN, _MAX, _DECAY_BELOW, _DECAY_ABOVE, CYCLE_START_SPEED)
	/** CYCLE_SPEED: base speed the cycle settles at (blocks/s). */
	public double speed = 18.0;
	/** CYCLE_SPEED_MIN: minimal speed as a ratio of {@link #speed}. */
	public double speedMin = 0.25;
	/** CYCLE_SPEED_MAX: maximal speed as a ratio of {@link #speed}; 0 = unlimited (Armagetron default). */
	public double speedMax = 2.0;
	/** CYCLE_SPEED_DECAY_BELOW: rate (1/s) speed approaches {@link #speed} from below. */
	public double decayBelow = 5.0;
	/** CYCLE_SPEED_DECAY_ABOVE: rate (1/s) speed approaches {@link #speed} from above. */
	public double decayAbove = 0.1;
	/** CYCLE_START_SPEED (blocks/s). */
	public double startSpeed = 12.0;

	// ---- wall acceleration (CYCLE_ACCEL*, CYCLE_WALL_NEAR)
	/** CYCLE_ACCEL (blocks^2/s^2: Armagetron units^2/s^2 times scale^2). */
	public double accel = 3.6;
	/** CYCLE_ACCEL_OFFSET (blocks). */
	public double accelOffset = 1.2;
	/** CYCLE_WALL_NEAR (blocks): walls closer than this accelerate. */
	public double wallNear = 3.6;
	/** CYCLE_ACCEL_SELF / _ENEMY multipliers. */
	public double accelSelf = 1.0;
	public double accelEnemy = 1.0;
	/** CYCLE_ACCEL_RIM: multiplier for static walls. Minecraft terrain counts as rim. */
	public double accelRim = 1.0;
	public double accelSlingshot = 1.0;
	public double accelTunnel = 1.0;

	// ---- brake (CYCLE_BRAKE, _REFILL, _DEPLETE)
	/** CYCLE_BRAKE: deceleration while braking (blocks/s^2). */
	public double brake = 18.0;
	/** CYCLE_BRAKE_REFILL: reservoir refill per second (reservoir is 0..1). */
	public double brakeRefill = 0.1;
	/** CYCLE_BRAKE_DEPLETE: reservoir use per second of braking. */
	public double brakeDeplete = 1.0;

	// ---- turning (CYCLE_DELAY*, CYCLE_TURN_SPEED_FACTOR, CYCLE_TURN_MEMORY)
	/** CYCLE_DELAY: minimum time between turns (s). */
	public double delay = 0.1;
	public double delayDoublebindBonus = 1.0;
	public double delayTimebased = 1.0;
	/** CYCLE_TURN_SPEED_FACTOR: speed multiplier applied at every turn. */
	public double turnSpeedFactor = 0.95;
	/** CYCLE_TURN_MEMORY: turns queued while the delay runs. */
	public int turnMemory = 3;

	// ---- rubber (CYCLE_RUBBER*)
	/** CYCLE_RUBBER: rubber reservoir (blocks of would-be travel into a wall). */
	public double rubber = 9.0;
	/** CYCLE_RUBBER_SPEED: logarithmic approach speed to a wall while rubber is in effect (1/s). */
	public double rubberSpeed = 40.0;
	/** CYCLE_RUBBER_TIME: timescale rubber is restored on (s). */
	public double rubberTime = 10.0;
	public double rubberTimebased = 0.0;
	/** CYCLE_RUBBER_MINDISTANCE and friends (blocks; _RATIO is dimensionless; _PREPARATION seconds). */
	public double rubberMinDistance = 0.0006;
	public double rubberMinDistanceRatio = 0.0001;
	public double rubberMinDistanceReservoir = 0.003;
	public double rubberMinDistanceUnprepared = 0.003;
	public double rubberMinDistancePreparation = 0.2;
	/** CYCLE_RUBBER_MINADJUST. */
	public double rubberMinAdjust = 0.05;
	public double rubberDelay = 0.0;
	public double rubberDelayBonus = 0.5;
	public double rubberMalusTurn = 0.0;
	public double rubberMalusTime = 5.0;

	// ---- boost (NOT Armagetron: armacraft addition, the brake's mirror image)
	/** Extra acceleration while the boost key is held and the boost reservoir is not empty (blocks/s^2); 0 = off. */
	public double boostAccel = 0;
	/** Boost reservoir use per second while boosting, and refill per second otherwise (reservoir 0..1). */
	public double boostDeplete = 1.0;
	public double boostRefill = 0.1;
	/** Speed cap while boosting, as a ratio of {@link #speed}; 0 = only the normal cap. */
	public double boostSpeedMax = 1.5;

	/**
	 * NOT Armagetron: rubber effectiveness multiplier against Minecraft terrain walls (blocks, low headroom, lava).
	 * Armagetron rubber assumes an arena of visible wall lines; natural terrain is full of 2-high walls nobody can
	 * read at speed. Trails keep exact preset rubber (factor 1).
	 */
	public double terrainRubberFactor = 1.0;

	/** Simulation substeps per call to {@link CycleMovement#tick} (Armagetron runs at frame rate; 20 Hz ticks are split). */
	public int substeps = 5;

	public CycleParams copy() {
		CycleParams c = new CycleParams();
		c.speed = speed;
		c.speedMin = speedMin;
		c.speedMax = speedMax;
		c.decayBelow = decayBelow;
		c.decayAbove = decayAbove;
		c.startSpeed = startSpeed;
		c.accel = accel;
		c.accelOffset = accelOffset;
		c.wallNear = wallNear;
		c.accelSelf = accelSelf;
		c.accelEnemy = accelEnemy;
		c.accelRim = accelRim;
		c.accelSlingshot = accelSlingshot;
		c.accelTunnel = accelTunnel;
		c.brake = brake;
		c.brakeRefill = brakeRefill;
		c.brakeDeplete = brakeDeplete;
		c.delay = delay;
		c.delayDoublebindBonus = delayDoublebindBonus;
		c.delayTimebased = delayTimebased;
		c.turnSpeedFactor = turnSpeedFactor;
		c.turnMemory = turnMemory;
		c.rubber = rubber;
		c.rubberSpeed = rubberSpeed;
		c.rubberTime = rubberTime;
		c.rubberTimebased = rubberTimebased;
		c.rubberMinDistance = rubberMinDistance;
		c.rubberMinDistanceRatio = rubberMinDistanceRatio;
		c.rubberMinDistanceReservoir = rubberMinDistanceReservoir;
		c.rubberMinDistanceUnprepared = rubberMinDistanceUnprepared;
		c.rubberMinDistancePreparation = rubberMinDistancePreparation;
		c.rubberMinAdjust = rubberMinAdjust;
		c.rubberDelay = rubberDelay;
		c.rubberDelayBonus = rubberDelayBonus;
		c.rubberMalusTurn = rubberMalusTurn;
		c.rubberMalusTime = rubberMalusTime;
		c.boostAccel = boostAccel;
		c.boostDeplete = boostDeplete;
		c.boostRefill = boostRefill;
		c.boostSpeedMax = boostSpeedMax;
		c.terrainRubberFactor = terrainRubberFactor;
		c.substeps = substeps;
		return c;
	}

	/** Fastest the cycle can go (blocks/s): CYCLE_SPEED_MAX if set, else Armagetron's sg_MaxSpeed bound. */
	public double maxSpeed() {
		if (speedMax > 0) {
			return speed * speedMax;
		}
		double wall = accel * (1 / accelOffset - 1 / (wallNear + accelOffset)) * Math.max(accelSelf, Math.max(accelEnemy, accelRim));
		double maxAccel = 2 * wall * Math.max(accelSlingshot, accelTunnel);
		return decayAbove > 0 ? speed + maxAccel / decayAbove : speed * 100;
	}
}
