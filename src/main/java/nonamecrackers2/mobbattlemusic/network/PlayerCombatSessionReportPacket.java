package nonamecrackers2.mobbattlemusic.network;

import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import nonamecrackers2.mobbattlemusic.command.PlayerCombatSessionServer;

public record PlayerCombatSessionReportPacket(UUID opponent)
{
	public void encode(FriendlyByteBuf buffer)
	{
		buffer.writeBoolean(this.opponent != null);
		if (this.opponent != null)
			buffer.writeUUID(this.opponent);
	}

	public static PlayerCombatSessionReportPacket decode(FriendlyByteBuf buffer)
	{
		return new PlayerCombatSessionReportPacket(buffer.readBoolean() ? buffer.readUUID() : null);
	}

	public void handle(Supplier<NetworkEvent.Context> context)
	{
		context.get().enqueueWork(() -> {
			ServerPlayer sender = context.get().getSender();
			if (sender != null)
				PlayerCombatSessionServer.report(sender, this.opponent);
		});
		context.get().setPacketHandled(true);
	}
}
