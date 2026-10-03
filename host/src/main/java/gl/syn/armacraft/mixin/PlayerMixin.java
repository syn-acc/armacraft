package gl.syn.armacraft.mixin;

import gl.syn.armacraft.ArmaController;
import gl.syn.armacraft.Armacraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shift normally ends spectating (ServerPlayer.tick: camera != this && wantsToStopRiding() -> setCamera(this))
 * and dismounts. While a bridge player is riding/death-cam, ignore it.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {

	@Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
	private void armacraft$lockCamera(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ServerPlayer sp) {
			ArmaController c = Armacraft.controller();
			if (c != null && c.isCameraLocked(sp)) {
				cir.setReturnValue(false);
			}
		}
	}
}
