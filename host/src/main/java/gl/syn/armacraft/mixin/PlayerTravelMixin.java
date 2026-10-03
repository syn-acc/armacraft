package gl.syn.armacraft.mixin;

import gl.syn.armacraft.ride.RideHooks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the local player rides, the client's ride controller replaces vanilla walking: {@code Player.travel} is
 * called from {@code LivingEntity.aiStep} (after input and jumping, before block effects), which is exactly where
 * the bike moves itself with {@code Entity.move}. The hook is installed by the client entrypoint; on a dedicated
 * server it is null and this is a no-op.
 */
@Mixin(Player.class)
public abstract class PlayerTravelMixin {

	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void armacraft$rideTravel(Vec3 input, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (!self.level().isClientSide()) {
			return;
		}
		RideHooks.TravelOverride o = RideHooks.clientTravel();
		if (o != null && o.travel(self, input)) {
			ci.cancel();
		}
	}
}
