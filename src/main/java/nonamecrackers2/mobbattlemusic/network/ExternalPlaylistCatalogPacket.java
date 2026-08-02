package nonamecrackers2.mobbattlemusic.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.command.ExternalPlaylistCatalogServer;

public class ExternalPlaylistCatalogPacket
{
	private final List<Playlist> playlists;
	
	public ExternalPlaylistCatalogPacket(List<Playlist> playlists)
	{
		this.playlists = List.copyOf(playlists);
	}
	
	public List<Playlist> playlists()
	{
		return this.playlists;
	}
	
	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeVarInt(this.playlists.size());
		for (Playlist playlist : this.playlists) {
			buffer.writeResourceLocation(playlist.configLocation());
			buffer.writeResourceLocation(playlist.trackLocation());
			buffer.writeVarInt(playlist.entries().size());
			for (Entry entry : playlist.entries()) {
				buffer.writeUtf(entry.id());
				buffer.writeUtf(entry.name());
			}
		}
	}
	
	public static ExternalPlaylistCatalogPacket decode(FriendlyByteBuf buffer)
	{
		int playlistCount = buffer.readVarInt();
		List<Playlist> playlists = new ArrayList<>(playlistCount);
		for (int i = 0; i < playlistCount; i++) {
			ResourceLocation configLocation = buffer.readResourceLocation();
			ResourceLocation trackLocation = buffer.readResourceLocation();
			int entryCount = buffer.readVarInt();
			List<Entry> entries = new ArrayList<>(entryCount);
			for (int j = 0; j < entryCount; j++)
				entries.add(new Entry(buffer.readUtf(), buffer.readUtf()));
			playlists.add(new Playlist(configLocation, trackLocation, entries));
		}
		return new ExternalPlaylistCatalogPacket(playlists);
	}
	
	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().enqueueWork(() -> {
			ServerPlayer sender = context.get().getSender();
			if (sender != null)
				ExternalPlaylistCatalogServer.update(sender, this.playlists);
		});
		context.get().setPacketHandled(true);
	}
	
	public static record Playlist(ResourceLocation configLocation, ResourceLocation trackLocation, List<Entry> entries)
	{
		public Playlist
		{
			entries = List.copyOf(entries);
		}
		
		public boolean matches(ResourceLocation id)
		{
			return this.configLocation.equals(id) || this.trackLocation.equals(id);
		}
	}
	
	public static record Entry(String id, String name) {}
}
