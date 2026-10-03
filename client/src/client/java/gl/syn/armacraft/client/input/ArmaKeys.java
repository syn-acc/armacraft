package gl.syn.armacraft.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import gl.syn.armacraft.client.ArenaClient;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;

/**
 * Riding controls, handled straight from the key event (KeyboardHandler.keyPress mixin) so a turn is sent the
 * moment the key goes down instead of on the next 20 Hz tick. Defaults follow the user's spec plus
 * Armagetron's keys_wasd.cfg for view switching (v, c) and glancing (q, e, w).
 */
public final class ArmaKeys {

	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("armacraft_client", "riding"));

	public static final KeyMapping TURN_LEFT = key("turn_left", InputConstants.KEY_A);
	public static final KeyMapping TURN_LEFT_ALT = key("turn_left_alt", InputConstants.KEY_LEFT);
	public static final KeyMapping TURN_RIGHT = key("turn_right", InputConstants.KEY_D);
	public static final KeyMapping TURN_RIGHT_ALT = key("turn_right_alt", InputConstants.KEY_RIGHT);
	public static final KeyMapping BRAKE = key("brake", InputConstants.KEY_S);
	public static final KeyMapping BRAKE_ALT = key("brake_alt", InputConstants.KEY_DOWN);
	public static final KeyMapping BRAKE_ALT2 = key("brake_alt2", InputConstants.KEY_SPACE);
	public static final KeyMapping SWITCH_VIEW = key("switch_view", InputConstants.KEY_V);
	public static final KeyMapping GLANCE_LEFT = key("glance_left", InputConstants.KEY_Q);
	public static final KeyMapping GLANCE_RIGHT = key("glance_right", InputConstants.KEY_E);
	public static final KeyMapping GLANCE_BACK = key("glance_back", InputConstants.KEY_W);
	/**
	 * Arena join/leave. Not J: J (like I K L O) turns right in the rider's Armagetron keymap, which armacraft's cycle
	 * mode imports. N is in no ride bind and unbound in vanilla.
	 */
	public static final KeyMapping JOIN = key("arena_join", InputConstants.KEY_N); // renamed from "join" (J) so a saved J in options.txt no longer applies

	private static final boolean[] brakeDown = new boolean[3];

	private ArmaKeys() {
	}

	private static KeyMapping key(String name, int code) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.armacraft_client." + name, InputConstants.Type.KEYBOARD, code, CATEGORY));
	}

	public static void init() {
	}

	/**
	 * @param action 1 press, 2 repeat, 0 release
	 * @return true to swallow the event (Minecraft never sees it)
	 */
	public static boolean onKey(int action, KeyEvent event) {
		Minecraft mc = Minecraft.getInstance();
		ArenaClient client = ArenaClient.get();
		if (client == null || mc.gui.screen() != null) {
			return false;
		}
		// never take a key from armacraft's cycle mode (its binds are the rider's Armagetron keymap), and never when
		// the arena renderer is switched off
		if ("off".equals(client.config().mode) || hostCycleMode()) {
			return false;
		}
		if (JOIN.matches(event)) {
			if (action == 1) {
				if (client.joinWanted()) {
					client.leave();
				} else {
					client.join();
				}
			}
			return action != 0;
		}
		if (!client.riding()) {
			return false;
		}
		boolean alive = client.ownAlive();
		boolean press = action == 1;
		boolean release = action == 0;

		// brakes: always track releases so a held brake can never stick
		KeyMapping[] brakes = {BRAKE, BRAKE_ALT, BRAKE_ALT2};
		for (int i = 0; i < brakes.length; i++) {
			if (brakes[i].matches(event)) {
				if (release && brakeDown[i]) {
					brakeDown[i] = false;
					if (!brakeDown[0] && !brakeDown[1] && !brakeDown[2]) {
						client.brakeKey(false);
					}
					return false;
				}
				if (alive) {
					if (press) {
						boolean wasDown = brakeDown[0] || brakeDown[1] || brakeDown[2];
						brakeDown[i] = true;
						if (!wasDown) {
							client.brakeKey(true);
						}
					}
					return !release;
				}
				return false;
			}
		}

		if (SWITCH_VIEW.matches(event)) {
			if (press) {
				if (client.camera().view() == gl.syn.armacraft.client.cam.CameraController.View.CHASE && alive) {
					client.camera().switchMode();
				} else {
					client.camera().toggleFree();
				}
			}
			return !release;
		}

		if (alive) {
			if (TURN_LEFT.matches(event) || TURN_LEFT_ALT.matches(event)) {
				if (press) {
					client.turnKey(-1);
				}
				return !release;
			}
			if (TURN_RIGHT.matches(event) || TURN_RIGHT_ALT.matches(event)) {
				if (press) {
					client.turnKey(1);
				}
				return !release;
			}
			if (GLANCE_LEFT.matches(event)) {
				client.camera().arma().glanceLeft = !release;
				return !release;
			}
			if (GLANCE_RIGHT.matches(event)) {
				client.camera().arma().glanceRight = !release;
				return !release;
			}
			if (GLANCE_BACK.matches(event)) {
				client.camera().arma().glanceBack = !release;
				return !release;
			}
		} else {
			// spectating: arrows switch the followed cycle (Armagetron turns switch the camera centre when dead)
			if (TURN_LEFT_ALT.matches(event)) {
				if (press) {
					client.camera().cycleFollow(client, -1);
				}
				return !release;
			}
			if (TURN_RIGHT_ALT.matches(event)) {
				if (press) {
					client.camera().cycleFollow(client, 1);
				}
				return !release;
			}
			client.camera().arma().glanceLeft = false;
			client.camera().arma().glanceRight = false;
			client.camera().arma().glanceBack = false;
		}
		return false;
	}

	private static java.lang.reflect.Method rideClient, cycleMode;
	private static boolean rideLookupDone;

	/**
	 * armacraft's ride/cycle mode is on: our ride HUD or camera were driven by it in the last quarter second, or the
	 * host reports cycle mode (looked up reflectively: not part of the ride-visuals api, absent in older hosts).
	 */
	public static boolean hostCycleMode() {
		if (gl.syn.armacraft.client.ridekit.ArmaRideVisuals.HUD.active() || gl.syn.armacraft.client.ridekit.ArmaRideVisuals.CAMERA.active()) {
			return true;
		}
		try {
			if (!rideLookupDone) {
				rideLookupDone = true;
				Class<?> mod = Class.forName("gl.syn.armacraft.client.ride.RideClientMod");
				rideClient = mod.getMethod("client");
				cycleMode = rideClient.getReturnType().getMethod("cycleMode");
			}
			if (rideClient != null && cycleMode != null) {
				Object rc = rideClient.invoke(null);
				return rc != null && Boolean.TRUE.equals(cycleMode.invoke(rc));
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			rideClient = null;
			cycleMode = null;
		}
		return false;
	}

	public static void releaseAll() {
		java.util.Arrays.fill(brakeDown, false);
	}
}
