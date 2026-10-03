package gl.syn.armacraft.ride.core;

import java.util.ArrayDeque;

/**
 * Armagetron's cycle movement rules (speed, wall acceleration, brake, turn delay, rubber) as a pure state
 * machine. No Minecraft classes: the caller measures the surroundings ({@link Surroundings}) and moves the
 * player by the returned distance.
 *
 * <p>Ported from armagetronad v0.2.9.3.0 {@code src/tron/gCycleMovement.cpp} (line numbers below refer to that
 * file). Differences, all deliberate:
 * <ul>
 *   <li>Armagetron simulates at frame rate and splits a timestep at events (rubber gets active, rubber runs out,
 *   brake runs dry: the recursive {@code TimestepCore} calls at 3650-3665, 3786-3799, 3943-3966). Here every
 *   20 Hz tick is split into {@link CycleParams#substeps} equal substeps (10 ms by default) instead.</li>
 *   <li>Queued turns run at the start of the first tick where {@code GetNextTurn} has passed (quantised to 50 ms;
 *   CYCLE_DELAY 0.1 s is exactly two ticks).</li>
 *   <li>Network-only terms are dropped: CYCLE_PING_RUBBER (no ping on a local simulation), lag/packet-loss
 *   tolerance, CYCLE_DELAY_BONUS (dedicated-server only), CYCLE_BOOST_* (default 0), tunnel squeezing
 *   (CYCLE_WIDTH default 0), gap detection (CYCLE_RUBBER_MINDISTANCE_GAP default 0).</li>
 * </ul>
 */
public final class CycleMovement {

	private static final double EPS = 1e-9;

	/** Result of one tick. */
	public record Step(double distance, boolean crashed, double rubberSpent) {}

	private CycleParams p;

	private int heading;
	private double speed;          // verletSpeed_
	private double acceleration;
	private double lastTimestep;   // lastTimestep_
	private double rubber;         // rubber used, 0 .. CYCLE_RUBBER
	private double rubberMalus;
	private double brakeReservoir = 1;
	private double brakeUsage;
	private boolean braking;
	/** Not Armagetron: boost reservoir (mirror of the brake). */
	private double boostReservoir = 1;
	private double boostUsage;
	private boolean boosting;
	private double time;           // lastTime
	private double lastTurnTimeLeft = -1e9;
	private double lastTurnTimeRight = -1e9;
	private final ArrayDeque<Integer> pendingTurns = new ArrayDeque<>();
	private double distance;
	private double distanceAtLastTurn;
	private double rubberSpeedFactor = 1;
	private int turns;
	private boolean crashed;
	private double lastSpace = Double.POSITIVE_INFINITY;

	/** A full copy (for planning turns ahead of the physics, e.g. to draw the bike at frame time). */
	public CycleMovement copy() {
		return copyInto(new CycleMovement(p, heading));
	}

	/** Overwrite {@code c} with this state and return it. Allocation-free once {@code c}'s turn queue has grown. */
	public CycleMovement copyInto(CycleMovement c) {
		c.p = p;
		c.heading = heading;
		c.speed = speed;
		c.acceleration = acceleration;
		c.lastTimestep = lastTimestep;
		c.rubber = rubber;
		c.rubberMalus = rubberMalus;
		c.brakeReservoir = brakeReservoir;
		c.brakeUsage = brakeUsage;
		c.braking = braking;
		c.boostReservoir = boostReservoir;
		c.boostUsage = boostUsage;
		c.boosting = boosting;
		c.time = time;
		c.lastTurnTimeLeft = lastTurnTimeLeft;
		c.lastTurnTimeRight = lastTurnTimeRight;
		c.pendingTurns.clear();
		for (int i = 0, n = pendingTurns.size(); i < n; i++) {
			Integer d = pendingTurns.pollFirst(); // rotate instead of iterating: no iterator
			pendingTurns.addLast(d);
			c.pendingTurns.addLast(d);
		}
		c.distance = distance;
		c.distanceAtLastTurn = distanceAtLastTurn;
		c.rubberSpeedFactor = rubberSpeedFactor;
		c.turns = turns;
		c.crashed = crashed;
		c.lastSpace = lastSpace;
		return c;
	}

	/** Planning only (on a {@link #copy}): move the clock forward without simulating. */
	public void skipTo(double t) {
		if (t > time) {
			time = t;
		}
	}

