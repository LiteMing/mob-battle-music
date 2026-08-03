package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * C2S: client reports a playback start / resume (AUD-22) with the wall-clock
 * send time (t1) and a session generation, used by the server for the
 * minimal-RTT clock-offset handshake (AUD-24/K6-B). Registered on the network
 * thread (consumerNetworkThread) so the server-receive moment t2 is captured
 * BEFORE the server main-thread queue.
 */
public class PlaybackStartReportPacket
{
	private final String trackId;
	private final long clientStartEpochMillis;
	private final long clientSendEpochMillis;
	private final long sessionGeneration;

	public PlaybackStartReportPacket(String trackId, long clientStartEpochMillis, long clientSendEpochMillis,
			long sessionGeneration)
	{
		this.trackId = trackId;
		this.clientStartEpochMillis = clientStartEpochMillis;
		this.clientSendEpochMillis = clientSendEpochMillis;
		this.sessionGeneration = sessionGeneration;
	}

	public String trackId()
	{
		return this.trackId;
	}

	public long clientStartEpochMillis()
	{
		return this.clientStartEpochMillis;
	}

	public long clientSendEpochMillis()
	{
		return this.clientSendEpochMillis;
	}

	public long sessionGeneration()
	{
		return this.sessionGeneration;
	}

	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeUtf(this.trackId);
		buffer.writeLong(this.clientStartEpochMillis);
		buffer.writeLong(this.clientSendEpochMillis);
		buffer.writeLong(this.sessionGeneration);
	}

	public static PlaybackStartReportPacket decode(FriendlyByteBuf buffer)
	{
		return new PlaybackStartReportPacket(buffer.readUtf(), buffer.readLong(), buffer.readLong(),
				buffer.readLong());
	}

	public void handle(Supplier<NetworkEvent.Context> context)
	{
		// K6-B: t2 is captured on the NETWORK thread, before the server
		// main-thread queue - the queueing delay Q never enters the offset
		long serverRecvEpochMillis = System.currentTimeMillis();
		NetworkEvent.Context ctx = context.get();
		ctx.enqueueWork(() -> {
			ServerPlayer player = ctx.getSender();
			if (player != null)
				MobBattleMusicNetwork.sendPlaybackClockSync(player,
						new PlaybackClockSyncPacket(this.trackId, this.clientStartEpochMillis,
								this.clientSendEpochMillis, serverRecvEpochMillis,
								System.currentTimeMillis(), this.sessionGeneration));
		});
		ctx.setPacketHandled(true);
	}
}
