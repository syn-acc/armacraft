package gl.syn.armacraft.client.core;

/**
 * Visual animation state of one cycle, replicating {@code gCycle::TimestepCore} (src/tron/gCycle.cpp,
 * v0.2.9.3.0) client-side:
 *
 * <ul>
 *   <li>Wheels: {@code rotate(rotationFrontWheel, 2*speed*animts/.43)}, rear with {@code .73}.</li>
 *   <li>Body direction {@code dir} chases the driving direction:
 *       {@code dir += dirDrive*animts*speed*3}, clamped to {@code |dir-dirDrive|^2 <= .8}, normalised.</li>
 *   <li>Lean ("skew"): damped ODE {@code skewDot -= 128*(skew+lr/2)*animts; skewDot /= 1+24*animts},
 *       driven by wall sensors 45 degrees left/right of the heading (lean away from close walls) and
 *       {@code skewDot += 4*d} on every turn (d=+1 right, -1 left), clamped to +-0.5.</li>
 * </ul>
 * {@code animts} is clamped to 0.2 s like the original.
 */
public final class CycleAnim {

	/** gCycle.cpp: {@code REAL extension = .25} (raised to the sparks distance, also .25 by default). */
	public static final double EXTENSION = .25;

	public V2 dir = new V2(1, 0);
	public double skew, skewDot;
	public V2 rotFront = new V2(1, 0);
	public V2 rotRear = new V2(1, 0);
	/** Set when the grind conditions that make Armagetron spawn sparks were met this step. */
	public boolean sparked;

	public void reset(V2 dirDrive) {
		dir = dirDrive.normalized();
		skew = skewDot = 0;
		rotFront = new V2(1, 0);
		rotRear = new V2(1, 0);
	}

	/** gCycle.cpp {@code static inline void rotate(eCoord &r, REAL angle)}. */
	static V2 rotate(V2 r, double angle) {
		double x = r.x();
		double nx = r.x() + r.y() * angle;
		double ny = r.y() - x * angle;
		return new V2(nx, ny).normalized();
	}

	/** Turn impulse applied in gCycle::Turn / sync handling. */
	public void turnImpulse(int d) {
		skewDot += 4 * d;
	}

	/**
	 * Advance by {@code animts} seconds.
	 *
	 * @param dirDrive driving direction (unit)
	 * @param speed    cycle speed (units/s)
	 * @param alive    whether lean and sensors apply
	 * @param flHit    left-front sensor hit along dirDrive.Turn(1,1), in multiples of that vector (>= EXTENSION when clear)
	 * @param frHit    right-front sensor hit along dirDrive.Turn(1,-1)
	 */
	public void step(double animts, V2 dirDrive, double speed, boolean alive, double flHit, double frHit) {
		step(animts, dirDrive, speed, speed, alive, flHit, frHit);
	}

	/**
	 * @param speed      Armagetron speed (units/s): the body swing towards dirDrive
	 * @param wheelSpeed speed in the model's own units (units/s) for wheel roll, so the wheels do not slip when the
	 *                   model is drawn at a different scale than the physics runs
	 */
	public void step(double animts, V2 dirDrive, double speed, double wheelSpeed, boolean alive, double flHit, double frHit) {
		if (!Double.isFinite(skew)) {
			skew = 0;
		}
		if (!Double.isFinite(skewDot)) {
			skewDot = 0;
		}
		if (animts < 0 || !Double.isFinite(animts)) {
			animts = 0;
		}
		if (animts > .2) {
			animts = .2;
		}
		sparked = false;

		rotFront = rotate(rotFront, 2 * wheelSpeed * animts / .43);
		rotRear = rotate(rotRear, 2 * wheelSpeed * animts / .73);

		// animate cycle direction
		{
			V2 d = dir.add(dirDrive.mul(animts * speed * 3));
			V2 dist = d.sub(dirDrive);
			double distSq = dist.normSq();
			final double maxDist = .8;
			if (distSq > maxDist) {
				d = dirDrive.add(dist.mul(1 / Math.sqrt(distSq / maxDist)));
			}
			if (d.normSq() > 0) {
				dir = d.normalized();
			}
		}

		if (alive) {
			double fl = Math.min(flHit, EXTENSION);
			double fr = Math.min(frHit, EXTENSION);
			double lr = (fl - fr) / EXTENSION;

			final double skewrelax = 24;
			skewDot -= 128 * (skew + lr / 2) * animts;
			skewDot /= 1 + skewrelax * animts;
			if (skew * skewDot > 4) {
				skewDot = 4 / skew;
			}
			skew += skewDot * animts;

			double fac = 0.5;
			if (skew > fr * fac) {
				skew = fr * fac;
			}
			if (skew < -fl * fac) {
				skew = -fl * fac;
			}

			if (Math.abs(skew) < Math.abs(lr * .8)) {
				skewDot -= lr * 1000 * animts;
				sparked = animts > 0;
			}
			if (Math.abs(skew) < Math.abs(lr * .9)) {
				skewDot -= lr * 100 * animts;
			}
		}

		if (skew > .5) {
			skew = .5;
			skewDot = 0;
		}
		if (skew < -.5) {
			skew = -.5;
			skewDot = 0;
		}
	}
}
