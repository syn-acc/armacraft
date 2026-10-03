package gl.syn.armacraft.mixin;

import gl.syn.armacraft.ride.RideHooks;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Riders drive on still water. 26.3's {@code LiquidBlock.getCollisionShape} gives a source block a collision shape
 * for a living entity when {@code getLiquidCollisionShape()} is non-empty, the entity is above it, and
 * {@code canStandOnFluid} accepts the fluid with no same fluid above (the Strider/lava mechanism). Returning a full
 * block for riders makes the surface flush with the shore, so the bike never dips. Both sides apply it (client
 * movement and the server's move replay agree); frost walker ice comes on top of this from the server.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityRideMixin {

	@Inject(method = "canStandOnFluid", at = @At("HEAD"), cancellable = true)
	private void armacraft$riderStandsOnWater(FluidState fluid, CallbackInfoReturnable<Boolean> cir) {
		if (fluid.is(FluidTags.WATER) && RideHooks.isRiding((LivingEntity) (Object) this)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "getLiquidCollisionShape", at = @At("HEAD"), cancellable = true)
	private void armacraft$riderWaterShape(CallbackInfoReturnable<VoxelShape> cir) {
		if (RideHooks.isRiding((LivingEntity) (Object) this)) {
			cir.setReturnValue(Shapes.block());
		}
	}
}
