package nonamecrackers2.mobbattlemusic.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.client.util.AggressiveEntityStateClient;

public class AggressiveEntityStatePacket
{
	private final Map<UUID, Long> entityStartTicks;
	
	public AggressiveEntityStatePacket(Map<UUID, Long> entityStartTicks)
	{
		this.entityStartTicks = Map.copyOf(entityStartTicks);
	}
	
	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeVarInt(this.entityStartTicks.size());
		for (Map.Entry<UUID, Long> entry : this.entityStartTicks.entrySet()) {
			UUID uuid = entry.getKey();
			buffer.writeUUID(uuid);
			buffer.writeVarLong(entry.getValue());
		}
	}
	
	public static AggressiveEntityStatePacket decode(FriendlyByteBuf buffer)
	{
		int count = buffer.readVarInt();
		Map<UUID, Long> starts = new LinkedHashMap<>();
		for (int i = 0; i < count; i++)
			starts.put(buffer.readUUID(), buffer.readVarLong());
		return new AggressiveEntityStatePacket(starts);
	}
	
	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().enqueueWork(() -> {
			DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> AggressiveEntityStateClient.apply(this.entityStartTicks));
		});
		context.get().setPacketHandled(true);
	}
}
