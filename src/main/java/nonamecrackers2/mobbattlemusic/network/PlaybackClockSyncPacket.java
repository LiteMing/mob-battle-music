package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.client.audio.MarkerClock;

/**
 * S2C: server-anchored clock sync (AUD-22). (trackId, startEpochMillis,
 * sendServerEpochMillis) - the client derives the authoritative position and
 * applies AUD-24 correction tiers.
 */
public class PlaybackClockSyncPacket
{
	private final String trackId;
	private final long startEpochMillis;
	private final long sendServerEpochMillis;

	public PlaybackClockSyncPacket(String trackId, long startEpochMillis, long sendServerEpochMillis)
	{
		this.trackId = trackId;
		this.startEpochMillis = startEpochMillis;
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

	public long sendServerEpochMillis()
	{
		return this.sendServerEpochMillis;
	}

	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeUtf(this.trackId);
		buffer.writeLong(this.startEpochMillis);
		buffer.writeLong(this.sendServerEpochMillis);
	}

	public static PlaybackClockSyncPacket decode(FriendlyByteBuf buffer)
	{
		return new PlaybackClockSyncPacket(buffer.readUtf(), buffer.readLong(), buffer.readLong());
	}

	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().enqueueWork(() -> {
			DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
					MarkerClock.realign(this.trackId, this.startEpochMillis, this.sendServerEpochMillis));
		});
		context.get().setPacketHandled(true);
	}
}
