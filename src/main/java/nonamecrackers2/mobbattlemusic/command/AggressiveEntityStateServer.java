package nonamecrackers2.mobbattlemusic.command;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.TickEvent;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.network.AggressiveEntityStatePacket;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;

public class AggressiveEntityStateServer
{
	public static void onPlayerTick(TickEvent.PlayerTickEvent event)
	{
		if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide())
			return;
		if (!(event.player instanceof ServerPlayer player))
			return;
		if (player.tickCount % 10 != 0)
			return;
		double radius = MobBattleMusicConfig.CLIENT.maxMobSearchRadius.get();
		Set<UUID> aggressiveEntities = new LinkedHashSet<>();
		for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(radius),
				mob -> mob.isAlive() && mob.getTarget() != null && mob.distanceTo(player) <= radius)) {
			aggressiveEntities.add(mob.getUUID());
		}
		MobBattleMusicNetwork.sendAggressiveEntityState(player, new AggressiveEntityStatePacket(aggressiveEntities));
	}
}
