package nonamecrackers2.mobbattlemusic.client.resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;
import nonamecrackers2.mobbattlemusic.MobBattleMusicMod;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadataCache;
import nonamecrackers2.mobbattlemusic.client.sound.track.ConfiguredAggressiveTrack;
import nonamecrackers2.mobbattlemusic.client.sound.track.ConfiguredAmbientTrack;
import nonamecrackers2.mobbattlemusic.client.sound.track.ConfiguredIdleTrack;
import nonamecrackers2.mobbattlemusic.client.sound.track.ConfiguredPlayerTrack;
import nonamecrackers2.mobbattlemusic.client.sound.track.EntityUuidTrack;
import nonamecrackers2.mobbattlemusic.client.sound.track.MobListTrack;
import nonamecrackers2.mobbattlemusic.client.sound.track.MobSpecificTrack;
import nonamecrackers2.mobbattlemusic.client.sound.track.MobTagTrack;
import nonamecrackers2.mobbattlemusic.client.sound.track.MobTrack;
import nonamecrackers2.mobbattlemusic.client.sound.track.PlayerSpecificTrack;
import nonamecrackers2.mobbattlemusic.client.sound.track.TrackType;
import nonamecrackers2.mobbattlemusic.client.util.MobBattleMusicUtils;
import nonamecrackers2.mobbattlemusic.client.util.MobSelection;
import nonamecrackers2.mobbattlemusic.network.ExternalPlaylistCatalogPacket;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;
import nonamecrackers2.mobbattlemusic.network.ServerExternalPlaylistSyncPacket;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;

public class MusicTracksManager extends SimpleJsonResourceReloadListener {
	private static final Gson GSON = new GsonBuilder().create();
	private static final MusicTracksManager INSTANCE = new MusicTracksManager();
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/MusicTracksManager");
	private List<TrackType> tracks;
	private List<TrackType> baseTracks;
	private final ExternalMusicHandler externalMusicHandler;
	private final Map<ResourceLocation, ExternalPlaylist> externalPlaylistsByTrack;
	private final Map<ResourceLocation, ResourceLocation> externalTrackByConfigLocation;
	private final Map<ResourceLocation, ResourceLocation> externalConfigByTrackLocation;
	private final Map<ResourceLocation, ExternalPlaylist> soundPlaylistsByTrack;
	private final Map<ResourceLocation, ResourceLocation> soundTrackByConfigLocation;
	private final Map<ResourceLocation, ResourceLocation> soundConfigByTrackLocation;
	private final Map<ResourceLocation, Integer> externalForcedSelections;
	private final Map<ResourceLocation, Integer> externalSessionSelections;
	private final Map<ResourceLocation, Integer> externalSequentialNextSelections;
	private final Map<ResourceLocation, DynamicExternalTrack> dynamicExternalTracks;
	private final Set<ResourceLocation> registeredDynamicConfigLocations;
	private final Map<String, List<String>> localSceneUrls;
	private final Map<String, List<String>> localEntityTypeUrls;
	private final Map<String, List<String>> localEntityUuidUrls;
	private final Map<String, List<String>> localIdleRuleUrls;
	private final Map<String, ExternalSelectionMode> localSelectionModes;
	private final Map<String, List<IdleCondition>> localIdleConditions;
	private final Map<String, Integer> localIdleIntervals;
	private final Map<String, Integer> localPriorityOverrides;
	private boolean localSceneUrlsLoaded;

	private MusicTracksManager() {
		super(GSON, "music_tracks");
		this.tracks = ImmutableList.copyOf(applyDefaultTrackTypes());
		this.baseTracks = this.tracks;
		this.externalMusicHandler = ExternalMusicHandler.getInstance();
		this.externalPlaylistsByTrack = new java.util.concurrent.ConcurrentHashMap<>();
		this.externalTrackByConfigLocation = new java.util.concurrent.ConcurrentHashMap<>();
		this.externalConfigByTrackLocation = new java.util.concurrent.ConcurrentHashMap<>();
		this.soundPlaylistsByTrack = new java.util.concurrent.ConcurrentHashMap<>();
		this.soundTrackByConfigLocation = new java.util.concurrent.ConcurrentHashMap<>();
		this.soundConfigByTrackLocation = new java.util.concurrent.ConcurrentHashMap<>();
		this.externalForcedSelections = new java.util.concurrent.ConcurrentHashMap<>();
		this.externalSessionSelections = new java.util.concurrent.ConcurrentHashMap<>();
		this.externalSequentialNextSelections = new java.util.concurrent.ConcurrentHashMap<>();
		this.dynamicExternalTracks = new java.util.concurrent.ConcurrentHashMap<>();
		this.registeredDynamicConfigLocations = java.util.concurrent.ConcurrentHashMap.newKeySet();
		this.localSceneUrls = new LinkedHashMap<>();
		this.localEntityTypeUrls = new LinkedHashMap<>();
		this.localEntityUuidUrls = new LinkedHashMap<>();
		this.localIdleRuleUrls = new LinkedHashMap<>();
		this.localSelectionModes = new LinkedHashMap<>();
		this.localIdleConditions = new LinkedHashMap<>();
		this.localIdleIntervals = new LinkedHashMap<>();
		this.localPriorityOverrides = new LinkedHashMap<>();
	}

	private static List<TrackType> applyDefaultTrackTypes() {
		return Lists.newArrayList(TrackType.PLAYER, TrackType.AGGRESSIVE, TrackType.AMBIENT);
	}

