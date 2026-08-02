package nonamecrackers2.mobbattlemusic.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.command.ServerTimelineMarkerStore;
import nonamecrackers2.mobbattlemusic.command.ServerExternalPlaylistStore;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;
import nonamecrackers2.mobbattlemusic.timeline.MobBattleMusicTimeline;

public record TimelineMarkerHitPacket(ResourceLocation playlist, int entryIndex, String url, TimelineMarker marker,
		int targetEntityId)
{
	private static final Map<String, Long> LAST_MARKER = new ConcurrentHashMap<>();

	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeResourceLocation(this.playlist);
		buffer.writeVarInt(this.entryIndex);
		buffer.writeUtf(this.url, 4096);
		buffer.writeVarLong(this.marker.timeMillis());
		buffer.writeResourceLocation(this.marker.eventId());
		buffer.writeVarInt(this.targetEntityId + 1);
	}

	public static TimelineMarkerHitPacket decode(FriendlyByteBuf buffer)
	{
		return new TimelineMarkerHitPacket(buffer.readResourceLocation(), buffer.readVarInt(), buffer.readUtf(4096),
				new TimelineMarker(buffer.readVarLong(), buffer.readResourceLocation()), buffer.readVarInt() - 1);
	}

	public void handle(Supplier<NetworkEvent.Context> context)
	{
		ServerPlayer player = context.get().getSender();
		if (player != null) {
			long now = System.currentTimeMillis();
			String key = player.getUUID() + "|" + this.playlist + "|" + this.marker.eventId() + "|" +
					this.marker.timeMillis();
			long previous = LAST_MARKER.getOrDefault(key, 0L);
			String serverUrl = ServerExternalPlaylistStore.playlistEntryUrl(player.getServer(), this.playlist,
					this.entryIndex);
			if (now - previous >= 250L && serverUrl != null && ServerTimelineMarkerStore.has(player.getServer(),
					this.playlist, serverUrl, this.marker)) {
				LAST_MARKER.put(key, now);
				Entity target = this.targetEntityId < 0 ? null : player.level().getEntity(this.targetEntityId);
				if (target != null && target.distanceToSqr(player) > 65_536.0D)
					target = null;
				Entity finalTarget = target;
				context.get().enqueueWork(() -> MobBattleMusicTimeline.fireServer(player, finalTarget, this.playlist,
						serverUrl, this.marker));
			}
		}
		context.get().setPacketHandled(true);
	}
}
