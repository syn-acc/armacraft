package gl.syn.armacraft.client.ride.mixin;

import gl.syn.armacraft.client.ride.RideClient;
import gl.syn.armacraft.client.ride.RideClientMod;
import gl.syn.armacraft.client.ride.RideFrameCost;
import gl.syn.armacraft.client.ride.RideTrace;
import gl.syn.armacraft.client.ride.api.ChaseCamera;
import gl.syn.armacraft.client.ride.render.DeathCam;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While riding, the camera pose comes from the ride {@link ChaseCamera} (26.3: {@code Camera.alignWithEntity} sets
 * position and rotation every frame from the camera entity; TAIL overrides both), detached (no first-person hand),
 * and vanilla view bobbing is off: {@code GameRenderer.bobView} shakes the whole view by walk distance, which at
 * riding speed is a ~5 Hz wobble of up to 0.1 blocks.
 */
@Mixin(Camera.class)
public abstract class CameraRideMixin {

	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void armacraft$chaseCam(float partialTicks, CallbackInfo ci) {
		RideClient rc = RideClientMod.client();
		Minecraft mc = Minecraft.getInstance();
		if (rc == null || !rc.cycleMode() || mc.player == null || mc.getCameraEntity() != mc.player) {
			return;
		}
		if (!rc.riding()) {
			// cycle mode, dead (or waiting for the first ride): the death cam
			DeathCam dc = RideClientMod.deathCam();
			Vec3 c = rc.crashPos() != null ? rc.crashPos() : mc.player.getPosition(partialTicks);
			dc.update(c, mc.player.getViewYRot(partialTicks), mc.player.getViewXRot(partialTicks), rc.deadSeconds() + partialTicks / 20.0);
			this.setRotation(dc.yaw(), dc.pitch());
			this.setPosition(dc.eye().x, dc.eye().y, dc.eye().z);
			this.detached = true;
			return;
		}
		RideFrameCost.frameStart();
		long t0 = RideFrameCost.start();
		RideClient.FrameState f = rc.frame(mc.player, partialTicks);
		RideFrameCost.add(t0, RideFrameCost.POSE);
		long t1 = RideFrameCost.start();
		ChaseCamera cam = RideClientMod.visuals().camera();
		cam.update(f.dt(), rc.cameraTarget(mc.player, partialTicks));
		Vec3 eye = cam.eye();
		this.setRotation(cam.yaw(), cam.pitch());
		this.setPosition(eye.x, eye.y, eye.z);
		this.detached = true;
		RideFrameCost.add(t1, RideFrameCost.CAMERA);
		if (RideTrace.enabled) {
			var avatar = mc.player.avatarState();
			RideTrace.add(new RideTrace.Frame(f.t(), RideTrace.phase, partialTicks, eye.x, eye.y, eye.z, cam.yaw(), cam.pitch(), f.pos().x, f.pos().y,
				f.pos().z, f.bodyYaw(), avatar.getInterpolatedBob(partialTicks), avatar.getBackwardsInterpolatedWalkDistance(partialTicks),
				net.minecraft.util.Mth.lerp(partialTicks, mc.player.yo, mc.player.getY()), mc.player.onGround()));
		}
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void armacraft$noViewBob(CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci) {
		RideClient rc = RideClientMod.client();
		if (rc != null && rc.cycleMode() && RideClientMod.suppressViewBob) {
			cameraState.entityRenderState.bob = 0f;
		}
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void armacraft$chaseFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		RideClient rc = RideClientMod.client();
		if (rc != null && rc.riding()) {
			float fov = RideClientMod.visuals().camera().fov();
			if (!Float.isNaN(fov)) {
				cir.setReturnValue(fov);
			}
		}
	}
}
