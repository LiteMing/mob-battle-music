package nonamecrackers2.mobbattlemusic.client.resource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadata;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadataCache;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;

/**
 * K15-A/K15-C: the central TrackAsset registry - the music-first creation
 * model with a REAL reverse index.
 *
 * A TrackAsset is the STABLE identity of a song (trackId = URL-derived SHA-1
 * hash), independent of which playlist binding uses it. rebuildDerivedIndexes()
 * is the single entry point: it builds BOTH maps in one pass, so every UI
 * query (usesFor, usesDisplay) is O(1) - never a rescan of all playlists.
 *
 * A TrackUse is one concrete use of the track in one playlist entry. Unlike
 * DynamicBinding (which is only the rule target), a TrackUse carries the
 * entry identity, so two entries of the same song in the same binding stay
 * distinct uses.
 *
 * Persistence stays on the legacy format (K15 scope): indexes are rebuilt on
 * every data commit, nothing new is written back.
 */
public final class TrackAssetRegistry
{
	private static final Map<String, TrackAsset> ASSETS = new LinkedHashMap<>();
	private static final Map<String, List<TrackUse>> USES_BY_TRACK = new LinkedHashMap<>();

	private TrackAssetRegistry() {}

	/**
	 * K15-C: rebuild the whole derived index in one pass. Must be called after
	 * EVERY playlist data commit (resource apply, local import, server sync,
	 * enable/disable, GUI edits) - not only on resource reload.
	 */
	public static synchronized void rebuildDerivedIndexes()
	{
		ASSETS.clear();
		USES_BY_TRACK.clear();
		MusicTracksManager manager = MusicTracksManager.getInstance();
		// K15-F: occurrence ordinals are counted in the SAME single pass -
		// the previous per-entry occurrenceIn() rescan was O(N^2) on large
		// playlists
		Map<String, Integer> occurrenceCounts = new java.util.HashMap<>();
		for (MusicTracksManager.ExternalPlaylist playlist : manager.getExternalPlaylists()) {
			MusicTracksManager.DynamicBinding binding = manager.editableBinding(playlist.configLocation());
			for (int i = 0; i < playlist.entries().size(); i++) {
				MusicTracksManager.ExternalPlaylistEntry entry = playlist.entries().get(i);
				String trackId = MusicTracksManager.entryId(entry.url());
				TrackAsset asset = ASSETS.get(trackId);
				if (asset == null) {
					asset = new TrackAsset(trackId, entry.url(), entry.name());
					ASSETS.put(trackId, asset);
				} else if (asset.title() == null) {
					ASSETS.put(trackId, asset.withTitle(entry.name()));
				}
				String occurrenceKey = playlist.configLocation() + "#" + entry.id();
				int ordinal = occurrenceCounts.getOrDefault(occurrenceKey, 0);
				occurrenceCounts.put(occurrenceKey, ordinal + 1);
				TrackUse use = new TrackUse(trackId, playlist.configLocation(), entry.id(), binding,
						i, List.copyOf(entry.conditions()), entry.url(), ordinal);
				USES_BY_TRACK.computeIfAbsent(trackId, key -> new ArrayList<>()).add(use);
			}
		}
	}

	/** The stable id for a source URL (same as entryId - kept as the canonical name here). */
	public static String trackId(String url)
	{
		return MusicTracksManager.entryId(url);
	}

	@Nullable
	public static TrackAsset byId(String trackId)
	{
		return ASSETS.get(trackId);
	}

	@Nullable
	public static TrackAsset byUrl(String url)
	{
		return ASSETS.get(MusicTracksManager.entryId(url));
	}

	public static List<TrackAsset> all()
	{
		return List.copyOf(ASSETS.values());
	}

	/**
	 * K15-C: O(1) reverse index - every use of a track.
	 */
	public static List<TrackUse> usesFor(String trackId)
	{
		return List.copyOf(USES_BY_TRACK.getOrDefault(trackId, List.of()));
	}

	/**
	 * K15-A: every playlist binding that uses this track, deduplicated by
	 * binding (display-level view).
	 */
	public static List<MusicTracksManager.DynamicBinding> bindingsFor(String trackId)
	{
		List<TrackUse> uses = usesFor(trackId);
		List<MusicTracksManager.DynamicBinding> result = new ArrayList<>();
		for (TrackUse use : uses) {
			if (use.binding() != null && !result.contains(use.binding()))
				result.add(use.binding());
		}
		return List.copyOf(result);
	}

	/**
	 * K15-A: human-readable uses of a track ("aggressive/remilia",
	 * "scene idle", "idle rule foo") - O(1) via the reverse index.
	 * K15-E: duplicate uses of the same song in the same binding are shown
	 * once with a count (aggressive/remilia x2), not repeated.
	 */
	public static String usesDisplay(String trackId)
	{
		List<TrackUse> uses = usesFor(trackId);
		if (uses.isEmpty())
			return "";
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (TrackUse use : uses) {
			if (use.binding() == null)
				continue;
			counts.merge(use.binding().displayName(), 1, Integer::sum);
		}
		StringBuilder builder = new StringBuilder();
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			if (!builder.isEmpty())
				builder.append(" | ");
			builder.append(entry.getKey());
			if (entry.getValue() > 1)
				builder.append(" x").append(entry.getValue());
		}
		return builder.toString();
	}

	/** Drop all indexes (logout / world unload). */
	public static synchronized void clear()
	{
		ASSETS.clear();
		USES_BY_TRACK.clear();
	}

	/**
	 * K15-A: one song, stable identity, optional metadata.
	 */
	public record TrackAsset(String trackId, String sourceUrl, @Nullable String title)
	{
		public TrackAsset withTitle(String title)
		{
			return new TrackAsset(this.trackId, this.sourceUrl, title);
		}

		@Nullable
		public MusicMetadata metadata()
		{
			return MusicMetadataCache.getInstance().get(this.sourceUrl).orElse(null);
		}
	}

	/**
	 * K15-C: ONE concrete use of a track in one playlist entry. useId is
	 * stable across reorders (playlist + entryId); entryIndex is the current
	 * display position only.
	 * K15-E: occurrenceOrdinal distinguishes the SAME url appearing twice in
	 * one playlist - playlist+entryId alone cannot (both have the same SHA-1).
	 * It is a compatibility fallback until a persisted assignmentId exists.
	 */
	public record TrackUse(String trackId, ResourceLocation playlistId, String entryId,
			@Nullable MusicTracksManager.DynamicBinding binding, int entryIndex,
			List<IdleCondition> entryConditions, String url, int occurrenceOrdinal)
	{
		public String useId()
		{
			return this.playlistId + "#" + this.entryId + "#" + this.occurrenceOrdinal;
		}
	}

}
