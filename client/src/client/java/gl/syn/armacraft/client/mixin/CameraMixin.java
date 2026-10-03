package gl.syn.armacraft.client.mixin;

import gl.syn.armacraft.client.ArenaClient;
import gl.syn.armacraft.client.cam.CameraController;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While riding, the camera pose and field of view come from the Armagetron camera. */
@Mixin(Camera.class)
public abstract class CameraMixin {

	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void armacraft$override(float partialTicks, CallbackInfo ci) {
		ArenaClient client = ArenaClient.get();
		if (client == null) {
			return;
		}
		CameraController cc = client.camera();
		if (cc.override()) {
			this.setRotation(cc.yaw(), cc.pitch());
			this.setPosition(cc.x(), cc.y(), cc.z());
			this.detached = false;
		}
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void armacraft$fov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		ArenaClient client = ArenaClient.get();
		if (client != null && client.camera().override()) {
			cir.setReturnValue(client.camera().fov());
		}
	}
}
