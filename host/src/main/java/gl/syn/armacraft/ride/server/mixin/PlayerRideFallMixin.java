package gl.syn.armacraft.ride.server.mixin;

import gl.syn.armacraft.ride.server.RideServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No fall damage while riding or during the post-ride grace. HEAD of {@code Player.causeFallDamage}, so the fall
 * sound and damage are both skipped; {@code Entity.checkFallDamage} still resets the fall distance on landing.
 */
@Mixin(Player.class)
public abstract class PlayerRideFallMixin {

	@Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
	private void armacraftRide$noFallDamage(double fallDistance, float damageModifier, DamageSource source,
		CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ServerPlayer sp && RideServer.shouldCancelFallDamage(sp)) {
			cir.setReturnValue(false);
		}
	}
}