	/** When the first queued turn may run (CYCLE_DELAY after the last turn), +inf if none is queued. */
	public double nextDueTurnTime() {
		return pendingTurns.isEmpty() ? Double.POSITIVE_INFINITY : Math.max(time, nextTurnTime(pendingTurns.peekFirst()));
	}

	public CycleMovement(CycleParams params, int heading) {
		this.p = params;
		this.heading = heading & 3;
		this.speed = params.startSpeed > 0 ? params.startSpeed : params.speed;
	}

	public CycleParams params() {
		return p;
	}

	/** New settings mid-ride (a preset switch): state (speed, rubber used, turn times, queue) carries over. */
	public void setParams(CycleParams params) {
		this.p = params;
	}

	// ---------------------------------------------------------------- turning

	/** gCycleMovement::GetTurnDelay (1068-1078). */
	public double turnDelay() {
		double baseDelay = p.delay;
		double speedFactor = speed / p.speed;
		return baseDelay * Math.pow(speedFactor, p.delayTimebased - 1);
	}

	/** gCycleMovement::GetTurnDelayDb (1080-1090): delay before a second turn in the same direction. */
	public double turnDelayDb() {
		return turnDelay() * p.delayDoublebindBonus;
	}

	/** gCycleMovement::GetNextTurn (1101-1127): earliest time a turn in {@code dir} is allowed. */
	public double nextTurnTime(int dir) {
		double right;
		double left;
		if (dir == 1) {
			right = lastTurnTimeRight + turnDelayDb();
			left = lastTurnTimeLeft + turnDelay();
		} else {
			right = lastTurnTimeLeft + turnDelayDb();
			left = lastTurnTimeRight + turnDelay();
		}
		return Math.max(left, right);
	}

	/**
	 * gCycleMovement::DoTurn (3362-3567): turn now if CYCLE_DELAY allows, else queue it (CYCLE_TURN_MEMORY;
	 * opposite turns beyond the memory cancel). +1 right, -1 left.
	 *
	 * @return true if the heading changed now
	 */
	public boolean requestTurn(int dir) {
		if (crashed) {
			return false;
		}
		dir = dir > 0 ? 1 : -1;
		if (pendingTurns.isEmpty() && nextTurnTime(dir) <= time + EPS) {
			doTurn(dir);
			return true;
		}
		int size = pendingTurns.size();
		if (size <= p.turnMemory) {
			pendingTurns.addLast(dir);
		} else if (pendingTurns.peekLast() != dir) {
			pendingTurns.removeLast(); // opposite turns cancel
		} else {
			pendingTurns.addLast(dir);
		}
		return false;
	}

	/**
	 * gCycleMovement::Timestep (2626-2637): run the first queued turn once its time has come. Call before
	 * measuring the surroundings for {@link #tick}, because the heading may change.
	 *
	 * @return true if the heading changed
	 */
	public boolean processPendingTurns() {
		if (crashed || pendingTurns.isEmpty()) {
			return false;
		}
		int dir = pendingTurns.peekFirst();
		if (nextTurnTime(dir) <= time + EPS) {
			pendingTurns.pollFirst();
			doTurn(dir);
			return true;
		}
		return false;
	}

	private void doTurn(int dir) {
		rubberSpeedFactor = 1;
		turns++;
		accelerationDiscontinuity();
		speed *= p.turnSpeedFactor;      // 3443
		rubberMalus += p.rubberMalusTurn; // 3444
		if (dir == 1) {
			lastTurnTimeRight = time;     // 3537
		} else {
			lastTurnTimeLeft = time;
		}
		heading = Heading.turn(heading, dir);
		distanceAtLastTurn = distance;
		lastSpace = Double.POSITIVE_INFINITY;
	}

	/** gCycleMovement::AccelerationDiscontinuity (2997-3002). */
	private void accelerationDiscontinuity() {
		speed = speed();
		lastTimestep = 0;
	}

	// ---------------------------------------------------------------- per tick

	public void setBraking(boolean on) {
		this.braking = on;
	}

	/** Not Armagetron: hold to accelerate while the boost reservoir lasts. */
	public void setBoosting(boolean on) {
		this.boosting = on;
	}

