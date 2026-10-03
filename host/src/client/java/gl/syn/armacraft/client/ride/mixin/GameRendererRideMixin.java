package gl.syn.armacraft.client.ride.mixin;

import gl.syn.armacraft.client.ride.RideClientMod;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No first-person hand or held item and no hit outline in cycle mode. 26.3's {@code GameRenderer.renderItemInHand}
 * checks the options' camera type (first person), not the camera's detached flag the chase cam sets, so it has to be
 * told; {@code shouldRenderBlockOutline} gates {@code LevelRenderer.submitBlockOutline} (the black wireframe around
 * the targeted block; 26.3 draws no box for a targeted entity).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererRideMixin {

	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void armacraft$noHandInCycleMode(CallbackInfo ci) {
		if (RideClientMod.client() != null && RideClientMod.client().cycleMode()) {
			RideClientMod.handHidden++;
			ci.cancel();
		}
	}

	@Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
	private void armacraft$noOutlineInCycleMode(CallbackInfoReturnable<Boolean> cir) {
		if (RideClientMod.client() != null && RideClientMod.client().cycleMode()) {
			RideClientMod.outlineHidden++;
			cir.setReturnValue(false);
		}
	}
}
