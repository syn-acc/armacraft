package gl.syn.armacraft.mixin;

import gl.syn.armacraft.ArmaController;
import gl.syn.armacraft.Armacraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional: forward TNT explosions inside the arena to the guest as "explode". */
@Mixin(PrimedTnt.class)
public abstract class PrimedTntMixin {

	@Shadow
	private float explosionPower;

	@Inject(method = "explode", at = @At("HEAD"))
	private void armacraft$forwardExplosion(CallbackInfo ci) {
		PrimedTnt self = (PrimedTnt) (Object) this;
		if (self.level() instanceof ServerLevel level) {
			ArmaController c = Armacraft.controller();
			if (c != null) {
				c.onExplosion(level, self, self.getX(), self.getY(), self.getZ(), this.explosionPower);
			}
		}
	}
}
