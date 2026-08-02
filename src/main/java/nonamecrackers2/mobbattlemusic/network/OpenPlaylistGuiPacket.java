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
		context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
				() -> MusicPlaylistScreen::openServerEditor));
		context.get().setPacketHandled(true);
	}
}
