package nonamecrackers2.mobbattlemusic.network;

import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.client.music.ExternalPlaylistControlClient;

/**
 * K14-B: server-authoritative Cue session sync. The server broadcasts the
 * logical playback session; clients with MBM start/seek/pause/resume/stop
 * their own audio to follow it. Clients WITHOUT MBM never receive this
 * packet (guarded by remoteHasChannel). The marker timeline is advanced on
 * the server regardless of client presence.
 */
public record CueSessionSyncPacket(UUID sessionId, Action action, ResourceLocation playlistId, String trackKey,
		String url, long revision, long startServerTick, long logicalPositionMillis)
{
	public enum Action
	{
		START,
		PAUSE,
		RESUME,
		STOP,
		SNAPSHOT
	}

	public static CueSessionSyncPacket start(UUID sessionId, ResourceLocation playlistId, String trackKey,
			String url, long revision, long startServerTick, long logicalPositionMillis)
	{
		return new CueSessionSyncPacket(sessionId, Action.START, playlistId, trackKey, url, revision,
				startServerTick, logicalPositionMillis);
	}

	public static CueSessionSyncPacket pause(UUID sessionId)
	{
		return new CueSessionSyncPacket(sessionId, Action.PAUSE, null, "", "", 0L, 0L, 0L);
	}

	public static CueSessionSyncPacket resume(UUID sessionId)
	{
		return new CueSessionSyncPacket(sessionId, Action.RESUME, null, "", "", 0L, 0L, 0L);
	}

	public static CueSessionSyncPacket stop(UUID sessionId)
	{
		return new CueSessionSyncPacket(sessionId, Action.STOP, null, "", "", 0L, 0L, 0L);
	}

	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeUUID(this.sessionId);
		buffer.writeEnum(this.action);
		boolean hasTarget = this.playlistId != null;
		buffer.writeBoolean(hasTarget);
		if (hasTarget) {
			buffer.writeResourceLocation(this.playlistId);
			buffer.writeUtf(this.trackKey, 512);
			buffer.writeUtf(this.url, 4096);
			buffer.writeVarLong(this.revision);
			buffer.writeVarLong(this.startServerTick);
		}
		buffer.writeVarLong(this.logicalPositionMillis);
	}

	public static CueSessionSyncPacket decode(FriendlyByteBuf buffer)
	{
		UUID sessionId = buffer.readUUID();
		Action action = buffer.readEnum(Action.class);
		boolean hasTarget = buffer.readBoolean();
		ResourceLocation playlistId = null;
		String trackKey = "";
		String url = "";
		long revision = 0L;
		long startServerTick = 0L;
		if (hasTarget) {
			playlistId = buffer.readResourceLocation();
			trackKey = buffer.readUtf(512);
			url = buffer.readUtf(4096);
			revision = buffer.readVarLong();
			startServerTick = buffer.readVarLong();
		}
		long logicalPositionMillis = buffer.readVarLong();
		return new CueSessionSyncPacket(sessionId, action, playlistId, trackKey, url, revision, startServerTick,
				logicalPositionMillis);
	}

	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().enqueueWork(() -> ExternalPlaylistControlClient.handleCueSessionSync(this));
		context.get().setPacketHandled(true);
	}
}
