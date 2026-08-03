package nonamecrackers2.mobbattlemusic.client.resource;

import javax.annotation.Nullable;

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
import nonamecrackers2.mobbattlemusic.client.music.IdleConditionStateClient;
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
import nonamecrackers2.mobbattlemusic.client.audio.PreviewChannel;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;

public class MusicTracksManager extends SimpleJsonResourceReloadListener {
	private static final Gson GSON = new GsonBuilder().create();
	private static final MusicTracksManager INSTANCE = new MusicTracksManager();
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/MusicTracksManager");
	// AUD-18: per-playlist monotonic revision, keyed by configLocation
	private static final java.util.concurrent.ConcurrentHashMap<String, Integer> PLAYLIST_REVISIONS =
			new java.util.concurrent.ConcurrentHashMap<>();
	// AUD-30 v1.2: data-change commit timestamp for deltaMs measurement
	private static volatile long lastDataChangeMillis;
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
	private final Map<String, List<List<IdleCondition>>> localEntryConditions;
	private final Map<String, Integer> localIdleIntervals;
	private final Map<String, Integer> localPriorityOverrides;
	private final Set<String> disabledMusicEntries;
	private boolean localSceneUrlsLoaded;
	private boolean musicStateLoaded;

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
		this.localEntryConditions = new LinkedHashMap<>();
		this.localIdleIntervals = new LinkedHashMap<>();
		this.localPriorityOverrides = new LinkedHashMap<>();
		this.disabledMusicEntries = new java.util.HashSet<>();
	}

	private static List<TrackType> applyDefaultTrackTypes() {
		return Lists.newArrayList(TrackType.PLAYER, TrackType.AGGRESSIVE, TrackType.AMBIENT);
	}

	/**
	 * AUD-17: stable entry identifier. URL-derived so it survives insert/delete/
	 * reorder (never a list index); a URL change yields a new key, which is
	 * exactly what invalidation must detect. Legacy data without ids inherits
	 * this automatically - no persistence migration is required.
	 */
	public static String entryId(String url)
	{
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
			byte[] hash = digest.digest((url == null ? "" : url).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(13);
			for (int i = 0; i < 6; i++)
				hex.append(String.format(java.util.Locale.ROOT, "%02x", hash[i]));
			return "u_" + hex;
		} catch (java.security.NoSuchAlgorithmException e) {
			return "u_" + Integer.toHexString((url == null ? "" : url).hashCode());
		}
	}

	public static int playlistRevision(ResourceLocation configLocation)
	{
		return configLocation == null ? 0 : PLAYLIST_REVISIONS.getOrDefault(configLocation.toString(), 0);
	}

	public static void bumpPlaylistRevision(ResourceLocation configLocation)
	{
		if (configLocation != null) {
			PLAYLIST_REVISIONS.merge(configLocation.toString(), 1, Integer::sum);
			// AUD-30 v1.2: stamp the data-change commit time for deltaMs
			MusicTracksManager.lastDataChangeMillis = System.currentTimeMillis();
		}
	}

	public static void bumpAllPlaylistRevisions()
	{
		for (String key : PLAYLIST_REVISIONS.keySet())
			PLAYLIST_REVISIONS.merge(key, 1, Integer::sum);
		// AUD-30 v1.2: stamp the data-change commit time for deltaMs
		MusicTracksManager.lastDataChangeMillis = System.currentTimeMillis();
	}

	/**
	 * AUD-30 v1.2: commit time of the last data-layer change, consumed by the
	 * AUD-19 invalidation log to compute deltaMs in code.
	 */
	public static long lastDataChangeMillis()
	{
		return MusicTracksManager.lastDataChangeMillis;
	}

	@Override
	protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
		Minecraft mc = Minecraft.getInstance();
		this.loadMusicState();
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
		// AUD-18 #5: whole reload invalidates every playlist
		MusicTracksManager.bumpAllPlaylistRevisions();
		// AUD-20 v1.1: entries may have vanished with the reload
		this.stopPreviewIfInvalid();
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
		if (element.isJsonObject()) {
			JsonObject object = element.getAsJsonObject();
			String url = GsonHelper.getAsString(object, "url");
			// Legacy data without an explicit id falls back to the URL-derived
			// stable key (AUD-17), so reorders never rename entries
			String id = object.has("id") ? GsonHelper.getAsString(object, "id") : entryId(url);
			String name = object.has("name") ? GsonHelper.getAsString(object, "name") : id;
			return new ExternalPlaylistEntry(id, name, url, parseConditions(object, "conditions"));
		}
		String url = GsonHelper.convertToString(element, "external URL");
		return new ExternalPlaylistEntry(entryId(url), entryId(url), url);
	}

	private static List<IdleCondition> parseConditions(JsonObject object, String member)
	{
		List<IdleCondition> conditions = Lists.newArrayList();
		for (JsonElement element : GsonHelper.getAsJsonArray(object, member, new JsonArray())) {
			if (!element.isJsonObject())
				continue;
			JsonObject condition = element.getAsJsonObject();
			conditions.add(new IdleCondition(GsonHelper.getAsString(condition, "type"),
					GsonHelper.getAsString(condition, "argument", ""),
					GsonHelper.getAsBoolean(condition, "inverted", false)));
		}
		return List.copyOf(conditions);
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
			resolvedEntries.add(new ExternalPlaylistEntry(entry.id(), entry.name(), entry.url(), entry.conditions()));
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
		int index = getExternalPlaylistSelectedIndex(playlist, location, false);
		return index < 0 ? null : playlist.entry(index).url();
	}

	public String selectExternalUrl(ResourceLocation location) {
		ExternalPlaylist playlist = this.externalPlaylistsByTrack.get(location);
		if (playlist == null)
			return null;
		int index = getExternalPlaylistSelectedIndex(playlist, location, true);
		return index < 0 ? null : playlist.entry(index).url();
	}

	public ResourceLocation selectSoundTrack(ResourceLocation location) {
		ExternalPlaylist playlist = this.soundPlaylistsByTrack.get(location);
		if (playlist == null)
			return null;
		int index = getExternalPlaylistSelectedIndex(playlist, location, true);
		if (index < 0)
			return null;
		String sound = playlist.entry(index).url();
		return soundLocation(sound);
	}

	public boolean isMusicEntryEnabled(ResourceLocation playlist, ExternalPlaylistEntry entry)
	{
		this.loadMusicState();
		return entry != null && !this.disabledMusicEntries.contains(musicEntryKey(playlist, entry.id())) &&
				!this.disabledMusicEntries.contains(legacyMusicEntryKey(playlist, entry.url()));
	}

	public PlaylistControlResult setMusicEntryEnabled(ResourceLocation playlist, ExternalPlaylistEntry entry,
			boolean enabled)
	{
		if (playlist == null || entry == null)
			return PlaylistControlResult.failure("Unknown music entry");
		this.loadMusicState();
		String key = musicEntryKey(playlist, entry.id());
		this.disabledMusicEntries.remove(legacyMusicEntryKey(playlist, entry.url()));
		if (enabled)
			this.disabledMusicEntries.remove(key);
		else
			this.disabledMusicEntries.add(key);
		ResourceLocation trackLocation = resolveSelectableTrackLocation(playlist);
		if (trackLocation != null)
			this.externalSessionSelections.remove(trackLocation);
		this.saveMusicState();
		// AUD-18 #2: entry enable/disable
		MusicTracksManager.bumpPlaylistRevision(playlist);
		// AUD-20 v1.1: a disabled entry's preview must stop
		this.stopPreviewIfInvalid();
		return PlaylistControlResult.success((enabled ? "Enabled " : "Disabled ") + playlist + " " + entry.name());
	}

	public boolean hasEnabledMusicEntries(ResourceLocation id)
	{
		ResourceLocation trackLocation = resolveSelectableTrackLocation(id);
		ExternalPlaylist playlist = trackLocation == null ? null : playlistByTrackLocation(trackLocation);
		return playlist == null || isValidIndex(playlist, this.externalForcedSelections.get(playlist.configLocation())) ||
				!playableEntryIndices(playlist).isEmpty();
	}

	public boolean hasPlayableMusicEntries(ResourceLocation id)
	{
		return hasEnabledMusicEntries(id);
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
		// AUD-18 #6: server push overrides the previous state; bump everything replaced
		for (java.util.Map.Entry<ResourceLocation, DynamicExternalTrack> entry : this.dynamicExternalTracks.entrySet()) {
			if (entry.getValue().source() == DynamicSource.SERVER)
				MusicTracksManager.bumpPlaylistRevision(entry.getKey());
		}
		this.dynamicExternalTracks.entrySet().removeIf(entry -> entry.getValue().source() == DynamicSource.SERVER);
		for (ServerExternalPlaylistSyncPacket.TrackDefinition definition : definitions) {
			MusicTracksManager.bumpPlaylistRevision(definition.configLocation());
			List<ExternalPlaylistEntry> entries = definition.entries().stream()
					.map(entry -> new ExternalPlaylistEntry(entry.id(), entry.name(), entry.url(), entry.conditions()))
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
		// AUD-20 v1.1: entries may have been removed by the server override
		this.stopPreviewIfInvalid();
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
		entryConditions(binding, true).add(List.of());
		this.saveLocalSceneUrls();
		this.refreshLocalDynamicTracks();
		this.rebuildTracksWithDynamic();
		this.syncExternalPlaylistCatalogToServer();
		// AUD-18 #1: entry add
		MusicTracksManager.bumpPlaylistRevision(dynamicConfigLocation(DynamicSource.LOCAL, binding));
		return PlaylistControlResult.success("Added local " + binding.displayName() + " music #" +
				urlsByTarget.get(binding.storageKey()).size());
	}

	/**
	 * K9-1: transactional import - parse/validate EVERY entry first, then
	 * commit the whole batch in one write. A single invalid reference aborts
	 * before anything is written: an import failure never mutates the
	 * existing playlists. Entries already present in the target list are
	 * skipped (re-importing produces no duplicates).
	 */
	public PlaylistControlResult importLocalUrls(DynamicBinding binding, List<String> urls) {
		this.loadLocalSceneUrls();
		Map<String, List<String>> urlsByTarget = this.localUrls(binding.kind());
		List<String> existing = urlsByTarget.computeIfAbsent(binding.storageKey(), key -> Lists.newArrayList());
		List<String> committed = new java.util.ArrayList<>();
		int invalid = 0;
		for (String raw : urls) {
			String reference = normalizeLocalMusicReference(raw);
			if (reference == null) {
				invalid++;
				continue;
			}
			if (existing.contains(reference))
				continue;
			committed.add(reference);
		}
		if (invalid > 0)
			return PlaylistControlResult.failure("Import aborted: " + invalid +
					" invalid reference(s); nothing was changed");
		if (committed.isEmpty())
			return PlaylistControlResult.success("Import skipped: all entries already present");
		existing.addAll(committed);
		List<List<IdleCondition>> conditions = entryConditions(binding, true);
		while (conditions.size() < existing.size())
			conditions.add(List.of());
		this.saveLocalSceneUrls();
		this.refreshLocalDynamicTracks();
		this.rebuildTracksWithDynamic();
		this.syncExternalPlaylistCatalogToServer();
		MusicTracksManager.bumpPlaylistRevision(dynamicConfigLocation(DynamicSource.LOCAL, binding));
		return PlaylistControlResult.success("Imported " + committed.size() +
				" music entries into " + binding.displayName());
	}

	/**
	 * K9-1: export one binding's entries as the MBM playlist export JSON
	 * (round-trips through PlaylistImportParser.parseMbmJson).
	 */
	public String exportLocalBindingJson(DynamicBinding binding) {
		this.loadLocalSceneUrls();
		List<String> urls = this.localUrls(binding.kind()).getOrDefault(binding.storageKey(), List.of());
		return PlaylistImportParser.buildExportJson(binding.storageKey(), urls);
	}

	// K9-1: current entries of a binding (import preview deduplication)
	public List<String> getLocalUrlsSnapshot(DynamicBinding binding) {
		this.loadLocalSceneUrls();
		return this.localUrls(binding.kind()).getOrDefault(binding.storageKey(), List.of());
	}

	// K9-1: expose the reference normalization for import preview validation
	public String normalizeReferenceForValidation(String raw) {
		return this.normalizeLocalMusicReference(raw);
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
		List<List<IdleCondition>> entryConditions = this.localEntryConditions.get(binding.serializedKey());
		if (entryConditions != null && index < entryConditions.size())
			entryConditions.remove(index);
		if (urls.isEmpty()) {
			urlsByTarget.remove(binding.storageKey());
			this.localSelectionModes.remove(binding.serializedKey());
			this.localIdleConditions.remove(binding.serializedKey());
			this.localEntryConditions.remove(binding.serializedKey());
			this.localIdleIntervals.remove(binding.serializedKey());
			this.localPriorityOverrides.remove(binding.serializedKey());
		}
		this.saveLocalSceneUrls();
		this.refreshLocalDynamicTracks();
		this.rebuildTracksWithDynamic();
		this.syncExternalPlaylistCatalogToServer();
		// AUD-18 #1: entry delete
		MusicTracksManager.bumpPlaylistRevision(dynamicConfigLocation(DynamicSource.LOCAL, binding));
		// AUD-20 v1.1: the deleted entry's preview must stop
		this.stopPreviewIfInvalid();
		return PlaylistControlResult.success("Deleted local " + binding.displayName() + " URL #" + (index + 1) + ": " + removed);
	}

	public PlaylistControlResult moveLocalEntry(DynamicBinding binding, int from, int to)
	{
		if (!hasLocalPlaylist(binding))
			return PlaylistControlResult.failure("No local playlist exists for " +
					(binding == null ? "unknown binding" : binding.displayName()));
		List<String> urls = this.localUrls(binding.kind()).get(binding.storageKey());
		if (from < 0 || to < 0 || from >= urls.size() || to >= urls.size())
			return PlaylistControlResult.failure("Music entry index out of range");
		if (from == to)
			return PlaylistControlResult.success("Music entry order unchanged");
		String value = urls.remove(from);
		urls.add(to, value);
		List<List<IdleCondition>> conditions = entryConditions(binding, true);
		if (from < conditions.size() && to < conditions.size()) {
			List<IdleCondition> condition = conditions.remove(from);
			conditions.add(to, condition);
		}
		saveAndRefreshLocalTracks();
		// AUD-18 #1: entry reorder
		MusicTracksManager.bumpPlaylistRevision(dynamicConfigLocation(DynamicSource.LOCAL, binding));
		return PlaylistControlResult.success("Moved local " + binding.displayName() + " entry to #" + (to + 1));
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
		// AUD-18 #4: playback order mode change
		MusicTracksManager.bumpPlaylistRevision(dynamicConfigLocation(DynamicSource.LOCAL, binding));
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
		// AUD-18: priority change
		MusicTracksManager.bumpPlaylistRevision(dynamicConfigLocation(DynamicSource.LOCAL, binding));
		return PlaylistControlResult.success("Set local " + binding.displayName() + " priority to " + priority);
	}

	public PlaylistControlResult setLocalIdleInterval(DynamicBinding binding, int seconds) {
		if (binding == null || binding.kind() != DynamicBinding.Kind.IDLE_RULE || !hasLocalPlaylist(binding))
			return PlaylistControlResult.failure("No local idle rule playlist exists");
		this.localIdleIntervals.put(binding.serializedKey(), Math.max(0, seconds));
		this.saveLocalSceneUrls();
		this.refreshLocalDynamicTracks();
		this.rebuildTracksWithDynamic();
		// AUD-18: idle interval change
		MusicTracksManager.bumpPlaylistRevision(dynamicConfigLocation(DynamicSource.LOCAL, binding));
		return PlaylistControlResult.success("Set local " + binding.displayName() + " interval to " + seconds + " seconds");
	}

	public PlaylistControlResult addLocalIdleCondition(DynamicBinding binding, IdleCondition condition) {
		if (binding == null || binding.kind() != DynamicBinding.Kind.IDLE_RULE || !hasLocalPlaylist(binding))
			return PlaylistControlResult.failure("No local idle rule playlist exists");
		this.localIdleConditions.computeIfAbsent(binding.serializedKey(), key -> Lists.newArrayList()).add(condition);
		this.saveLocalSceneUrls();
		this.refreshLocalDynamicTracks();
		this.rebuildTracksWithDynamic();
		// AUD-18: idle condition change
		MusicTracksManager.bumpPlaylistRevision(dynamicConfigLocation(DynamicSource.LOCAL, binding));
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
		// AUD-18: idle condition change
		MusicTracksManager.bumpPlaylistRevision(dynamicConfigLocation(DynamicSource.LOCAL, binding));
		return PlaylistControlResult.success("Deleted local idle condition #" + (index + 1));
	}

	public PlaylistControlResult addLocalEntryCondition(DynamicBinding binding, int entryIndex, IdleCondition condition)
	{
		if (!hasLocalPlaylist(binding))
			return PlaylistControlResult.failure("No local playlist exists for " +
					(binding == null ? "unknown binding" : binding.displayName()));
		List<List<IdleCondition>> entries = entryConditions(binding, true);
		if (entryIndex < 0 || entryIndex >= entries.size())
			return PlaylistControlResult.failure("Music entry index out of range");
		List<IdleCondition> conditions = Lists.newArrayList(entries.get(entryIndex));
		conditions.add(condition);
		entries.set(entryIndex, conditions);
		saveAndRefreshLocalTracks();
		// AUD-18: entry condition change
		MusicTracksManager.bumpPlaylistRevision(dynamicConfigLocation(DynamicSource.LOCAL, binding));
		return PlaylistControlResult.success("Added condition to " + binding.displayName() + " track #" +
				(entryIndex + 1));
	}

	public PlaylistControlResult deleteLocalEntryCondition(DynamicBinding binding, int entryIndex, int conditionIndex)
	{
		List<List<IdleCondition>> entries = binding == null ? null : this.localEntryConditions.get(binding.serializedKey());
		if (entries == null || entryIndex < 0 || entryIndex >= entries.size())
			return PlaylistControlResult.failure("Music entry index out of range");
		List<IdleCondition> conditions = Lists.newArrayList(entries.get(entryIndex));
		if (conditionIndex < 0 || conditionIndex >= conditions.size())
			return PlaylistControlResult.failure("Condition index out of range");
		conditions.remove(conditionIndex);
		entries.set(entryIndex, conditions);
		saveAndRefreshLocalTracks();
		// AUD-18: entry condition change
		MusicTracksManager.bumpPlaylistRevision(dynamicConfigLocation(DynamicSource.LOCAL, binding));
		return PlaylistControlResult.success("Deleted track condition #" + (conditionIndex + 1));
	}

	private List<List<IdleCondition>> entryConditions(DynamicBinding binding, boolean create)
	{
		List<String> urls = this.localUrls(binding.kind()).getOrDefault(binding.storageKey(), List.of());
		List<List<IdleCondition>> entries = create
				? this.localEntryConditions.computeIfAbsent(binding.serializedKey(), key -> Lists.newArrayList())
				: this.localEntryConditions.get(binding.serializedKey());
		if (entries == null)
			return List.of();
		while (entries.size() < urls.size())
			entries.add(List.of());
		while (entries.size() > urls.size())
			entries.remove(entries.size() - 1);
		return entries;
	}

	private void saveAndRefreshLocalTracks()
	{
		this.saveLocalSceneUrls();
		this.refreshLocalDynamicTracks();
		this.rebuildTracksWithDynamic();
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
			// AUD-18 #1: entity-binding playlist removal
			MusicTracksManager.bumpAllPlaylistRevisions();
			// AUD-20 v1.1: the removed playlist's entries are gone
			this.stopPreviewIfInvalid();
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
		if (isPlayableIndex(playlist, session))
			return session;
		return -1;
	}

	/**
	 * AUD-17/AUD-19: resolve the playlist binding for a playing URL, or null
	 * when the URL is played directly (no playlist owns it).
	 */
	public @Nullable PlaybackTarget resolvePlaybackTarget(String url)
	{
		if (url == null)
			return null;
		for (DynamicExternalTrack track : this.dynamicExternalTracks.values()) {
			for (ExternalPlaylistEntry entry : track.entries()) {
				if (entry.url().equals(url) && isMusicEntryEnabled(track.configLocation(), entry))
					return new PlaybackTarget(track.configLocation(), MusicTracksManager.entryId(url));
			}
		}
		for (ExternalPlaylist playlist : this.externalPlaylistsByTrack.values()) {
			for (ExternalPlaylistEntry entry : playlist.entries()) {
				if (entry.url().equals(url) && isMusicEntryEnabled(playlist.configLocation(), entry))
					return new PlaybackTarget(playlist.configLocation(), MusicTracksManager.entryId(url));
			}
		}
		return null;
	}

	/**
	 * AUD-19/AUD-20: is the entry referenced by (playlistId, entryKey) still present
	 * and enabled?
	 */
	public boolean isPlaybackTargetActive(ResourceLocation playlistId, String entryKey)
	{
		if (playlistId == null || entryKey == null)
			return false;
		for (DynamicExternalTrack track : this.dynamicExternalTracks.values()) {
			if (!track.configLocation().equals(playlistId))
				continue;
			for (ExternalPlaylistEntry entry : track.entries()) {
				if (entry.id().equals(entryKey) && isMusicEntryEnabled(playlistId, entry))
					return true;
			}
		}
		ExternalPlaylist playlist = this.playlistByConfigLocation(playlistId);
		if (playlist != null) {
			for (ExternalPlaylistEntry entry : playlist.entries()) {
				if (entry.id().equals(entryKey) && isMusicEntryEnabled(playlistId, entry))
					return true;
			}
		}
		return false;
	}

	/**
	 * AUD-20 v1.1: stop the preview when its key no longer refers to an
	 * enabled playlist entry. Called from entry delete / disable / URL change /
	 * server override / reload paths. The dependency direction is outside-in:
	 * the data layer compares and stops; PreviewChannel never queries data.
	 */
	private void stopPreviewIfInvalid()
	{
		String key = PreviewChannel.currentPreviewKey();
		if (key == null || this.isUrlPlayable(key))
			return;
		PreviewChannel.stop();
		LOGGER.debug("Stopped preview: its entry '{}' is no longer playable", key);
	}
	
	private boolean isUrlPlayable(String url)
	{
		for (DynamicExternalTrack track : this.dynamicExternalTracks.values()) {
			for (ExternalPlaylistEntry entry : track.entries()) {
				if (entry.url().equals(url) && isMusicEntryEnabled(track.configLocation(), entry))
					return true;
			}
		}
		for (ExternalPlaylist playlist : this.externalPlaylistsByTrack.values()) {
			for (ExternalPlaylistEntry entry : playlist.entries()) {
				if (entry.url().equals(url) && isMusicEntryEnabled(playlist.configLocation(), entry))
					return true;
			}
		}
		return false;
	}

	private @Nullable ExternalPlaylist playlistByConfigLocation(ResourceLocation configLocation)
	{
		for (ExternalPlaylist playlist : this.externalPlaylistsByTrack.values()) {
			if (playlist.configLocation().equals(configLocation))
				return playlist;
		}
		for (ExternalPlaylist playlist : this.soundPlaylistsByTrack.values()) {
			if (playlist.configLocation().equals(configLocation))
				return playlist;
		}
		return null;
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

	public void synchronizeSessionSelection(ResourceLocation id, long seed)
	{
		ResourceLocation trackLocation = resolveSelectableTrackLocation(id);
		ExternalPlaylist playlist = trackLocation == null ? null : playlistByTrackLocation(trackLocation);
		if (playlist == null || isValidIndex(playlist, this.externalForcedSelections.get(playlist.configLocation())))
			return;
		List<Integer> playable = playableEntryIndices(playlist);
		if (playable.isEmpty())
			return;
		int selected = playable.get(Math.floorMod(Long.hashCode(seed), playable.size()));
		this.externalSessionSelections.put(trackLocation, selected);
	}

	private int getExternalPlaylistSelectedIndex(ExternalPlaylist playlist, ResourceLocation trackLocation, boolean createSession) {
		Integer forced = this.externalForcedSelections.get(playlist.configLocation());
		if (isValidIndex(playlist, forced))
			return forced;
		Integer session = this.externalSessionSelections.get(trackLocation);
		if (isPlayableIndex(playlist, session))
			return session;
		List<Integer> enabled = playableEntryIndices(playlist);
		if (enabled.isEmpty())
			return -1;
		if (!createSession)
			return enabled.get(0);
		int selected = switch (playlist.selectionMode()) {
			case RANDOM -> enabled.get(ThreadLocalRandom.current().nextInt(enabled.size()));
			case FIRST -> enabled.get(0);
			case SEQUENTIAL -> {
				int next = this.externalSequentialNextSelections.getOrDefault(playlist.configLocation(), 0);
				int found = enabled.get(0);
				for (int offset = 0; offset < playlist.size(); offset++) {
					int candidate = Math.floorMod(next + offset, playlist.size());
					if (isPlayableIndex(playlist, candidate)) {
						found = candidate;
						break;
					}
				}
				this.externalSequentialNextSelections.put(playlist.configLocation(), (found + 1) % playlist.size());
				yield found;
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

	private boolean isPlayableIndex(ExternalPlaylist playlist, Integer index)
	{
		return isValidIndex(playlist, index) && isMusicEntryEnabled(playlist.configLocation(), playlist.entry(index)) &&
				entryConditionsMatch(playlist, index);
	}

	private List<Integer> playableEntryIndices(ExternalPlaylist playlist)
	{
		List<Integer> enabled = Lists.newArrayList();
		for (int i = 0; i < playlist.size(); i++) {
			if (isPlayableIndex(playlist, i))
				enabled.add(i);
		}
		return enabled;
	}

	private boolean entryConditionsMatch(ExternalPlaylist playlist, int index)
	{
		ExternalPlaylistEntry entry = playlist.entry(index);
		if (entry.conditions().isEmpty())
			return true;
		DynamicExternalTrack dynamic = this.dynamicExternalTracks.get(playlist.configLocation());
		if (dynamic != null && dynamic.source() == DynamicSource.SERVER)
			return IdleConditionStateClient.isEntryActive(playlist.configLocation(), index);
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && IdleConditionRegistry.test(mc.player, entry.conditions());
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
			List<List<IdleCondition>> conditionsByEntry = entryConditions(binding, true);
			for (int i = 0; i < urls.size(); i++)
				entries.add(new ExternalPlaylistEntry(entryId(urls.get(i)), "local_" + (i + 1), urls.get(i),
						conditionsByEntry.get(i)));
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
				resolvedEntries.add(new ExternalPlaylistEntry(entry.id(), entry.name(), soundReference(sound), entry.conditions()));
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
			resolvedEntries.add(new ExternalPlaylistEntry(entry.id(), entry.name(), entry.url(), entry.conditions()));
			externalMusicHandler.prepareMusicFile(entry.url());
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
			JsonObject entryConditions = GsonHelper.getAsJsonObject(root, "entry_conditions", new JsonObject());
			JsonObject idleIntervals = GsonHelper.getAsJsonObject(root, "idle_intervals", new JsonObject());
			JsonObject priorities = GsonHelper.getAsJsonObject(root, "priorities", new JsonObject());
			this.localSceneUrls.clear();
			this.localEntityTypeUrls.clear();
			this.localEntityUuidUrls.clear();
			this.localIdleRuleUrls.clear();
			this.localSelectionModes.clear();
			this.localIdleConditions.clear();
			this.localEntryConditions.clear();
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
			readEntryConditions(entryConditions, this.localEntryConditions);
			migrateLegacyIdleConditions();
			readIntegerSettings(idleIntervals, this.localIdleIntervals, 0, 86400);
			readIntegerSettings(priorities, this.localPriorityOverrides, -1000, 1000);
		} catch (Exception e) {
			LOGGER.warn("Failed to load local external playlist cache from {}", path, e);
		}
	}

	private static void readEntryConditions(JsonObject object, Map<String, List<List<IdleCondition>>> output)
	{
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			DynamicBinding binding = DynamicBinding.parse(entry.getKey());
			if (binding == null || !entry.getValue().isJsonArray())
				continue;
			List<List<IdleCondition>> tracks = Lists.newArrayList();
			for (JsonElement trackElement : entry.getValue().getAsJsonArray()) {
				if (!trackElement.isJsonArray()) {
					tracks.add(List.of());
					continue;
				}
				JsonObject holder = new JsonObject();
				holder.add("conditions", trackElement);
				tracks.add(parseConditions(holder, "conditions"));
			}
			output.put(binding.serializedKey(), tracks);
		}
	}

	private void migrateLegacyIdleConditions()
	{
		for (Map.Entry<String, List<IdleCondition>> legacy : this.localIdleConditions.entrySet()) {
			if (this.localEntryConditions.containsKey(legacy.getKey()))
				continue;
			DynamicBinding binding = DynamicBinding.parse(legacy.getKey());
			if (binding == null)
				continue;
			List<String> urls = this.localUrls(binding.kind()).getOrDefault(binding.storageKey(), List.of());
			List<List<IdleCondition>> migrated = Lists.newArrayList();
			for (int i = 0; i < urls.size(); i++)
				migrated.add(List.copyOf(legacy.getValue()));
			if (!migrated.isEmpty())
				this.localEntryConditions.put(binding.serializedKey(), migrated);
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
			JsonObject entryConditions = new JsonObject();
			this.localEntryConditions.forEach((binding, tracks) -> {
				JsonArray trackArray = new JsonArray();
				for (List<IdleCondition> conditions : tracks) {
					JsonArray conditionArray = new JsonArray();
					for (IdleCondition condition : conditions)
						conditionArray.add(writeCondition(condition));
					trackArray.add(conditionArray);
				}
				entryConditions.add(binding, trackArray);
			});
			root.add("entry_conditions", entryConditions);
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

	private static JsonObject writeCondition(IdleCondition condition)
	{
		JsonObject object = new JsonObject();
		object.addProperty("type", condition.type());
		object.addProperty("argument", condition.argument());
		object.addProperty("inverted", condition.inverted());
		return object;
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

	private void loadMusicState()
	{
		if (this.musicStateLoaded)
			return;
		this.musicStateLoaded = true;
		Path path = musicStatePath();
		if (!Files.isRegularFile(path))
			return;
		try {
			JsonObject root = GsonHelper.parse(Files.readString(path, StandardCharsets.UTF_8));
			for (JsonElement element : GsonHelper.getAsJsonArray(root, "disabled_entries", new JsonArray())) {
				if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString())
					this.disabledMusicEntries.add(element.getAsString());
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to load disabled music entries from {}", path, e);
		}
	}

	private void saveMusicState()
	{
		Path path = musicStatePath();
		try {
			Files.createDirectories(path.getParent());
			JsonObject root = new JsonObject();
			JsonArray disabled = new JsonArray();
			this.disabledMusicEntries.stream().sorted().forEach(disabled::add);
			root.add("disabled_entries", disabled);
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.error("Failed to save disabled music entries to {}", path, e);
		}
	}

	private static String musicEntryKey(ResourceLocation playlist, String source)
	{
		return playlist + "\nid:" + source;
	}

	private static String legacyMusicEntryKey(ResourceLocation playlist, String source)
	{
		return playlist + "\n" + source;
	}

	private static Path musicStatePath()
	{
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config")
				.resolve("mobbattlemusic_music_state.json");
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

	// AUD-17: resolved playback target for a URL being played
	public static record PlaybackTarget(ResourceLocation playlistId, String entryKey) {}

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

	public static record ExternalPlaylistEntry(String id, String name, String url, List<IdleCondition> conditions)
	{
		public ExternalPlaylistEntry
		{
			conditions = List.copyOf(conditions);
		}

		public ExternalPlaylistEntry(String id, String name, String url)
		{
			this(id, name, url, List.of());
		}
	}

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
