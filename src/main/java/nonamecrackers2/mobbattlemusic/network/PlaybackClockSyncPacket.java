package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * S2C: anchor confirmation (AUD-22/K7-B). The anchor is a LOCAL self-anchor,
 * so this packet only confirms that the server saw the playback start report -
 * it carries NO t1/t2/t3/t4 probe timestamps (probing is owned by
 * ClockOffsetProbeResponsePacket) and the client handler NEVER re-anchors on
 * it. The echoed session generation rejects stale confirmations.
 */
public class PlaybackClockSyncPacket
{
	private final String trackId;
	private final long sessionGeneration;

	public PlaybackClockSyncPacket(String trackId, long sessionGeneration)
	{
		this.trackId = trackId;
		this.sessionGeneration = sessionGeneration;
	}

	public String trackId()
	{
		return this.trackId;
	}

	public long sessionGeneration()
	{
		return this.sessionGeneration;
	}

	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeUtf(this.trackId);
		buffer.writeLong(this.sessionGeneration);
	}

	public static PlaybackClockSyncPacket decode(FriendlyByteBuf buffer)
	{
		return new PlaybackClockSyncPacket(buffer.readUtf(), buffer.readLong());
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
