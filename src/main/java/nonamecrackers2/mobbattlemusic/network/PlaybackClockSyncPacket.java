package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.client.audio.MarkerClock;

/**
 * S2C: server-anchored clock sync (AUD-22/CUE-4). Carries the CUE-4 handshake
 * sample: t1 (client send, echoed), t2 (server receive), t3 (server send) -
 * the client computes the clock offset and keeps the lowest-RTT sample.
 * (trackId, startEpochMillis[client domain], clientSendEpochMillis,
 * serverRecvEpochMillis, sendServerEpochMillis)
 */
public class PlaybackClockSyncPacket
{
	private final String trackId;
	private final long startEpochMillis;
	private final long clientSendEpochMillis;
	private final long serverRecvEpochMillis;
	private final long sendServerEpochMillis;

	public PlaybackClockSyncPacket(String trackId, long startEpochMillis, long clientSendEpochMillis,
			long serverRecvEpochMillis, long sendServerEpochMillis)
	{
		this.trackId = trackId;
		this.startEpochMillis = startEpochMillis;
		this.clientSendEpochMillis = clientSendEpochMillis;
		this.serverRecvEpochMillis = serverRecvEpochMillis;
		this.sendServerEpochMillis = sendServerEpochMillis;
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

	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeUtf(this.trackId);
		buffer.writeLong(this.startEpochMillis);
		buffer.writeLong(this.clientSendEpochMillis);
		buffer.writeLong(this.serverRecvEpochMillis);
		buffer.writeLong(this.sendServerEpochMillis);
	}

	public static PlaybackClockSyncPacket decode(FriendlyByteBuf buffer)
	{
		return new PlaybackClockSyncPacket(buffer.readUtf(), buffer.readLong(), buffer.readLong(),
				buffer.readLong(), buffer.readLong());
	}

	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().enqueueWork(() -> {
			DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
					MarkerClock.realign(this.trackId, this.startEpochMillis, this.sendServerEpochMillis,
							this.clientSendEpochMillis, this.serverRecvEpochMillis));
		});
		context.get().setPacketHandled(true);
	}
}
