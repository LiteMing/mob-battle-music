package nonamecrackers2.mobbattlemusic.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.common.collect.Lists;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.registries.ForgeRegistries;
import nonamecrackers2.mobbattlemusic.MobBattleMusicMod;
import nonamecrackers2.mobbattlemusic.network.ExternalPlaylistCatalogPacket;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;
import nonamecrackers2.mobbattlemusic.network.ServerExternalPlaylistSyncPacket;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;

public class ServerExternalPlaylistStore
{
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/ServerExternalPlaylistStore");
	private static final Map<String, List<String>> SCENE_URLS = new LinkedHashMap<>();
	private static final Map<String, List<String>> ENTITY_TYPE_URLS = new LinkedHashMap<>();
	private static final Map<String, List<String>> ENTITY_UUID_URLS = new LinkedHashMap<>();
	private static final Map<String, List<String>> IDLE_RULE_URLS = new LinkedHashMap<>();
	private static final Map<String, String> SELECTION_MODES = new LinkedHashMap<>();
	private static final Map<String, List<IdleCondition>> IDLE_CONDITIONS = new LinkedHashMap<>();
	private static final Map<String, Integer> IDLE_INTERVALS = new LinkedHashMap<>();
	private static final Map<String, Integer> PRIORITIES = new LinkedHashMap<>();
	private static boolean loaded;
	
	public static int add(CommandSourceStack source, String scene, String url)
	{
		Binding binding = Binding.scene(scene);
		if (binding == null) {
			source.sendFailure(Component.literal("Unsupported scene: " + scene));
			return 0;
		}
		return add(source, binding, url);
	}
	
	public static int addEntityType(CommandSourceStack source, ResourceLocation entityType, String url)
	{
		return addEntityType(source, "aggressive", entityType, url);
	}
	
	public static int addEntityType(CommandSourceStack source, String scene, ResourceLocation entityType, String url)
	{
		Binding binding = Binding.entityType(scene, entityType);
		if (binding == null) {
			source.sendFailure(Component.literal("Unknown " + scene + " entity type: " + entityType));
			return 0;
		}
		return add(source, binding, url);
	}
	
	public static int addEntityUuid(CommandSourceStack source, String uuid, String url)
	{
		return addEntityUuid(source, "aggressive", uuid, url);
	}
	
	public static int addEntityUuid(CommandSourceStack source, String scene, String uuid, String url)
	{
		Binding binding = Binding.entityUuid(scene, uuid);
		if (binding == null) {
			source.sendFailure(Component.literal("Invalid " + scene + " entity UUID: " + uuid));
			return 0;
		}
		return add(source, binding, url);
	}

	public static int addIdleRule(CommandSourceStack source, String ruleId, String url)
	{
		Binding binding = Binding.idleRule(ruleId);
		if (binding == null) {
			source.sendFailure(Component.literal("Invalid idle rule id: " + ruleId));
			return 0;
		}
		return add(source, binding, url);
	}
	
	private static int add(CommandSourceStack source, Binding binding, String url)
	{
		if (!isValidUrl(url)) {
			source.sendFailure(Component.literal("Invalid URL: " + url));
			return 0;
		}
		load(source.getServer());
		List<String> urls = urls(binding.kind()).computeIfAbsent(binding.storageKey(), key -> Lists.newArrayList());
		urls.add(url);
		save(source.getServer());
		syncAll(source.getServer());
		int finalIndex = urls.size();
		source.sendSuccess(() -> Component.literal("Added server " + binding.displayName() + " URL #" + finalIndex), true);
		return urls.size();
	}
	
	public static int delete(CommandSourceStack source, String scene, int index)
	{
		Binding binding = Binding.scene(scene);
		if (binding == null) {
			source.sendFailure(Component.literal("Unsupported scene: " + scene));
			return 0;
		}
		return delete(source, binding, index);
	}
	
	public static int deleteEntityType(CommandSourceStack source, ResourceLocation entityType, int index)
	{
		return deleteEntityType(source, "aggressive", entityType, index);
	}
	
	public static int deleteEntityType(CommandSourceStack source, String scene, ResourceLocation entityType, int index)
	{
		Binding binding = Binding.entityType(scene, entityType);
		if (binding == null) {
			source.sendFailure(Component.literal("Unknown " + scene + " entity type: " + entityType));
			return 0;
		}
		return delete(source, binding, index);
	}
	
	public static int deleteEntityUuid(CommandSourceStack source, String uuid, int index)
	{
		return deleteEntityUuid(source, "aggressive", uuid, index);
	}
	
	public static int deleteEntityUuid(CommandSourceStack source, String scene, String uuid, int index)
	{
		Binding binding = Binding.entityUuid(scene, uuid);
		if (binding == null) {
			source.sendFailure(Component.literal("Invalid " + scene + " entity UUID: " + uuid));
			return 0;
		}
		return delete(source, binding, index);
	}

	public static int deleteIdleRule(CommandSourceStack source, String ruleId, int index)
	{
		Binding binding = Binding.idleRule(ruleId);
		if (binding == null) {
			source.sendFailure(Component.literal("Invalid idle rule id: " + ruleId));
			return 0;
		}
		return delete(source, binding, index);
	}
	
