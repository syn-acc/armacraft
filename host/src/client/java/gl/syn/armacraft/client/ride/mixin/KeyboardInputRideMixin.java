package gl.syn.armacraft.client.ride.mixin;

import gl.syn.armacraft.client.ride.RideClient;
import gl.syn.armacraft.client.ride.RideClientMod;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In cycle mode the keyboard belongs to the light cycle (RideInput), so the player itself gets no walking, jumping,
 * sneaking or sprinting input, even from a key vanilla still thinks is down.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputRideMixin {

	@Inject(method = "tick", at = @At("TAIL"))
	private void armacraft$noWalkingWhileRiding(CallbackInfo ci) {
		RideClient rc = RideClientMod.client();
		if (rc != null && rc.cycleMode()) {
			ClientInput self = (ClientInput) (Object) this;
			self.keyPresses = Input.EMPTY;
			((ClientInputAccessor) self).armacraft$setMoveVector(Vec2.ZERO);
		}
	}
}
