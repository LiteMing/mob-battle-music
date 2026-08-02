package nonamecrackers2.mobbattlemusic.client.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.loading.FMLPaths;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class AudioFilterManager
{
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/AudioFilterManager");
	private static final int MAX_STACKED_FILTERS = 8;
	private static final Map<ResourceLocation, AudioFilterDefinition> CONFIG_DEFINITIONS = new LinkedHashMap<>();
	private static final Map<ResourceLocation, AudioFilterDefinition> RUNTIME_DEFINITIONS = new LinkedHashMap<>();
	private static final AtomicLong REVISION = new AtomicLong();
	private static volatile List<AudioFilterDefinition> active = List.of();
	private static boolean configEnabled;

	private AudioFilterManager() {}

	public static synchronized void register(AudioFilterDefinition definition)
	{
		RUNTIME_DEFINITIONS.put(definition.id(), definition);
	}

	public static synchronized boolean remove(String id)
	{
		ResourceLocation location = ResourceLocation.tryParse(id);
		return location != null && RUNTIME_DEFINITIONS.remove(location) != null;
	}

	public static synchronized List<AudioFilterDefinition> definitions()
	{
		Map<ResourceLocation, AudioFilterDefinition> merged = mergedDefinitions();
		return List.copyOf(merged.values());
	}

	public static void tick(Player player)
	{
		List<AudioFilterDefinition> next = new ArrayList<>();
		for (AudioFilterDefinition definition : definitions()) {
			if (next.size() >= MAX_STACKED_FILTERS)
				break;
			if (IdleConditionRegistry.test(player, definition.conditions()))
				next.add(definition);
		}
		List<AudioFilterDefinition> immutable = List.copyOf(next);
		if (!immutable.equals(active)) {
			active = immutable;
			REVISION.incrementAndGet();
			GlobalAudioFilterManager.onDefinitionsChanged(immutable);
		}
	}

	public static long revision()
	{
		return REVISION.get();
	}

	public static void deactivate()
	{
		if (!active.isEmpty()) {
			active = List.of();
			REVISION.incrementAndGet();
			GlobalAudioFilterManager.onDefinitionsChanged(active);
		}
	}

	public static List<AudioFilterDefinition> activeMbmFilters()
	{
		return active;
	}

	public static synchronized void loadConfig()
	{
		Path path = configPath();
		CONFIG_DEFINITIONS.clear();
		if (!Files.isRegularFile(path)) {
			writeDefault(path);
			configEnabled = false;
			return;
		}
		try {
			JsonObject root = GsonHelper.parse(Files.readString(path, StandardCharsets.UTF_8));
			configEnabled = GsonHelper.getAsBoolean(root, "enabled", false);
			if (!configEnabled)
				return;
			JsonArray filters = GsonHelper.getAsJsonArray(root, "filters", new JsonArray());
			for (JsonElement element : filters) {
				if (!element.isJsonObject())
					continue;
				AudioFilterDefinition definition = parse(element.getAsJsonObject());
				if (definition != null)
					CONFIG_DEFINITIONS.put(definition.id(), definition);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to load audio filters from {}", path, e);
		}
	}

	private static Map<ResourceLocation, AudioFilterDefinition> mergedDefinitions()
	{
		Map<ResourceLocation, AudioFilterDefinition> merged = new LinkedHashMap<>();
		if (configEnabled)
			merged.putAll(CONFIG_DEFINITIONS);
		merged.putAll(RUNTIME_DEFINITIONS);
		return merged;
	}

	private static AudioFilterDefinition parse(JsonObject object)
	{
		try {
			ResourceLocation id = new ResourceLocation(GsonHelper.getAsString(object, "id"));
			AudioFilterDefinition.Scope scope = AudioFilterDefinition.Scope.parse(
					GsonHelper.getAsString(object, "scope", "mbm"));
			AudioFilterDefinition.Type type = AudioFilterDefinition.Type.parse(GsonHelper.getAsString(object, "type"));
			List<IdleCondition> conditions = new ArrayList<>();
			for (JsonElement element : GsonHelper.getAsJsonArray(object, "conditions", new JsonArray())) {
				if (!element.isJsonObject())
					continue;
				JsonObject condition = element.getAsJsonObject();
				conditions.add(new IdleCondition(GsonHelper.getAsString(condition, "type"),
						GsonHelper.getAsString(condition, "argument", ""),
						GsonHelper.getAsBoolean(condition, "inverted", false)));
			}
			return new AudioFilterDefinition(id, scope, type,
					GsonHelper.getAsDouble(object, "frequency_hz", 1_000.0D),
					GsonHelper.getAsDouble(object, "q", 0.707D),
					GsonHelper.getAsDouble(object, "gain_db", 0.0D),
					GsonHelper.getAsInt(object, "bit_depth", 12),
					GsonHelper.getAsInt(object, "sample_rate_hz", 22_050), conditions);
		} catch (Exception e) {
			LOGGER.warn("Ignoring invalid audio filter definition: {}", object, e);
			return null;
		}
	}

	private static void writeDefault(Path path)
	{
		JsonObject root = new JsonObject();
		root.addProperty("enabled", false);
		JsonArray filters = new JsonArray();
		JsonObject underwater = new JsonObject();
		underwater.addProperty("id", "mobbattlemusic:underwater_low_pass");
		underwater.addProperty("scope", "mbm");
		underwater.addProperty("type", "low_pass");
		underwater.addProperty("frequency_hz", 900);
		underwater.addProperty("q", 0.707D);
		JsonArray conditions = new JsonArray();
		JsonObject condition = new JsonObject();
		condition.addProperty("type", "mobbattlemusic:underwater");
		condition.addProperty("argument", "");
		condition.addProperty("inverted", false);
		conditions.add(condition);
		underwater.add("conditions", conditions);
		filters.add(underwater);
		root.add("filters", filters);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Failed to create default audio filter config at {}", path, e);
		}
	}

	private static Path configPath()
	{
		return FMLPaths.CONFIGDIR.get().resolve("mobbattlemusic_audio_filters.json");
	}
}