	private static int delete(CommandSourceStack source, Binding binding, int index)
	{
		load(source.getServer());
		List<String> urls = urls(binding.kind()).get(binding.storageKey());
		if (urls == null || index < 0 || index >= urls.size()) {
			source.sendFailure(Component.literal("Index " + (index + 1) + " out of range for server " + binding.displayName()));
			return 0;
		}
		String removed = urls.remove(index);
		if (urls.isEmpty()) {
			urls(binding.kind()).remove(binding.storageKey());
			SELECTION_MODES.remove(binding.serializedKey());
			IDLE_CONDITIONS.remove(binding.serializedKey());
			IDLE_INTERVALS.remove(binding.serializedKey());
			PRIORITIES.remove(binding.serializedKey());
		}
		save(source.getServer());
		syncAll(source.getServer());
		int finalIndex = index + 1;
		source.sendSuccess(() -> Component.literal("Deleted server " + binding.displayName() + " URL #" + finalIndex + ": " + removed), true);
		return 1;
	}

	public static int setSelectionMode(CommandSourceStack source, String scene, String selectionMode)
	{
		Binding binding = Binding.scene(scene);
		if (binding == null) {
			source.sendFailure(Component.literal("Unsupported scene: " + scene));
			return 0;
		}
		return setSelectionMode(source, binding, selectionMode);
	}

	public static int setEntityTypeSelectionMode(CommandSourceStack source, String scene, ResourceLocation entityType,
			String selectionMode)
	{
		Binding binding = Binding.entityType(scene, entityType);
		if (binding == null) {
			source.sendFailure(Component.literal("Unknown " + scene + " entity type: " + entityType));
			return 0;
		}
		return setSelectionMode(source, binding, selectionMode);
	}

	public static int setEntityUuidSelectionMode(CommandSourceStack source, String scene, String uuid,
			String selectionMode)
	{
		Binding binding = Binding.entityUuid(scene, uuid);
		if (binding == null) {
			source.sendFailure(Component.literal("Invalid " + scene + " entity UUID: " + uuid));
			return 0;
		}
		return setSelectionMode(source, binding, selectionMode);
	}

	public static int setIdleRuleSelectionMode(CommandSourceStack source, String ruleId, String selectionMode)
	{
		Binding binding = Binding.idleRule(ruleId);
		if (binding == null) {
			source.sendFailure(Component.literal("Invalid idle rule id: " + ruleId));
			return 0;
		}
		return setSelectionMode(source, binding, selectionMode);
	}

	private static int setSelectionMode(CommandSourceStack source, Binding binding, String selectionMode)
	{
		String normalized = normalizeSelectionMode(selectionMode);
		if (normalized == null) {
			source.sendFailure(Component.literal("Unknown playback order: " + selectionMode));
			return 0;
		}
		load(source.getServer());
		List<String> urls = urls(binding.kind()).get(binding.storageKey());
		if (urls == null || urls.isEmpty()) {
			source.sendFailure(Component.literal("No server playlist exists for " + binding.displayName()));
			return 0;
		}
		SELECTION_MODES.put(binding.serializedKey(), normalized);
		save(source.getServer());
		syncAll(source.getServer());
		source.sendSuccess(() -> Component.literal("Set server " + binding.displayName() +
				" playback order to " + normalized), true);
		return 1;
	}

	public static List<String> supportedSelectionModes()
	{
		return List.of("random", "sequential", "first");
	}

	public static int setPriority(CommandSourceStack source, String scene, int priority)
	{
		return setPriority(source, Binding.scene(scene), priority);
	}

	public static int setEntityTypePriority(CommandSourceStack source, String scene, ResourceLocation entityType, int priority)
	{
		return setPriority(source, Binding.entityType(scene, entityType), priority);
	}

	public static int setEntityUuidPriority(CommandSourceStack source, String scene, String uuid, int priority)
	{
		return setPriority(source, Binding.entityUuid(scene, uuid), priority);
	}

	public static int setIdleRulePriority(CommandSourceStack source, String ruleId, int priority)
	{
		return setPriority(source, Binding.idleRule(ruleId), priority);
	}

	private static int setPriority(CommandSourceStack source, Binding binding, int priority)
	{
		if (!hasPlaylist(source.getServer(), binding)) {
			source.sendFailure(Component.literal("No server playlist exists for this binding"));
			return 0;
		}
		PRIORITIES.put(binding.serializedKey(), priority);
		save(source.getServer());
		syncAll(source.getServer());
		source.sendSuccess(() -> Component.literal("Set server " + binding.displayName() + " priority to " + priority), true);
		return 1;
	}

	public static int setIdleRuleInterval(CommandSourceStack source, String ruleId, int seconds)
	{
		Binding binding = Binding.idleRule(ruleId);
		if (!hasPlaylist(source.getServer(), binding)) {
			source.sendFailure(Component.literal("No server idle rule playlist exists for " + ruleId));
			return 0;
		}
		IDLE_INTERVALS.put(binding.serializedKey(), seconds);
		save(source.getServer());
		syncAll(source.getServer());
		source.sendSuccess(() -> Component.literal("Set idle interval to " + seconds + " seconds"), true);
		return 1;
	}

