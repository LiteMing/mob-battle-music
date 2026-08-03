package nonamecrackers2.mobbattlemusic.client.resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * K9-1: playlist import parsing - M3U/M3U8, MBM playlist JSON (the local
 * playlist file format, enabling export->import round trips) and plain text
 * (clipboard). Pure parsing: no state, no manager access; validation and
 * commit live in MusicTracksManager.importLocalUrls (transactional).
 */
public final class PlaylistImportParser
{
	public static final String EXPORT_FORMAT_ID = "mbm-playlist-export";
	public static final int EXPORT_FORMAT_VERSION = 1;

	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/PlaylistImportParser");

	private PlaylistImportParser() {}

	/**
	 * K10-D: import line abstraction - parsers produce plain lines, GUI
	 * layers may attach extra metadata (e.g. the source binding of JSON
	 * imports) by implementing this interface.
	 */
	public interface ImportLine
	{
		String raw();

		String title();
	}

	public static record PlainImportLine(String raw, String title) implements ImportLine {}

	/**
	 * Parse an M3U/M3U8 file. #EXTINF lines carry the display title; plain
	 * URL lines are entries. Comment/blank lines are skipped. A non-URL
	 * line (e.g. an unresolved path) is kept as an entry so the preview can
	 * mark it as an error row - nothing is dropped silently.
	 */
	public static List<ImportLine> parseM3U(Path file)
	{
		List<ImportLine> lines = new ArrayList<>();
		String pendingTitle = "";
		try {
			for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String line = raw.trim();
				if (line.isEmpty())
					continue;
				if (line.startsWith("#"))
				{
					if (line.startsWith("#EXTINF:"))
						pendingTitle = extractExtinfTitle(line);
					continue;
				}
				lines.add(new PlainImportLine(line, pendingTitle.isEmpty() ? null : pendingTitle));
				pendingTitle = "";
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to read M3U file {}", file, e);
			return List.of();
		}
		return lines;
	}

	private static String extractExtinfTitle(String extinfLine)
	{
		int comma = extinfLine.indexOf(',');
		return comma >= 0 ? extinfLine.substring(comma + 1).trim() : "";
	}

	/**
	 * Parse plain text (clipboard): one URL per line, or space/newline
	 * separated tokens. Invalid tokens are kept so the preview can flag them.
	 */
	public static List<ImportLine> parseText(String text)
	{
		List<ImportLine> lines = new ArrayList<>();
		if (text == null)
			return lines;
		for (String token : text.trim().split("[\\s,;]+")) {
			if (token.isEmpty())
				continue;
			lines.add(new PlainImportLine(token, null));
		}
		return lines;
	}

	/**
	 * K11-D: shared file-type predicates used by the drop callback and the
	 * import screen - directories and local audio files must reach the
	 * import preview too (audio files are shown as non-streamable rows).
	 */
	public static boolean isImportableFile(Path file)
	{
		String lower = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
		return lower.endsWith(".m3u") || lower.endsWith(".m3u8") || lower.endsWith(".json")
				|| isAudioFile(lower) || lower.endsWith(".txt");
	}

	public static boolean isAudioFile(String lower)
	{
		return lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".wav")
				|| lower.endsWith(".flac") || lower.endsWith(".m4a");
	}

	/**
	 * The export format - a single binding's entries as
	 * { "format": "mbm-playlist-export", "version": 1, "binding": <key>,
	 *   "entries": [url, ...] }.
	 */
	public static String buildExportJson(String bindingKey, List<String> urls)
	{
		JsonObject root = new JsonObject();
		root.addProperty("format", EXPORT_FORMAT_ID);
		root.addProperty("version", EXPORT_FORMAT_VERSION);
		root.addProperty("binding", bindingKey);
		JsonArray entries = new JsonArray();
		for (String url : urls)
			entries.add(url);
		root.add("entries", entries);
		return new GsonBuilder().setPrettyPrinting().create().toJson(root);
	}

	/**
	 * Parse an MBM JSON file. Supports two shapes:
	 * 1) the export format above (single binding);
	 * 2) the local playlist file format (scenes/entity_types/entity_uuids/
	 *    idle_rules sections) - returns every binding's entries keyed by
	 *    storage key.
	 * Malformed JSON yields an empty map (the preview shows nothing); per-
	 * binding parse errors are logged and skipped, never fatal.
	 */
	public static Map<String, List<ImportLine>> parseMbmJson(Path file)
	{
		Map<String, List<ImportLine>> result = new LinkedHashMap<>();
		JsonObject root;
		try {
			root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException | JsonSyntaxException | IllegalStateException e) {
			LOGGER.warn("Failed to parse MBM JSON file {}: {}", file, e.toString());
			return result;
		}
		if (root.has("format") && EXPORT_FORMAT_ID.equals(root.get("format").getAsString())) {
			String binding = root.has("binding") ? root.get("binding").getAsString() : "scene:aggressive";
			List<ImportLine> lines = new ArrayList<>();
			if (root.has("entries"))
				for (JsonElement element : root.getAsJsonArray("entries"))
					lines.add(new PlainImportLine(element.getAsString(), null));
			result.put(binding, lines);
			return result;
		}
		parseSection(result, root, "scenes");
		parseSection(result, root, "entity_types");
		parseSection(result, root, "entity_uuids");
		parseSection(result, root, "idle_rules");
		return result;
	}

	private static void parseSection(Map<String, List<ImportLine>> result, JsonObject root, String section)
	{
		if (!root.has(section))
			return;
		JsonObject sectionObject = root.getAsJsonObject(section);
		for (Map.Entry<String, JsonElement> entry : sectionObject.entrySet()) {
			List<ImportLine> lines = new ArrayList<>();
			try {
				for (JsonElement urlElement : entry.getValue().getAsJsonArray())
					lines.add(new PlainImportLine(urlElement.getAsString(), null));
			} catch (IllegalStateException e) {
				LOGGER.warn("MBM JSON section {} entry {} is not an array; skipped", section, entry.getKey());
				continue;
			}
			if (!lines.isEmpty())
				result.put(entry.getKey(), lines);
		}
	}
}
