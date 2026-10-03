package gl.syn.armacraft.client.cam;

import gl.syn.armacraft.client.ArenaClient;
import gl.syn.armacraft.client.core.ArenaMapping;
import gl.syn.armacraft.client.core.ArenaModel;
import gl.syn.armacraft.client.core.ArmaCam;
import gl.syn.armacraft.client.core.V2;
import gl.syn.armacraft.client.kit.BikeAnimator;
import gl.syn.armacraft.client.kit.ChaseCam;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides what the Minecraft camera shows while riding and computes the pose every frame:
 * <ul>
 *   <li>own cycle alive: Armagetron custom (chase) or in-cycle camera ({@link ArmaCam});</li>
 *   <li>own cycle just died: keep watching the explosion for {@code camera.death_hold} seconds;</li>
 *   <li>afterwards until respawn: free spectator camera (mouse look, WASD/space/shift, sprint = faster),
 *       or follow another cycle with the left/right arrows like Armagetron's spectator camera;</li>
 *   <li>joined but not spawned yet: follow an alive cycle.</li>
 * </ul>
 */
public final class CameraController {

	public enum View { NONE, CHASE, FREE }

	private final ChaseCam chase = new ChaseCam();
	private final ArmaCam arma = chase.raw();
	private View view = View.NONE;
	private boolean wantFree;
	private Integer followId;
	private boolean freeInit;
	private double fx, fy, fz;
	private Integer watchedId;

	// output pose (Minecraft)
	private double x, y, z;
	private float yaw, pitch, fov;
	// output pose (Armagetron) for sounds / rim lowering
	private double[] armaEye;

	public ArmaCam arma() {
		return arma;
	}

	public ChaseCam chase() {
		return chase;
	}

	public boolean override() {
		return view != View.NONE;
	}

	public View view() {
		return view;
	}

	public double x() {
		return x;
	}

	public double y() {
		return y;
	}

