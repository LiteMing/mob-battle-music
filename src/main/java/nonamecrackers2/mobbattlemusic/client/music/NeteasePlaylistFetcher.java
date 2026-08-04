package nonamecrackers2.mobbattlemusic.client.music;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * K13-A: Netease playlist resolution - fetch the track list of a
 * music.163.com/playlist?id=xxx URL and turn it into playable song URLs.
 * Same API family as NeteaseMusicSearch (cloudsearch): the public
 * playlist-detail endpoint. Network work runs on the CompletableFuture
 * common pool, never on the client tick thread.
 */
public final class NeteasePlaylistFetcher
{
	private static final Pattern PLAYLIST_ID = Pattern.compile(
			"[?&]id=(\\d+)|/playlist/(\\d+)");
	private static final URI DETAIL_ENDPOINT = URI.create("https://music.163.com/api/v6/playlist/detail");
	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private NeteasePlaylistFetcher() {}

	/**
	 * Extract the playlist id from a music.163.com playlist URL, or null when
	 * the URL does not carry one.
	 */
	public static String playlistId(String url)
	{
		if (url == null)
			return null;
		Matcher matcher = PLAYLIST_ID.matcher(url);
		if (!matcher.find())
			return null;
		return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
	}

	/**
	 * Fetch the songs of a playlist URL. Empty result with a null failure
	 * means "no playlist id in URL"; a failed fetch completes with a
	 * CompletionException carrying the HTTP/parse error.
	 */
	public static CompletableFuture<List<Song>> fetch(String playlistUrl)
	{
		String id = playlistId(playlistUrl);
		if (id == null)
			return CompletableFuture.completedFuture(List.of());
		String query = "?id=" + id;
		HttpRequest request = HttpRequest.newBuilder(URI.create(DETAIL_ENDPOINT.toString() + query))
				.header("Referer", "https://music.163.com/")
				.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
						+ "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
				.header("Accept", "application/json, text/plain, */*")
				.timeout(Duration.ofSeconds(25))
				.GET()
				.build();
		return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(NeteasePlaylistFetcher::parse);
	}

	private static List<Song> parse(HttpResponse<String> response)
	{
		if (response.statusCode() < 200 || response.statusCode() >= 300)
			throw new CompletionException(new IllegalStateException(
					"Netease playlist returned HTTP " + response.statusCode()));
		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		// /api/v6/playlist/detail wraps the playlist under "playlist"
		JsonObject playlist = root != null && root.has("playlist") && root.get("playlist").isJsonObject()
				? root.getAsJsonObject("playlist") : null;
		// older endpoint shape: root.tracks directly
		JsonArray tracks = null;
		if (playlist != null && playlist.has("trackIds") && playlist.getAsJsonArray("trackIds") != null) {
			tracks = detailTracks(playlist);
		}
		if (tracks == null)
			tracks = root != null && root.has("tracks") && root.get("tracks").isJsonArray()
					? root.getAsJsonArray("tracks") : null;
		if (tracks == null)
			throw new CompletionException(new IllegalStateException(
					"Netease playlist response has no tracks"));
		List<Song> songs = new ArrayList<>();
		for (JsonElement element : tracks) {
			if (!element.isJsonObject())
				continue;
			JsonObject song = element.getAsJsonObject();
			String id = string(song, "id");
			if (id.isBlank())
				continue;
			JsonArray artists = song.has("ar") && song.get("ar").isJsonArray()
					? song.getAsJsonArray("ar")
					: (song.has("artists") && song.get("artists").isJsonArray()
							? song.getAsJsonArray("artists") : null);
			songs.add(new Song(id, string(song, "name"), artists(artists)));
		}
		return List.copyOf(songs);
	}

	// /api/v6 returns only trackIds; resolve each through the song/detail
	// endpoint in parallel (bounded to 50 tracks per playlist page)
	private static JsonArray detailTracks(JsonObject playlist)
	{
		JsonArray ids = playlist.getAsJsonArray("trackIds");
		if (ids.isEmpty())
			return null;
		int limit = Math.min(50, ids.size());
		StringBuilder joined = new StringBuilder();
		for (int i = 0; i < limit; i++) {
			JsonElement element = ids.get(i);
			String id = element.isJsonObject() ? string(element.getAsJsonObject(), "id") : "";
			if (id.isBlank() && element.isJsonPrimitive())
				id = element.getAsString();
			if (id.isBlank())
				continue;
			if (!joined.isEmpty())
				joined.append(',');
			joined.append(id);
		}
		if (joined.isEmpty())
			return null;
		try {
			String body = CLIENT.send(
					HttpRequest.newBuilder(URI.create(
							"https://music.163.com/api/song/detail/?ids=%5B" + joined + "%5D"))
							.header("Referer", "https://music.163.com/")
							.header("User-Agent", "Mozilla/5.0")
							.timeout(Duration.ofSeconds(25))
							.GET().build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
			JsonObject root = JsonParser.parseString(body).getAsJsonObject();
			return root.has("songs") && root.get("songs").isJsonArray() ? root.getAsJsonArray("songs") : null;
		} catch (Exception e) {
			throw new CompletionException(e);
		}
	}

	private static String string(JsonObject object, String key)
	{
		return object != null && object.has(key) && !object.get(key).isJsonNull()
				? object.get(key).getAsString() : "";
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

	public static record Song(String id, String title, String artist)
	{
		public String url()
		{
			return "https://music.163.com/song?id=" + this.id;
		}
	}
}