	public static int addIdleRuleCondition(CommandSourceStack source, String ruleId, String type, String argument,
			boolean inverted)
	{
		Binding binding = Binding.idleRule(ruleId);
		if (!hasPlaylist(source.getServer(), binding)) {
			source.sendFailure(Component.literal("No server idle rule playlist exists for " + ruleId));
			return 0;
		}
		if (!IdleConditionRegistry.isRegistered(type)) {
			source.sendFailure(Component.literal("Unknown idle condition type: " + type));
			return 0;
		}
		List<IdleCondition> conditions = IDLE_CONDITIONS.computeIfAbsent(binding.serializedKey(), key -> Lists.newArrayList());
		conditions.add(new IdleCondition(type, argument, inverted));
		save(source.getServer());
		syncAll(source.getServer());
		source.sendSuccess(() -> Component.literal("Added idle condition #" + conditions.size()), true);
		return conditions.size();
	}

	public static int deleteIdleRuleCondition(CommandSourceStack source, String ruleId, int index)
	{
		Binding binding = Binding.idleRule(ruleId);
		List<IdleCondition> conditions = binding == null ? null : IDLE_CONDITIONS.get(binding.serializedKey());
		if (conditions == null || index < 0 || index >= conditions.size()) {
			source.sendFailure(Component.literal("Idle condition index out of range"));
			return 0;
		}
		conditions.remove(index);
		if (conditions.isEmpty())
			IDLE_CONDITIONS.remove(binding.serializedKey());
		save(source.getServer());
		syncAll(source.getServer());
		source.sendSuccess(() -> Component.literal("Deleted idle condition #" + (index + 1)), true);
		return 1;
	}

	private static boolean hasPlaylist(MinecraftServer server, Binding binding)
	{
		if (binding == null)
			return false;
		load(server);
		List<String> entries = urls(binding.kind()).get(binding.storageKey());
		return entries != null && !entries.isEmpty();
	}
	
	public static int list(CommandSourceStack source, String scene)
	{
		Binding binding = Binding.scene(scene);
		if (binding == null) {
			source.sendFailure(Component.literal("Unsupported scene: " + scene));
			return 0;
		}
		return list(source, binding);
	}
	
	public static int listEntityType(CommandSourceStack source, ResourceLocation entityType)
	{
		return listEntityType(source, "aggressive", entityType);
	}
	
	public static int listEntityType(CommandSourceStack source, String scene, ResourceLocation entityType)
	{
		Binding binding = Binding.entityType(scene, entityType);
		if (binding == null) {
			source.sendFailure(Component.literal("Unknown " + scene + " entity type: " + entityType));
			return 0;
		}
		return list(source, binding);
	}
	
	public static int listEntityUuid(CommandSourceStack source, String uuid)
	{
		return listEntityUuid(source, "aggressive", uuid);
	}
	
	public static int listEntityUuid(CommandSourceStack source, String scene, String uuid)
	{
		Binding binding = Binding.entityUuid(scene, uuid);
		if (binding == null) {
			source.sendFailure(Component.literal("Invalid " + scene + " entity UUID: " + uuid));
			return 0;
		}
		return list(source, binding);
	}

	public static int listIdleRule(CommandSourceStack source, String ruleId)
	{
		Binding binding = Binding.idleRule(ruleId);
		if (binding == null) {
			source.sendFailure(Component.literal("Invalid idle rule id: " + ruleId));
			return 0;
		}
		return list(source, binding);
	}
	
	private static int list(CommandSourceStack source, Binding binding)
	{
		load(source.getServer());
		List<String> urls = urls(binding.kind()).getOrDefault(binding.storageKey(), List.of());
		source.sendSystemMessage(Component.literal("[Mob Battle Music] Server " + binding.displayName() + " URLs: " + urls.size()));
		source.sendSystemMessage(Component.literal("  Playback order: " + selectionMode(binding)));
		source.sendSystemMessage(Component.literal("  Priority: " +
				PRIORITIES.getOrDefault(binding.serializedKey(), defaultPriority(binding))));
		if (binding.kind() == Kind.IDLE_RULE) {
			source.sendSystemMessage(Component.literal("  Interval: " +
					IDLE_INTERVALS.getOrDefault(binding.serializedKey(), 0) + " seconds"));
			List<IdleCondition> conditions = IDLE_CONDITIONS.getOrDefault(binding.serializedKey(), List.of());
			for (int i = 0; i < conditions.size(); i++) {
				IdleCondition condition = conditions.get(i);
				source.sendSystemMessage(Component.literal("  Condition " + (i + 1) + ": " +
						(condition.inverted() ? "NOT " : "") + condition.type() + " " + condition.argument()));
			}
		}
		listUrls(source, "  ", urls);
		if (binding.kind() == Kind.SCENE) {
			listSceneBindings(source, binding.scene());
			return urls.size() + countSceneBindings(binding.scene());
		}
		return urls.size();
	}
	
