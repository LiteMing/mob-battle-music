package nonamecrackers2.mobbattlemusic.client.music;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class NeteaseMusicSearch
{
	private static final URI SEARCH_ENDPOINT = URI.create("https://music.163.com/api/cloudsearch/pc/");
	private static final URI ALBUM_ENDPOINT = URI.create("https://music.163.com/api/album/");
	private static final int PAGE_SIZE = 30;
	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private NeteaseMusicSearch() {}

	/** K16-H: cloudsearch type - 1 song, 10 album, 1000 playlist. */
	public enum SearchType
	{
		SONG(1), ALBUM(10), PLAYLIST(1000);

		private final int code;

		SearchType(int code)
		{
			this.code = code;
		}

		public int code()
		{
			return this.code;
		}
	}

	/** K16-H: common result shape for the GUI list (song/album/playlist). */
	public interface Item
	{
		String id();

		String title();

		String subtitle();

		SearchType kind();
	}

	public static CompletableFuture<SearchPage> search(String keyword, int page)
	{
		return search(keyword, page, SearchType.SONG);
	}

	public static CompletableFuture<SearchPage> search(String keyword, int page, SearchType type)
	{
		String normalized = keyword == null ? "" : keyword.trim();
		if (normalized.isEmpty())
			return CompletableFuture.completedFuture(new SearchPage(List.of(), page, false));
		int safePage = Math.max(0, page);
		String body = "s=" + encode(normalized) + "&offset=" + safePage * PAGE_SIZE +
				"&limit=" + PAGE_SIZE + "&type=" + type.code() + "&total=true";
		HttpRequest request = HttpRequest.newBuilder(SEARCH_ENDPOINT)
				.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
				.header("Referer", "https://music.163.com/")
				.header("User-Agent", "Mozilla/5.0")
				.timeout(Duration.ofSeconds(20))
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(response -> parse(response, safePage, type));
	}

	private static SearchPage parse(HttpResponse<String> response, int page, SearchType type)
	{
		if (response.statusCode() < 200 || response.statusCode() >= 300)
			throw new CompletionException(new IllegalStateException("Netease search returned HTTP " + response.statusCode()));
		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		JsonObject result = object(root, "result");
		if (result == null)
			return new SearchPage(List.of(), page, false);
		List<Item> parsed = new ArrayList<>();
		int total;
		switch (type) {
			case ALBUM -> {
				JsonArray albums = array(result, "albums");
				if (albums != null) {
					for (JsonElement element : albums) {
						if (!element.isJsonObject())
							continue;
						JsonObject album = element.getAsJsonObject();
						String id = string(album, "id");
						if (id.isBlank())
							continue;
						JsonObject artist = object(album, "artist");
						parsed.add(new Album(id, string(album, "name"),
								artist == null ? "" : string(artist, "name"), string(album, "picUrl")));
					}
				}
				total = intValue(result, "albumCount", parsed.size());
			}
			case PLAYLIST -> {
				JsonArray playlists = array(result, "playlists");
				if (playlists != null) {
					for (JsonElement element : playlists) {
						if (!element.isJsonObject())
							continue;
						JsonObject playlist = element.getAsJsonObject();
						String id = string(playlist, "id");
						if (id.isBlank())
							continue;
						JsonObject creator = object(playlist, "creator");
						parsed.add(new PlaylistItem(id, string(playlist, "name"),
								creator == null ? "" : string(creator, "nickname"),
								intValue(playlist, "trackCount", 0), string(playlist, "picUrl")));
					}
				}
				total = intValue(result, "playlistCount", parsed.size());
			}
			default -> {
				JsonArray songs = array(result, "songs");
				if (songs != null) {
					for (JsonElement element : songs) {
						if (!element.isJsonObject())
							continue;
						JsonObject song = element.getAsJsonObject();
						String id = string(song, "id");
						if (id.isBlank())
							continue;
						JsonObject album = object(song, "al");
						if (album == null)
							album = object(song, "album");
						JsonArray artists = array(song, "ar");
						if (artists == null)
							artists = array(song, "artists");
						long duration = longValue(song, "dt", longValue(song, "duration", 0L));
						parsed.add(new Song(id, string(song, "name"), artists(artists),
								album == null ? "" : string(album, "name"),
								album == null ? "" : string(album, "picUrl"), duration));
					}
				}
				total = intValue(result, "songCount", parsed.size());
			}
		}
		boolean hasNext = (page + 1) * PAGE_SIZE < total;
		return new SearchPage(List.copyOf(parsed), page, hasNext);
	}

	/**
	 * K16-H: the tracks of an album - /api/album/{id} returns album.songs in
	 * the same shape as search results.
	 */
	public static CompletableFuture<List<Song>> fetchAlbumTracks(String albumId)
	{
		HttpRequest request = HttpRequest.newBuilder(URI.create(ALBUM_ENDPOINT.toString() + albumId))
				.header("Referer", "https://music.163.com/")
				.header("User-Agent", "Mozilla/5.0")
				.timeout(Duration.ofSeconds(25))
				.GET()
				.build();
		return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(NeteaseMusicSearch::parseAlbum);
	}

	private static List<Song> parseAlbum(HttpResponse<String> response)
	{
		if (response.statusCode() < 200 || response.statusCode() >= 300)
			throw new CompletionException(new IllegalStateException(
					"Netease album returned HTTP " + response.statusCode()));
		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		JsonObject album = object(root, "album");
		JsonArray songs = album == null ? array(root, "songs") : array(album, "songs");
		if (songs == null)
			return List.of();
		List<Song> parsed = new ArrayList<>();
		for (JsonElement element : songs) {
			if (!element.isJsonObject())
				continue;
			JsonObject song = element.getAsJsonObject();
			String id = string(song, "id");
			if (id.isBlank())
				continue;
			JsonObject al = object(song, "al");
			if (al == null)
				al = object(song, "album");
			JsonArray artists = array(song, "ar");
			if (artists == null)
				artists = array(song, "artists");
			long duration = longValue(song, "dt", longValue(song, "duration", 0L));
			parsed.add(new Song(id, string(song, "name"), artists(artists),
					al == null ? "" : string(al, "name"),
					al == null ? "" : string(al, "picUrl"), duration));
		}
		return List.copyOf(parsed);
	}

	private static String encode(String value)
	{
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static JsonObject object(JsonObject object, String key)
	{
		return object != null && object.has(key) && object.get(key).isJsonObject()
				? object.getAsJsonObject(key) : null;
	}

	private static JsonArray array(JsonObject object, String key)
	{
		return object != null && object.has(key) && object.get(key).isJsonArray()
				? object.getAsJsonArray(key) : null;
	}

	private static String string(JsonObject object, String key)
	{
		return object != null && object.has(key) && !object.get(key).isJsonNull()
				? object.get(key).getAsString() : "";
	}

	private static int intValue(JsonObject object, String key, int fallback)
	{
		try {
			return object != null && object.has(key) ? object.get(key).getAsInt() : fallback;
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private static long longValue(JsonObject object, String key, long fallback)
	{
		try {
			return object != null && object.has(key) ? object.get(key).getAsLong() : fallback;
		} catch (RuntimeException ignored) {
			return fallback;
		}

	}

	private static String artists(JsonArray artists)
	{
		if (artists == null || artists.isEmpty())
			return "";
		StringBuilder builder = new StringBuilder();
		for (JsonElement element : artists) {
			if (!element.isJsonObject())
				continue;
			String name = string(element.getAsJsonObject(), "name");
			if (name.isBlank())
				continue;
			if (!builder.isEmpty())
				builder.append(", ");
			builder.append(name);
		}
		return builder.toString();
	}

	public static record SearchPage(List<Item> items, int page, boolean hasNext) {}

	public static record Song(String id, String title, String artist, String album, String coverUrl, long durationMillis)
			implements Item
	{
		public String url()
		{
			return "https://music.163.com/song?id=" + this.id;
		}

		@Override
		public String subtitle()
		{
			return this.artist + (this.album.isBlank() ? "" : "  /  " + this.album);
		}

		@Override
		public SearchType kind()
		{
			return SearchType.SONG;
		}
	}

	/** K16-H: a searchable album - clicking it opens its track list. */
	public record Album(String id, String name, String artist, String coverUrl) implements Item
	{
		@Override
		public String title()
		{
			return this.name;
		}

		@Override
		public String subtitle()
		{
			return this.artist;
		}

		@Override
		public SearchType kind()
		{
			return SearchType.ALBUM;
		}
	}

	/** K16-H: a searchable playlist - clicking it opens its track list. */
	public record PlaylistItem(String id, String name, String creator, int trackCount, String coverUrl) implements Item
	{
		@Override
		public String title()
		{
			return this.name;
		}

		@Override
		public String subtitle()
		{
			return (this.creator.isBlank() ? "" : this.creator + "  /  ") + this.trackCount + " tracks";
		}

		@Override
		public SearchType kind()
		{
			return SearchType.PLAYLIST;
		}
	}
}
