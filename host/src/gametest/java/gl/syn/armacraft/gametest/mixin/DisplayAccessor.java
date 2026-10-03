package gl.syn.armacraft.gametest.mixin;

import com.mojang.math.Transformation;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Test-only: read back a display entity's synced transformation. */
@Mixin(Display.class)
public interface DisplayAccessor {

	@Invoker("createTransformation")
	static Transformation armacraft$createTransformation(SynchedEntityData data) {
		throw new AssertionError();
	}
}
