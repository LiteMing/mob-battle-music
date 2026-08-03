package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * K7-B: the dedicated C2S clock probe - the playback start/resume packets are
 * no longer used as offset probes. t1 is captured on the client immediately
 * before the actual send. Carries the current connection-level probe nonce so
 * a stale connection's or stale window's response can never enter the current
 * estimator.
 */
public class ClockOffsetProbeRequestPacket
{
	private final long probeNonce;
	private final long clientSendEpochMillis;

	public ClockOffsetProbeRequestPacket(long probeNonce, long clientSendEpochMillis)
	{
		this.probeNonce = probeNonce;
		this.clientSendEpochMillis = clientSendEpochMillis;
	}

	public long probeNonce()
	{
		return this.probeNonce;
	}

	public long clientSendEpochMillis()
	{
		return this.clientSendEpochMillis;
	}

	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeLong(this.probeNonce);
		buffer.writeLong(this.clientSendEpochMillis);
	}

	public static ClockOffsetProbeRequestPacket decode(FriendlyByteBuf buffer)
	{
		return new ClockOffsetProbeRequestPacket(buffer.readLong(), buffer.readLong());
	}

	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().setPacketHandled(true);
	}
}