	private static void listSceneBindings(CommandSourceStack source, String scene)
	{
		listBindings(source, scene, Kind.ENTITY_TYPE, ENTITY_TYPE_URLS);
		listBindings(source, scene, Kind.ENTITY_UUID, ENTITY_UUID_URLS);
	}
	
	private static int countSceneBindings(String scene)
	{
		return countBindings(scene, Kind.ENTITY_TYPE, ENTITY_TYPE_URLS) +
				countBindings(scene, Kind.ENTITY_UUID, ENTITY_UUID_URLS);
	}
	
	private static int countBindings(String scene, Kind kind, Map<String, List<String>> map)
	{
		int count = 0;
		for (String key : map.keySet()) {
			Binding binding = Binding.create(kind, key);
			if (binding != null && binding.scene().equals(scene))
				count++;
		}
		return count;
	}
	
	private static void listBindings(CommandSourceStack source, String scene, Kind kind, Map<String, List<String>> map)
	{
		for (Map.Entry<String, List<String>> entry : map.entrySet()) {
			Binding binding = Binding.create(kind, entry.getKey());
			if (binding == null || !binding.scene().equals(scene))
				continue;
			source.sendSystemMessage(Component.literal("  " + bindingLabel(source.getServer(), binding) +
					" URLs: " + entry.getValue().size()));
			listUrls(source, "    ", entry.getValue());
		}
	}
	
	private static void listUrls(CommandSourceStack source, String prefix, List<String> urls)
	{
		for (int i = 0; i < urls.size(); i++)
			source.sendSystemMessage(Component.literal(prefix + (i + 1) + ": " + urls.get(i)));
	}

	public static List<ResourceLocation> recordedEntityTypes(MinecraftServer server, String scene)
	{
		load(server);
		List<ResourceLocation> types = Lists.newArrayList();
		for (String key : ENTITY_TYPE_URLS.keySet()) {
			Binding binding = Binding.create(Kind.ENTITY_TYPE, key);
			if (binding == null || !binding.scene().equals(scene))
				continue;
			types.add(new ResourceLocation(binding.target()));
		}
		return types;
	}

	public static List<String> recordedEntityUuids(MinecraftServer server, String scene)
	{
		load(server);
		List<String> uuids = Lists.newArrayList();
		for (String key : ENTITY_UUID_URLS.keySet()) {
			Binding binding = Binding.create(Kind.ENTITY_UUID, key);
			if (binding != null && binding.scene().equals(scene))
				uuids.add(binding.target());
		}
		return uuids;
	}

	public static List<String> recordedIdleRules(MinecraftServer server)
	{
		load(server);
		return IDLE_RULE_URLS.keySet().stream().map(key -> Binding.create(Kind.IDLE_RULE, key))
				.filter(java.util.Objects::nonNull).map(Binding::target).toList();
	}
	
	private static String bindingLabel(MinecraftServer server, Binding binding)
	{
		if (binding.kind() == Kind.ENTITY_TYPE)
			return "type " + binding.target();
		if (binding.kind() == Kind.ENTITY_UUID) {
			UUID uuid = UUID.fromString(binding.target());
			Entity entity = findEntity(server, uuid);
			ResourceLocation type = entity == null ? null : ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
			String suffix = entity == null ? "missing" : type + " " + entity.getDisplayName().getString();
			return "uuid " + binding.target() + " (" + suffix + ")";
		}
		return binding.displayName();
	}
	
