package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * C2S: client reports a playback start (AUD-22/CUE-4) with the wall-clock send
 * time (t1) of the report itself, used by the server for the minimal-RTT
 * clock-offset handshake (AUD-24 K5).
 */
public class PlaybackStartReportPacket
{
	private final String trackId;
	private final long clientStartEpochMillis;
	private final long clientSendEpochMillis;

	public PlaybackStartReportPacket(String trackId, long clientStartEpochMillis, long clientSendEpochMillis)
	{
		this.trackId = trackId;
		this.clientStartEpochMillis = clientStartEpochMillis;
		this.clientSendEpochMillis = clientSendEpochMillis;
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

	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeUtf(this.trackId);
		buffer.writeLong(this.clientStartEpochMillis);
		buffer.writeLong(this.clientSendEpochMillis);
	}

	public static PlaybackStartReportPacket decode(FriendlyByteBuf buffer)
	{
		return new PlaybackStartReportPacket(buffer.readUtf(), buffer.readLong(), buffer.readLong());
	}

	public void handle(Supplier<NetworkEvent.Context> context)
	{
		NetworkEvent.Context ctx = context.get();
		ctx.enqueueWork(() -> {
			ServerPlayer player = ctx.getSender();
			if (player != null)
				MobBattleMusicNetwork.sendPlaybackClockSync(player,
						new PlaybackClockSyncPacket(this.trackId, this.clientStartEpochMillis,
								this.clientSendEpochMillis, System.currentTimeMillis(),
								System.currentTimeMillis()));
		});
		ctx.setPacketHandled(true);
	}
}
