package gl.syn.armacraft.client.ride;

import com.mojang.blaze3d.platform.InputConstants;
import gl.syn.armacraft.ride.RideConfig;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.input.KeyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cycle-mode keyboard. While cycle mode is on and no screen is open, every key event
 * ({@code KeyboardHandler.keyPress}, see KeyboardHandlerRideMixin) comes here first and vanilla never sees it, except
 * the pass-through keys: Escape (pause menu), the chat and command keys (T, /), Enter (opens chat, as Armagetron's
 * CHAT), the player list, screenshot and fullscreen keys. So no inventory, drop, hand swap, hotbar, sprint, sneak,
 * social or Friends List screen can be reached from the keyboard. Turn keys are one turn per press (repeats
 * ignored); held keys (brake, boost, glances) are tracked press to release.
 */
public final class RideInput {

	private static final Logger LOG = LoggerFactory.getLogger("armacraft/ride-input");

	public enum Action { TURN_LEFT, TURN_RIGHT, BRAKE, BRAKE_TOGGLE, ACCELERATE, GLANCE_LEFT, GLANCE_RIGHT, GLANCE_BACK, EXIT }

	private final Map<Integer, Action> bindings = new HashMap<>();
	private final Map<Action, java.util.List<String>> names = new EnumMap<>(Action.class);
	private final Map<Action, Set<Integer>> held = new EnumMap<>(Action.class);
	/** A turn key press: direction (-1 left, +1 right), its physics time (see {@link #clock}) and wall-clock nanos. */
	public record TurnPress(int dir, double time, long nanos) {}

	private final ArrayDeque<TurnPress> turns = new ArrayDeque<>();
	/** Physics time of "now" for stamping key presses (set by the ride client); NaN = unknown. */
	private java.util.function.DoubleSupplier clock = () -> Double.NaN;
	private final Map<Action, Integer> lastKey = new EnumMap<>(Action.class);
	private final Map<Action, Long> lastNanos = new EnumMap<>(Action.class);
	private boolean exit;
	private boolean braking;
	private double doublebindTime = -10;
	/** Diagnostics: key events taken by cycle mode, of which bound to a ride action / swallowed unbound. */
	public int events;
	public int swallowed;
	public int ignoredDoublebinds;

	public RideInput() {
		for (Action a : Action.values()) {
			held.put(a, new HashSet<>());
		}
		configure(RideConfig.builtinKeys(), -10);
	}

	public void configure(RideConfig.Keys keys, double doublebindTime) {
		this.doublebindTime = doublebindTime;
		bindings.clear();
		names.clear();
		bind(keys.turnLeft, Action.TURN_LEFT);
		bind(keys.turnRight, Action.TURN_RIGHT);
		bind(keys.brake, Action.BRAKE);
		bind(keys.brakeToggle, Action.BRAKE_TOGGLE);
		bind(keys.accelerate, Action.ACCELERATE);
		bind(keys.glanceLeft, Action.GLANCE_LEFT);
		bind(keys.glanceRight, Action.GLANCE_RIGHT);
		bind(keys.glanceBack, Action.GLANCE_BACK);
		bind(keys.exit, Action.EXIT);
	}

	private void bind(List<String> names, Action a) {
		if (names == null) {
			return;
		}
		for (String n : names) {
			try {
				InputConstants.Key k = InputConstants.getKey(n);
				if (k != null && k.getValue() != InputConstants.UNKNOWN.getValue()) {
					bindings.put(k.getValue(), a);
					this.names.computeIfAbsent(a, x -> new java.util.ArrayList<>()).add(n.replace("key.keyboard.", ""));
				} else {
					LOG.warn("armacraft-ride.json keys: unknown key name {}", n);
				}
			} catch (RuntimeException e) {
				LOG.warn("armacraft-ride.json keys: bad key name {} ({})", n, e.getMessage());
			}
		}
	}

	/** Binds as "action=keys" (startup log). */
	public String describe() {
		StringBuilder sb = new StringBuilder();
		names.forEach((a, l) -> sb.append(a.name().toLowerCase()).append('=').append(String.join(",", l)).append("  "));
		return sb.toString().strip();
	}

