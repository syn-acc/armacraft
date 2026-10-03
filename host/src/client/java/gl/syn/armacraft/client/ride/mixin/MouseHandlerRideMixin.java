package gl.syn.armacraft.client.ride.mixin;

import gl.syn.armacraft.client.ride.RideClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cycle mode owns the mouse buttons and wheel too: no attacking, using, picking blocks or hotbar scrolling. Mouse
 * look stays free (onMove is untouched), and a click that only re-grabs the mouse still works.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerRideMixin {

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void armacraft$cycleButtons(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
		if (swallow()) {
			ci.cancel();
		}
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void armacraft$cycleScroll(long handle, double x, double y, CallbackInfo ci) {
		if (swallow()) {
			ci.cancel();
		}
	}

	private static boolean swallow() {
		Minecraft mc = Minecraft.getInstance();
		return RideClientMod.client() != null && RideClientMod.client().cycleMode() && mc.gui.screen() == null && mc.mouseHandler.isMouseGrabbed();
	}
}
