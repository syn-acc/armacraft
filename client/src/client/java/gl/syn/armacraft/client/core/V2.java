package gl.syn.armacraft.client.core;

/** Minimal immutable 2D vector with Armagetron eCoord semantics. */
public record V2(double x, double y) {
	public static final V2 ZERO = new V2(0, 0);

	public V2 add(V2 o) {
		return new V2(x + o.x, y + o.y);
	}

	public V2 sub(V2 o) {
		return new V2(x - o.x, y - o.y);
	}

	public V2 mul(double s) {
		return new V2(x * s, y * s);
	}

	public double dot(V2 o) {
		return x * o.x + y * o.y;
	}

	/** z component of the 3D cross product. */
	public double cross(V2 o) {
		return x * o.y - y * o.x;
	}

	public double normSq() {
		return x * x + y * y;
	}

	public double norm() {
		return Math.sqrt(normSq());
	}

	public V2 normalized() {
		double n = norm();
		return n > 0 ? new V2(x / n, y / n) : this;
	}

	/** eCoord::Turn(a, b): complex multiplication by (a + ib). */
	public V2 turn(double a, double b) {
		return new V2(x * a - y * b, y * a + x * b);
	}

	public V2 turn(V2 o) {
		return turn(o.x, o.y);
	}

	/** eCoord::Conj. */
	public V2 conj() {
		return new V2(x, -y);
	}

	public static V2 lerp(V2 a, V2 b, double t) {
		return new V2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
	}

	public boolean finite() {
		return Double.isFinite(x) && Double.isFinite(y);
	}

	public double dist(V2 o) {
		return sub(o).norm();
	}
}
