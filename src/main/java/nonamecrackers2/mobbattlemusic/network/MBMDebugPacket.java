package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.client.command.MobBattleMusicClientCommands;

/**
 * K16-K: probe action carried from the server-side /mbmplaylist debug subtree
 * to the client. All probe state (MarkerClock, ProbeRing, playback channel,
 * session) is client-local, so the server command merely forwards which probe
 * action to run. Mirrors the old client-only /mbm debug subtree, now reachable
 * through /mbmplaylist debug so there is no client/server literal clash with
 * the bare /mbmplaylist (which opens the GUI).
 */
public class MBMDebugPacket
{
	public static final int ACTION_SESSION = 0;
	public static final int ACTION_INJECT_DRIFT = 1;
	public static final int ACTION_DUMP = 2;

	private final int action;
	private final double value;

	public MBMDebugPacket(int action, double value)
	{
		this.action = action;
		this.value = value;
	}

	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeInt(this.action);
		buffer.writeDouble(this.value);
	}

	public static MBMDebugPacket decode(FriendlyByteBuf buffer)
	{
		return new MBMDebugPacket(buffer.readInt(), buffer.readDouble());
	}

	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
				() -> () -> MobBattleMusicClientCommands.runProbe(this.action, this.value)));
		context.get().setPacketHandled(true);
	}
}