	public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event)
	{
		if (event.getEntity() instanceof ServerPlayer player) {
			load(player.getServer());
			sync(player);
		}
	}
	
	public static void onLivingDeath(LivingDeathEvent event)
	{
		if (!event.getEntity().level().isClientSide())
			removeEntityUuid(event.getEntity().getServer(), event.getEntity().getUUID());
	}
	
	public static void onEntityLeaveLevel(EntityLeaveLevelEvent event)
	{
		if (event.getLevel().isClientSide())
			return;
		Entity.RemovalReason reason = event.getEntity().getRemovalReason();
		if (reason != null && reason.shouldDestroy())
			removeEntityUuid(event.getEntity().getServer(), event.getEntity().getUUID());
	}
	
	private static void removeEntityUuid(MinecraftServer server, UUID uuid)
	{
		if (server == null)
			return;
		load(server);
		boolean removed = false;
		for (String key : List.copyOf(ENTITY_UUID_URLS.keySet())) {
			Binding binding = Binding.create(Kind.ENTITY_UUID, key);
			if (binding != null && binding.target().equals(uuid.toString())) {
				ENTITY_UUID_URLS.remove(key);
				SELECTION_MODES.remove(binding.serializedKey());
				removed = true;
			}
		}
		if (removed) {
			save(server);
			syncAll(server);
		}
	}
	
	private static Entity findEntity(MinecraftServer server, UUID uuid)
	{
		for (ServerLevel level : server.getAllLevels()) {
			Entity entity = level.getEntity(uuid);
			if (entity != null)
				return entity;
		}
		return null;
	}
	
	public static void syncAll(MinecraftServer server)
	{
		load(server);
		ServerExternalPlaylistSyncPacket packet = new ServerExternalPlaylistSyncPacket(trackDefinitions(server));
		for (ServerPlayer player : server.getPlayerList().getPlayers())
			MobBattleMusicNetwork.sendServerExternalPlaylistSync(player, packet);
	}
	
	public static void sync(ServerPlayer player)
	{
		MobBattleMusicNetwork.sendServerExternalPlaylistSync(player, new ServerExternalPlaylistSyncPacket(trackDefinitions(player.getServer())));
	}
	
	public static List<ExternalPlaylistCatalogPacket.Playlist> catalogPlaylists(MinecraftServer server)
	{
		load(server);
		return trackDefinitions(server).stream()
				.map(definition -> new ExternalPlaylistCatalogPacket.Playlist(
						definition.configLocation(),
						definition.configLocation(),
						definition.entries().stream()
								.map(entry -> new ExternalPlaylistCatalogPacket.Entry(entry.id(), entry.name()))
								.toList()))
				.toList();
	}
	
	public static String chooseRandomEntry(MinecraftServer server, ResourceLocation playlistId)
	{
		load(server);
		for (ServerExternalPlaylistSyncPacket.TrackDefinition definition : trackDefinitions(server)) {
			if (!definition.configLocation().equals(playlistId) || definition.entries().isEmpty())
				continue;
			int selected = java.util.concurrent.ThreadLocalRandom.current().nextInt(definition.entries().size());
			return definition.entries().get(selected).id();
		}
		return null;
	}

	public static Set<ResourceLocation> activeIdleRules(ServerPlayer player)
	{
		load(player.getServer());
		java.util.Set<ResourceLocation> active = new java.util.LinkedHashSet<>();
		for (String key : IDLE_RULE_URLS.keySet()) {
			Binding binding = Binding.create(Kind.IDLE_RULE, key);
			if (binding != null && IdleConditionRegistry.test(player,
					IDLE_CONDITIONS.getOrDefault(binding.serializedKey(), List.of())))
				active.add(configLocation(binding));
		}
		return Set.copyOf(active);
	}
	
	public static String playlistEntryUrl(MinecraftServer server, ResourceLocation playlist, int index)
	{
		load(server);
		for (ServerExternalPlaylistSyncPacket.TrackDefinition definition : trackDefinitions(server)) {
			if (definition.configLocation().equals(playlist) && index >= 0 && index < definition.entries().size())
				return definition.entries().get(index).url();
		}
		return null;
	}

	public static List<ResourceLocation> recordedPlaylists(MinecraftServer server)
	{
		load(server);
		return trackDefinitions(server).stream().map(ServerExternalPlaylistSyncPacket.TrackDefinition::configLocation)
				.toList();
	}

	private static List<ServerExternalPlaylistSyncPacket.TrackDefinition> trackDefinitions(MinecraftServer server)
	{
		List<ServerExternalPlaylistSyncPacket.TrackDefinition> definitions = Lists.newArrayList();
		addTrackDefinitions(server, definitions, Kind.SCENE, SCENE_URLS);
		addTrackDefinitions(server, definitions, Kind.ENTITY_TYPE, ENTITY_TYPE_URLS);
		addTrackDefinitions(server, definitions, Kind.ENTITY_UUID, ENTITY_UUID_URLS);
		addTrackDefinitions(server, definitions, Kind.IDLE_RULE, IDLE_RULE_URLS);
		return definitions;
	}
	
	private static void addTrackDefinitions(MinecraftServer server,
			List<ServerExternalPlaylistSyncPacket.TrackDefinition> definitions,
			Kind kind, Map<String, List<String>> urlsByTarget)
	{
		urlsByTarget.forEach((key, urls) -> {
			Binding binding = Binding.create(kind, key);
			if (binding == null || urls.isEmpty())
				return;
			List<ServerExternalPlaylistSyncPacket.Entry> entries = Lists.newArrayList();
			ResourceLocation config = configLocation(binding);
			for (int i = 0; i < urls.size(); i++) {
				String url = urls.get(i);
				entries.add(new ServerExternalPlaylistSyncPacket.Entry(String.valueOf(i + 1), "server_" + (i + 1), url,
						ServerTimelineMarkerStore.markers(server, config, url)));
			}
			definitions.add(new ServerExternalPlaylistSyncPacket.TrackDefinition(config, binding.serializedKey(),
					PRIORITIES.getOrDefault(binding.serializedKey(), defaultPriority(binding)), defaultFadeTime(binding),
					selectionMode(binding), IDLE_CONDITIONS.getOrDefault(binding.serializedKey(), List.of()),
					IDLE_INTERVALS.getOrDefault(binding.serializedKey(), 0), entries));
		});
	}
	
	public static ResourceLocation configLocation(String scene)
	{
		Binding binding = Binding.scene(scene);
		return MobBattleMusicMod.id("server/" + (binding == null ? normalizeScene(scene) : binding.configPath()));
	}
	
	public static ResourceLocation configLocation(Binding binding)
	{
		return MobBattleMusicMod.id("server/" + binding.configPath());
	}
	
	public static List<String> supportedScenes()
	{
		return List.of("player", "aggressive", "ambient", "idle");
	}
	
	public static boolean isSupportedScene(String scene)
	{
		return supportedScenes().contains(normalizeScene(scene));
	}
	
	private static int defaultPriority(String scene)
	{
		Binding binding = Binding.scene(scene);
		return binding == null ? 0 : defaultPriority(binding);
	}
	
	private static int defaultPriority(Binding binding)
	{
		if (binding.kind() == Kind.ENTITY_UUID && "aggressive".equals(binding.scene()))
			return -2;
		if (binding.kind() == Kind.ENTITY_TYPE && "aggressive".equals(binding.scene()))
			return -1;
		return switch (normalizeScene(binding.scene())) {
			case "player" -> 0;
			case "aggressive" -> 1;
			case "ambient" -> 2;
			case "idle" -> 3;
			default -> 0;
		};
	}
	
	private static int defaultFadeTime(String scene)
	{
		Binding binding = Binding.scene(scene);
		return binding == null ? 20 : defaultFadeTime(binding);
	}
	
	private static int defaultFadeTime(Binding binding)
	{
		return switch (normalizeScene(binding.scene())) {
			case "ambient" -> 120;
			case "aggressive" -> 40;
			case "idle" -> 60;
			default -> 20;
		};
	}
	
	private static String normalizeScene(String scene)
	{
		return scene == null ? "" : scene.toLowerCase(Locale.ROOT);
	}
	
	private static Map<String, List<String>> urls(Kind kind)
	{
		return switch (kind) {
			case SCENE -> SCENE_URLS;
			case ENTITY_TYPE -> ENTITY_TYPE_URLS;
			case ENTITY_UUID -> ENTITY_UUID_URLS;
			case IDLE_RULE -> IDLE_RULE_URLS;
		};
	}
	
	private static boolean isValidUrl(String url)
	{
		if (url == null || url.isBlank())
			return false;
		String lower = url.toLowerCase(Locale.ROOT);
		return lower.startsWith("http://") || lower.startsWith("https://");
	}

	private static String selectionMode(Binding binding)
	{
		return SELECTION_MODES.getOrDefault(binding.serializedKey(), "random");
	}

	private static String normalizeSelectionMode(String selectionMode)
	{
		if (selectionMode == null)
			return null;
		return switch (selectionMode.toLowerCase(Locale.ROOT)) {
			case "random" -> "random";
			case "sequential", "sequence" -> "sequential";
			case "first", "fixed" -> "first";
			default -> null;
		};
	}
	
	private static void load(MinecraftServer server)
	{
		if (loaded)
			return;
		loaded = true;
		Path path = path(server);
		if (!Files.isRegularFile(path))
			return;
		try {
			JsonObject root = GsonHelper.parse(Files.readString(path, StandardCharsets.UTF_8));
			JsonObject scenes = GsonHelper.getAsJsonObject(root, "scenes", new JsonObject());
			JsonObject entityTypes = GsonHelper.getAsJsonObject(root, "entity_types", new JsonObject());
			JsonObject entityUuids = GsonHelper.getAsJsonObject(root, "entity_uuids", new JsonObject());
			JsonObject idleRules = GsonHelper.getAsJsonObject(root, "idle_rules", new JsonObject());
			JsonObject selectionModes = GsonHelper.getAsJsonObject(root, "selection_modes", new JsonObject());
			JsonObject idleConditions = GsonHelper.getAsJsonObject(root, "idle_conditions", new JsonObject());
			JsonObject idleIntervals = GsonHelper.getAsJsonObject(root, "idle_intervals", new JsonObject());
			JsonObject priorities = GsonHelper.getAsJsonObject(root, "priorities", new JsonObject());
			SCENE_URLS.clear();
			ENTITY_TYPE_URLS.clear();
			ENTITY_UUID_URLS.clear();
			IDLE_RULE_URLS.clear();
			SELECTION_MODES.clear();
			IDLE_CONDITIONS.clear();
			IDLE_INTERVALS.clear();
			PRIORITIES.clear();
			readUrls(scenes, Kind.SCENE, SCENE_URLS);
			readUrls(entityTypes, Kind.ENTITY_TYPE, ENTITY_TYPE_URLS);
			readUrls(entityUuids, Kind.ENTITY_UUID, ENTITY_UUID_URLS);
			readUrls(idleRules, Kind.IDLE_RULE, IDLE_RULE_URLS);
			for (Map.Entry<String, JsonElement> entry : selectionModes.entrySet()) {
				Binding binding = Binding.parse(entry.getKey());
				String mode = normalizeSelectionMode(GsonHelper.convertToString(entry.getValue(), "selection mode"));
				if (binding != null && mode != null)
					SELECTION_MODES.put(binding.serializedKey(), mode);
			}
			readIdleConditions(idleConditions, IDLE_CONDITIONS);
			readIntegerSettings(idleIntervals, IDLE_INTERVALS, 0, 86400);
			readIntegerSettings(priorities, PRIORITIES, -1000, 1000);
		} catch (Exception e) {
			LOGGER.warn("Failed to load server external playlists from {}", path, e);
		}
	}

	private static void readIdleConditions(JsonObject object, Map<String, List<IdleCondition>> output)
	{
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			Binding binding = Binding.parse(entry.getKey());
			if (binding == null || binding.kind() != Kind.IDLE_RULE || !entry.getValue().isJsonArray())
				continue;
			List<IdleCondition> conditions = Lists.newArrayList();
			for (JsonElement element : entry.getValue().getAsJsonArray()) {
				if (!element.isJsonObject())
					continue;
				JsonObject condition = element.getAsJsonObject();
				conditions.add(new IdleCondition(GsonHelper.getAsString(condition, "type"),
						GsonHelper.getAsString(condition, "argument", ""),
						GsonHelper.getAsBoolean(condition, "inverted", false)));
			}
			if (!conditions.isEmpty())
				output.put(binding.serializedKey(), conditions);
		}
	}

	private static void readIntegerSettings(JsonObject object, Map<String, Integer> output, int min, int max)
	{
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			Binding binding = Binding.parse(entry.getKey());
			if (binding == null)
				continue;
			int value = entry.getValue().getAsInt();
			if (value >= min && value <= max)
				output.put(binding.serializedKey(), value);
		}
	}
	
	private static void readUrls(JsonObject object, Kind kind, Map<String, List<String>> output)
	{
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			Binding binding = Binding.create(kind, entry.getKey());
			if (binding == null || !entry.getValue().isJsonArray())
				continue;
			List<String> urls = Lists.newArrayList();
			for (JsonElement urlElement : entry.getValue().getAsJsonArray())
				urls.add(GsonHelper.convertToString(urlElement, "url"));
			if (!urls.isEmpty())
				output.put(binding.storageKey(), urls);
		}
	}
	
	private static void save(MinecraftServer server)
	{
		Path path = path(server);
		try {
			Files.createDirectories(path.getParent());
			JsonObject root = new JsonObject();
			JsonObject scenes = new JsonObject();
			JsonObject entityTypes = new JsonObject();
			JsonObject entityUuids = new JsonObject();
			JsonObject idleRules = new JsonObject();
			writeUrls(scenes, SCENE_URLS);
			writeUrls(entityTypes, ENTITY_TYPE_URLS);
			writeUrls(entityUuids, ENTITY_UUID_URLS);
			writeUrls(idleRules, IDLE_RULE_URLS);
			root.add("scenes", scenes);
			root.add("entity_types", entityTypes);
			root.add("entity_uuids", entityUuids);
			root.add("idle_rules", idleRules);
			JsonObject selectionModes = new JsonObject();
			SELECTION_MODES.forEach(selectionModes::addProperty);
			root.add("selection_modes", selectionModes);
			JsonObject idleConditions = new JsonObject();
			IDLE_CONDITIONS.forEach((binding, conditions) -> {
				JsonArray array = new JsonArray();
				for (IdleCondition condition : conditions) {
					JsonObject object = new JsonObject();
					object.addProperty("type", condition.type());
					object.addProperty("argument", condition.argument());
					object.addProperty("inverted", condition.inverted());
					array.add(object);
				}
				idleConditions.add(binding, array);
			});
			root.add("idle_conditions", idleConditions);
			JsonObject idleIntervals = new JsonObject();
			IDLE_INTERVALS.forEach(idleIntervals::addProperty);
			root.add("idle_intervals", idleIntervals);
			JsonObject priorities = new JsonObject();
			PRIORITIES.forEach(priorities::addProperty);
			root.add("priorities", priorities);
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.error("Failed to save server external playlists to {}", path, e);
		}
	}
	
	private static void writeUrls(JsonObject object, Map<String, List<String>> urlsByTarget)
	{
		urlsByTarget.forEach((target, urls) -> {
			JsonArray array = new JsonArray();
			urls.forEach(array::add);
			object.add(target, array);
		});
	}
	
	private static Path path(MinecraftServer server)
	{
		return server.getServerDirectory().toPath().resolve("config").resolve("mobbattlemusic_server_playlists.json");
	}
	
	public static enum Kind
	{
		SCENE,
		ENTITY_TYPE,
		ENTITY_UUID,
		IDLE_RULE
	}
	
	public static record Binding(Kind kind, String scene, String target)
	{
		private static final String ENTITY_TYPE_PREFIX = "entity_type:";
		private static final String ENTITY_UUID_PREFIX = "entity_uuid:";
		private static final String IDLE_RULE_PREFIX = "idle_rule:";
		private static final String SCENE_PREFIX = "scene:";
		
		public Binding
		{
			scene = normalizeCombatScene(kind == Kind.SCENE ? target : scene);
			target = normalizeTarget(kind, target);
		}

		public static Binding parse(String raw)
		{
			if (raw == null || raw.isBlank())
				return null;
			if (raw.startsWith(ENTITY_TYPE_PREFIX)) {
				String value = raw.substring(ENTITY_TYPE_PREFIX.length());
				int split = value.indexOf(':');
				if (split > 0 && isSupportedEntityScene(value.substring(0, split)))
					return entityType(value.substring(0, split), value.substring(split + 1));
				return entityType("aggressive", value);
			}
			if (raw.startsWith(ENTITY_UUID_PREFIX)) {
				String value = raw.substring(ENTITY_UUID_PREFIX.length());
				int split = value.indexOf(':');
				if (split > 0 && isSupportedEntityScene(value.substring(0, split)))
					return entityUuid(value.substring(0, split), value.substring(split + 1));
				return entityUuid("aggressive", value);
			}
			if (raw.startsWith(IDLE_RULE_PREFIX))
				return idleRule(raw.substring(IDLE_RULE_PREFIX.length()));
			if (raw.startsWith(SCENE_PREFIX))
				return scene(raw.substring(SCENE_PREFIX.length()));
			return scene(raw);
		}
		
		public static Binding create(Kind kind, String target)
		{
			String scene = "aggressive";
			String value = target;
			int split = target == null ? -1 : target.indexOf('|');
			if (kind != Kind.SCENE && split > 0) {
				scene = target.substring(0, split);
				value = target.substring(split + 1);
			}
			return switch (kind) {
				case SCENE -> scene(target);
				case ENTITY_TYPE -> entityType(scene, value);
				case ENTITY_UUID -> entityUuid(scene, value);
				case IDLE_RULE -> idleRule(value);
			};
		}
		
		public static Binding scene(String scene)
		{
			scene = normalizeScene(scene);
			return isSupportedScene(scene) ? new Binding(Kind.SCENE, scene, scene) : null;
		}
		
		public static Binding entityType(ResourceLocation entityType)
		{
			return entityType("aggressive", entityType);
		}
		
		public static Binding entityType(String scene, ResourceLocation entityType)
		{
			EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(entityType);
			ResourceLocation id = type == null ? null : ForgeRegistries.ENTITY_TYPES.getKey(type);
			scene = normalizeCombatScene(scene);
			return id == null || !isSupportedEntityScene(scene) ? null : new Binding(Kind.ENTITY_TYPE, scene, id.toString());
		}
		
		public static Binding entityType(String rawId)
		{
			return entityType("aggressive", rawId);
		}
		
		public static Binding entityType(String scene, String rawId)
		{
			try {
				return entityType(scene, new ResourceLocation(rawId));
			} catch (Exception e) {
				return null;
			}
		}
		
		public static Binding entityUuid(String rawUuid)
		{
			return entityUuid("aggressive", rawUuid);
		}
		
		public static Binding entityUuid(String scene, String rawUuid)
		{
			try {
				scene = normalizeCombatScene(scene);
				if (!isSupportedEntityScene(scene))
					return null;
				return new Binding(Kind.ENTITY_UUID, scene, UUID.fromString(rawUuid).toString());
			} catch (Exception e) {
				return null;
			}
		}

		public static Binding idleRule(String rawId)
		{
			try {
				return new Binding(Kind.IDLE_RULE, "idle", new ResourceLocation(rawId).toString());
			} catch (Exception e) {
				return null;
			}
		}
		
		public String serializedKey()
		{
			return switch (this.kind) {
				case SCENE -> SCENE_PREFIX + this.scene;
				case ENTITY_TYPE -> ENTITY_TYPE_PREFIX + this.scene + ":" + this.target;
				case ENTITY_UUID -> ENTITY_UUID_PREFIX + this.scene + ":" + this.target;
				case IDLE_RULE -> IDLE_RULE_PREFIX + this.target;
			};
		}
		
		public String displayName()
		{
			return switch (this.kind) {
				case SCENE -> "scene " + this.scene;
				case ENTITY_TYPE -> this.scene + " entity_type " + this.target;
				case ENTITY_UUID -> this.scene + " entity_uuid " + this.target;
				case IDLE_RULE -> "idle rule " + this.target;
			};
		}
		
		public String storageKey()
		{
			return switch (this.kind) {
				case SCENE -> this.scene;
				case IDLE_RULE -> this.target;
				default -> this.scene + "|" + this.target;
			};
		}
		
		public String configPath()
		{
			if (this.kind == Kind.SCENE)
				return this.scene;
			if (this.kind == Kind.IDLE_RULE) {
				ResourceLocation id = new ResourceLocation(this.target);
				return "idle/rule/" + id.getNamespace() + "/" + id.getPath();
			}
			if (this.kind == Kind.ENTITY_UUID)
				return this.scene + "/entity_uuid/" + this.target;
			ResourceLocation id = new ResourceLocation(this.target);
			return this.scene + "/entity_type/" + id.getNamespace() + "/" + id.getPath();
		}
		
		private static String normalizeTarget(Kind kind, String target)
		{
			return switch (kind) {
				case SCENE -> normalizeScene(target);
				case ENTITY_TYPE -> new ResourceLocation(target).toString();
				case ENTITY_UUID -> UUID.fromString(target).toString();
				case IDLE_RULE -> new ResourceLocation(target).toString();
			};
		}
		
		private static String normalizeCombatScene(String scene)
		{
			return normalizeScene(scene);
		}
		
		private static boolean isSupportedEntityScene(String scene)
		{
			scene = normalizeCombatScene(scene);
			return "aggressive".equals(scene) || "ambient".equals(scene);
		}
	}
}
