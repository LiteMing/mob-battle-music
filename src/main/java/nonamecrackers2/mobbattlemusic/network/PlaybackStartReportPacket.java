package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * C2S: client reports a playback start (or a periodic resync, AUD-24 every
 * 5 seconds) so the server can anchor the authoritative clock (AUD-22).
 */
public class PlaybackStartReportPacket
{
	private final String trackId;
	private final long clientStartEpochMillis;

	public PlaybackStartReportPacket(String trackId, long clientStartEpochMillis)
	{
		this.trackId = trackId;
		this.clientStartEpochMillis = clientStartEpochMillis;
	}

	public String trackId()
	{
		return this.trackId;
	}

	public long clientStartEpochMillis()
	{
		return this.clientStartEpochMillis;
	}

	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeUtf(this.trackId);
		buffer.writeLong(this.clientStartEpochMillis);
	}

	public static PlaybackStartReportPacket decode(FriendlyByteBuf buffer)
	{
		return new PlaybackStartReportPacket(buffer.readUtf(), buffer.readLong());
	}

	public void handle(Supplier<NetworkEvent.Context> context)
	{
		NetworkEvent.Context ctx = context.get();
		ctx.enqueueWork(() -> {
			ServerPlayer player = ctx.getSender();
			if (player != null)
				MobBattleMusicNetwork.sendPlaybackClockSync(player,
						new PlaybackClockSyncPacket(this.trackId, this.clientStartEpochMillis,
								System.currentTimeMillis()));
		});
		ctx.setPacketHandled(true);
	}
}