	/**
	 * Advances the simulation by {@code dt} seconds against fixed surroundings. The space ahead shrinks by the
	 * distance already covered within the tick.
	 */
	public Step tick(double dt, Surroundings env) {
		if (crashed) {
			return new Step(0, true, 0);
		}
		if (env.hold() || !(dt > 0)) {
			time += Math.max(0, dt);
			return new Step(0, false, 0);
		}
		// ~10 ms substeps whatever the length (a tick split at a turn gives short segments)
		int n = Math.max(1, (int) Math.ceil(dt / (0.05 / Math.max(1, p.substeps)) - 1e-9));
		double ts = dt / n;
		double moved = 0;
		double rubberBefore = rubber;
		for (int i = 0; i < n && !crashed; i++) {
			moved += timestepCore(ts, env.spaceAhead() - moved, env);
		}
		return new Step(moved, crashed, Math.max(0, rubber - rubberBefore));
	}

	/** gCycleMovement::TimestepCore (3637-4246), one substep. Returns the distance covered. */
	private double timestepCore(double ts, double rawSpace, Surroundings env) {
		calculateAcceleration(env);
		applyAcceleration(ts);

		double step = speed * ts;
		rubberSpeedFactor = 1;

		// sg_RubberValues (2963-2989) without the ping term
		double rubberGranted = p.rubber;
		double rubberEffectiveness = Math.pow(speed / p.speed, p.rubberTimebased); // 2984
		rubberEffectiveness /= (1 + rubberMalus);
		// not Armagetron: terrain walls get a bigger rubber budget (CycleParams.terrainRubberFactor)
		if (env.frontKind() == Surroundings.Kind.TERRAIN && p.terrainRubberFactor > 0) {
			rubberEffectiveness *= p.terrainRubberFactor;
		}
		// reduce it if the cycle turned recently (3719-3740)
		double delayTime = Math.max(lastTurnTimeRight, lastTurnTimeLeft) + turnDelay() * p.rubberDelay; // 3721
		if (time < delayTime) {
			rubberEffectiveness *= p.rubberDelayBonus;
		}

		// GetMaxSpaceAhead (1720-2012): hit distance minus the stop offset
		double offset = stopDistance(rawSpace, env.wallLength(), rubberEffectiveness);
		double space = rawSpace - offset;
		lastSpace = space;

		if (rubberGranted > rubber && p.rubberSpeed > 0 && step > -EPS && rubberEffectiveness > 0) {
			// 3755-3785
			double beta = ts * p.rubberSpeed;
			double neededSpace;
			double rubberFactor;
			if (beta > .001) {
				rubberFactor = 1 - Math.exp(-beta);
				neededSpace = step / rubberFactor;
			} else {
				rubberFactor = beta;
				neededSpace = speed / p.rubberSpeed;
			}
			if (rubberFactor > .999) {
				rubberFactor = .999;
			}
			if (neededSpace < step * 3) {
				neededSpace = step * 3;
			}
			if (space < neededSpace) {
				// 3923-3985: approach the wall exponentially, pay the difference in rubber
				double rubberStep = space * rubberFactor;
				if (rubberStep > step) {
					rubberStep = step;
				}
				if (step < 0) {
					step = 0;
				}
				double rubberNeeded = Math.max(0, step - rubberStep);
				double rubberAvailable = (rubberGranted - rubber) * rubberEffectiveness; // 3937
				if (rubberNeeded > rubberAvailable) {
					rubberNeeded = rubberAvailable;
				}
				rubber += rubberNeeded / rubberEffectiveness; // 3971
				if (step > 0) {
					rubberSpeedFactor = 1 - rubberNeeded / step; // 3979
				} else {
					rubberSpeedFactor = space / neededSpace;
				}
				if (rubberSpeedFactor < 0) {
					rubberSpeedFactor = 0;
				}
				step -= rubberNeeded;
				if (step < 0) {
					step = 0;
				}
			}
		}

		// Move(): a step through the wall throws gCycleDeath; rubber can still absorb it (4073-4145)
		if (step > rawSpace + 1e-7) {
			rubberSpeedFactor = 0;
			if (rubberEffectiveness <= 0 || step >= (rubberGranted - rubber) * rubberEffectiveness) { // 4080
				rubber = rubberGranted;
				step = Math.max(0, rawSpace);
				crashed = true;
			} else {
				rubber += step / rubberEffectiveness; // 4137: stay where we are
				step = 0;
			}
		}

		// decide over kill (4161-4170); a dead cycle keeps a full rubber meter
		if (rubber > rubberGranted + 1e-12) {
			crashed = true;
		}
		if (crashed) {
			rubber = rubberGranted;
			time += ts;
			distance += step;
			return step;
		}

		// brake reservoir (4175-4176)
		brakeReservoir -= brakeUsage * ts;
		brakeReservoir = Math.max(0, Math.min(1, brakeReservoir));
		boostReservoir = Math.max(0, Math.min(1, boostReservoir - boostUsage * ts));

		// rubber and malus decay (4178-4187)
		if (p.rubberTime > 0) {
			rubber /= (1 + ts / p.rubberTime);
		} else {
			rubber = 0;
		}
		if (p.rubberMalusTime > 0) {
			rubberMalus /= (1 + ts / p.rubberMalusTime);
		} else {
			rubberMalus = 0;
		}
		if (rubber >= rubberGranted) {
			rubber = rubberGranted;
		}

		time += ts;
		distance += step;
		return step;
	}