	/**
	 * A raw key event while cycle mode is on and no screen is open.
	 *
	 * @param action 1 press, 2 repeat, 0 release
	 * @return true to swallow it (vanilla never sees it)
	 */
	public boolean onKey(int action, KeyEvent event) {
		// the deploy key (R, rebindable in Controls) toggles back out; swallowed, so vanilla never sees the press and
		// its KeyMapping gets no click that would re-enter; repeats and the release do nothing
		if (RideKeys.RIDE.matches(event)) {
			if (action == 1) {
				exit = true;
			}
			return true;
		}
		Action a = bindings.get(event.key());
		if (a == null) {
			Minecraft mc = Minecraft.getInstance();
			if (event.isEscape() || mc.options.keyChat.matches(event) || mc.options.keyCommand.matches(event) || mc.options.keyPlayerList.matches(event)
				|| mc.options.keyScreenshot.matches(event) || mc.options.keyFullscreen.matches(event)) {
				return false;
			}
			if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
				if (action == 1 && mc.player != null) {
					mc.gui.openChatScreen(ChatComponent.ChatMethod.MESSAGE);
				}
				return true;
			}
			swallowed++;
			return true;
		}
		events++;
		if (action == 2) {
			return true; // key repeat: never a new turn
		}
		boolean press = action == 1;
		switch (a) {
			case TURN_LEFT, TURN_RIGHT -> {
				if (press) {
					if (isDoublebind(a, event.key())) {
						ignoredDoublebinds++;
					} else {
						TurnPress tp = new TurnPress(a == Action.TURN_LEFT ? -1 : 1, clock.getAsDouble(), System.nanoTime());
						turns.addLast(tp);
						if (pressListener != null) {
							pressListener.accept(tp);
						}
					}
				}
			}
			case EXIT -> {
				if (press) {
					exit = true;
				}
			}
			// gCycle::Act (gCycle.cpp:3662-3681): CYCLE_BRAKE sets braking to the key state, CYCLE_BRAKE_TOGGLE flips it
			case BRAKE -> {
				braking = press;
				track(a, event.key(), press);
			}
			case BRAKE_TOGGLE -> {
				if (press) {
					braking = !braking;
				}
			}
			default -> track(a, event.key(), press);
		}
		return true;
	}

	private void track(Action a, int key, boolean press) {
		if (press) {
			held.get(a).add(key);
		} else {
			held.get(a).remove(key);
		}
	}

	/** uBind::IsDoubleBind (ui/uInput.cpp:283-295): another key for the same action within DOUBLEBIND_TIME is ignored. */
	private boolean isDoublebind(Action a, int key) {
		long now = System.nanoTime();
		Integer lk = lastKey.get(a);
		Long lt = lastNanos.get(a);
		boolean ret = doublebindTime > 0 && lk != null && lk != key && lt != null && (now - lt) / 1e9 < doublebindTime;
		lastKey.put(a, key);
		lastNanos.put(a, now);
		return ret;
	}

	public boolean held(Action a) {
		return !held.get(a).isEmpty();
	}

	/** Armagetron's single braking flag: set by CYCLE_BRAKE, flipped by CYCLE_BRAKE_TOGGLE. */
	public boolean braking() {
		return braking;
	}

	/** A new cycle does not brake (gCycle.cpp:5946). */
	public void resetBrake() {
		braking = false;
	}

	/** Turns pressed since the last call, in order: -1 left, +1 right. */
	public int pollTurn() {
		TurnPress t = turns.pollFirst();
		return t == null ? 0 : t.dir();
	}

	public TurnPress peekTurnPress() {
		return turns.peekFirst();
	}

	public TurnPress pollTurnPress() {
		return turns.pollFirst();
	}

	/** Number of presses not applied yet. */
	public int pendingTurnCount() {
		return turns.size();
	}

	/**
	 * The presses not applied yet, oldest first, into {@code times}/{@code dirs} (up to their length).
	 * Allocation-free (called every frame while a press waits for its tick).
	 * @return how many were written
	 */
	public int pendingTurnPresses(double[] times, int[] dirs) {
		int k = 0;
		for (int i = 0, n = turns.size(); i < n; i++) {
			TurnPress t = turns.pollFirst(); // rotate instead of iterating: no iterator
			turns.addLast(t);
			if (k < times.length) {
				times[k] = t.time();
				dirs[k] = t.dir();
				k++;
			}
		}
		return k;
	}

	public void setClock(java.util.function.DoubleSupplier clock) {
		this.clock = clock;
	}

	private java.util.function.Consumer<TurnPress> pressListener;

	/** Diagnostics hook: called on every accepted turn press. */
	public void setPressListener(java.util.function.Consumer<TurnPress> l) {
		this.pressListener = l;
	}

	public boolean pollExit() {
		boolean e = exit;
		exit = false;
		return e;
	}

	/** Drop held ride keys (their releases may not reach us, e.g. while a screen is open). */
	public void resetHeld() {
		for (Set<Integer> s : held.values()) {
			s.clear();
		}
		braking = false;
	}

	/** Entering or leaving cycle mode: nothing carries over in either direction. */
	public void reset() {
		turns.clear();
		exit = false;
		resetHeld();
		KeyMapping.releaseAll();
	}

	public boolean isBound(int key) {
		return bindings.containsKey(key);
	}

	static boolean screenOpen() {
		return Minecraft.getInstance().gui.screen() != null;
	}
}
