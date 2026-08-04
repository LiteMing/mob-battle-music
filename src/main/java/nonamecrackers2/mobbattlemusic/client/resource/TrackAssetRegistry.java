package nonamecrackers2.mobbattlemusic.client.resource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadata;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadataCache;

/**
 * K15-A: the central TrackAsset registry - the music-first creation model.
 *
 * A TrackAsset is the STABLE identity of a song, independent of which
 * playlist binding (scene/entity/idle rule) uses it. The track id is derived
 * from the source URL (the existing entryId hash), so the same URL registered
 * in several bindings yields the SAME track id - this is the seed of
 * "one song, many uses".
 *
 * The runtime selection engine keeps its condition-first index (fast);
 * this registry is the authoring/identity layer on top: given a track id you
 * can find every binding (assignment) that uses it, and given a URL you get
 * one canonical asset instead of N duplicated entries.
 *
 * Persistence stays on the legacy format for now (K15 scope): assets are
 * re-registered on load; nothing is written back yet.
 */
public final class TrackAssetRegistry
{
	private static final Map<String, TrackAsset> ASSETS = new LinkedHashMap<>();

	private TrackAssetRegistry() {}

	/**
	 * Register (or reuse) the asset for a source URL. Same URL -> same asset,
	 * regardless of how many bindings reference it.
	 */
	public static TrackAsset register(String url, @Nullable String titleHint)
	{
		String trackId = MusicTracksManager.entryId(url);
		TrackAsset existing = ASSETS.get(trackId);
		if (existing != null) {
			if (titleHint != null && !titleHint.isBlank() && existing.title() == null)
				existing = new TrackAsset(trackId, url, titleHint, existing.bindings());
			return existing;
		}
		TrackAsset asset = new TrackAsset(trackId, url, titleHint, List.of());
		ASSETS.put(trackId, asset);
		return asset;
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
	 * K15-A: every playlist binding (assignment) that uses this track - the
	 * music-first reverse index the current condition-first UI is missing.
	 */
	public static List<MusicTracksManager.DynamicBinding> bindingsFor(String trackId)
	{
		TrackAsset asset = ASSETS.get(trackId);
		if (asset == null)
			return List.of();
		MusicTracksManager manager = MusicTracksManager.getInstance();
		java.util.List<MusicTracksManager.DynamicBinding> result = new java.util.ArrayList<>();
		for (MusicTracksManager.ExternalPlaylist playlist : manager.getExternalPlaylists()) {
			for (MusicTracksManager.ExternalPlaylistEntry entry : playlist.entries()) {
				if (entry.id().equals(trackId) || entry.url().equals(asset.sourceUrl())) {
					MusicTracksManager.DynamicBinding binding = manager.editableBinding(playlist.configLocation());
					if (binding != null && !result.contains(binding))
						result.add(binding);
					break;
				}
			}
		}
		return List.copyOf(result);
	}

	/**
	 * K15-A: human-readable uses of a track ("aggressive/remilia",
	 * "scene idle", "idle rule foo") - the music-first answer to "where is
	 * this song used?".
	 */
	public static String usesDisplay(String trackId)
	{
		List<MusicTracksManager.DynamicBinding> bindings = bindingsFor(trackId);
		if (bindings.isEmpty())
			return "";
		StringBuilder builder = new StringBuilder();
		for (MusicTracksManager.DynamicBinding binding : bindings) {
			if (!builder.isEmpty())
				builder.append(" | ");
			builder.append(binding.displayName());
		}
		return builder.toString();
	}

	/** Drop all assets (resource reload / logout). */
	public static void clear()
	{
		ASSETS.clear();
	}

	/**
	 * K15-A: one song, stable identity, optional metadata.
	 */
	public record TrackAsset(String trackId, String sourceUrl, @Nullable String title,
			List<MusicTracksManager.DynamicBinding> bindings)
	{
		public TrackAsset withTitle(String title)
		{
			return new TrackAsset(this.trackId, this.sourceUrl, title, this.bindings);
		}

		@Nullable
		public MusicMetadata metadata()
		{
			return MusicMetadataCache.getInstance().get(this.sourceUrl).orElse(null);
		}
	}
}
