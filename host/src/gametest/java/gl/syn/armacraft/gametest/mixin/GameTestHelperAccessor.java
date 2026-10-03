package gl.syn.armacraft.gametest.mixin;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Test-only: lets a wall-clock timeout fail the test cleanly instead of being retried as "not yet". */
@Mixin(GameTestHelper.class)
public interface GameTestHelperAccessor {

	@Accessor("testInfo")
	GameTestInfo armacraft$testInfo();
}
