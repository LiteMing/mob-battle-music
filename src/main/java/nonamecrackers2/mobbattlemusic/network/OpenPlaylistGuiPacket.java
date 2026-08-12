package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.client.gui.MusicPlaylistScreen;

public class OpenPlaylistGuiPacket
{
	public void encode(FriendlyByteBuf buffer)
	{
	}
	
	public static OpenPlaylistGuiPacket decode(FriendlyByteBuf buffer)
	{
		return new OpenPlaylistGuiPacket();
	}
	
	public void handle(Supplier<NetworkEvent.Context> context)
	{
		// K16-K: the server-open GUI starts in the LOCAL (client-side) editor
		// so permission-less players can configure their own client music;
		// the SERVER editor remains permission-gated inside the GUI
		context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
				() -> MusicPlaylistScreen::openLocalEditor));
		context.get().setPacketHandled(true);
	}
}
