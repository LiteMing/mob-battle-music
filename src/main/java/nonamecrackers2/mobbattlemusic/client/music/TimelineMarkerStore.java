package nonamecrackers2.mobbattlemusic.client.music;

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

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.fml.loading.FMLPaths;
import nonamecrackers2.mobbattlemusic.network.ServerExternalPlaylistSyncPacket;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class TimelineMarkerStore
{
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/TimelineMarkerStore");
	private static final Map<String, Map<String, List<TimelineMarker>>> LOCAL = new LinkedHashMap<>();
	private static final Map<String, Map<String, List<TimelineMarker>>> SERVER = new LinkedHashMap<>();
	private static boolean loaded;

	private TimelineMarkerStore() {}

	public static synchronized List<TimelineMarker> markers(ResourceLocation playlist, String url)
	{
		return markers(playlist, url, -1);
	}

	public static synchronized List<TimelineMarker> markers(ResourceLocation playlist, String url, int entryIndex)
	{
		load();
		Map<String, List<TimelineMarker>> local = LOCAL.get(playlist.toString());
		if (local != null) {
			List<TimelineMarker> indexed = local.get(entryKey(entryIndex));
			if (indexed != null)
				return List.copyOf(indexed);
			if (local.containsKey(url))
				return List.copyOf(local.get(url));
		}
		Map<String, List<TimelineMarker>> server = SERVER.getOrDefault(playlist.toString(), Map.of());
		List<TimelineMarker> indexed = server.get(entryKey(entryIndex));
		return List.copyOf(indexed == null ? server.getOrDefault(url, List.of()) : indexed);
	}

	// K14-D: strict source isolation - the SERVER editor must only ever see
	// the server snapshot (a LOCAL marker for the same playlist/index must
	// not leak in and corrupt the delete index math); the LOCAL editor only
	// sees local markers.
	public static synchronized List<TimelineMarker> localMarkers(ResourceLocation playlist, String url, int entryIndex)
	{
		load();
		Map<String, List<TimelineMarker>> local = LOCAL.get(playlist.toString());
		if (local != null) {
			List<TimelineMarker> indexed = local.get(entryKey(entryIndex));
			if (indexed != null)
				return List.copyOf(indexed);
			if (local.containsKey(url))
				return List.copyOf(local.get(url));
		}
		return List.of();
	}

	public static synchronized List<TimelineMarker> serverMarkers(ResourceLocation playlist, String url, int entryIndex)
	{
		Map<String, List<TimelineMarker>> server = SERVER.getOrDefault(playlist.toString(), Map.of());
		List<TimelineMarker> indexed = server.get(entryKey(entryIndex));
		return List.copyOf(indexed == null ? server.getOrDefault(url, List.of()) : indexed);
	}

	public static synchronized void applyServerSync(ServerExternalPlaylistSyncPacket packet)
	{
		SERVER.clear();
		for (ServerExternalPlaylistSyncPacket.TrackDefinition track : packet.tracks()) {
			Map<String, List<TimelineMarker>> entries = new LinkedHashMap<>();
			for (int i = 0; i < track.entries().size(); i++) {
				ServerExternalPlaylistSyncPacket.Entry entry = track.entries().get(i);
				if (!entry.markers().isEmpty()) {
					entries.put(entryKey(i), entry.markers());
					entries.put(entry.url(), entry.markers());
					String resolved = UrlResolver.resolveUrl(entry.url());
					if (!resolved.equals(entry.url()))
						entries.put(resolved, entry.markers());
				}
			}
			if (!entries.isEmpty())
				SERVER.put(track.configLocation().toString(), entries);
		}
	}

	public static synchronized void clearServer()
	{
		SERVER.clear();
	}

	public static synchronized String addLocal(ResourceLocation playlist, int entryIndex, TimelineMarker marker)
	{
		load();
		List<TimelineMarker> markers = LOCAL.computeIfAbsent(playlist.toString(), key -> new LinkedHashMap<>())
				.computeIfAbsent(entryKey(entryIndex), key -> new ArrayList<>());
		markers.add(marker);
		markers.sort(Comparator.comparingLong(TimelineMarker::timeMillis));
		save();
		return "Added timeline marker #" + (markers.indexOf(marker) + 1);
	}

	public static synchronized String deleteLocal(ResourceLocation playlist, int entryIndex, int index)
	{
		load();
		Map<String, List<TimelineMarker>> entries = LOCAL.get(playlist.toString());
		String entryKey = entryKey(entryIndex);
		List<TimelineMarker> markers = entries == null ? null : entries.get(entryKey);
		if (markers == null || index < 0 || index >= markers.size())
			return "Timeline marker index out of range";
		markers.remove(index);
		if (markers.isEmpty())
			entries.remove(entryKey);
		if (entries.isEmpty())
			LOCAL.remove(playlist.toString());
		save();
		return "Deleted timeline marker #" + (index + 1);
	}

	private static void load()
	{
		if (loaded)
			return;
		loaded = true;
		Path path = path();
		if (!Files.isRegularFile(path))
			return;
		try {
			read(GsonHelper.parse(Files.readString(path, StandardCharsets.UTF_8)), LOCAL);
		} catch (Exception e) {
			LOGGER.warn("Failed to load timeline markers from {}", path, e);
		}
	}

	private static void read(JsonObject root, Map<String, Map<String, List<TimelineMarker>>> output)
	{
		for (Map.Entry<String, JsonElement> playlist : root.entrySet()) {
			if (!playlist.getValue().isJsonObject() || ResourceLocation.tryParse(playlist.getKey()) == null)
				continue;
			Map<String, List<TimelineMarker>> entries = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> entry : playlist.getValue().getAsJsonObject().entrySet()) {
				if (!entry.getValue().isJsonArray())
					continue;
				List<TimelineMarker> markers = new ArrayList<>();
				for (JsonElement markerElement : entry.getValue().getAsJsonArray()) {
					JsonObject marker = markerElement.getAsJsonObject();
					ResourceLocation event = ResourceLocation.tryParse(GsonHelper.getAsString(marker, "event"));
					if (event != null)
						markers.add(new TimelineMarker(GsonHelper.getAsLong(marker, "time_ms"), event));
				}
				markers.sort(Comparator.comparingLong(TimelineMarker::timeMillis));
				if (!markers.isEmpty())
					entries.put(entry.getKey(), markers);
			}
			if (!entries.isEmpty())
				output.put(playlist.getKey(), entries);
		}
	}

	private static void save()
	{
		JsonObject root = new JsonObject();
		LOCAL.forEach((playlist, entries) -> {
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
			Files.createDirectories(path().getParent());
			Files.writeString(path(), new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.error("Failed to save timeline markers", e);
		}
	}

	private static Path path()
	{
		return FMLPaths.CONFIGDIR.get().resolve("mobbattlemusic_timeline_markers.json");
	}

	private static String entryKey(int index)
	{
		return index < 0 ? "" : "#" + index;
	}
}
