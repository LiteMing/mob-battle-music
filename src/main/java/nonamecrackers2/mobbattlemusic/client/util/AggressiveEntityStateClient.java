package nonamecrackers2.mobbattlemusic.client.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AggressiveEntityStateClient
{
	private static final Map<UUID, Long> SERVER_AGGRESSIVE_ENTITIES = new ConcurrentHashMap<>();
	
	public static void apply(Map<UUID, Long> startTicks)
	{
		SERVER_AGGRESSIVE_ENTITIES.clear();
		SERVER_AGGRESSIVE_ENTITIES.putAll(startTicks);
	}
	
	public static boolean isServerAggressive(UUID uuid)
	{
		return SERVER_AGGRESSIVE_ENTITIES.containsKey(uuid);
	}

	public static long startTick(UUID uuid)
	{
		return SERVER_AGGRESSIVE_ENTITIES.getOrDefault(uuid, -1L);
	}
	
	public static void clear()
	{
		SERVER_AGGRESSIVE_ENTITIES.clear();
	}
}
