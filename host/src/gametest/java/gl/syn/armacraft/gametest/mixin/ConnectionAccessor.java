package gl.syn.armacraft.gametest.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Test-only: the netty channel of a mock player's connection (an EmbeddedChannel), for RelayProbe. */
@Mixin(Connection.class)
public interface ConnectionAccessor {

	@Accessor("channel")
	Channel armacraft$channel();
}
