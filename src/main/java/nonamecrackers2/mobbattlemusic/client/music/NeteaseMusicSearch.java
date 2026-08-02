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
	private static final int PAGE_SIZE = 30;
	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private NeteaseMusicSearch() {}

	public static CompletableFuture<SearchPage> search(String keyword, int page)
	{
		String normalized = keyword == null ? "" : keyword.trim();
		if (normalized.isEmpty())
			return CompletableFuture.completedFuture(new SearchPage(List.of(), page, false));
		int safePage = Math.max(0, page);
		String body = "s=" + encode(normalized) + "&offset=" + safePage * PAGE_SIZE +
				"&limit=" + PAGE_SIZE + "&type=1&total=true";
		HttpRequest request = HttpRequest.newBuilder(SEARCH_ENDPOINT)
				.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
				.header("Referer", "https://music.163.com/")
				.header("User-Agent", "Mozilla/5.0")
				.timeout(Duration.ofSeconds(20))
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(response -> parse(response, safePage));
	}

	private static SearchPage parse(HttpResponse<String> response, int page)
	{
		if (response.statusCode() < 200 || response.statusCode() >= 300)
			throw new CompletionException(new IllegalStateException("Netease search returned HTTP " + response.statusCode()));
		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		JsonObject result = object(root, "result");
		JsonArray songs = result == null ? null : array(result, "songs");
		if (songs == null)
			return new SearchPage(List.of(), page, false);
		List<Song> parsed = new ArrayList<>();
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
		int total = intValue(result, "songCount", parsed.size());
		boolean hasNext = (page + 1) * PAGE_SIZE < total;
		return new SearchPage(List.copyOf(parsed), page, hasNext);
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

	public static record SearchPage(List<Song> songs, int page, boolean hasNext) {}

	public static record Song(String id, String title, String artist, String album, String coverUrl, long durationMillis)
	{
		public String url()
		{
			return "https://music.163.com/song?id=" + this.id;
		}
	}
}
