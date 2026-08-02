package nonamecrackers2.mobbattlemusic.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.client.music.IdleConditionStateClient;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;

public record IdleConditionStatePacket(List<ResourceLocation> activeRules,
		List<IdleConditionRegistry.Descriptor> descriptors)
{
	public IdleConditionStatePacket
	{
		activeRules = List.copyOf(activeRules);
		descriptors = List.copyOf(descriptors);
	}

	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeVarInt(this.activeRules.size());
		this.activeRules.forEach(buffer::writeResourceLocation);
		buffer.writeVarInt(this.descriptors.size());
		for (IdleConditionRegistry.Descriptor descriptor : this.descriptors) {
			buffer.writeResourceLocation(descriptor.id());
			buffer.writeUtf(descriptor.displayName());
		}
	}

	public static IdleConditionStatePacket decode(FriendlyByteBuf buffer)
	{
		int activeCount = buffer.readVarInt();
		List<ResourceLocation> active = new ArrayList<>(activeCount);
		for (int i = 0; i < activeCount; i++)
			active.add(buffer.readResourceLocation());
		int descriptorCount = buffer.readVarInt();
		List<IdleConditionRegistry.Descriptor> descriptors = new ArrayList<>(descriptorCount);
		for (int i = 0; i < descriptorCount; i++)
			descriptors.add(new IdleConditionRegistry.Descriptor(buffer.readResourceLocation(), buffer.readUtf()));
		return new IdleConditionStatePacket(active, descriptors);
	}

	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
				() -> () -> IdleConditionStateClient.update(this.activeRules, this.descriptors)));
		context.get().setPacketHandled(true);
	}
}
