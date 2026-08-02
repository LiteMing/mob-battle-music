package nonamecrackers2.mobbattlemusic.client.music;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

public class MusicMetadataCache
{
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/MusicMetadataCache");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final MusicMetadataCache INSTANCE = new MusicMetadataCache();
	private final Map<String, CompletableFuture<Optional<MusicMetadata>>> requests = new ConcurrentHashMap<>();
	private final HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final Path directory;
	
	private MusicMetadataCache()
	{
		this.directory = Minecraft.getInstance().gameDirectory.toPath()
				.resolve("mobbattlemusic").resolve("cache").resolve("metadata");
		try {
			Files.createDirectories(this.directory);
		} catch (IOException e) {
			LOGGER.warn("Failed to create music metadata cache directory", e);
		}
	}
	
	public static MusicMetadataCache getInstance()
	{
		return INSTANCE;
	}
	
	public CompletableFuture<Optional<MusicMetadata>> prepare(String url)
	{
		String songId = UrlResolver.neteaseSongId(url);
		if (songId == null)
			return CompletableFuture.completedFuture(Optional.empty());
		Optional<MusicMetadata> cached = this.getBySongId(songId);
		if (cached.isPresent())
			return CompletableFuture.completedFuture(cached);
		return this.requests.computeIfAbsent(songId, id -> CompletableFuture.supplyAsync(() -> this.fetchNetease(url, id))
				.whenComplete((result, error) -> this.requests.remove(id)));
	}
	
	public Optional<MusicMetadata> get(String url)
	{
		String songId = UrlResolver.neteaseSongId(url);
		return songId == null ? Optional.empty() : this.getBySongId(songId);
	}
	
	public Optional<MusicMetadata> getBySongId(String songId)
	{
		Path path = metadataPath(songId);
		if (!Files.isRegularFile(path))
			return Optional.empty();
		try {
			return Optional.ofNullable(GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), MusicMetadata.class));
		} catch (Exception e) {
			LOGGER.debug("Failed to read metadata cache {}", path, e);
			return Optional.empty();
		}
	}
	
	public Path coverPath(MusicMetadata metadata)
	{
		return metadata.coverFileName() == null || metadata.coverFileName().isBlank()
				? null
				: this.directory.resolve(metadata.coverFileName());
	}
	
	private Optional<MusicMetadata> fetchNetease(String sourceUrl, String songId)
	{
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("https://music.163.com/api/song/detail/?ids=%5B" + songId + "%5D"))
					.header("User-Agent", "Mozilla/5.0")
					.timeout(Duration.ofSeconds(15))
					.GET()
					.build();
			HttpResponse<String> response = this.client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300)
				return Optional.empty();
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			JsonArray songs = root.getAsJsonArray("songs");
			if (songs == null || songs.isEmpty())
				return Optional.empty();
			JsonObject song = songs.get(0).getAsJsonObject();
			String title = string(song, "name");
			String artist = artists(song.getAsJsonArray("artists"));
			JsonObject albumObject = song.has("album") && song.get("album").isJsonObject()
					? song.getAsJsonObject("album") : null;
			String album = albumObject == null ? "" : string(albumObject, "name");
			String coverUrl = albumObject == null ? "" : string(albumObject, "picUrl");
			String coverFileName = downloadCover(songId, coverUrl);
			MusicMetadata metadata = new MusicMetadata(sourceUrl, songId, title, artist, album, coverUrl, coverFileName);
			Files.writeString(metadataPath(songId), GSON.toJson(metadata), StandardCharsets.UTF_8);
			return Optional.of(metadata);
		} catch (Exception e) {
			LOGGER.debug("Failed to fetch Netease metadata for {}", songId, e);
			return Optional.empty();
		}
	}
	
	private String downloadCover(String songId, String coverUrl)
	{
		if (coverUrl == null || coverUrl.isBlank())
			return "";
		try {
			String extension = coverUrl.contains(".png") ? ".png" : ".jpg";
			String fileName = songId + extension;
			Path target = this.directory.resolve(fileName);
			if (Files.isRegularFile(target))
				return fileName;
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(coverUrl))
					.header("User-Agent", "Mozilla/5.0")
					.timeout(Duration.ofSeconds(15))
					.GET()
					.build();
			HttpResponse<byte[]> response = this.client.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				Files.write(target, response.body());
				return fileName;
			}
		} catch (Exception e) {
			LOGGER.debug("Failed to download cover for {}", songId, e);
		}
		return "";
	}
	
	private Path metadataPath(String songId)
	{
		return this.directory.resolve(songId + ".json");
	}
	
	private static String string(JsonObject object, String key)
	{
		return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
	}
	
	private static String artists(JsonArray artists)
	{
		if (artists == null || artists.isEmpty())
			return "";
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < artists.size(); i++) {
			if (i > 0)
				builder.append(", ");
			builder.append(string(artists.get(i).getAsJsonObject(), "name"));
		}
		return builder.toString();
	}
}
