package gl.syn.armacraft.client.mixin;

import gl.syn.armacraft.client.input.ArmaKeys;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Riding keys are handled on the key event itself (edge triggered, no tick quantisation). */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void armacraft$keyPress(long handle, int action, KeyEvent event, CallbackInfo ci) {
		if (handle == 0L || handle != Minecraft.getInstance().getWindow().handle()) {
			return;
		}
		if (ArmaKeys.onKey(action, event)) {
			ci.cancel();
		}
	}
}
