package nonamecrackers2.mobbattlemusic.command;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import nonamecrackers2.mobbattlemusic.network.IdleConditionStatePacket;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;

public final class IdleConditionStateServer
{
	private static final Map<UUID, Set<ResourceLocation>> LAST_ACTIVE = new ConcurrentHashMap<>();

	private IdleConditionStateServer() {}

	public static void onPlayerTick(TickEvent.PlayerTickEvent event)
	{
		if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player) ||
				player.tickCount % 10 != 0)
			return;
		Set<ResourceLocation> active = ServerExternalPlaylistStore.activeIdleRules(player);
		Set<ResourceLocation> previous = LAST_ACTIVE.put(player.getUUID(), Set.copyOf(active));
		if (!active.equals(previous))
			send(player, active);
	}

	public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event)
	{
		if (event.getEntity() instanceof ServerPlayer player) {
			Set<ResourceLocation> active = ServerExternalPlaylistStore.activeIdleRules(player);
			LAST_ACTIVE.put(player.getUUID(), Set.copyOf(active));
			send(player, active);
		}
	}

	public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event)
	{
		LAST_ACTIVE.remove(event.getEntity().getUUID());
	}

	private static void send(ServerPlayer player, Set<ResourceLocation> active)
	{
		MobBattleMusicNetwork.sendIdleConditionState(player, new IdleConditionStatePacket(List.copyOf(active),
				List.copyOf(IdleConditionRegistry.descriptors())));
	}
}
