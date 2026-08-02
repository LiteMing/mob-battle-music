package nonamecrackers2.mobbattlemusic.client.music;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;

public final class IdleConditionStateClient
{
	private static final Set<ResourceLocation> ACTIVE_RULES = ConcurrentHashMap.newKeySet();
	private static final Set<String> ACTIVE_ENTRIES = ConcurrentHashMap.newKeySet();
	private static volatile List<IdleConditionRegistry.Descriptor> descriptors = List.of();

	private IdleConditionStateClient() {}

	public static void update(Collection<ResourceLocation> activeRules, Collection<String> activeEntries,
			Collection<IdleConditionRegistry.Descriptor> availableConditions)
	{
		ACTIVE_RULES.clear();
		ACTIVE_RULES.addAll(activeRules);
		ACTIVE_ENTRIES.clear();
		ACTIVE_ENTRIES.addAll(activeEntries);
		descriptors = List.copyOf(availableConditions);
	}

	public static boolean isActive(ResourceLocation rule)
	{
		return ACTIVE_RULES.contains(rule);
	}

	public static boolean isEntryActive(ResourceLocation playlist, int entryIndex)
	{
		return ACTIVE_ENTRIES.contains(playlist + "#" + entryIndex);
	}

	public static List<IdleConditionRegistry.Descriptor> descriptors()
	{
		return descriptors.isEmpty() ? List.copyOf(IdleConditionRegistry.descriptors()) : descriptors;
	}

	public static void clear()
	{
		ACTIVE_RULES.clear();
		ACTIVE_ENTRIES.clear();
		descriptors = List.of();
	}
}
