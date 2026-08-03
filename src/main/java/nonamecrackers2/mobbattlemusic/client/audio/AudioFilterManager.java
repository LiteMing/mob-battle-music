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
	private static final int PRESET_VERSION = 1;
	private static final int MAX_STACKED_FILTERS = 8;
	private static final Map<ResourceLocation, AudioFilterDefinition> CONFIG_DEFINITIONS = new LinkedHashMap<>();
	private static final Map<ResourceLocation, AudioFilterDefinition> RUNTIME_DEFINITIONS = new LinkedHashMap<>();
	private static final AtomicLong REVISION = new AtomicLong();
	private static volatile List<AudioFilterDefinition> active = List.of();
	// AUD-48 v1.2: explicit pending-removal state - a boolean flag plus the
	// fade-out target configuration. Sentinel-free: the flag alone decides
	// whether a removal is pending, never emptiness or null.
	private static volatile boolean removalPending;
	private static volatile List<AudioFilterDefinition> pendingActive = List.of();
	private static boolean configEnabled;

	private AudioFilterManager() {}

	public static synchronized void register(AudioFilterDefinition definition)
	{
		RUNTIME_DEFINITIONS.put(definition.id(), definition);
	}

	public static synchronized boolean configEnabled()
	{
		return configEnabled;
	}

	public static synchronized List<AudioFilterDefinition> configDefinitions()
	{
		return List.copyOf(CONFIG_DEFINITIONS.values());
	}

	public static synchronized List<AudioFilterDefinition> runtimeDefinitions()
	{
		return List.copyOf(RUNTIME_DEFINITIONS.values());
	}

	public static synchronized void setConfigEnabled(boolean enabled)
	{
		configEnabled = enabled;
		saveConfig();
	}

	public static synchronized void putConfigDefinition(AudioFilterDefinition definition)
	{
		CONFIG_DEFINITIONS.put(definition.id(), definition);
		saveConfig();
	}

	public static synchronized boolean isConfigDefinitionEnabled(ResourceLocation id)
	{
		AudioFilterDefinition definition = CONFIG_DEFINITIONS.get(id);
		return definition != null && definition.enabled();
	}

	public static synchronized void setConfigDefinitionEnabled(ResourceLocation id, boolean enabled)
	{
		AudioFilterDefinition definition = CONFIG_DEFINITIONS.get(id);
		if (definition == null)
			return;
		CONFIG_DEFINITIONS.put(id, definition.withEnabled(enabled));
		saveConfig();
	}

	public static synchronized boolean removeConfigDefinition(ResourceLocation id)
	{
		if (CONFIG_DEFINITIONS.remove(id) == null)
			return false;
		saveConfig();
		return true;
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
		// AUD-48 v1.2: consume the pending removal once the mix has reached
		// zero - chain removal is the action after the fade, never the same
		// tick as setMixTarget(0)
		if (removalPending && PcmFilterChain.mixCurrent() <= 0.001f) {
			active = pendingActive;
			removalPending = false;
			pendingActive = List.of();
			REVISION.incrementAndGet();
			GlobalAudioFilterManager.onDefinitionsChanged(active);
		}
		List<AudioFilterDefinition> next = new ArrayList<>();
		for (AudioFilterDefinition definition : definitions()) {
			if (next.size() >= MAX_STACKED_FILTERS)
				break;
			if (definition.enabled() && IdleConditionRegistry.test(player, definition.conditions()))
				next.add(definition);
		}
		List<AudioFilterDefinition> immutable = List.copyOf(next);
		// AUD-48 v1.2 #2: while a removal fades out, the comparison reference
		// is the fade-out target state, not the current active
		List<AudioFilterDefinition> reference = removalPending ? pendingActive : active;
		if (immutable.equals(reference))
			return;
		boolean nowActive = !immutable.isEmpty();
		if (removalPending) {
			// AUD-48 v1.2 #3: activation conditions became true again while
			// the fade-out is still running - cancel the pending removal and
			// pull the mix back. No chain change and no REVISION bump unless
			// the configuration actually differs from what is installed.
			removalPending = false;
			pendingActive = List.of();
			// Instant-only filters skip the fade (user option): mix jumps
			PcmFilterChain.setMixTarget(1.0f, instantInMillis(immutable));
			if (!immutable.equals(active)) {
				active = immutable;
				REVISION.incrementAndGet();
				GlobalAudioFilterManager.onDefinitionsChanged(immutable);
			}
			return;
		}
		boolean wasActive = !active.isEmpty();
		if (!wasActive && nowActive) {
			// AUD-48: activation fades the mix in (150ms); the chain can
			// be installed immediately - it is evaluated as the wet
			// component of the crossfade. Instant-only filters skip the fade.
			PcmFilterChain.setMixTarget(1.0f, instantInMillis(immutable));
			active = immutable;
			REVISION.incrementAndGet();
			GlobalAudioFilterManager.onDefinitionsChanged(immutable);
		} else if (wasActive && !nowActive) {
			// AUD-48 v1.2: deactivation only fades the mix and sets the
			// explicit pending flag; the chain is removed once the mix
			// reaches zero. Instant-only filters skip the fade.
			PcmFilterChain.setMixTarget(0.0f, instantOutMillis(active));
			removalPending = true;
			pendingActive = immutable;
		} else {
			// Recomposition while active: install immediately; per-processor
			// state is preserved or a >=20ms crossfade runs
			active = immutable;
			REVISION.incrementAndGet();
			GlobalAudioFilterManager.onDefinitionsChanged(immutable);
		}
	}

	public static boolean isRemovalPending()
	{
		return removalPending;
	}

	// AUD-48: filters with the instant option skip the mix fade (user option);
	// when every filter in the configuration is instant, the fade duration is 0
	private static long instantInMillis(List<AudioFilterDefinition> definitions)
	{
		boolean instant = !definitions.isEmpty() && definitions.stream().allMatch(AudioFilterDefinition::instant);
		return instant ? 0L : PcmFilterChain.mixInMillis();
	}

	private static long instantOutMillis(List<AudioFilterDefinition> definitions)
	{
		boolean instant = !definitions.isEmpty() && definitions.stream().allMatch(AudioFilterDefinition::instant);
		return instant ? 0L : PcmFilterChain.mixOutMillis();
	}

	public static long revision()
	{
		return REVISION.get();
	}

	public static void deactivate()
	{
		// AUD-48 v1.2 #4: deactivation goes through the same gated path as
		// tick() - fade the mix, set the explicit pending flag; the chain is
		// removed when the mix reaches zero (or by the next tick's consume)
		if (!active.isEmpty() && !removalPending) {
			PcmFilterChain.setMixTarget(0.0f, PcmFilterChain.mixOutMillis());
			removalPending = true;
			pendingActive = List.of();
		}
	}

	public static List<AudioFilterDefinition> activeMbmFilters()
	{
		return active.stream().filter(definition -> definition.scope() != AudioFilterDefinition.Scope.MINECRAFT)
				.toList();
	}

	public static synchronized void loadConfig()
	{
		Path path = configPath();
		CONFIG_DEFINITIONS.clear();
		configEnabled = false;
		if (!Files.isRegularFile(path))
			writeDefault(path);
		try {
			JsonObject root = GsonHelper.parse(Files.readString(path, StandardCharsets.UTF_8));
			configEnabled = GsonHelper.getAsBoolean(root, "enabled", false);
			JsonArray filters = GsonHelper.getAsJsonArray(root, "filters", new JsonArray());
			for (JsonElement element : filters) {
				if (!element.isJsonObject())
					continue;
				AudioFilterDefinition definition = parse(element.getAsJsonObject());
				if (definition != null)
					CONFIG_DEFINITIONS.put(definition.id(), definition);
			}
			if (GsonHelper.getAsInt(root, "preset_version", 0) < PRESET_VERSION) {
				addMissingPresets();
				saveConfig();
			}
		} catch (Exception e) {
			LOGGER.error("Failed to load audio filters from {}", path, e);
		}
	}

	private static void saveConfig()
	{
		JsonObject root = new JsonObject();
		root.addProperty("enabled", configEnabled);
		root.addProperty("preset_version", PRESET_VERSION);
		JsonArray filters = new JsonArray();
		for (AudioFilterDefinition definition : CONFIG_DEFINITIONS.values()) {
			JsonObject object = new JsonObject();
			object.addProperty("id", definition.id().toString());
			object.addProperty("enabled", definition.enabled());
			object.addProperty("scope", definition.scope().getSerializedName());
			object.addProperty("type", definition.type().getSerializedName());
			object.addProperty("frequency_hz", definition.frequencyHz());
			object.addProperty("q", definition.q());
			object.addProperty("gain_db", definition.gainDb());
			object.addProperty("bit_depth", definition.bitDepth());
			object.addProperty("sample_rate_hz", definition.sampleRateHz());
			JsonArray conditions = new JsonArray();
			for (IdleCondition condition : definition.conditions()) {
				JsonObject conditionObject = new JsonObject();
				conditionObject.addProperty("type", condition.type());
				conditionObject.addProperty("argument", condition.argument());
				conditionObject.addProperty("inverted", condition.inverted());
				conditions.add(conditionObject);
			}
			object.add("conditions", conditions);
			object.addProperty("instant", definition.instant());
			filters.add(object);
		}
		root.add("filters", filters);
		Path path = configPath();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root),
					StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.error("Failed to save audio filters to {}", path, e);
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
			return new AudioFilterDefinition(id, GsonHelper.getAsBoolean(object, "enabled", true), scope, type,
					GsonHelper.getAsDouble(object, "frequency_hz", 1_000.0D),
					GsonHelper.getAsDouble(object, "q", 0.707D),
					GsonHelper.getAsDouble(object, "gain_db", 0.0D),
					GsonHelper.getAsInt(object, "bit_depth", 12),
					GsonHelper.getAsInt(object, "sample_rate_hz", 22_050), conditions,
					GsonHelper.getAsBoolean(object, "instant", false));
		} catch (Exception e) {
			LOGGER.warn("Ignoring invalid audio filter definition: {}", object, e);
			return null;
		}
	}

	private static void writeDefault(Path path)
	{
		JsonObject root = new JsonObject();
		root.addProperty("enabled", false);
		root.addProperty("preset_version", PRESET_VERSION);
		JsonArray filters = new JsonArray();
		filters.add(defaultFilter("mobbattlemusic:underwater_low_pass", "low_pass", 900.0D, 12, 22_050,
				"mobbattlemusic:underwater", ""));
		filters.add(defaultFilter("mobbattlemusic:nether_high_pass", "high_pass", 250.0D, 12, 22_050,
				"mobbattlemusic:dimension", "minecraft:the_nether"));
		filters.add(defaultFilter("mobbattlemusic:end_lofi", "lofi", 1_000.0D, 10, 12_000,
				"mobbattlemusic:dimension", "minecraft:the_end"));
		root.add("filters", filters);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Failed to create default audio filter config at {}", path, e);
		}
	}

	private static void addMissingPresets()
	{
		putMissingPreset("mobbattlemusic:underwater_low_pass", AudioFilterDefinition.Type.LOW_PASS, 900.0D,
				12, 22_050, "mobbattlemusic:underwater", "");
		putMissingPreset("mobbattlemusic:nether_high_pass", AudioFilterDefinition.Type.HIGH_PASS, 250.0D,
				12, 22_050, "mobbattlemusic:dimension", "minecraft:the_nether");
		putMissingPreset("mobbattlemusic:end_lofi", AudioFilterDefinition.Type.LOFI, 1_000.0D,
				10, 12_000, "mobbattlemusic:dimension", "minecraft:the_end");
	}

	private static void putMissingPreset(String id, AudioFilterDefinition.Type type, double frequency, int bitDepth,
			int sampleRate, String conditionType, String conditionArgument)
	{
		ResourceLocation location = new ResourceLocation(id);
		CONFIG_DEFINITIONS.putIfAbsent(location, new AudioFilterDefinition(location, false,
				AudioFilterDefinition.Scope.MBM, type, frequency, 0.707D, 0.0D, bitDepth, sampleRate,
				List.of(new IdleCondition(conditionType, conditionArgument, false)),
				"mobbattlemusic:underwater_low_pass".equals(location.toString())));
	}

	private static JsonObject defaultFilter(String id, String type, double frequency, int bitDepth, int sampleRate,
			String conditionType, String conditionArgument)
	{
		JsonObject filter = new JsonObject();
		filter.addProperty("id", id);
		filter.addProperty("enabled", false);
		filter.addProperty("scope", "mbm");
		filter.addProperty("type", type);
		filter.addProperty("frequency_hz", frequency);
		filter.addProperty("q", 0.707D);
		filter.addProperty("gain_db", 0.0D);
		filter.addProperty("bit_depth", bitDepth);
		filter.addProperty("sample_rate_hz", sampleRate);
		JsonObject condition = new JsonObject();
		condition.addProperty("type", conditionType);
		condition.addProperty("argument", conditionArgument);
		condition.addProperty("inverted", false);
		JsonArray conditions = new JsonArray();
		conditions.add(condition);
		filter.add("conditions", conditions);
		return filter;
	}

	private static Path configPath()
	{
		return FMLPaths.CONFIGDIR.get().resolve("mobbattlemusic_audio_filters.json");
	}
}
