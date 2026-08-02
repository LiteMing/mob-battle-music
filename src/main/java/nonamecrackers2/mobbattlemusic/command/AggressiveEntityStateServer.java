package nonamecrackers2.mobbattlemusic.command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.TickEvent;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.network.AggressiveEntityStatePacket;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;

public class AggressiveEntityStateServer
{
	private static final Map<UUID, SessionState> SESSIONS = new ConcurrentHashMap<>();

	public static void onPlayerTick(TickEvent.PlayerTickEvent event)
	{
		if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide())
			return;
		if (!(event.player instanceof ServerPlayer player))
			return;
		if (player.tickCount % 10 != 0)
			return;
		double radius = MobBattleMusicConfig.CLIENT.maxMobSearchRadius.get();
		long gameTime = player.serverLevel().getGameTime();
		Map<UUID, Long> aggressiveEntities = new LinkedHashMap<>();
		for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(radius),
				mob -> mob.isAlive() && mob.getTarget() != null && mob.distanceTo(player) <= radius)) {
			SessionState previous = SESSIONS.get(mob.getUUID());
			long start = previous == null || gameTime - previous.lastSeenTick() > 20L ? gameTime : previous.startTick();
			SESSIONS.put(mob.getUUID(), new SessionState(start, gameTime));
			aggressiveEntities.put(mob.getUUID(), start);
		}
		SESSIONS.entrySet().removeIf(entry -> gameTime - entry.getValue().lastSeenTick() > 200L);
		MobBattleMusicNetwork.sendAggressiveEntityState(player, new AggressiveEntityStatePacket(aggressiveEntities));
	}

	private record SessionState(long startTick, long lastSeenTick) {}
}
