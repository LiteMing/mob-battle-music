package nonamecrackers2.mobbattlemusic.client.util;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AggressiveEntityStateClient
{
	private static final Set<UUID> SERVER_AGGRESSIVE_ENTITIES = ConcurrentHashMap.newKeySet();
	
	public static void apply(Set<UUID> uuids)
	{
		SERVER_AGGRESSIVE_ENTITIES.clear();
		SERVER_AGGRESSIVE_ENTITIES.addAll(uuids);
	}
	
	public static boolean isServerAggressive(UUID uuid)
	{
		return SERVER_AGGRESSIVE_ENTITIES.contains(uuid);
	}
	
	public static void clear()
	{
		SERVER_AGGRESSIVE_ENTITIES.clear();
	}
}
