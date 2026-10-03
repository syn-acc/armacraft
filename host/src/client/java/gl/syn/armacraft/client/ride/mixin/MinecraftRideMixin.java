package gl.syn.armacraft.client.ride.mixin;

import gl.syn.armacraft.client.ride.RideClientMod;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.3's Friends List ({@code FriendsOverlayScreen}) opens from {@code Minecraft.handleGlobalKeyPress} on
 * {@code options.keyFriends}, default key O (scancode 18), which is one of the user's turn-right keys. The key event
 * never reaches vanilla in cycle mode (KeyboardHandlerRideMixin), and as a second guard the toggle itself is refused
 * while cycle mode is on, also from the pause menu where vanilla allows it.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftRideMixin {

	@Inject(method = "toggleFriendsScreen", at = @At("HEAD"), cancellable = true)
	private void armacraft$noFriendsInCycleMode(CallbackInfoReturnable<Boolean> cir) {
		if (RideClientMod.client() != null && RideClientMod.client().cycleMode()) {
			RideClientMod.friendsBlocked++;
			cir.setReturnValue(false);
		}
	}
}
