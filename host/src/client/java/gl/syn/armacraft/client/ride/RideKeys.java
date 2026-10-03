package gl.syn.armacraft.client.ride;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * The cycle-mode toggle (R, rebindable in Controls): enters cycle mode through vanilla's key mapping, leaves it as a
 * raw key event inside cycle mode (RideInput). Riding keys themselves are raw key events configured in
 * armacraft-ride.json.
 */
public final class RideKeys {

	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("armacraft", "ride"));

	public static final KeyMapping RIDE = key("ride", InputConstants.KEY_R);

	private RideKeys() {}

	private static KeyMapping key(String name, int code) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.armacraft." + name, InputConstants.Type.KEYBOARD, code, CATEGORY));
	}

	public static void init() {
	}

	/** Forget presses queued before the ride started (vanilla never consumes clicks of movement keys). */
	public static void drain(Minecraft mc) {
		for (KeyMapping k : new KeyMapping[] {mc.options.keyLeft, mc.options.keyRight, mc.options.keyShift}) {
			while (k.consumeClick()) {
				// drain
			}
		}
	}
}
