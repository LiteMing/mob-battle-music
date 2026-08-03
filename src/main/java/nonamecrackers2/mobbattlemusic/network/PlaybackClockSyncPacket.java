package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.client.audio.MarkerClock;

/**
 * S2C: server clock-sync response (AUD-22/K6-B). Carries the CUE-4 handshake
 * sample: t1 (echoed), t2 (server recv, captured pre-queue), t3 (server
 * send), plus the echoed session generation. The client feeds the estimator
 * and applies a normalized anchor only when the generation matches - a stale
 * response must never overwrite a newer track.
 */
public class PlaybackClockSyncPacket
{
	private final String trackId;
	private final long startEpochMillis;
	private final long clientSendEpochMillis;
	private final long serverRecvEpochMillis;
	private final long sendServerEpochMillis;
	private final long sessionGeneration;

	public PlaybackClockSyncPacket(String trackId, long startEpochMillis, long clientSendEpochMillis,
			long serverRecvEpochMillis, long sendServerEpochMillis, long sessionGeneration)
	{
		this.trackId = trackId;
		this.startEpochMillis = startEpochMillis;
		this.clientSendEpochMillis = clientSendEpochMillis;
		this.serverRecvEpochMillis = serverRecvEpochMillis;
		this.sendServerEpochMillis = sendServerEpochMillis;
		this.sessionGeneration = sessionGeneration;
	}

	public String trackId()
	{
		return this.trackId;
	}

	public long startEpochMillis()
	{
		return this.startEpochMillis;
	}

	public long clientSendEpochMillis()
	{
		return this.clientSendEpochMillis;
	}

	public long serverRecvEpochMillis()
	{
		return this.serverRecvEpochMillis;
	}

	public long sendServerEpochMillis()
	{
		return this.sendServerEpochMillis;
	}

	public long sessionGeneration()
	{
		return this.sessionGeneration;
	}

	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeUtf(this.trackId);
		buffer.writeLong(this.startEpochMillis);
		buffer.writeLong(this.clientSendEpochMillis);
		buffer.writeLong(this.serverRecvEpochMillis);
		buffer.writeLong(this.sendServerEpochMillis);
		buffer.writeLong(this.sessionGeneration);
	}

	public static PlaybackClockSyncPacket decode(FriendlyByteBuf buffer)
	{
		return new PlaybackClockSyncPacket(buffer.readUtf(), buffer.readLong(), buffer.readLong(),
				buffer.readLong(), buffer.readLong(), buffer.readLong());
	}

	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().enqueueWork(() -> {
			DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
					nonamecrackers2.mobbattlemusic.client.audio.WorldPlaybackChannel.handleClockSync(this));
		});
		context.get().setPacketHandled(true);
	}
}
