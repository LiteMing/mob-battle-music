package nonamecrackers2.mobbattlemusic.command;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;
import nonamecrackers2.mobbattlemusic.network.PlayerCombatSessionStatePacket;

public final class PlayerCombatSessionServer
{
	private static final long SESSION_GRACE_TICKS = 40L;
	private static final Map<SessionKey, SessionState> SESSIONS = new ConcurrentHashMap<>();

	private PlayerCombatSessionServer() {}

	public static void report(ServerPlayer player, UUID opponentId)
	{
		if (opponentId == null) {
			MobBattleMusicNetwork.sendPlayerCombatSessionState(player,
					new PlayerCombatSessionStatePacket(null, -1L));
			return;
		}
		ServerPlayer opponent = player.server.getPlayerList().getPlayer(opponentId);
		if (opponent == null || opponent == player || opponent.serverLevel() != player.serverLevel()) {
			MobBattleMusicNetwork.sendPlayerCombatSessionState(player,
					new PlayerCombatSessionStatePacket(null, -1L));
			return;
		}
		long now = player.serverLevel().getGameTime();
		SessionKey key = SessionKey.of(player.getUUID(), opponentId);
		SessionState state = SESSIONS.compute(key, (ignored, previous) -> {
			long start = previous == null || now - previous.lastSeenTick() > SESSION_GRACE_TICKS
					? now : previous.startTick();
			return new SessionState(start, now);
		});
		SESSIONS.entrySet().removeIf(entry -> now - entry.getValue().lastSeenTick() > SESSION_GRACE_TICKS * 3L);
		MobBattleMusicNetwork.sendPlayerCombatSessionState(player,
				new PlayerCombatSessionStatePacket(opponentId, state.startTick()));
	}

	private record SessionKey(UUID first, UUID second)
	{
		static SessionKey of(UUID left, UUID right)
		{
			return left.compareTo(right) <= 0 ? new SessionKey(left, right) : new SessionKey(right, left);
		}
	}

	private record SessionState(long startTick, long lastSeenTick) {}
}
