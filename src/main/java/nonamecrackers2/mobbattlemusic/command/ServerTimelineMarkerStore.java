package nonamecrackers2.mobbattlemusic.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.GsonHelper;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ServerTimelineMarkerStore
{
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/ServerTimelineMarkerStore");
	private static final Map<String, Map<String, List<TimelineMarker>>> MARKERS = new LinkedHashMap<>();
	private static boolean loaded;

	private ServerTimelineMarkerStore() {}

	public static int add(CommandSourceStack source, ResourceLocation playlist, int trackIndex, long timeMillis,
			ResourceLocation eventId)
	{
		String url = ServerExternalPlaylistStore.playlistEntryUrl(source.getServer(), playlist, trackIndex);
		if (url == null) {
			source.sendFailure(Component.literal("Unknown playlist or track index"));
			return 0;
		}
		load(source.getServer());
		List<TimelineMarker> markers = MARKERS.computeIfAbsent(playlist.toString(), key -> new LinkedHashMap<>())
				.computeIfAbsent(url, key -> new ArrayList<>());
		markers.add(new TimelineMarker(timeMillis, eventId));
		markers.sort(Comparator.comparingLong(TimelineMarker::timeMillis));
		save(source.getServer());
		ServerExternalPlaylistStore.syncAll(source.getServer());
		source.sendSuccess(() -> Component.literal("Added timeline marker for " + playlist + " track #" +
				(trackIndex + 1) + " at " + timeMillis + " ms"), true);
		return 1;
	}

	public static int delete(CommandSourceStack source, ResourceLocation playlist, int trackIndex, int markerIndex)
	{
		String url = ServerExternalPlaylistStore.playlistEntryUrl(source.getServer(), playlist, trackIndex);
		load(source.getServer());
		Map<String, List<TimelineMarker>> entries = MARKERS.get(playlist.toString());
		List<TimelineMarker> markers = entries == null || url == null ? null : entries.get(url);
		if (markers == null || markerIndex < 0 || markerIndex >= markers.size()) {
			source.sendFailure(Component.literal("Timeline marker index out of range"));
			return 0;
		}
		markers.remove(markerIndex);
		if (markers.isEmpty())
			entries.remove(url);
		if (entries.isEmpty())
			MARKERS.remove(playlist.toString());
		save(source.getServer());
		ServerExternalPlaylistStore.syncAll(source.getServer());
		source.sendSuccess(() -> Component.literal("Deleted timeline marker #" + (markerIndex + 1)), true);
		return 1;
	}

	public static synchronized List<TimelineMarker> markers(MinecraftServer server, ResourceLocation playlist, String url)
	{
		load(server);
		return List.copyOf(MARKERS.getOrDefault(playlist.toString(), Map.of()).getOrDefault(url, List.of()));
	}

	public static boolean has(MinecraftServer server, ResourceLocation playlist, String url, TimelineMarker marker)
	{
		return markers(server, playlist, url).contains(marker);
	}

	public static synchronized boolean hasAny(MinecraftServer server, ResourceLocation playlist, TimelineMarker marker)
	{
		load(server);
		return MARKERS.getOrDefault(playlist.toString(), Map.of()).values().stream()
				.anyMatch(markers -> markers.contains(marker));
	}

	private static synchronized void load(MinecraftServer server)
	{
		if (loaded)
			return;
		loaded = true;
		Path path = path(server);
		if (!Files.isRegularFile(path))
			return;
		try {
			JsonObject root = GsonHelper.parse(Files.readString(path, StandardCharsets.UTF_8));
			for (Map.Entry<String, JsonElement> playlist : root.entrySet()) {
				if (!playlist.getValue().isJsonObject() || ResourceLocation.tryParse(playlist.getKey()) == null)
					continue;
				Map<String, List<TimelineMarker>> entries = new LinkedHashMap<>();
				for (Map.Entry<String, JsonElement> entry : playlist.getValue().getAsJsonObject().entrySet()) {
					if (!entry.getValue().isJsonArray())
						continue;
					List<TimelineMarker> markers = new ArrayList<>();
					for (JsonElement markerElement : entry.getValue().getAsJsonArray()) {
						JsonObject object = markerElement.getAsJsonObject();
						ResourceLocation event = ResourceLocation.tryParse(GsonHelper.getAsString(object, "event"));
						if (event != null)
							markers.add(new TimelineMarker(GsonHelper.getAsLong(object, "time_ms"), event));
					}
					markers.sort(Comparator.comparingLong(TimelineMarker::timeMillis));
					if (!markers.isEmpty())
						entries.put(entry.getKey(), markers);
				}
				if (!entries.isEmpty())
					MARKERS.put(playlist.getKey(), entries);
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to load server timeline markers from {}", path, e);
		}
	}

	private static void save(MinecraftServer server)
	{
		JsonObject root = new JsonObject();
		MARKERS.forEach((playlist, entries) -> {
			JsonObject entryObject = new JsonObject();
			entries.forEach((url, markers) -> {
				JsonArray array = new JsonArray();
				for (TimelineMarker marker : markers) {
					JsonObject object = new JsonObject();
					object.addProperty("time_ms", marker.timeMillis());
					object.addProperty("event", marker.eventId().toString());
					array.add(object);
				}
				entryObject.add(url, array);
			});
			root.add(playlist, entryObject);
		});
		try {
			Files.createDirectories(path(server).getParent());
			Files.writeString(path(server), new GsonBuilder().setPrettyPrinting().create().toJson(root),
					StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.error("Failed to save server timeline markers", e);
		}
	}

	private static Path path(MinecraftServer server)
	{
		return server.getServerDirectory().toPath().resolve("config").resolve("mobbattlemusic_timeline_markers.json");
	}
}
