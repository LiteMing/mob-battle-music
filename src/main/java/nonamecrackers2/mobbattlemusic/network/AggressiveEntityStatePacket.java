package nonamecrackers2.mobbattlemusic.network;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.client.util.AggressiveEntityStateClient;

public class AggressiveEntityStatePacket
{
	private final Set<UUID> entityUuids;
	
	public AggressiveEntityStatePacket(Set<UUID> entityUuids)
	{
		this.entityUuids = Set.copyOf(entityUuids);
	}
	
	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeVarInt(this.entityUuids.size());
		for (UUID uuid : this.entityUuids)
			buffer.writeUUID(uuid);
	}
	
	public static AggressiveEntityStatePacket decode(FriendlyByteBuf buffer)
	{
		int count = buffer.readVarInt();
		Set<UUID> uuids = new LinkedHashSet<>();
		for (int i = 0; i < count; i++)
			uuids.add(buffer.readUUID());
		return new AggressiveEntityStatePacket(uuids);
	}
	
	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().enqueueWork(() -> {
			DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> AggressiveEntityStateClient.apply(this.entityUuids));
		});
		context.get().setPacketHandled(true);
	}
}
