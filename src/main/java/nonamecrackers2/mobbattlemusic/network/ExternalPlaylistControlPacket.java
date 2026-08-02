package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.client.music.ExternalPlaylistControlClient;

public class ExternalPlaylistControlPacket
{
	public enum Action
	{
		SET,
		RANDOM_NEXT,
		CLEAR,
		PLAY_SELECTION,
		PLAY_URL,
		STOP
	}
	
	private final ResourceLocation playlistId;
	private final Action action;
	private final String selection;
	
	public ExternalPlaylistControlPacket(ResourceLocation playlistId, Action action, String selection)
	{
		this.playlistId = playlistId;
		this.action = action;
		this.selection = selection;
	}
	
	public ResourceLocation playlistId()
	{
		return this.playlistId;
	}
	
	public Action action()
	{
		return this.action;
	}
	
	public String selection()
	{
		return this.selection;
	}
	
	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeResourceLocation(this.playlistId);
		buffer.writeEnum(this.action);
		buffer.writeUtf(this.selection);
	}
	
	public static ExternalPlaylistControlPacket decode(FriendlyByteBuf buffer)
	{
		return new ExternalPlaylistControlPacket(buffer.readResourceLocation(), buffer.readEnum(Action.class), buffer.readUtf());
	}
	
	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().enqueueWork(() -> {
			DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ExternalPlaylistControlClient.handle(this));
		});
		context.get().setPacketHandled(true);
	}
}
