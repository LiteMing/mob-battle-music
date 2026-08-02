package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.command.ServerExternalPlaylistStore;

public class ServerExternalPlaylistSyncRequestPacket
{
	public void encode(FriendlyByteBuf buffer)
	{
	}
	
	public static ServerExternalPlaylistSyncRequestPacket decode(FriendlyByteBuf buffer)
	{
		return new ServerExternalPlaylistSyncRequestPacket();
	}
	
	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().enqueueWork(() -> {
			ServerPlayer sender = context.get().getSender();
			if (sender != null)
				ServerExternalPlaylistStore.sync(sender);
		});
		context.get().setPacketHandled(true);
	}
}
