package gl.syn.armacraft.client.mixin;

import gl.syn.armacraft.client.ArenaClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Per-frame update before the camera is set up, and no hand while riding. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@Inject(method = "update", at = @At("HEAD"))
	private void armacraft$frame(DeltaTracker deltaTracker, CallbackInfo ci) {
		ArenaClient client = ArenaClient.get();
		if (client != null) {
			client.onFrame();
		}
	}

	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void armacraft$noHand(CallbackInfo ci) {
		ArenaClient client = ArenaClient.get();
		if (client != null && client.camera().override()) {
			ci.cancel();
		}
	}
}
