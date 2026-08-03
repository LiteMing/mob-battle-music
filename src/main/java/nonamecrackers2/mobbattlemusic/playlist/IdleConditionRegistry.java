package nonamecrackers2.mobbattlemusic.playlist;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import nonamecrackers2.mobbattlemusic.MobBattleMusicMod;

public final class IdleConditionRegistry
{
	private static final Map<ResourceLocation, Definition> DEFINITIONS = new LinkedHashMap<>();

	static {
		register(MobBattleMusicMod.id("dimension"), "Dimension", (player, argument) ->
				matchesLocation(player.level().dimension().location(), argument));
		register(MobBattleMusicMod.id("biome"), "Biome", (player, argument) ->
				player.level().getBiome(player.blockPosition()).unwrapKey()
						.map(key -> key.location().toString().equals(argument)).orElse(false));
		register(MobBattleMusicMod.id("structure"), "Structure", IdleConditionRegistry::insideStructure);
		register(MobBattleMusicMod.id("underwater"), "Underwater", (player, argument) -> player.isUnderWater());
		// K9-2: entity condition - any matching mob type within 12 blocks
		register(MobBattleMusicMod.id("entity"), "Entity", (player, argument) -> {
			ResourceLocation id = ResourceLocation.tryParse(argument);
			if (id == null)
				return false;
			return !player.level().getEntitiesOfClass(Mob.class,
					player.getBoundingBox().inflate(12.0D),
					mob -> mob.isAlive() && mob.getType() == BuiltInRegistries.ENTITY_TYPE.get(id)).isEmpty();
		});
		// K9-2: scene condition - combat / idle / underwater current state
		register(MobBattleMusicMod.id("scene"), "Scene", (player, argument) -> {
			boolean combatNearby = !player.level().getEntitiesOfClass(Mob.class,
					player.getBoundingBox().inflate(12.0D),
					mob -> mob.isAlive() && (mob.isAggressive()
							|| mob.getTarget() == player
							|| nonamecrackers2.mobbattlemusic.client.util.AggressiveEntityStateClient
									.isServerAggressive(mob.getUUID()))).isEmpty();
			return switch (argument == null ? "" : argument) {
				case "combat" -> combatNearby;
				case "idle" -> !combatNearby;
				case "underwater" -> player.isUnderWater();
				default -> false;
			};
		});
	}

	private static boolean matchesLocation(ResourceLocation location, String argument)
	{
		return location.toString().equals(argument) ||
				!argument.contains(":") && location.getPath().equals(argument);
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

	/**
	 * K9-2: AND/OR group evaluation. The list is split at OR joins: a
	 * condition whose join is OR starts a new OR-block; blocks OR together
	 * while conditions inside a block AND together. All-AND lists evaluate
	 * exactly as before.
	 */
	public static synchronized boolean test(Player player, List<IdleCondition> conditions)
	{
		boolean anyBlock = false;
		boolean currentBlock = true;
		boolean first = true;
		for (IdleCondition condition : conditions) {
			if (!first && condition.join() == IdleCondition.Join.OR) {
				anyBlock |= currentBlock;
				currentBlock = true;
			}
			currentBlock &= testOne(player, condition);
			first = false;
		}
		anyBlock |= currentBlock;
		return anyBlock;
	}

	/**
	 * K9-2: a single condition's raw (pre-inversion) match, for diagnostics.
	 */
	public static synchronized boolean testOne(Player player, IdleCondition condition)
	{
		Definition definition = DEFINITIONS.get(new ResourceLocation(condition.type()));
		if (definition == null)
			return false;
		try {
			return definition.predicate().test(player, condition.argument());
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * K9-2: per-condition diagnostic - the raw match plus the current
	 * environment's value expressed as a readable reason.
	 */
	public static synchronized MatchResult diagnose(Player player, IdleCondition condition)
	{
		ResourceLocation type = new ResourceLocation(condition.type());
		Definition definition = DEFINITIONS.get(type);
		if (definition == null)
			return new MatchResult(false, "unregistered condition type " + type);
		boolean matched;
		String reason;
		try {
			matched = definition.predicate().test(player, condition.argument());
		} catch (Exception e) {
			return new MatchResult(false, "evaluation error: " + e.toString());
		}
		String current = switch (type.getPath()) {
			case "dimension" -> "current=" + player.level().dimension().location();
			case "biome" -> "current=" + player.level().getBiome(player.blockPosition()).unwrapKey()
					.map(key -> key.location().toString()).orElse("unknown");
			case "structure" -> insideStructure(player, condition.argument()) ? "inside" : "not inside";
			case "underwater" -> "underwater=" + player.isUnderWater();
			case "entity" -> {
				ResourceLocation id = ResourceLocation.tryParse(condition.argument());
				int count = id == null ? 0 : player.level().getEntitiesOfClass(Mob.class,
						player.getBoundingBox().inflate(12.0D),
						mob -> mob.isAlive() && mob.getType() == BuiltInRegistries.ENTITY_TYPE.get(id)).size();
				yield "nearby " + condition.argument() + "=" + count;
			}
			case "scene" -> "scene check on argument '" + condition.argument() + "'";
			default -> "";
		};
		return new MatchResult(matched, current.isEmpty() ? (matched ? "matched" : "unmatched") : current);
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

	/**
	 * K9-2: one condition's diagnostic outcome for the Conditions inspector.
	 */
	public record MatchResult(boolean matched, String reason) {}

	private static record Definition(ResourceLocation id, String displayName,
			BiPredicate<Player, String> predicate) {}
}
