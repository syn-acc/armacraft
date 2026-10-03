package gl.syn.armacraft.gametest.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Test-only: ask the renderer whether it would draw the block hit outline this frame. */
@Mixin(GameRenderer.class)
public interface GameRendererInvoker {

	@Invoker("shouldRenderBlockOutline")
	boolean armacraft$shouldRenderBlockOutline();
}
