package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * C2S: client reports a playback start / resume (AUD-22) with a session
 * generation. K7-B: this is a PURE anchor notification - it is NOT an offset
 * probe (the dedicated ClockOffsetProbeRequestPacket owns calibration, so no
 * t1/t2/t3/t4 fields live here). The server answers with the anchor
 * confirmation PlaybackClockSyncPacket.
 */
public class PlaybackStartReportPacket
{
	private final String trackId;
	private final long clientStartEpochMillis;
	private final long sessionGeneration;

	public PlaybackStartReportPacket(String trackId, long clientStartEpochMillis, long sessionGeneration)
	{
		this.trackId = trackId;
		this.clientStartEpochMillis = clientStartEpochMillis;
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

	public long sessionGeneration()
	{
		return this.sessionGeneration;
	}

	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeUtf(this.trackId);
		buffer.writeLong(this.clientStartEpochMillis);
		buffer.writeLong(this.sessionGeneration);
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
						new PlaybackClockSyncPacket(this.trackId, this.sessionGeneration));
		});
		ctx.setPacketHandled(true);
	}
}