	public double z() {
		return z;
	}

	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return pitch;
	}

	public float fov() {
		return fov;
	}

	/** {eyeX, eyeY, eyeZ, lookX, lookY} in Armagetron coordinates, or null. */
	public double[] armaEye() {
		return armaEye;
	}

	public Integer watchedId() {
		return watchedId;
	}

	public boolean inCycle() {
		return view == View.CHASE && arma.mode == ArmaCam.Mode.IN;
	}

	public void toggleFree() {
		wantFree = !wantFree;
		if (!wantFree) {
			freeInit = false;
		}
	}

	public void switchMode() {
		arma.switchMode();
	}

	/** Spectating: follow the next/previous alive cycle (Armagetron SwitchCenter). */
	public void cycleFollow(ArenaClient client, int d) {
		List<ArenaModel.Cycle> alive = new ArrayList<>();
		for (ArenaModel.Cycle c : client.model().cycles()) {
			if (c.alive) {
				alive.add(c);
			}
		}
		if (alive.isEmpty()) {
			return;
		}
		alive.sort((a, b) -> Integer.compare(a.id, b.id));
		int idx = 0;
		for (int i = 0; i < alive.size(); i++) {
			if (followId != null && alive.get(i).id == followId) {
				idx = Math.floorMod(i + d, alive.size());
				break;
			}
		}
		followId = alive.get(idx).id;
		wantFree = false;
	}

	public void reset() {
		view = View.NONE;
		wantFree = false;
		followId = null;
		freeInit = false;
		armaEye = null;
		watchedId = null;
	}

	public void update(ArenaClient client, double ts) {
		if (!client.riding()) {
			view = View.NONE;
			armaEye = null;
			watchedId = null;
			freeInit = false;
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		ArenaMapping map = client.mapping();
		double t = client.gameTime();
		ArenaModel.Cycle own = client.ownCycle();
		ArenaModel.Cycle center = null;
		V2 centerPos = null;

		if (own != null && own.alive) {
			center = own;
			wantFree = false;
			followId = null;
		} else if (own != null && Double.isFinite(own.deathTime) && t - own.deathTime < client.config().camera.deathHold) {
			center = own;
		} else if (!wantFree) {
			ArenaModel.Cycle f = followId != null ? client.model().cycle(followId) : null;
			if (f == null || !f.alive) {
				f = null;
				if (own == null || !Double.isFinite(own.deathTime)) {
					// waiting for the first spawn: watch something alive
					for (ArenaModel.Cycle c : client.model().cycles()) {
						if (c.alive) {
							f = c;
							break;
						}
					}
				}
				followId = f != null ? f.id : null;
			}
			center = f;
			if (center == null && own != null) {
				wantFree = true;   // own cycle dead and nothing chosen: free spectator camera until respawn
			}
		}

		float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
		chase.fovDeg = client.config().camera.fov;
		fov = chase.verticalFov(aspect);

		if (center != null) {
			centerPos = center.alive ? client.renderPos(center) : (center.deathPos != null ? center.deathPos : client.renderPos(center));
			V2 dir = center.anim.dir;
			V2 lastDir = client.lastDrive(center);
			Vec3 target = new Vec3(map.mcX(centerPos.x()), map.mcY(0), map.mcZ(centerPos.y()));
			chase.update(ts, target, BikeAnimator.yawFromHeading(dir), BikeAnimator.yawFromHeading(lastDir), center.speed, map.scale(), t);
			Vec3 e = chase.eye();
			x = e.x;
			y = e.y;
			z = e.z;
			yaw = chase.yaw();
			pitch = chase.pitch();
			armaEye = new double[] {map.arenaX(x), map.arenaY(z), map.arenaZ(y), arma.lookX, arma.lookY};
			watchedId = center.id;
			view = View.CHASE;
			freeInit = false;
			return;
		}

		// free spectator camera (Minecraft-style flight)
		LocalPlayer player = mc.player;
		if (!freeInit) {
			if (view == View.CHASE) {
				fx = x;
				fy = y;
				fz = z;
			} else {
				double[] b = client.model().bounds();
				double cxA = b != null ? (b[0] + b[2]) / 2 : 0, cyA = b != null ? (b[1] + b[3]) / 2 : 0;
				fx = map.mcX(cxA);
				fy = map.mcY(40);
				fz = map.mcZ(cyA - 60);
				yaw = 180;
				pitch = 30;
			}
			if (player != null) {
				player.setYRot(yaw);
				player.setXRot(pitch);
				player.yRotO = yaw;
				player.xRotO = pitch;
			}
			freeInit = true;
		}
		if (player != null) {
			yaw = player.getYRot();
			pitch = player.getXRot();
		}
		if (mc.gui.screen() == null) {
			var o = mc.options;
			double fwd = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
			double strafe = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
			double up = (o.keyJump.isDown() ? 1 : 0) - (o.keyShift.isDown() ? 1 : 0);
			double speed = client.config().camera.freeSpeed * (o.keySprint.isDown() ? 3 : 1) * Math.min(ts, 0.1);
			double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
			// Minecraft forward vector for yaw/pitch
			double lx = -Math.sin(yr) * Math.cos(pr), ly = -Math.sin(pr), lz = Math.cos(yr) * Math.cos(pr);
			double sxv = Math.cos(yr), szv = Math.sin(yr);   // left vector (yaw + 90)
			fx += (lx * fwd + sxv * strafe) * speed;
			fy += (ly * fwd + up) * speed;
			fz += (lz * fwd + szv * strafe) * speed;
		}
		x = fx;
		y = fy;
		z = fz;
		double ex = map.arenaX(x), ey = map.arenaY(z), ez = map.arenaZ(y);
		double yr = Math.toRadians(yaw);
		armaEye = new double[] {ex, ey, ez, -Math.sin(yr), -Math.cos(yr)};
		watchedId = null;
		view = View.FREE;
	}
}