	/**
	 * The stop offset GetMaxSpaceAhead subtracts from the front hit (1739-1765, 1816, 1967-1975):
	 * CYCLE_RUBBER_MINDISTANCE plus the reservoir, preparation and wall-length terms, capped by MINADJUST.
	 */
	private double stopDistance(double rawSpace, double wallLength, double rubberEffectiveness) {
		double mindistance = p.rubberMinDistance;
		if (p.rubber > 0 && rubberEffectiveness > 0) {
			double rubberUsageSpeed = speed * (1 - rubberSpeedFactor) / rubberEffectiveness;
			double rubberUsed = rubberUsageSpeed * lastTimestep;
			double filling = Math.min(1, (rubber + rubberUsed) / p.rubber);
			mindistance += p.rubberMinDistanceReservoir * (1 - filling);
		}
		if (p.rubberMinDistancePreparation > 0) {
			double badPreparation = p.rubberMinDistancePreparation / (p.rubberMinDistancePreparation + (time - lastTurnTime()));
			mindistance += p.rubberMinDistanceUnprepared * badPreparation;
		}
		double stopDistance = mindistance + p.rubberMinDistanceRatio * wallLength; // 1816
		double maxStop = (distanceSinceLastTurn() + rawSpace) * (1 - p.rubberMinAdjust); // 1972
		if (maxStop < stopDistance) {
			stopDistance = maxStop;
		}
		return Math.max(0, stopDistance);
	}

	/** gCycleMovement::CalculateAcceleration (3013-3261). */
	private void calculateAcceleration(Surroundings env) {
		brakeUsage = 0;
		acceleration = 0;
		if (braking) {
			if (brakeReservoir > 0) {
				brakeUsage = p.brakeDeplete;
				acceleration -= p.brake; // 3040
			} else {
				brakeReservoir = 0;
			}
		} else if (brakeReservoir < 1) {
			brakeUsage = -p.brakeRefill;
		} else {
			brakeReservoir = 1;
		}

		// not Armagetron: boost, the brake mirrored
		boostUsage = 0;
		if (p.boostAccel > 0) {
			if (boosting && boostReservoir > 0) {
				boostUsage = p.boostDeplete;
				acceleration += p.boostAccel;
			} else if (!boosting && boostReservoir < 1) {
				boostUsage = -p.boostRefill;
			}
		}

		double baseSpeed = p.speed;
		if (speed <= baseSpeed) {
			acceleration += (baseSpeed - speed) * p.decayBelow; // 3067
		} else {
			acceleration += (baseSpeed - speed) * p.decayAbove; // 3069
		}

		// wall acceleration from the two diagonal-back sensors (3080-3200)
		double total = 0;
		boolean slingshot = true;
		boolean oneOwnWall = false;
		for (Surroundings.Side s : new Surroundings.Side[] {env.left(), env.right()}) {
			if (s != null && s.hit() && s.clearance() < p.wallNear) {
				double wallAcceleration = p.accel * ((1 / (s.clearance() + p.accelOffset)) - (1 / (p.wallNear + p.accelOffset))); // 3151
				switch (s.kind()) {
					case SELF -> {
						wallAcceleration *= p.accelSelf;
						oneOwnWall = true;
					}
					case ENEMY -> wallAcceleration *= p.accelEnemy;
					case TERRAIN -> wallAcceleration *= p.accelRim;
					default -> {
						wallAcceleration = 0;
						slingshot = false;
					}
				}
				total += wallAcceleration;
			} else {
				slingshot = false;
			}
		}
		if (slingshot) {
			total *= oneOwnWall ? p.accelSlingshot : p.accelTunnel; // 3248-3254
		}
		acceleration += total;
	}

