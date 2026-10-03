package gl.syn.armacraft.client.mixin;

import gl.syn.armacraft.client.ArenaClient;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hide the server's block_display fallback inside the arena while this renderer draws it. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void armacraft$hideFallback(E entity, Frustum culler, double camX, double camY, double camZ, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
		if (!(entity instanceof Display.BlockDisplay)) {
			return;
		}
		ArenaClient client = ArenaClient.get();
		if (client == null || !client.config().render.hideFallbackDisplays || !client.active()) {
			return;
		}
		double[] b = client.mcBounds();
		if (b == null) {
			return;
		}
		double x = entity.getX(), y = entity.getY(), z = entity.getZ();
		if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) {
			client.countHiddenDisplay();
			cir.setReturnValue(false);
		}
	}
}
