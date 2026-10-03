package gl.syn.armacraft.client.ride.mixin;

import gl.syn.armacraft.client.ride.RideClientMod;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Riding keys come from the raw key event (one press = one turn) and are swallowed while riding. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerRideMixin {

	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void armacraft$rideKeys(long handle, int action, KeyEvent event, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (handle == 0L || handle != mc.getWindow().handle() || RideClientMod.client() == null) {
			return;
		}
		if (RideClientMod.client().onRawKey(action, event)) {
			ci.cancel();
		}
	}
}