	/** gCycleMovement::ApplyAcceleration (3274-3350): verlet step, exact exponential decay for large steps, clamp. */
	private void applyAcceleration(double dt) {
		double verletTimestep = .5 * (dt + lastTimestep); // 3281
		lastTimestep = dt;
		boolean properDecay = false;
		double maxTimestep = Math.max(verletTimestep, dt);
		if (p.decayBelow * maxTimestep > .1 || p.decayAbove * maxTimestep > .1) {
			double baseSpeed = p.speed;
			double speedDecay = speed < baseSpeed ? p.decayBelow : p.decayAbove;
			if (speedDecay * maxTimestep > .1 && dt > EPS) {
				properDecay = true;
				double decayAcceleration = (baseSpeed - speed) * speedDecay;
				acceleration -= decayAcceleration;
				baseSpeed += acceleration / speedDecay;
				speed = baseSpeed + (speed - baseSpeed) * Math.exp(-speedDecay * verletTimestep);
				acceleration = (baseSpeed - speed) * (1 - Math.exp(-speedDecay * dt * .5)) / (.5 * dt);
			}
		}
		if (!properDecay) {
			speed += acceleration * verletTimestep;
		}
		double minSpeed = p.speed * p.speedMin;
		double maxSpeed = p.speedMax > 0 ? p.speed * p.speedMax : (100 + p.speed) * 100000;
		if (boostUsage > 0 && p.boostSpeedMax > 0 && speed > p.speed * p.boostSpeedMax && acceleration > 0) {
			// boosting never pushes past its own cap (decay brings it back down afterwards)
			speed = Math.max(p.speed * p.boostSpeedMax, speed - acceleration * verletTimestep);
		}
		if (!Double.isFinite(speed)) {
			speed = minSpeed;
			acceleration = 0;
		} else if (speed < minSpeed) {
			speed = minSpeed;
			acceleration = 0; // 3343
		} else if (speed > maxSpeed) {
			speed = maxSpeed;
			acceleration = 0;
		}
	}

	// ---------------------------------------------------------------- state

	/** gCycleMovement::Speed (989-993). */
	public double speed() {
		double ret = speed + .5 * lastTimestep * acceleration;
		return Math.max(0, ret);
	}

	public int heading() {
		return heading;
	}

	public boolean braking() {
		return braking;
	}

	public double brakeReservoir() {
		return brakeReservoir;
	}

	public double boostReservoir() {
		return boostReservoir;
	}

	public boolean boosting() {
		return boosting && boostReservoir > 0 && p.boostAccel > 0;
	}

	/** Rubber used so far (Armagetron's {@code rubber}), 0 = fresh. */
	public double rubberUsed() {
		return rubber;
	}

	/** Rubber left as a fraction 1 (full) .. 0 (crash on the next wall contact). */
	public double rubberLeft() {
		return p.rubber > 0 ? Math.max(0, 1 - rubber / p.rubber) : 0;
	}

	public double rubberSpeedFactor() {
		return rubberSpeedFactor;
	}

	public boolean crashed() {
		return crashed;
	}

	/** Mark crashed from outside (e.g. the server says another rider's trail got us). */
	public void crash() {
		crashed = true;
	}

	public double time() {
		return time;
	}

	public double distance() {
		return distance;
	}

	public double distanceSinceLastTurn() {
		return distance - distanceAtLastTurn;
	}

	public double lastTurnTime() {
		return Math.max(lastTurnTimeLeft, lastTurnTimeRight);
	}

	public int turns() {
		return turns;
	}

	public int pendingTurns() {
		return pendingTurns.size();
	}

	/** Space ahead after the stop offset, as seen in the last substep (diagnostics). */
	public double lastSpace() {
		return lastSpace;
	}

	/** For tests and resync: set the current speed directly. */
	public void setSpeed(double v) {
		speed = v;
		acceleration = 0;
		lastTimestep = 0;
	}
}