	@Override
	protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
		Minecraft mc = Minecraft.getInstance();
		List<TrackType> list = applyDefaultTrackTypes();
		this.externalPlaylistsByTrack.clear();
		this.externalTrackByConfigLocation.clear();
		this.externalConfigByTrackLocation.clear();
		this.soundPlaylistsByTrack.clear();
		this.soundTrackByConfigLocation.clear();
		this.soundConfigByTrackLocation.clear();
		this.externalSessionSelections.clear();
		for (var entry : files.entrySet()) {
			try {
				JsonObject object = GsonHelper.convertToJsonObject(entry.getValue(), "file");

				String type = GsonHelper.getAsString(object, "type");
				
				// Check for external_url/external_urls field
				ResourceLocation track;
				if (object.has("external_url") || object.has("external_urls")) {
					List<ExternalPlaylistEntry> externalEntries = parseExternalUrlList(object);
					track = parseExternalUrlTrack(externalEntries, entry.getKey(), object);
					if (track == null) {
						// If external URL parsing failed, skip this track
						continue;
					}
				} else if (object.has("sound")) {
					track = new ResourceLocation(GsonHelper.getAsString(object, "sound"));
					if (mc.getSoundManager().getSoundEvent(track) == null)
						throw new NullPointerException("Unknown sound with id '" + track + "'");
				} else {
					LOGGER.error("Track configuration '{}' missing both 'sound' and 'external_url' fields", entry.getKey());
					continue;
				}
				
				int priority = GsonHelper.getAsInt(object, "priority");

				switch (type) {
					case "mob_specific": {
						String matchMethod = object.has("match_method") ? GsonHelper.getAsString(object, "match_method")
								: null;
						String matchValue = object.has("match_value") ? GsonHelper.getAsString(object, "match_value")
								: null;
						final String mm = matchMethod;
						final String mv = matchValue;
						insert(list, parseMobTrack(object, track, (t, f, g, s) -> {
							return new MobSpecificTrack(parseEntityType(GsonHelper.getAsString(object, "mob")), mm, mv,
									t, f, g, s);
						}), priority);
						break;
					}
					case "mob_list": {
						insert(list, parseMobTrack(object, track, (t, f, g, s) -> {
							JsonArray array = GsonHelper.getAsJsonArray(object, "mobs");
							List<EntityType<?>> entityTypes = Lists.newArrayList();
							for (JsonElement element : array)
								entityTypes.add(parseEntityType(GsonHelper.convertToString(element, "entity type")));
							return new MobListTrack(entityTypes, t, f, g, s);
						}), priority);
						break;
					}
					case "mob_tag": {
						insert(list, parseMobTrack(object, track, (t, f, g, s) -> {
							TagKey<EntityType<?>> tag = TagKey.codec(Registries.ENTITY_TYPE)
									.parse(JsonOps.INSTANCE, object.get("tag")).resultOrPartial(m -> {
										throw new JsonSyntaxException(m);
									}).get();
							return new MobTagTrack(tag, t, f, g, s);
						}), priority);
						break;
					}
					case "ambient": {
						int fadeTime = GsonHelper.getAsInt(object, "fade_time");
						insert(list, new ConfiguredAmbientTrack(track, fadeTime), priority);
						break;
					}
					case "idle": {
						int fadeTime = GsonHelper.getAsInt(object, "fade_time");
						int interval = GsonHelper.getAsInt(object, "interval_seconds", 0);
						insert(list, new ConfiguredIdleTrack(track, fadeTime, false, List.of(), interval), priority);
						break;
					}
					case "aggressive": {
						int fadeTime = GsonHelper.getAsInt(object, "fade_time");
						insert(list, new ConfiguredAggressiveTrack(track, fadeTime), priority);
						break;
					}
					case "player": {
						int fadeTime = GsonHelper.getAsInt(object, "fade_time");
						insert(list, new ConfiguredPlayerTrack(track, fadeTime), priority);
						break;
					}
					case "player_specific": {
						String name = GsonHelper.getAsString(object, "player_name");
						int fadeTime = GsonHelper.getAsInt(object, "fade_time");
						insert(list, new PlayerSpecificTrack(name, false, track, fadeTime), priority);
						break;
					}
					case "player_specific_uuid": {
						String uuid = GsonHelper.getAsString(object, "player_uuid");
						UUID.fromString(uuid);
						int fadeTime = GsonHelper.getAsInt(object, "fade_time");
						insert(list, new PlayerSpecificTrack(uuid, true, track, fadeTime), priority);
						break;
					}
					default:
						throw new JsonSyntaxException("Unknown type '" + type + "'");
				}
			} catch (Exception e) {
				LOGGER.debug("Failed to generate track for file '" + entry.getKey() + "': ", e);
			}
		}
		this.externalForcedSelections.keySet().removeIf(id -> !this.externalTrackByConfigLocation.containsKey(id));
		this.baseTracks = ImmutableList.copyOf(list);
		this.loadLocalSceneUrls();
		this.refreshLocalDynamicTracks();
		this.rebuildTracksWithDynamic();
		this.syncExternalPlaylistCatalogToServer();
	}

	private static void insert(List<TrackType> list, TrackType track, int index) {
		if (index <= 0)
			list.add(0, track);
		else if (index >= list.size())
			list.add(track);
		else
			list.add(index, track);
	}

	public List<TrackType> getTracks() {
		return this.tracks;
	}

	private static <T extends MobTrack> T parseMobTrack(JsonObject object, ResourceLocation track,
			MusicTracksManager.MobTrackBuilder<T> builder) {
		int fadeTime = GsonHelper.getAsInt(object, "fade_time");
		MobSelection.GroupType group = MobBattleMusicUtils
				.parseEnum(MobSelection.GroupType.class, GsonHelper.getAsString(object, "group")).resultOrPartial(m -> {
					throw new NoSuchElementException(m);
				}).get();
		MobSelection.Selector selector = MobBattleMusicUtils
				.parseEnum(MobSelection.Selector.class, GsonHelper.getAsString(object, "selector"))
				.resultOrPartial(m -> {
					throw new NoSuchElementException(m);
				}).get();
		return builder.make(track, fadeTime, group, selector);
	}

	private static EntityType<?> parseEntityType(String rawId) {
		ResourceLocation id = new ResourceLocation(rawId);
		EntityType<?> entityType = ForgeRegistries.ENTITY_TYPES.getValue(id);
		if (entityType == null)
			throw new NullPointerException("Unknown entity with id: '" + id + "'");
		return entityType;
	}

	public static MusicTracksManager getInstance() {
		return INSTANCE;
	}
	
	private static List<ExternalPlaylistEntry> parseExternalUrlList(JsonObject object) {
		JsonElement element = object.has("external_urls") ? object.get("external_urls") : object.get("external_url");
		List<ExternalPlaylistEntry> entries = Lists.newArrayList();
		if (element.isJsonArray()) {
			int index = 0;
			for (JsonElement urlElement : element.getAsJsonArray()) {
				entries.add(parseExternalPlaylistEntry(urlElement, index));
				index++;
			}
		} else {
			entries.add(parseExternalPlaylistEntry(element, 0));
		}
		if (entries.isEmpty())
			throw new JsonSyntaxException("External URL playlist must not be empty");
		return entries;
	}

	private static ExternalPlaylistEntry parseExternalPlaylistEntry(JsonElement element, int index) {
		String fallbackId = String.valueOf(index + 1);
		if (element.isJsonObject()) {
			JsonObject object = element.getAsJsonObject();
			String id = object.has("id") ? GsonHelper.getAsString(object, "id") : fallbackId;
			String url = GsonHelper.getAsString(object, "url");
			String name = object.has("name") ? GsonHelper.getAsString(object, "name") : id;
			return new ExternalPlaylistEntry(id, name, url);
		}
		return new ExternalPlaylistEntry(fallbackId, fallbackId, GsonHelper.convertToString(element, "external URL"));
	}
	
	/**
	 * Parse and prepare an external URL playlist
	 * @param entries The external URL entries
	 * @param configLocation The configuration file location
	 * @param object The raw track configuration object
	 * @return The ResourceLocation for the track, or null if failed
	 */
	private ResourceLocation parseExternalUrlTrack(List<ExternalPlaylistEntry> entries, ResourceLocation configLocation, JsonObject object) {
		List<ExternalPlaylistEntry> resolvedEntries = Lists.newArrayList();
		for (ExternalPlaylistEntry entry : entries) {
			MusicMetadataCache.getInstance().prepare(entry.url());
			String resolvedUrl = nonamecrackers2.mobbattlemusic.client.music.UrlResolver.resolveUrl(entry.url());
			if (!isValidUrl(resolvedUrl)) {
				LOGGER.error("Invalid external URL in '{}': '{}'. Must be HTTP or HTTPS.", configLocation, entry.url());
				return null;
			}
			resolvedEntries.add(new ExternalPlaylistEntry(entry.id(), entry.name(), resolvedUrl));
		}

		try {
			ExternalSelectionMode selectionMode = object.has("selection")
					? ExternalSelectionMode.fromSerializedName(GsonHelper.getAsString(object, "selection"))
					: ExternalSelectionMode.RANDOM;
			
			for (ExternalPlaylistEntry entry : resolvedEntries) {
				LOGGER.info("Preparing external music for '{}': {} ({})", configLocation, entry.id(), entry.url());
				externalMusicHandler.prepareMusicFile(entry.url()).thenAccept(cachedPath -> {
					if (cachedPath != null) {
						LOGGER.info("Successfully prepared external music: {} -> {}", entry.url(), cachedPath);
					} else {
						LOGGER.error("Failed to prepare external music from URL: {}", entry.url());
					}
				});
			}
			
			String hash = externalMusicHandler.getCache().hashUrl(resolvedEntries.stream()
					.map(ExternalPlaylistEntry::url)
					.reduce("", (a, b) -> a + "\n" + b));
			ResourceLocation location = new ResourceLocation("mobbattlemusic", "external/" + hash);
			
			ExternalPlaylist playlist = new ExternalPlaylist(configLocation, location,
					ImmutableList.copyOf(resolvedEntries), selectionMode);
			this.externalPlaylistsByTrack.put(location, playlist);
			this.externalTrackByConfigLocation.put(configLocation, location);
			this.externalConfigByTrackLocation.put(location, configLocation);
			LOGGER.debug("Mapped ResourceLocation {} to external playlist {} with {} URLs", location, configLocation,
					resolvedEntries.size());
			
			return location;
			
		} catch (Exception e) {
			LOGGER.error("Error preparing external music playlist '{}': {}", configLocation, e.getMessage());
			return null;
		}
	}
	
	/**
	 * Check if a ResourceLocation is an external URL track
	 * @param location The ResourceLocation to check
	 * @return true if this is an external URL track
	 */
	public boolean isExternalUrl(ResourceLocation location) {
		return externalPlaylistsByTrack.containsKey(location);
	}

	public boolean isSoundPlaylist(ResourceLocation location) {
		return this.soundPlaylistsByTrack.containsKey(location);
	}
	
	/**
	 * Get the external URL for a ResourceLocation
	 * @param location The ResourceLocation
	 * @return The URL, or null if not an external URL track
	 */
	public String getExternalUrl(ResourceLocation location) {
		ExternalPlaylist playlist = this.externalPlaylistsByTrack.get(location);
		if (playlist == null)
			return null;
		return playlist.entry(getExternalPlaylistSelectedIndex(playlist, location, false)).url();
	}

	public String selectExternalUrl(ResourceLocation location) {
		ExternalPlaylist playlist = this.externalPlaylistsByTrack.get(location);
		if (playlist == null)
			return null;
		return playlist.entry(getExternalPlaylistSelectedIndex(playlist, location, true)).url();
	}

	public ResourceLocation selectSoundTrack(ResourceLocation location) {
		ExternalPlaylist playlist = this.soundPlaylistsByTrack.get(location);
		if (playlist == null)
			return null;
		String sound = playlist.entry(getExternalPlaylistSelectedIndex(playlist, location, true)).url();
		return soundLocation(sound);
	}

	public void clearExternalSessionSelection(ResourceLocation location) {
		this.externalSessionSelections.remove(location);
	}

	public void clearSoundSessionSelection(ResourceLocation location) {
		ResourceLocation trackLocation = this.soundTrackByConfigLocation.getOrDefault(location, location);
		this.externalSessionSelections.remove(trackLocation);
	}

	public List<ExternalPlaylist> getExternalPlaylists() {
		List<ExternalPlaylist> playlists = Lists.newArrayList();
		for (ResourceLocation location : this.externalTrackByConfigLocation.values()) {
			ExternalPlaylist playlist = this.externalPlaylistsByTrack.get(location);
			if (playlist != null)
				playlists.add(playlist);
		}
		return ImmutableList.copyOf(playlists);
	}

	public List<ExternalPlaylist> getSelectablePlaylists() {
		List<ExternalPlaylist> playlists = Lists.newArrayList(this.getExternalPlaylists());
		for (ResourceLocation location : this.soundTrackByConfigLocation.values()) {
			ExternalPlaylist playlist = this.soundPlaylistsByTrack.get(location);
			if (playlist != null)
				playlists.add(playlist);
		}
		return ImmutableList.copyOf(playlists);
	}

	public DynamicBinding editableBinding(ResourceLocation configLocation) {
		DynamicExternalTrack track = this.dynamicExternalTracks.get(configLocation);
		return track == null ? null : track.binding();
	}

	public DynamicSource dynamicSource(ResourceLocation configLocation) {
		DynamicExternalTrack track = this.dynamicExternalTracks.get(configLocation);
		return track == null ? null : track.source();
	}

	public ExternalSelectionMode dynamicSelectionMode(ResourceLocation configLocation) {
		DynamicExternalTrack track = this.dynamicExternalTracks.get(configLocation);
		return track == null ? null : track.selectionMode();
	}

	public DynamicPlaylistSettings dynamicSettings(ResourceLocation configLocation) {
		DynamicExternalTrack track = this.dynamicExternalTracks.get(configLocation);
		if (track == null)
			return null;
		return new DynamicPlaylistSettings(track.priority(), track.selectionMode(),
				List.copyOf(track.idleConditions()), track.idleIntervalSeconds());
	}
	
	public void applyServerExternalPlaylists(List<ServerExternalPlaylistSyncPacket.TrackDefinition> definitions) {
		this.dynamicExternalTracks.entrySet().removeIf(entry -> entry.getValue().source() == DynamicSource.SERVER);
		for (ServerExternalPlaylistSyncPacket.TrackDefinition definition : definitions) {
			List<ExternalPlaylistEntry> entries = definition.entries().stream()
					.map(entry -> new ExternalPlaylistEntry(entry.id(), entry.name(), entry.url()))
					.toList();
			DynamicBinding binding = DynamicBinding.parse(definition.scene());
			if (!entries.isEmpty() && binding != null) {
				ExternalSelectionMode selectionMode;
				try {
					selectionMode = ExternalSelectionMode.fromSerializedName(definition.selectionMode());
				} catch (JsonSyntaxException e) {
					LOGGER.warn("Ignoring invalid server playlist order '{}' for {}", definition.selectionMode(),
							definition.configLocation());
					selectionMode = ExternalSelectionMode.RANDOM;
				}
				this.dynamicExternalTracks.put(definition.configLocation(), new DynamicExternalTrack(
						definition.configLocation(),
						binding,
						entries,
						definition.priority(),
						definition.fadeTime(),
						selectionMode,
						definition.idleConditions(),
						definition.idleIntervalSeconds(),
						DynamicSource.SERVER));
			}
		}
		this.rebuildTracksWithDynamic();
	}
	
	public PlaylistControlResult addLocalSceneUrl(String scene, String url) {
		DynamicBinding binding = DynamicBinding.scene(scene);
		if (binding == null)
			return PlaylistControlResult.failure("Unsupported battle scene: " + scene);
		return this.addLocalUrl(binding, url);
	}
	
	public PlaylistControlResult addLocalEntityTypeUrl(String entityType, String url) {
		return this.addLocalEntityTypeUrl("aggressive", entityType, url);
	}
	
	public PlaylistControlResult addLocalEntityTypeUrl(String scene, String entityType, String url) {
		DynamicBinding binding = DynamicBinding.entityType(scene, entityType);
		if (binding == null)
			return PlaylistControlResult.failure("Unknown " + scene + " entity type: " + entityType);
		return this.addLocalUrl(binding, url);
	}
	
	public PlaylistControlResult addLocalEntityUuidUrl(String uuid, String url) {
		return this.addLocalEntityUuidUrl("aggressive", uuid, url);
	}
	
	public PlaylistControlResult addLocalEntityUuidUrl(String scene, String uuid, String url) {
		DynamicBinding binding = DynamicBinding.entityUuid(scene, uuid);
		if (binding == null)
			return PlaylistControlResult.failure("Invalid " + scene + " entity UUID: " + uuid);
		return this.addLocalUrl(binding, url);
	}

	public PlaylistControlResult addLocalIdleRuleUrl(String ruleId, String url) {
		DynamicBinding binding = DynamicBinding.idleRule(ruleId);
		if (binding == null)
			return PlaylistControlResult.failure("Invalid idle rule id: " + ruleId);
		return this.addLocalUrl(binding, url);
	}
	
	private PlaylistControlResult addLocalUrl(DynamicBinding binding, String url) {
		String reference = normalizeLocalMusicReference(url);
		if (reference == null)
			return PlaylistControlResult.failure("Invalid music reference: " + url);
		this.loadLocalSceneUrls();
		Map<String, List<String>> urlsByTarget = this.localUrls(binding.kind());
		urlsByTarget.computeIfAbsent(binding.storageKey(), key -> Lists.newArrayList()).add(reference);
		this.saveLocalSceneUrls();
		this.refreshLocalDynamicTracks();
		this.rebuildTracksWithDynamic();
		this.syncExternalPlaylistCatalogToServer();
		return PlaylistControlResult.success("Added local " + binding.displayName() + " music #" +
				urlsByTarget.get(binding.storageKey()).size());
	}
	
	public PlaylistControlResult deleteLocalSceneUrl(String scene, int index) {
		DynamicBinding binding = DynamicBinding.scene(scene);
		if (binding == null)
			return PlaylistControlResult.failure("Unsupported battle scene: " + scene);
		return this.deleteLocalUrl(binding, index);
	}
	
	public PlaylistControlResult deleteLocalEntityTypeUrl(String entityType, int index) {
		return this.deleteLocalEntityTypeUrl("aggressive", entityType, index);
	}
	
	public PlaylistControlResult deleteLocalEntityTypeUrl(String scene, String entityType, int index) {
		DynamicBinding binding = DynamicBinding.entityType(scene, entityType);
		if (binding == null)
			return PlaylistControlResult.failure("Unknown " + scene + " entity type: " + entityType);
		return this.deleteLocalUrl(binding, index);
	}
	
	public PlaylistControlResult deleteLocalEntityUuidUrl(String uuid, int index) {
		return this.deleteLocalEntityUuidUrl("aggressive", uuid, index);
	}
	
	public PlaylistControlResult deleteLocalEntityUuidUrl(String scene, String uuid, int index) {
		DynamicBinding binding = DynamicBinding.entityUuid(scene, uuid);
		if (binding == null)
			return PlaylistControlResult.failure("Invalid " + scene + " entity UUID: " + uuid);
		return this.deleteLocalUrl(binding, index);
	}

	public PlaylistControlResult deleteLocalIdleRuleUrl(String ruleId, int index) {
		DynamicBinding binding = DynamicBinding.idleRule(ruleId);
		if (binding == null)
			return PlaylistControlResult.failure("Invalid idle rule id: " + ruleId);
		return this.deleteLocalUrl(binding, index);
	}
	
	private PlaylistControlResult deleteLocalUrl(DynamicBinding binding, int index) {
		this.loadLocalSceneUrls();
		Map<String, List<String>> urlsByTarget = this.localUrls(binding.kind());
		List<String> urls = urlsByTarget.get(binding.storageKey());
		if (urls == null || index < 0 || index >= urls.size())
			return PlaylistControlResult.failure("Index " + (index + 1) + " out of range for local " +
					binding.displayName());
		String removed = urls.remove(index);
		if (urls.isEmpty()) {
			urlsByTarget.remove(binding.storageKey());
			this.localSelectionModes.remove(binding.serializedKey());
			this.localIdleConditions.remove(binding.serializedKey());
			this.localIdleIntervals.remove(binding.serializedKey());
			this.localPriorityOverrides.remove(binding.serializedKey());
		}
		this.saveLocalSceneUrls();
		this.refreshLocalDynamicTracks();
		this.rebuildTracksWithDynamic();
		this.syncExternalPlaylistCatalogToServer();
		return PlaylistControlResult.success("Deleted local " + binding.displayName() + " URL #" + (index + 1) + ": " + removed);
	}

	public PlaylistControlResult setLocalSelectionMode(DynamicBinding binding, ExternalSelectionMode selectionMode) {
		if (binding == null || selectionMode == null)
			return PlaylistControlResult.failure("Invalid playlist or playback order");
		this.loadLocalSceneUrls();
		List<String> urls = this.localUrls(binding.kind()).get(binding.storageKey());
		if (urls == null || urls.isEmpty())
			return PlaylistControlResult.failure("No local playlist exists for " + binding.displayName());
		this.localSelectionModes.put(binding.serializedKey(), selectionMode);
		this.externalForcedSelections.remove(dynamicConfigLocation(DynamicSource.LOCAL, binding));
		this.externalSessionSelections.remove(dynamicConfigLocation(DynamicSource.LOCAL, binding));
		this.externalSequentialNextSelections.remove(dynamicConfigLocation(DynamicSource.LOCAL, binding));
		this.saveLocalSceneUrls();
		this.refreshLocalDynamicTracks();
		this.rebuildTracksWithDynamic();
		return PlaylistControlResult.success("Set local " + binding.displayName() + " playback order to " +
				selectionMode.getSerializedName());
	}

	public PlaylistControlResult setLocalPriority(DynamicBinding binding, int priority) {
		if (!hasLocalPlaylist(binding))
			return PlaylistControlResult.failure("No local playlist exists for " +
					(binding == null ? "unknown binding" : binding.displayName()));
		this.localPriorityOverrides.put(binding.serializedKey(), priority);
		this.saveLocalSceneUrls();
		this.refreshLocalDynamicTracks();
		this.rebuildTracksWithDynamic();
		return PlaylistControlResult.success("Set local " + binding.displayName() + " priority to " + priority);
	}

	public PlaylistControlResult setLocalIdleInterval(DynamicBinding binding, int seconds) {
		if (binding == null || binding.kind() != DynamicBinding.Kind.IDLE_RULE || !hasLocalPlaylist(binding))
			return PlaylistControlResult.failure("No local idle rule playlist exists");
		this.localIdleIntervals.put(binding.serializedKey(), Math.max(0, seconds));
		this.saveLocalSceneUrls();
		this.refreshLocalDynamicTracks();
		this.rebuildTracksWithDynamic();
		return PlaylistControlResult.success("Set local " + binding.displayName() + " interval to " + seconds + " seconds");
	}

	public PlaylistControlResult addLocalIdleCondition(DynamicBinding binding, IdleCondition condition) {
		if (binding == null || binding.kind() != DynamicBinding.Kind.IDLE_RULE || !hasLocalPlaylist(binding))
			return PlaylistControlResult.failure("No local idle rule playlist exists");
		this.localIdleConditions.computeIfAbsent(binding.serializedKey(), key -> Lists.newArrayList()).add(condition);
		this.saveLocalSceneUrls();
		this.refreshLocalDynamicTracks();
		this.rebuildTracksWithDynamic();
		return PlaylistControlResult.success("Added condition to local " + binding.displayName());
	}

	public PlaylistControlResult deleteLocalIdleCondition(DynamicBinding binding, int index) {
		List<IdleCondition> conditions = binding == null ? null : this.localIdleConditions.get(binding.serializedKey());
		if (conditions == null || index < 0 || index >= conditions.size())
			return PlaylistControlResult.failure("Idle condition index out of range");
		conditions.remove(index);
		if (conditions.isEmpty())
			this.localIdleConditions.remove(binding.serializedKey());
		this.saveLocalSceneUrls();
		this.refreshLocalDynamicTracks();
		this.rebuildTracksWithDynamic();
		return PlaylistControlResult.success("Deleted local idle condition #" + (index + 1));
	}

	private boolean hasLocalPlaylist(DynamicBinding binding) {
		if (binding == null)
			return false;
		this.loadLocalSceneUrls();
		List<String> urls = this.localUrls(binding.kind()).get(binding.storageKey());
		return urls != null && !urls.isEmpty();
	}
	
	public Map<String, List<String>> getLocalSceneUrls() {
		this.loadLocalSceneUrls();
		Map<String, List<String>> copy = new LinkedHashMap<>();
		this.localSceneUrls.forEach((scene, urls) -> copy.put(scene, List.copyOf(urls)));
		return copy;
	}
	
	public Map<String, List<String>> getLocalEntityTypeUrls() {
		this.loadLocalSceneUrls();
		Map<String, List<String>> copy = new LinkedHashMap<>();
		this.localEntityTypeUrls.forEach((entityType, urls) -> copy.put(entityType, List.copyOf(urls)));
		return copy;
	}
	
	public Map<String, List<String>> getLocalEntityUuidUrls() {
		this.loadLocalSceneUrls();
		Map<String, List<String>> copy = new LinkedHashMap<>();
		this.localEntityUuidUrls.forEach((uuid, urls) -> copy.put(uuid, List.copyOf(urls)));
		return copy;
	}
	
	public boolean removeLocalEntityUuid(UUID uuid) {
		this.loadLocalSceneUrls();
		boolean removed = this.localEntityUuidUrls.keySet().removeIf(key -> {
			DynamicBinding binding = DynamicBinding.create(DynamicBinding.Kind.ENTITY_UUID, key);
			return binding != null && binding.target().equals(uuid.toString());
		});
		if (removed) {
			this.localSelectionModes.keySet().removeIf(key -> {
				DynamicBinding binding = DynamicBinding.parse(key);
				return binding != null && binding.kind() == DynamicBinding.Kind.ENTITY_UUID &&
						binding.target().equals(uuid.toString());
			});
			this.saveLocalSceneUrls();
			this.refreshLocalDynamicTracks();
			this.rebuildTracksWithDynamic();
			this.syncExternalPlaylistCatalogToServer();
		}
		return removed;
	}
	
	public void syncExternalPlaylistCatalogToServer() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null)
			return;
		List<ExternalPlaylistCatalogPacket.Playlist> playlists = this.getExternalPlaylists().stream()
				.map(playlist -> new ExternalPlaylistCatalogPacket.Playlist(
						playlist.configLocation(),
						playlist.trackLocation(),
						playlist.entries().stream()
								.map(entry -> new ExternalPlaylistCatalogPacket.Entry(entry.id(), entry.name()))
								.toList()))
				.toList();
		MobBattleMusicNetwork.sendExternalPlaylistCatalogToServer(new ExternalPlaylistCatalogPacket(playlists));
	}

	public int getExternalPlaylistSelectedIndex(ResourceLocation id) {
		ResourceLocation trackLocation = resolveSelectableTrackLocation(id);
		if (trackLocation == null)
			return -1;
		ExternalPlaylist playlist = this.playlistByTrackLocation(trackLocation);
		if (playlist == null)
			return -1;
		Integer forced = this.externalForcedSelections.get(playlist.configLocation());
		if (isValidIndex(playlist, forced))
			return forced;
		Integer session = this.externalSessionSelections.get(trackLocation);
		if (isValidIndex(playlist, session))
			return session;
		return -1;
	}

	public PlaylistControlResult setExternalPlaylistSelection(ResourceLocation id, int index) {
		ResourceLocation trackLocation = resolveSelectableTrackLocation(id);
		if (trackLocation == null)
			return PlaylistControlResult.failure("Unknown playlist: " + id);
		ExternalPlaylist playlist = this.playlistByTrackLocation(trackLocation);
		if (!isValidIndex(playlist, index)) {
			return PlaylistControlResult.failure("Index " + (index + 1) + " out of range for " +
					playlist.configLocation() + " (1-" + playlist.size() + ")");
		}
		this.externalForcedSelections.put(playlist.configLocation(), index);
		this.externalSessionSelections.remove(trackLocation);
		return PlaylistControlResult.success("Selected " + playlist.configLocation() + " #" + (index + 1) +
				" (" + playlist.entry(index).id() + ")");
	}

	public PlaylistControlResult setExternalPlaylistSelection(ResourceLocation id, String selection) {
		ResourceLocation trackLocation = resolveSelectableTrackLocation(id);
		if (trackLocation == null)
			return PlaylistControlResult.failure("Unknown playlist: " + id);
		ExternalPlaylist playlist = this.playlistByTrackLocation(trackLocation);
		int index = resolveEntryIndex(playlist, selection);
		if (index < 0)
			return PlaylistControlResult.failure("Unknown music id/index '" + selection + "' for " + playlist.configLocation());
		return setExternalPlaylistSelection(id, index);
	}

	public PlaylistControlResult randomizeExternalPlaylistSelection(ResourceLocation id) {
		ResourceLocation trackLocation = resolveSelectableTrackLocation(id);
		if (trackLocation == null)
			return PlaylistControlResult.failure("Unknown playlist: " + id);
		ExternalPlaylist playlist = this.playlistByTrackLocation(trackLocation);
		int current = getExternalPlaylistSelectedIndex(playlist.configLocation());
		int next;
		if (playlist.size() <= 1) {
			next = 0;
		} else {
			next = ThreadLocalRandom.current().nextInt(playlist.size() - 1);
			if (next >= current)
				next++;
		}
		this.externalForcedSelections.put(playlist.configLocation(), next);
		this.externalSessionSelections.remove(trackLocation);
		return PlaylistControlResult.success("Random selected " + playlist.configLocation() + " #" + (next + 1) +
				" (" + playlist.entry(next).id() + ")");
	}

	public PlaylistControlResult clearExternalPlaylistSelection(ResourceLocation id) {
		ResourceLocation trackLocation = resolveSelectableTrackLocation(id);
		if (trackLocation == null)
			return PlaylistControlResult.failure("Unknown playlist: " + id);
		ExternalPlaylist playlist = this.playlistByTrackLocation(trackLocation);
		this.externalForcedSelections.remove(playlist.configLocation());
		this.externalSessionSelections.remove(trackLocation);
		return PlaylistControlResult.success("Cleared forced selection for " + playlist.configLocation());
	}

	private int getExternalPlaylistSelectedIndex(ExternalPlaylist playlist, ResourceLocation trackLocation, boolean createSession) {
		Integer forced = this.externalForcedSelections.get(playlist.configLocation());
		if (isValidIndex(playlist, forced))
			return forced;
		Integer session = this.externalSessionSelections.get(trackLocation);
		if (isValidIndex(playlist, session))
			return session;
		if (!createSession)
			return 0;
		int selected = switch (playlist.selectionMode()) {
			case RANDOM -> ThreadLocalRandom.current().nextInt(playlist.size());
			case FIRST -> 0;
			case SEQUENTIAL -> {
				int next = this.externalSequentialNextSelections.getOrDefault(playlist.configLocation(), 0);
				if (next < 0 || next >= playlist.size())
					next = 0;
				this.externalSequentialNextSelections.put(playlist.configLocation(), (next + 1) % playlist.size());
				yield next;
			}
		};
		this.externalSessionSelections.put(trackLocation, selected);
		return selected;
	}

	private static int resolveEntryIndex(ExternalPlaylist playlist, String selection) {
		if (selection == null || selection.isBlank())
			return -1;
		try {
			int index = Integer.parseInt(selection) - 1;
			if (isValidIndex(playlist, index))
				return index;
		} catch (NumberFormatException ignored) {
		}
		for (int i = 0; i < playlist.entries().size(); i++) {
			ExternalPlaylistEntry entry = playlist.entries().get(i);
			if (entry.id().equals(selection) || entry.name().equals(selection))
				return i;
		}
		return -1;
	}

	private ResourceLocation resolveExternalTrackLocation(ResourceLocation id) {
		if (this.externalPlaylistsByTrack.containsKey(id))
			return id;
		return this.externalTrackByConfigLocation.get(id);
	}

	private ResourceLocation resolveSelectableTrackLocation(ResourceLocation id) {
		ResourceLocation trackLocation = resolveExternalTrackLocation(id);
		if (trackLocation != null)
			return trackLocation;
		if (this.soundPlaylistsByTrack.containsKey(id))
			return id;
		return this.soundTrackByConfigLocation.get(id);
	}

	private ExternalPlaylist playlistByTrackLocation(ResourceLocation trackLocation) {
		ExternalPlaylist playlist = this.externalPlaylistsByTrack.get(trackLocation);
		return playlist != null ? playlist : this.soundPlaylistsByTrack.get(trackLocation);
	}

	private static boolean isValidIndex(ExternalPlaylist playlist, Integer index) {
		return index != null && index >= 0 && index < playlist.size();
	}
	
	public String getExternalPlaylistUrl(ResourceLocation id, String selection) {
		ResourceLocation trackLocation = resolveExternalTrackLocation(id);
		if (trackLocation == null)
			return null;
		ExternalPlaylist playlist = this.externalPlaylistsByTrack.get(trackLocation);
		if (playlist == null)
			return null;
		int index = resolveEntryIndex(playlist, selection);
		if (index < 0)
			return null;
		return playlist.entry(index).url();
	}

	public String describeTrack(ResourceLocation trackLocation, String source)
	{
		String context = describeTrackContext(trackLocation);
		String music = describeMusicSource(source == null ? trackLocation.toString() : source);
		return context.isBlank() ? music : music + " - " + context;
	}

	public String describeTrackContext(ResourceLocation trackLocation)
	{
		ResourceLocation configLocation = this.externalConfigByTrackLocation.get(trackLocation);
		if (configLocation == null)
			configLocation = this.soundConfigByTrackLocation.get(trackLocation);
		if (configLocation == null && this.dynamicExternalTracks.containsKey(trackLocation))
			configLocation = trackLocation;
		DynamicExternalTrack dynamicTrack = configLocation == null ? null : this.dynamicExternalTracks.get(configLocation);
		if (dynamicTrack != null)
			return describeBinding(dynamicTrack.binding());
		return configLocation == null ? trackLocation.toString() : configLocation.toString();
	}

	public String describeMusicSource(String source)
	{
		return MusicMetadataCache.getInstance().get(source)
				.map(metadata -> {
					String artist = metadata.displayArtist();
					return artist.isBlank() ? metadata.displayTitle(source) : metadata.displayTitle(source) + " / " + artist;
				})
				.orElseGet(() -> {
					ResourceLocation sound = soundLocation(source);
					return sound == null ? source : "sound:" + sound;
				});
	}
	
	public static List<String> supportedDynamicScenes() {
		return List.of("player", "aggressive", "ambient", "idle");
	}
	
	public static boolean isSupportedDynamicScene(String scene) {
		return supportedDynamicScenes().contains(normalizeScene(scene));
	}
	
	public static ResourceLocation dynamicConfigLocation(DynamicSource source, String scene) {
		DynamicBinding binding = DynamicBinding.scene(scene);
		return MobBattleMusicMod.id(source.getSerializedName() + "/" + (binding == null ? normalizeScene(scene) : binding.configPath()));
	}
	
	public static ResourceLocation dynamicConfigLocation(DynamicSource source, DynamicBinding binding) {
		return MobBattleMusicMod.id(source.getSerializedName() + "/" + binding.configPath());
	}
	
	public static int defaultPriority(String scene) {
		DynamicBinding binding = DynamicBinding.scene(scene);
		return binding == null ? 0 : defaultPriority(binding);
	}
	
	public static int defaultPriority(DynamicBinding binding) {
		if (binding.kind() == DynamicBinding.Kind.ENTITY_UUID && "aggressive".equals(binding.scene()))
			return -2;
		if (binding.kind() == DynamicBinding.Kind.ENTITY_TYPE && "aggressive".equals(binding.scene()))
			return -1;
		return switch (normalizeScene(binding.scene())) {
			case "player" -> 0;
			case "aggressive" -> 1;
			case "ambient" -> 2;
			case "idle" -> 3;
			default -> 0;
		};
	}
	
	public static int defaultFadeTime(String scene) {
		DynamicBinding binding = DynamicBinding.scene(scene);
		return binding == null ? 20 : defaultFadeTime(binding);
	}
	
	public static int defaultFadeTime(DynamicBinding binding) {
		return switch (normalizeScene(binding.scene())) {
			case "player" -> (int)(MobBattleMusicConfig.CLIENT.playerFadeTime.get() * 20.0D);
			case "aggressive" -> (int)(MobBattleMusicConfig.CLIENT.aggressiveFadeTime.get() * 20.0D);
			case "ambient" -> (int)(MobBattleMusicConfig.CLIENT.nonAggressiveFadeTime.get() * 20.0D);
			case "idle" -> 60;
			default -> 20;
		};
	}
	
	private static String normalizeScene(String scene) {
		return scene == null ? "" : scene.toLowerCase(Locale.ROOT);
	}

	private static String describeBinding(DynamicBinding binding)
	{
		String scene = switch (binding.scene()) {
			case "player" -> "player";
			case "ambient" -> "ambient";
			case "aggressive" -> "boss/aggressive";
			case "idle" -> "idle";
			default -> binding.scene();
		};
		return switch (binding.kind()) {
			case SCENE -> scene;
			case ENTITY_TYPE -> scene + " type " + binding.target();
			case ENTITY_UUID -> scene + " uuid " + binding.target();
			case IDLE_RULE -> "idle rule " + binding.target();
		};
	}
	
	private Map<String, List<String>> localUrls(DynamicBinding.Kind kind) {
		return switch (kind) {
			case SCENE -> this.localSceneUrls;
			case ENTITY_TYPE -> this.localEntityTypeUrls;
			case ENTITY_UUID -> this.localEntityUuidUrls;
			case IDLE_RULE -> this.localIdleRuleUrls;
		};
	}
	
	private void refreshLocalDynamicTracks() {
		this.dynamicExternalTracks.entrySet().removeIf(entry -> entry.getValue().source() == DynamicSource.LOCAL);
		this.refreshLocalDynamicTracks(DynamicBinding.Kind.SCENE, this.localSceneUrls);
		this.refreshLocalDynamicTracks(DynamicBinding.Kind.ENTITY_TYPE, this.localEntityTypeUrls);
		this.refreshLocalDynamicTracks(DynamicBinding.Kind.ENTITY_UUID, this.localEntityUuidUrls);
		this.refreshLocalDynamicTracks(DynamicBinding.Kind.IDLE_RULE, this.localIdleRuleUrls);
	}
	
	private void refreshLocalDynamicTracks(DynamicBinding.Kind kind, Map<String, List<String>> urlsByTarget) {
		urlsByTarget.forEach((key, urls) -> {
			DynamicBinding binding = DynamicBinding.create(kind, key);
			if (binding == null || urls.isEmpty())
				return;
			List<ExternalPlaylistEntry> entries = Lists.newArrayList();
			for (int i = 0; i < urls.size(); i++)
				entries.add(new ExternalPlaylistEntry(String.valueOf(i + 1), "local_" + (i + 1), urls.get(i)));
			ResourceLocation id = dynamicConfigLocation(DynamicSource.LOCAL, binding);
			ExternalSelectionMode selectionMode = this.localSelectionModes.getOrDefault(binding.serializedKey(),
					ExternalSelectionMode.RANDOM);
			List<IdleCondition> idleConditions = this.localIdleConditions.getOrDefault(binding.serializedKey(), List.of());
			int idleInterval = this.localIdleIntervals.getOrDefault(binding.serializedKey(), 0);
			this.dynamicExternalTracks.put(id, new DynamicExternalTrack(id, binding, entries,
					this.localPriorityOverrides.getOrDefault(binding.serializedKey(), defaultPriority(binding)),
					defaultFadeTime(binding), selectionMode, idleConditions, idleInterval, DynamicSource.LOCAL));
		});
	}
	
	private void rebuildTracksWithDynamic() {
		this.removeRegisteredDynamicExternalPlaylists();
		List<TrackType> merged = Lists.newArrayList(this.baseTracks);
		List<DynamicExternalTrack> dynamicTracks = this.dynamicExternalTracks.values().stream()
				.sorted(java.util.Comparator.comparingInt(DynamicExternalTrack::priority).reversed()
						.thenComparingInt(MusicTracksManager::dynamicSourceInsertionRank)
						.thenComparing(track -> track.configLocation().toString()))
				.toList();
		for (DynamicExternalTrack dynamicTrack : dynamicTracks) {
			if (dynamicTrack.entries().isEmpty())
				continue;
			TrackType track = this.createDynamicTrack(dynamicTrack);
			if (track == null)
				continue;
			ResolvedDynamicPlaylist resolvedPlaylist = this.createDynamicPlaylist(dynamicTrack);
			if (resolvedPlaylist == null)
				continue;
			if (resolvedPlaylist.kind() == DynamicPlaylistKind.SOUND) {
				this.soundPlaylistsByTrack.put(dynamicTrack.configLocation(), resolvedPlaylist.playlist());
				this.soundTrackByConfigLocation.put(dynamicTrack.configLocation(), dynamicTrack.configLocation());
				this.soundConfigByTrackLocation.put(dynamicTrack.configLocation(), dynamicTrack.configLocation());
			} else {
				this.externalPlaylistsByTrack.put(dynamicTrack.configLocation(), resolvedPlaylist.playlist());
				this.externalTrackByConfigLocation.put(dynamicTrack.configLocation(), dynamicTrack.configLocation());
				this.externalConfigByTrackLocation.put(dynamicTrack.configLocation(), dynamicTrack.configLocation());
			}
			this.registeredDynamicConfigLocations.add(dynamicTrack.configLocation());
			insert(merged, track, dynamicTrack.priority());
		}
		this.tracks = ImmutableList.copyOf(merged);
	}
	
	private static int dynamicSourceInsertionRank(DynamicExternalTrack track) {
		return track.source() == DynamicSource.SERVER ? 1 : 0;
	}
	
	private void removeRegisteredDynamicExternalPlaylists() {
		for (ResourceLocation configLocation : this.registeredDynamicConfigLocations) {
			this.externalPlaylistsByTrack.remove(configLocation);
			this.externalTrackByConfigLocation.remove(configLocation);
			this.externalConfigByTrackLocation.remove(configLocation);
			this.soundPlaylistsByTrack.remove(configLocation);
			this.soundTrackByConfigLocation.remove(configLocation);
			this.soundConfigByTrackLocation.remove(configLocation);
			this.externalSessionSelections.remove(configLocation);
			this.externalForcedSelections.remove(configLocation);
		}
		this.registeredDynamicConfigLocations.clear();
	}
	
	private TrackType createDynamicTrack(DynamicExternalTrack dynamicTrack) {
		DynamicBinding binding = dynamicTrack.binding();
		if (binding.kind() == DynamicBinding.Kind.IDLE_RULE)
			return new ConfiguredIdleTrack(dynamicTrack.configLocation(), dynamicTrack.fadeTime(),
					dynamicTrack.source() == DynamicSource.SERVER, dynamicTrack.idleConditions(),
					dynamicTrack.idleIntervalSeconds());
		if (binding.kind() == DynamicBinding.Kind.ENTITY_TYPE) {
			MobSelection.GroupType group = entityGroup(binding.scene());
			MobSelection.Selector selector = entitySelector(binding.scene());
			boolean matchPanicTarget = "aggressive".equals(binding.scene());
			return new MobSpecificTrack(parseEntityType(binding.target()), null, null, dynamicTrack.configLocation(),
					dynamicTrack.fadeTime(), group, selector, matchPanicTarget);
		}
		if (binding.kind() == DynamicBinding.Kind.ENTITY_UUID) {
			MobSelection.GroupType group = entityGroup(binding.scene());
			MobSelection.Selector selector = entitySelector(binding.scene());
			boolean matchPanicTarget = "aggressive".equals(binding.scene());
			return new EntityUuidTrack(UUID.fromString(binding.target()), dynamicTrack.configLocation(),
					dynamicTrack.fadeTime(), group, selector, matchPanicTarget);
		}
		return switch (binding.scene()) {
			case "player" -> new ConfiguredPlayerTrack(dynamicTrack.configLocation(), dynamicTrack.fadeTime());
			case "aggressive" -> new ConfiguredAggressiveTrack(dynamicTrack.configLocation(), dynamicTrack.fadeTime());
			case "ambient" -> new ConfiguredAmbientTrack(dynamicTrack.configLocation(), dynamicTrack.fadeTime());
			case "idle" -> new ConfiguredIdleTrack(dynamicTrack.configLocation(), dynamicTrack.fadeTime(), false,
					List.of(), dynamicTrack.idleIntervalSeconds());
			default -> null;
		};
	}
	
	private static MobSelection.GroupType entityGroup(String scene) {
		return "ambient".equals(scene) ? MobSelection.GroupType.ENEMIES : MobSelection.GroupType.ATTACKING;
	}
	
	private static MobSelection.Selector entitySelector(String scene) {
		return "ambient".equals(scene) ? MobSelection.Selector.LINE_OF_SIGHT : MobSelection.Selector.ANY;
	}
	
	private ResolvedDynamicPlaylist createDynamicPlaylist(DynamicExternalTrack dynamicTrack) {
		List<ExternalPlaylistEntry> resolvedEntries = Lists.newArrayList();
		DynamicPlaylistKind kind = null;
		for (ExternalPlaylistEntry entry : dynamicTrack.entries()) {
			ResourceLocation sound = soundLocation(entry.url());
			if (sound != null) {
				if (kind == DynamicPlaylistKind.EXTERNAL) {
					LOGGER.warn("Skipping sound entry '{}' in mixed URL playlist '{}'", entry.url(),
							dynamicTrack.configLocation());
					continue;
				}
				kind = DynamicPlaylistKind.SOUND;
				resolvedEntries.add(new ExternalPlaylistEntry(entry.id(), entry.name(), soundReference(sound)));
				continue;
			}
			String resolvedUrl = nonamecrackers2.mobbattlemusic.client.music.UrlResolver.resolveUrl(entry.url());
			if (!isValidUrl(resolvedUrl)) {
				LOGGER.error("Invalid dynamic external URL in '{}': '{}'", dynamicTrack.configLocation(), entry.url());
				continue;
			}
			if (kind == DynamicPlaylistKind.SOUND) {
				LOGGER.warn("Skipping URL entry '{}' in mixed sound playlist '{}'", entry.url(),
						dynamicTrack.configLocation());
				continue;
			}
			kind = DynamicPlaylistKind.EXTERNAL;
			MusicMetadataCache.getInstance().prepare(entry.url());
			resolvedEntries.add(new ExternalPlaylistEntry(entry.id(), entry.name(), resolvedUrl));
			externalMusicHandler.prepareMusicFile(resolvedUrl);
		}
		if (resolvedEntries.isEmpty())
			return null;
		return new ResolvedDynamicPlaylist(new ExternalPlaylist(dynamicTrack.configLocation(), dynamicTrack.configLocation(),
				ImmutableList.copyOf(resolvedEntries), dynamicTrack.selectionMode()), kind);
	}
	
	private void loadLocalSceneUrls() {
		if (this.localSceneUrlsLoaded)
			return;
		this.localSceneUrlsLoaded = true;
		Path path = localSceneUrlsPath();
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
			this.localSceneUrls.clear();
			this.localEntityTypeUrls.clear();
			this.localEntityUuidUrls.clear();
			this.localIdleRuleUrls.clear();
			this.localSelectionModes.clear();
			this.localIdleConditions.clear();
			this.localIdleIntervals.clear();
			this.localPriorityOverrides.clear();
			this.readLocalUrls(scenes, DynamicBinding.Kind.SCENE, this.localSceneUrls);
			this.readLocalUrls(entityTypes, DynamicBinding.Kind.ENTITY_TYPE, this.localEntityTypeUrls);
			this.readLocalUrls(entityUuids, DynamicBinding.Kind.ENTITY_UUID, this.localEntityUuidUrls);
			this.readLocalUrls(idleRules, DynamicBinding.Kind.IDLE_RULE, this.localIdleRuleUrls);
			for (Map.Entry<String, JsonElement> entry : selectionModes.entrySet()) {
				DynamicBinding binding = DynamicBinding.parse(entry.getKey());
				if (binding == null)
					continue;
				try {
					this.localSelectionModes.put(binding.serializedKey(),
							ExternalSelectionMode.fromSerializedName(GsonHelper.convertToString(entry.getValue(), "selection mode")));
				} catch (JsonSyntaxException e) {
					LOGGER.warn("Ignoring invalid local playlist order for {}", entry.getKey());
				}
			}
			readIdleConditions(idleConditions, this.localIdleConditions);
			readIntegerSettings(idleIntervals, this.localIdleIntervals, 0, 86400);
			readIntegerSettings(priorities, this.localPriorityOverrides, -1000, 1000);
		} catch (Exception e) {
			LOGGER.warn("Failed to load local external playlist cache from {}", path, e);
		}
	}

	private static void readIdleConditions(JsonObject object, Map<String, List<IdleCondition>> output) {
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			DynamicBinding binding = DynamicBinding.parse(entry.getKey());
			if (binding == null || binding.kind() != DynamicBinding.Kind.IDLE_RULE || !entry.getValue().isJsonArray())
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

	private static void readIntegerSettings(JsonObject object, Map<String, Integer> output, int min, int max) {
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			DynamicBinding binding = DynamicBinding.parse(entry.getKey());
			if (binding == null)
				continue;
			int value = entry.getValue().getAsInt();
			if (value >= min && value <= max)
				output.put(binding.serializedKey(), value);
		}
	}
	
	private void readLocalUrls(JsonObject object, DynamicBinding.Kind kind, Map<String, List<String>> output) {
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			DynamicBinding binding = DynamicBinding.create(kind, entry.getKey());
			if (binding == null || !entry.getValue().isJsonArray())
				continue;
			List<String> urls = Lists.newArrayList();
			for (JsonElement urlElement : entry.getValue().getAsJsonArray())
				urls.add(GsonHelper.convertToString(urlElement, "url"));
			if (!urls.isEmpty())
				output.put(binding.storageKey(), urls);
		}
	}
	
	private void saveLocalSceneUrls() {
		Path path = localSceneUrlsPath();
		try {
			Files.createDirectories(path.getParent());
			JsonObject root = new JsonObject();
			JsonObject scenes = new JsonObject();
			JsonObject entityTypes = new JsonObject();
			JsonObject entityUuids = new JsonObject();
			JsonObject idleRules = new JsonObject();
			writeUrls(scenes, this.localSceneUrls);
			writeUrls(entityTypes, this.localEntityTypeUrls);
			writeUrls(entityUuids, this.localEntityUuidUrls);
			writeUrls(idleRules, this.localIdleRuleUrls);
			root.add("scenes", scenes);
			root.add("entity_types", entityTypes);
			root.add("entity_uuids", entityUuids);
			root.add("idle_rules", idleRules);
			JsonObject selectionModes = new JsonObject();
			this.localSelectionModes.forEach((binding, mode) -> selectionModes.addProperty(binding,
					mode.getSerializedName()));
			root.add("selection_modes", selectionModes);
			JsonObject idleConditions = new JsonObject();
			this.localIdleConditions.forEach((binding, conditions) -> {
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
			this.localIdleIntervals.forEach(idleIntervals::addProperty);
			root.add("idle_intervals", idleIntervals);
			JsonObject priorities = new JsonObject();
			this.localPriorityOverrides.forEach(priorities::addProperty);
			root.add("priorities", priorities);
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.error("Failed to save local external playlist cache to {}", path, e);
		}
	}
	
	private static void writeUrls(JsonObject object, Map<String, List<String>> urlsByTarget) {
		urlsByTarget.forEach((target, urls) -> {
			JsonArray array = new JsonArray();
			urls.forEach(array::add);
			object.add(target, array);
		});
	}
	
	private static Path localSceneUrlsPath() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("mobbattlemusic_local_playlists.json");
	}
	
	/**
	 * Validate if a URL is valid HTTP or HTTPS
	 * @param url The URL to validate
	 * @return true if valid
	 */
	private boolean isValidUrl(String url) {
		if (url == null || url.isEmpty()) {
			return false;
		}
		
		String lowerUrl = url.toLowerCase();
		return lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://");
	}

	private String normalizeLocalMusicReference(String raw) {
		if (raw == null || raw.isBlank())
			return null;
		ResourceLocation sound = soundLocation(raw);
		if (sound != null)
			return soundReference(sound);
		String resolvedUrl = nonamecrackers2.mobbattlemusic.client.music.UrlResolver.resolveUrl(raw);
		return isValidUrl(resolvedUrl) ? raw : null;
	}

	private static String soundReference(ResourceLocation sound) {
		return "sound:" + sound;
	}

	private static ResourceLocation soundLocation(String raw) {
		if (raw == null || raw.isBlank())
			return null;
		String id = raw.startsWith("sound:") ? raw.substring("sound:".length()) : raw;
		ResourceLocation location;
		try {
			location = new ResourceLocation(id);
		} catch (Exception e) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		return mc.getSoundManager().getSoundEvent(location) == null ? null : location;
	}

	@FunctionalInterface
	public static interface MobTrackBuilder<T extends MobTrack> {
		T make(ResourceLocation track, int fadeTime, MobSelection.GroupType group, MobSelection.Selector selector);
	}
	
	public static record DynamicExternalTrack(ResourceLocation configLocation, DynamicBinding binding,
			List<ExternalPlaylistEntry> entries, int priority, int fadeTime, ExternalSelectionMode selectionMode,
			List<IdleCondition> idleConditions, int idleIntervalSeconds, DynamicSource source) {
		public DynamicExternalTrack {
			entries = List.copyOf(entries);
			idleConditions = List.copyOf(idleConditions);
		}
	}

	private static record ResolvedDynamicPlaylist(ExternalPlaylist playlist, DynamicPlaylistKind kind) {}

	private static enum DynamicPlaylistKind {
		EXTERNAL,
		SOUND
	}
	
	public static record DynamicBinding(Kind kind, String scene, String target) {
		private static final String ENTITY_TYPE_PREFIX = "entity_type:";
		private static final String ENTITY_UUID_PREFIX = "entity_uuid:";
		private static final String IDLE_RULE_PREFIX = "idle_rule:";
		private static final String SCENE_PREFIX = "scene:";
		
		public DynamicBinding {
			scene = normalizeCombatScene(kind == Kind.SCENE ? target : scene);
			target = normalizeTarget(kind, target);
		}
		
		public static DynamicBinding parse(String raw) {
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
		
		public static DynamicBinding create(Kind kind, String target) {
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
		
		public static DynamicBinding scene(String scene) {
			scene = normalizeScene(scene);
			return isSupportedDynamicScene(scene) ? new DynamicBinding(Kind.SCENE, scene, scene) : null;
		}
		
		public static DynamicBinding entityType(String rawId) {
			return entityType("aggressive", rawId);
		}
		
		public static DynamicBinding entityType(String scene, String rawId) {
			try {
				scene = normalizeCombatScene(scene);
				if (!isSupportedEntityScene(scene))
					return null;
				EntityType<?> type = parseEntityType(rawId);
				ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
				return id == null ? null : new DynamicBinding(Kind.ENTITY_TYPE, scene, id.toString());
			} catch (Exception e) {
				return null;
			}
		}
		
		public static DynamicBinding entityUuid(String rawUuid) {
			return entityUuid("aggressive", rawUuid);
		}
		
		public static DynamicBinding entityUuid(String scene, String rawUuid) {
			try {
				scene = normalizeCombatScene(scene);
				if (!isSupportedEntityScene(scene))
					return null;
				return new DynamicBinding(Kind.ENTITY_UUID, scene, UUID.fromString(rawUuid).toString());
			} catch (Exception e) {
				return null;
			}
		}

		public static DynamicBinding idleRule(String rawId) {
			try {
				return new DynamicBinding(Kind.IDLE_RULE, "idle", new ResourceLocation(rawId).toString());
			} catch (Exception e) {
				return null;
			}
		}
		
		public String serializedKey() {
			return switch (this.kind) {
				case SCENE -> SCENE_PREFIX + this.scene;
				case ENTITY_TYPE -> ENTITY_TYPE_PREFIX + this.scene + ":" + this.target;
				case ENTITY_UUID -> ENTITY_UUID_PREFIX + this.scene + ":" + this.target;
				case IDLE_RULE -> IDLE_RULE_PREFIX + this.target;
			};
		}
		
		public String displayName() {
			return switch (this.kind) {
				case SCENE -> "scene " + this.scene;
				case ENTITY_TYPE -> this.scene + " entity_type " + this.target;
				case ENTITY_UUID -> this.scene + " entity_uuid " + this.target;
				case IDLE_RULE -> "idle rule " + this.target;
			};
		}
		
		public String storageKey() {
			return switch (this.kind) {
				case SCENE -> this.scene;
				case IDLE_RULE -> this.target;
				default -> this.scene + "|" + this.target;
			};
		}
		
		public String configPath() {
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
		
		private static String normalizeTarget(Kind kind, String target) {
			return switch (kind) {
				case SCENE -> normalizeScene(target);
				case ENTITY_TYPE -> new ResourceLocation(target).toString();
				case ENTITY_UUID -> UUID.fromString(target).toString();
				case IDLE_RULE -> new ResourceLocation(target).toString();
			};
		}
		
		private static String normalizeCombatScene(String scene) {
			return normalizeScene(scene);
		}
		
		private static boolean isSupportedEntityScene(String scene) {
			scene = normalizeCombatScene(scene);
			return "aggressive".equals(scene) || "ambient".equals(scene);
		}
		
		public static enum Kind {
			SCENE,
			ENTITY_TYPE,
			ENTITY_UUID,
			IDLE_RULE
		}
	}
	
	public static enum DynamicSource {
		LOCAL("local"),
		SERVER("server");
		
		private final String serializedName;
		
		DynamicSource(String serializedName) {
			this.serializedName = serializedName;
		}
		
		public String getSerializedName() {
			return this.serializedName;
		}
	}

	public static record ExternalPlaylist(ResourceLocation configLocation, ResourceLocation trackLocation,
			List<ExternalPlaylistEntry> entries, ExternalSelectionMode selectionMode) {
		public ExternalPlaylistEntry entry(int index) {
			return this.entries.get(index);
		}

		public int size() {
			return this.entries.size();
		}

		public List<String> urls() {
			return this.entries.stream().map(ExternalPlaylistEntry::url).toList();
		}
	}

	public static record ExternalPlaylistEntry(String id, String name, String url) {}

	public static record DynamicPlaylistSettings(int priority, ExternalSelectionMode selectionMode,
			List<IdleCondition> idleConditions, int idleIntervalSeconds) {}

	public static record PlaylistControlResult(boolean success, String message) {
		public static PlaylistControlResult success(String message) {
			return new PlaylistControlResult(true, message);
		}

		public static PlaylistControlResult failure(String message) {
			return new PlaylistControlResult(false, message);
		}
	}

	public static enum ExternalSelectionMode {
		RANDOM,
		SEQUENTIAL,
		FIRST;

		public String getSerializedName() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public ExternalSelectionMode next() {
			return switch (this) {
				case RANDOM -> SEQUENTIAL;
				case SEQUENTIAL -> FIRST;
				case FIRST -> RANDOM;
			};
		}

		public static ExternalSelectionMode fromSerializedName(String name) {
			if ("first".equalsIgnoreCase(name) || "fixed".equalsIgnoreCase(name))
				return FIRST;
			if ("sequential".equalsIgnoreCase(name) || "sequence".equalsIgnoreCase(name))
				return SEQUENTIAL;
			if ("random".equalsIgnoreCase(name))
				return RANDOM;
			throw new JsonSyntaxException("Unknown external playlist selection mode '" + name + "'");
		}
	}
}
