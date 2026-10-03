package gl.syn.armacraft.mixin;

import gl.syn.armacraft.ride.RideHooks;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A rider's collision box is narrow ({@code bike_width}, default 0.2 instead of a player's 0.6). The bike is never
 * moved sideways while it rides (its trail has to stay a straight line, as in Armagetron), so it meets 1-wide gaps
 * at whatever lane its last turn left it on; a narrow box passes them unless the lane is within half its width of
 * the gap's edge. Height and eye height stay. Both sides apply it (the server replays the client's moves against
 * its own box); the ride code calls {@code refreshDimensions()} when a ride starts and ends.
 */
@Mixin(Avatar.class)
public abstract class AvatarRideMixin {

	@Inject(method = "getDefaultDimensions", at = @At("RETURN"), cancellable = true)
	private void armacraft$riderWidth(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		Entity self = (Entity) (Object) this;
		if (self.level() == null || !RideHooks.isRiding(self)) {
			return;
		}
		EntityDimensions d = cir.getReturnValue();
		float w = RideHooks.rideWidth();
		if (w < d.width()) {
			cir.setReturnValue(new EntityDimensions(w, d.height(), d.eyeHeight(), d.attachments(), d.fixed()));
		}
	}
}
