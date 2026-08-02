package nonamecrackers2.mobbattlemusic.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.client.music.ExternalPlaylistControlClient;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;

public class ServerExternalPlaylistSyncPacket
{
	private final List<TrackDefinition> tracks;
	
	public ServerExternalPlaylistSyncPacket(List<TrackDefinition> tracks)
	{
		this.tracks = List.copyOf(tracks);
	}
	
	public List<TrackDefinition> tracks()
	{
		return this.tracks;
	}
	
	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeVarInt(this.tracks.size());
		for (TrackDefinition track : this.tracks) {
			buffer.writeResourceLocation(track.configLocation());
			buffer.writeUtf(track.scene());
			buffer.writeVarInt(track.priority());
			buffer.writeVarInt(track.fadeTime());
			buffer.writeUtf(track.selectionMode());
			buffer.writeVarInt(track.idleConditions().size());
			for (IdleCondition condition : track.idleConditions()) {
				buffer.writeUtf(condition.type());
				buffer.writeUtf(condition.argument());
				buffer.writeBoolean(condition.inverted());
			}
			buffer.writeVarInt(track.idleIntervalSeconds());
			buffer.writeVarInt(track.entries().size());
			for (Entry entry : track.entries()) {
				buffer.writeUtf(entry.id());
				buffer.writeUtf(entry.name());
				buffer.writeUtf(entry.url());
				buffer.writeVarInt(entry.markers().size());
				for (TimelineMarker marker : entry.markers()) {
					buffer.writeVarLong(marker.timeMillis());
					buffer.writeResourceLocation(marker.eventId());
				}
			}
		}
	}
	
	public static ServerExternalPlaylistSyncPacket decode(FriendlyByteBuf buffer)
	{
		int trackCount = buffer.readVarInt();
		List<TrackDefinition> tracks = new ArrayList<>(trackCount);
		for (int i = 0; i < trackCount; i++) {
			ResourceLocation configLocation = buffer.readResourceLocation();
			String scene = buffer.readUtf();
			int priority = buffer.readVarInt();
			int fadeTime = buffer.readVarInt();
			String selectionMode = buffer.readUtf();
			int conditionCount = buffer.readVarInt();
			List<IdleCondition> idleConditions = new ArrayList<>(conditionCount);
			for (int j = 0; j < conditionCount; j++)
				idleConditions.add(new IdleCondition(buffer.readUtf(), buffer.readUtf(), buffer.readBoolean()));
			int idleIntervalSeconds = buffer.readVarInt();
			int entryCount = buffer.readVarInt();
			List<Entry> entries = new ArrayList<>(entryCount);
			for (int j = 0; j < entryCount; j++) {
				String id = buffer.readUtf();
				String name = buffer.readUtf();
				String url = buffer.readUtf();
				int markerCount = buffer.readVarInt();
				List<TimelineMarker> markers = new ArrayList<>(markerCount);
				for (int k = 0; k < markerCount; k++)
					markers.add(new TimelineMarker(buffer.readVarLong(), buffer.readResourceLocation()));
				entries.add(new Entry(id, name, url, markers));
			}
			tracks.add(new TrackDefinition(configLocation, scene, priority, fadeTime, selectionMode, idleConditions,
					idleIntervalSeconds, entries));
		}
		return new ServerExternalPlaylistSyncPacket(tracks);
	}
	
	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().enqueueWork(() -> {
			DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ExternalPlaylistControlClient.handleServerSync(this));
		});
		context.get().setPacketHandled(true);
	}
	
	public static record TrackDefinition(ResourceLocation configLocation, String scene, int priority, int fadeTime,
			String selectionMode, List<IdleCondition> idleConditions, int idleIntervalSeconds, List<Entry> entries)
	{
		public TrackDefinition
		{
			idleConditions = List.copyOf(idleConditions);
			entries = List.copyOf(entries);
		}
	}
	
	public static record Entry(String id, String name, String url, List<TimelineMarker> markers)
	{
		public Entry
		{
			markers = List.copyOf(markers);
		}

		public Entry(String id, String name, String url)
		{
			this(id, name, url, List.of());
		}
	}
}
