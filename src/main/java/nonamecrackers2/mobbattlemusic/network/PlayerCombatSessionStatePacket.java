package nonamecrackers2.mobbattlemusic.network;

import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.client.util.PlayerCombatSessionClient;

public record PlayerCombatSessionStatePacket(UUID opponent, long startTick)
{
	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeBoolean(this.opponent != null);
		if (this.opponent != null)
			buffer.writeUUID(this.opponent);
		buffer.writeVarLong(Math.max(0L, this.startTick));
	}

	public static PlayerCombatSessionStatePacket decode(FriendlyByteBuf buffer)
	{
		UUID opponent = buffer.readBoolean() ? buffer.readUUID() : null;
		long startTick = buffer.readVarLong();
		return new PlayerCombatSessionStatePacket(opponent, opponent == null ? -1L : startTick);
	}

	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
				() -> () -> PlayerCombatSessionClient.apply(this.opponent, this.startTick)));
		context.get().setPacketHandled(true);
	}
}
