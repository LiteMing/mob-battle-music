package nonamecrackers2.mobbattlemusic.playlist;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import nonamecrackers2.mobbattlemusic.MobBattleMusicMod;

public final class IdleConditionRegistry
{
	private static final Map<ResourceLocation, Definition> DEFINITIONS = new LinkedHashMap<>();

	static {
		register(MobBattleMusicMod.id("dimension"), "Dimension", (player, argument) ->
				player.level().dimension().location().toString().equals(argument));
		register(MobBattleMusicMod.id("biome"), "Biome", (player, argument) ->
				player.level().getBiome(player.blockPosition()).unwrapKey()
						.map(key -> key.location().toString().equals(argument)).orElse(false));
		register(MobBattleMusicMod.id("structure"), "Structure", IdleConditionRegistry::insideStructure);
		register(MobBattleMusicMod.id("underwater"), "Underwater", (player, argument) -> player.isUnderWater());
	}

	private IdleConditionRegistry() {}

	public static synchronized void register(String id, String displayName,
			BiPredicate<Player, String> predicate)
	{
		register(new ResourceLocation(id), displayName, predicate);
	}

	public static synchronized void register(ResourceLocation id, String displayName,
			BiPredicate<Player, String> predicate)
	{
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(predicate, "predicate");
		DEFINITIONS.put(id, new Definition(id, displayName == null || displayName.isBlank() ? id.toString() : displayName,
				predicate));
	}

	public static synchronized Collection<Descriptor> descriptors()
	{
		return DEFINITIONS.values().stream().map(definition -> new Descriptor(definition.id(), definition.displayName()))
				.toList();
	}

	public static synchronized boolean isRegistered(String id)
	{
		try {
			return DEFINITIONS.containsKey(new ResourceLocation(id));
		} catch (Exception e) {
			return false;
		}
	}

	public static synchronized boolean test(Player player, List<IdleCondition> conditions)
	{
		for (IdleCondition condition : conditions) {
			Definition definition = DEFINITIONS.get(new ResourceLocation(condition.type()));
			boolean matched = false;
			if (definition != null) {
				try {
					matched = definition.predicate().test(player, condition.argument());
				} catch (Exception ignored) {
				}
			}
			if (condition.inverted() ? matched : !matched)
				return false;
		}
		return true;
	}

	private static boolean insideStructure(Player player, String argument)
	{
		if (!(player.level() instanceof ServerLevel level))
			return false;
		ResourceLocation id = ResourceLocation.tryParse(argument);
		if (id == null)
			return false;
		Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
		Structure structure = registry.get(id);
		if (structure == null)
			return false;
		StructureStart start = level.structureManager().getStructureWithPieceAt(player.blockPosition(), structure);
		return start != null && start.isValid();
	}

	public static record Descriptor(ResourceLocation id, String displayName) {}

	private static record Definition(ResourceLocation id, String displayName,
			BiPredicate<Player, String> predicate) {}
}
