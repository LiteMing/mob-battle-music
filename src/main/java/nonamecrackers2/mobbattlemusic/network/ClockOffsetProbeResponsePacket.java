package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * K7-B: the dedicated S2C clock-probe response. t3 is captured on the server
 * network thread immediately before the actual send; t4 is captured on the
 * client network thread (consumerNetworkThread) BEFORE the client main-thread
 * queue and the sample is fed straight into the thread-safe estimator. The
 * response NEVER calls MarkerClock.realign*() - a probe must not mutate any
 * track anchor. The echoed nonce guards against stale connections/windows.
 */
public class ClockOffsetProbeResponsePacket
{
	private final long probeNonce;
	private final long clientSendEpochMillis;
	private final long serverRecvEpochMillis;
	private final long sendServerEpochMillis;

	public ClockOffsetProbeResponsePacket(long probeNonce, long clientSendEpochMillis,
			long serverRecvEpochMillis, long sendServerEpochMillis)
	{
		this.probeNonce = probeNonce;
		this.clientSendEpochMillis = clientSendEpochMillis;
		this.serverRecvEpochMillis = serverRecvEpochMillis;
		this.sendServerEpochMillis = sendServerEpochMillis;
	}

	public long probeNonce()
	{
		return this.probeNonce;
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
		buffer.writeLong(this.probeNonce);
		buffer.writeLong(this.clientSendEpochMillis);
		buffer.writeLong(this.serverRecvEpochMillis);
		buffer.writeLong(this.sendServerEpochMillis);
	}

	public static ClockOffsetProbeResponsePacket decode(FriendlyByteBuf buffer)
	{
		return new ClockOffsetProbeResponsePacket(buffer.readLong(), buffer.readLong(),
				buffer.readLong(), buffer.readLong());
	}

	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().setPacketHandled(true);
	}
}
