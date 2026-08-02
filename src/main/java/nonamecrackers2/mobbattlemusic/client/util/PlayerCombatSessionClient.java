package nonamecrackers2.mobbattlemusic.client.util;

import java.util.UUID;

public final class PlayerCombatSessionClient
{
	private static UUID opponent;
	private static long startTick = -1L;

	private PlayerCombatSessionClient() {}

	public static void apply(UUID target, long start)
	{
		opponent = target;
		startTick = target == null ? -1L : start;
	}

	public static long startTick(UUID target)
	{
		return target != null && target.equals(opponent) ? startTick : -1L;
	}

	public static void clear()
	{
		opponent = null;
		startTick = -1L;
	}
}
