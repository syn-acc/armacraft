package gl.syn.armacraft.mixin;

import gl.syn.armacraft.render.EntitySet;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** armacraft display entities are runtime-only: never written to chunk storage. */
@Mixin(Entity.class)
public abstract class EntityMixin {

	@Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
	private void armacraft$neverSave(CallbackInfoReturnable<Boolean> cir) {
		if (((Entity) (Object) this).entityTags().contains(EntitySet.TAG)) {
			cir.setReturnValue(false);
		}
	}
}
