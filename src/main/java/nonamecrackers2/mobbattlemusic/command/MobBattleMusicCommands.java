package nonamecrackers2.mobbattlemusic.command;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.registries.ForgeRegistries;
import nonamecrackers2.mobbattlemusic.MobBattleMusicMod;
import nonamecrackers2.mobbattlemusic.client.audio.MarkerClock;
import nonamecrackers2.mobbattlemusic.client.audio.MbmSessionState;
import nonamecrackers2.mobbattlemusic.client.audio.PcmFilterChain;
import nonamecrackers2.mobbattlemusic.client.audio.PlaybackHandle;
import nonamecrackers2.mobbattlemusic.client.audio.PreviewChannel;
import nonamecrackers2.mobbattlemusic.client.audio.SourceRef;
import nonamecrackers2.mobbattlemusic.client.audio.WorldPlaybackChannel;
import nonamecrackers2.mobbattlemusic.client.audio.AudioFilterManager;
import nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler;
import nonamecrackers2.mobbattlemusic.client.music.StreamMusicPlayer;
import nonamecrackers2.mobbattlemusic.client.music.TimelineMarkerStore;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.network.ExternalPlaylistControlPacket;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;

public class MobBattleMusicCommands
{
	public static void register(RegisterCommandsEvent event)
	{
		RequiredArgumentBuilder<CommandSourceStack, EntitySelector> targets = Commands.argument("targets", EntityArgument.players());
		targets.then(Commands.literal("set")
				.then(playlistArgument()
						.then(Commands.argument("music", StringArgumentType.word())
								.suggests(ExternalPlaylistCatalogServer::suggestEntries)
								.executes(context -> send(context.getSource(),
										EntityArgument.getPlayers(context, "targets"),
										ResourceLocationArgument.getId(context, "playlist"),
										ExternalPlaylistControlPacket.Action.SET,
										StringArgumentType.getString(context, "music"))))));
		targets.then(Commands.literal("random_next")
				.then(playlistArgument()
						.executes(context -> sendRandomNext(context.getSource(),
								EntityArgument.getPlayers(context, "targets"),
								ResourceLocationArgument.getId(context, "playlist")))));
		targets.then(Commands.literal("play")
				.then(playlistArgument()
						.then(Commands.argument("music", StringArgumentType.word())
								.suggests(ExternalPlaylistCatalogServer::suggestEntries)
								.executes(context -> send(context.getSource(),
										EntityArgument.getPlayers(context, "targets"),
										ResourceLocationArgument.getId(context, "playlist"),
										ExternalPlaylistControlPacket.Action.PLAY_SELECTION,
										StringArgumentType.getString(context, "music"))))));
		targets.then(Commands.literal("play_url")
				.then(Commands.argument("url", StringArgumentType.greedyString())
						.executes(context -> send(context.getSource(),
								EntityArgument.getPlayers(context, "targets"),
								MobBattleMusicMod.id("direct"),
								ExternalPlaylistControlPacket.Action.PLAY_URL,
								StringArgumentType.getString(context, "url")))));
		targets.then(Commands.literal("stop")
				.executes(context -> send(context.getSource(),
						EntityArgument.getPlayers(context, "targets"),
						MobBattleMusicMod.id("direct"),
						ExternalPlaylistControlPacket.Action.STOP,
						"")));
		targets.then(Commands.literal("clear")
				.then(playlistArgument()
						.executes(context -> send(context.getSource(),
								EntityArgument.getPlayers(context, "targets"),
								ResourceLocationArgument.getId(context, "playlist"),
								ExternalPlaylistControlPacket.Action.CLEAR,
								""))));
		targets.then(Commands.literal("list")
				.executes(context -> ExternalPlaylistCatalogServer.list(context.getSource(),
						EntityArgument.getPlayers(context, "targets"))));
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mobbattlemusic")
				.requires(MobBattleMusicCommands::canUsePlaylistCommand);
		for (String scene : ServerExternalPlaylistStore.supportedScenes())
			root.then(serverSceneLiteral(scene, scene));
		root.then(timelineMarkerArgument());
		root.then(entryConditionArgument());
		root.then(Commands.literal("debug")
				.then(Commands.literal("session")
						.executes(context -> debugSession(context.getSource())))
				.then(Commands.literal("inject-drift")
						.then(Commands.argument("seconds", DoubleArgumentType.doubleArg())
								.executes(context -> injectDrift(context.getSource(),
										DoubleArgumentType.getDouble(context, "seconds"))))));
		root.then(Commands.literal("scene")
				.then(Commands.argument("scene", StringArgumentType.word())
						.suggests(MobBattleMusicCommands::suggestScenes)
						.then(Commands.literal("add")
								.then(Commands.argument("url", StringArgumentType.greedyString())
										.executes(context -> ServerExternalPlaylistStore.add(context.getSource(),
												StringArgumentType.getString(context, "scene"),
												StringArgumentType.getString(context, "url")))))
						.then(Commands.literal("delete")
								.then(Commands.argument("index", IntegerArgumentType.integer(1))
										.executes(context -> ServerExternalPlaylistStore.delete(context.getSource(),
												StringArgumentType.getString(context, "scene"),
												IntegerArgumentType.getInteger(context, "index") - 1))))
						.then(Commands.literal("list")
								.executes(context -> ServerExternalPlaylistStore.list(context.getSource(),
										StringArgumentType.getString(context, "scene"))))
						.then(Commands.literal("order")
								.then(selectionModeArgument()
										.executes(context -> ServerExternalPlaylistStore.setSelectionMode(context.getSource(),
												StringArgumentType.getString(context, "scene"),
												StringArgumentType.getString(context, "selection_mode")))))));
		root.then(targets);
		event.getDispatcher().register(root);
		event.getDispatcher().register(Commands.literal("mbmplaylist")
				.requires(MobBattleMusicCommands::canUsePlaylistCommand)
				.executes(context -> openGui(context.getSource())));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> timelineMarkerArgument()
	{
		return Commands.literal("marker")
				.then(Commands.argument("marker_playlist", ResourceLocationArgument.id())
						.suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
								ServerExternalPlaylistStore.recordedPlaylists(context.getSource().getServer()), builder))
						.then(Commands.argument("track_index", IntegerArgumentType.integer(1))
								.then(Commands.literal("add")
										.then(Commands.argument("time_ms", LongArgumentType.longArg(0L, 86_400_000L))
												.then(Commands.argument("event_id", ResourceLocationArgument.id())
														.executes(context -> ServerTimelineMarkerStore.add(context.getSource(),
																ResourceLocationArgument.getId(context, "marker_playlist"),
																IntegerArgumentType.getInteger(context, "track_index") - 1,
																LongArgumentType.getLong(context, "time_ms"),
																ResourceLocationArgument.getId(context, "event_id"))))))
								.then(Commands.literal("delete")
										.then(Commands.argument("marker_index", IntegerArgumentType.integer(1))
												.executes(context -> ServerTimelineMarkerStore.delete(context.getSource(),
														ResourceLocationArgument.getId(context, "marker_playlist"),
														IntegerArgumentType.getInteger(context, "track_index") - 1,
														IntegerArgumentType.getInteger(context, "marker_index") - 1))))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> entryConditionArgument()
	{
		RequiredArgumentBuilder<CommandSourceStack, String> conditionType =
				Commands.argument("entry_condition_type", StringArgumentType.word())
						.suggests(MobBattleMusicCommands::suggestConditionTypes)
						.executes(context -> addEntryCondition(context, false, ""))
						.then(Commands.argument("entry_condition_argument", StringArgumentType.greedyString())
								.executes(context -> addEntryCondition(context, false,
										StringArgumentType.getString(context, "entry_condition_argument"))));
		RequiredArgumentBuilder<CommandSourceStack, String> invertedConditionType =
				Commands.argument("entry_condition_type", StringArgumentType.word())
						.suggests(MobBattleMusicCommands::suggestConditionTypes)
						.executes(context -> addEntryCondition(context, true, ""))
						.then(Commands.argument("entry_condition_argument", StringArgumentType.greedyString())
								.executes(context -> addEntryCondition(context, true,
										StringArgumentType.getString(context, "entry_condition_argument"))));
		return Commands.literal("entry_condition")
				.then(Commands.argument("entry_playlist", ResourceLocationArgument.id())
						.suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
								ServerExternalPlaylistStore.recordedPlaylists(context.getSource().getServer()), builder))
						.then(Commands.argument("entry_track_index", IntegerArgumentType.integer(1))
								.then(Commands.literal("add").then(conditionType))
								.then(Commands.literal("add_not").then(invertedConditionType))
								.then(Commands.literal("delete")
										.then(Commands.argument("entry_condition_index", IntegerArgumentType.integer(1))
												.executes(context -> ServerExternalPlaylistStore.deleteEntryCondition(
														context.getSource(),
														ResourceLocationArgument.getId(context, "entry_playlist"),
														IntegerArgumentType.getInteger(context, "entry_track_index") - 1,
														IntegerArgumentType.getInteger(context, "entry_condition_index") - 1))))));
	}

	private static int addEntryCondition(CommandContext<CommandSourceStack> context, boolean inverted, String argument)
	{
		return ServerExternalPlaylistStore.addEntryCondition(context.getSource(),
				ResourceLocationArgument.getId(context, "entry_playlist"),
				IntegerArgumentType.getInteger(context, "entry_track_index") - 1,
				StringArgumentType.getString(context, "entry_condition_type"), argument, inverted);
	}

	private static boolean canUsePlaylistCommand(CommandSourceStack source)
	{
		return source.hasPermission(2) || source.getServer().isSingleplayer();
	}

	private static int openGui(CommandSourceStack source)
	{
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendFailure(Component.literal("Only players can open the Mob Battle Music GUI"));
			return 0;
		}
		MobBattleMusicNetwork.openPlaylistGui(player);
		return 1;
	}

	private static int debugSession(CommandSourceStack source)
	{
		if (Dist.DEDICATED_SERVER.isDedicatedServer())
		{
			source.sendFailure(Component.literal("debug session is a client-side probe and is not available on a dedicated server"));
			return 0;
		}
		String output = DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> MobBattleMusicCommands::buildDebugSessionOutput);
		for (String line : output.split("\n", -1))
			source.sendSuccess(() -> Component.literal(line), false);
		return 1;
	}

	private static int injectDrift(CommandSourceStack source, double seconds)
	{
		if (Dist.DEDICATED_SERVER.isDedicatedServer())
		{
			source.sendFailure(Component.literal("inject-drift is a client-side test hook and is not available on a dedicated server"));
			return 0;
		}
		DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MarkerClock.injectDrift(seconds));
		source.sendSuccess(() -> Component.literal("Injected " + seconds + "s of clock drift (S15 test hook)"), false);
		return 1;
	}

	private static String buildDebugSessionOutput()
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		StreamMusicPlayer main = handler.getPlayer();

		StringBuilder output = new StringBuilder();
		output.append("session=").append(MbmSessionState.current())
				.append(" focused=").append(MbmSessionState.isFocused())
				.append(" paused=").append(MbmSessionState.isPausedNow())
				.append(" published=").append(MbmSessionState.isPublishedNow()).append('\n');

		WorldPlaybackChannel.ChannelState worldChannelState = WorldPlaybackChannel.state();
		// AUD-44: gain envelope currents; final = track x mute x seek
		float trackGain = main.trackEnv().current();
		float muteGain = StreamMusicPlayer.MUTE_ENV.current();
		float seekGain = main.seekEnv().current();
		float finalGain = trackGain * muteGain * seekGain;
		output.append("world.state=").append(worldChannelState.name())
				.append(" gain=").append(String.format(Locale.ROOT, "%.2f", finalGain))
				.append(" track=").append(handler.getCurrentlyPlayingUrl() == null
						? "none" : handler.getCurrentlyPlayingUrl())
				.append(" pos=").append(formatSeconds(handler.getPositionMillis())).append('\n');
		output.append("world.gain final=").append(String.format(Locale.ROOT, "%.2f", finalGain))
				.append(" track=").append(String.format(Locale.ROOT, "%.2f", trackGain))
				.append(" mute=").append(String.format(Locale.ROOT, "%.2f", muteGain))
				.append(" seek=").append(String.format(Locale.ROOT, "%.2f", seekGain))
				// AUD-30 v1.7: who is writing the envelopes
				.append(" trackTarget=").append(String.format(Locale.ROOT, "%.2f", main.trackEnv().target()))
				.append(" gateOwner=").append(gateOwner()).append('\n');

		// AUD-30 v1.3/AUD-47: audible vs decoded position and their latency gap
		long audiblePos = handler.getPositionMillis();
		long decodedPos = handler.getDecodedPositionMillis();
		output.append("world.pos audible=").append(formatSeconds(audiblePos))
				.append(" decoded=").append(formatSeconds(decodedPos))
				.append(" outLatency=").append(Math.max(0L, decodedPos - audiblePos)).append("ms").append('\n');

		// AUD-30 v1.3/v1.5: output line capacity, fill, watermark, underruns
		int lineBuffer = main.getLineBufferBytes();
		int lineAvailable = main.getLineAvailableBytes();
		long lineWatermark = main.getLineWatermarkBytes();
		output.append("world.line buffer=").append(lineBuffer)
				.append("B fill=").append(Math.max(0, lineBuffer - lineAvailable)).append("B")
				.append(" watermark=").append(lineWatermark).append("B")
				.append(" underruns=").append(main.getUnderruns()).append('\n');

		// AUD-30 v1.3/AUD-48: active filters and dry/wet mix envelope
		java.util.List<String> activeFilters = AudioFilterManager.activeMbmFilters().stream()
				.map(definition -> definition.id().getPath())
				.toList();
		output.append("world.filter active=[").append(String.join(",", activeFilters))
				.append("] mix=").append(String.format(Locale.ROOT, "%.2f", PcmFilterChain.mixCurrent()))
				.append(" target=").append(String.format(Locale.ROOT, "%.2f", PcmFilterChain.mixTarget()))
				// AUD-30 v1.6: explicit pending-removal flag
				.append(" pendingRemoval=").append(AudioFilterManager.isRemovalPending()).append('\n');

		String clockState = !MarkerClock.isActive() ? "STOPPED" : MarkerClock.state().name();
		double drift = 0.0D;
		String currentUrl = handler.getCurrentlyPlayingUrl();
		if (MarkerClock.isActive() && currentUrl != null)
			drift = MarkerClock.driftSeconds(currentUrl, handler.getPositionMillis());
		long lastSync = MarkerClock.millisSinceLastSync();
		output.append("world.clock=").append(clockState)
				.append(" drift=").append(formatSignedSeconds(drift))
				.append(" lastSync=").append(lastSync < 0L
						? "n/a" : String.format(Locale.ROOT, "%.1fs_ago", lastSync / 1000.0D))
				// AUD-30 v1.2/v1.8: injected drift (S15) and its TTL; clock
				// state; seek accounting (AUD-52)
				.append(" injected=").append(String.format(Locale.ROOT, "%.2f",
						MarkerClock.injectedDriftSeconds()))
				.append(" state=").append(MarkerClock.state().name())
				.append(" seeks=").append(WorldPlaybackChannel.seekCount())
				.append(" sinceSeek=").append(WorldPlaybackChannel.millisSinceSeek())
				.append(" injectedTtl=").append(MarkerClock.injectedTtlMillis()).append('\n');

		PlaybackHandle worldHandle = WorldPlaybackChannel.handle();
		String playlistRef = "n/a";
		String entryRef = "n/a";
		String revRef = "n/a";
		if (worldHandle != null && worldHandle.sourceRef() != null) {
			SourceRef ref = worldHandle.sourceRef();
			if (ref.isDirect())
				playlistRef = ref.playlistId();
			else
				playlistRef = ref.playlistId();
			entryRef = ref.entryKey();
			revRef = String.valueOf(ref.revision());
		}
		output.append("world.ref=playlist=").append(playlistRef)
				.append(" entry=").append(entryRef)
				.append(" rev=").append(revRef).append('\n');

		String previewState;
		if (!PreviewChannel.isActive())
			previewState = "STOPPED";
		else if (handler.getPreviewPlayer().isPlaying())
			previewState = "PLAYING";
		else
			// AUD-30 v1.2: download/buffering phase of an external preview
			// (the preview player is never manually paused)
			previewState = "PREPARING";
		output.append("preview.state=").append(previewState)
				.append(" pos=").append(formatSeconds(PreviewChannel.positionMillis()))
				.append(" track=").append(PreviewChannel.currentTrack() == null
						? "none" : PreviewChannel.currentTrack()).append('\n');

		int handles = (WorldPlaybackChannel.handle() == null ? 0 : 1) + (PreviewChannel.handle() == null ? 0 : 1);
		output.append("handles=").append(handles).append(" orphaned=0").append('\n');

		output.append("markers.fired=").append(MarkerClock.firedMarkers())
				.append(" markers.next=").append(nextMarker());
		return output.toString();
	}

	private static String gateOwner()
	{
		StreamMusicPlayer player = ExternalMusicHandler.getInstance().getPlayer();
		if (WorldPlaybackChannel.gateOwns(player.trackEnv()))
			return "track";
		if (WorldPlaybackChannel.gateOwns(player.seekEnv()))
			return "seek";
		return "none";
	}

	private static String nextMarker()
	{
		PlaybackHandle worldHandle = WorldPlaybackChannel.handle();
		if (worldHandle == null || worldHandle.sourceRef() == null)
			return "n/a";
		SourceRef ref = worldHandle.sourceRef();
		if (ref.isDirect())
			return "n/a";
		ResourceLocation playlistId = ResourceLocation.tryParse(ref.playlistId());
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		String url = handler.getCurrentlyPlayingUrl();
		if (playlistId == null || url == null)
			return "n/a";
		int index = MusicTracksManager.getInstance().getExternalPlaylistSelectedIndex(playlistId);
		long position = handler.getPositionMillis();
		for (TimelineMarker marker : TimelineMarkerStore.markers(playlistId, url, index)) {
			if (marker.timeMillis() > position)
				return marker.eventId() + "@" + String.format(Locale.ROOT, "%.1fs",
						marker.timeMillis() / 1000.0D);
		}
		return "n/a";
	}

	private static String formatSignedSeconds(double seconds)
	{
		return String.format(Locale.ROOT, "%+.2fs", seconds);
	}

	private static String formatSeconds(long millis)
	{
		return String.format(Locale.ROOT, "%.2fs", millis / 1000.0D);
	}
	
	private static LiteralArgumentBuilder<CommandSourceStack> serverSceneLiteral(String scene)
	{
		return serverSceneLiteral(scene, scene);
	}
	
	private static LiteralArgumentBuilder<CommandSourceStack> serverSceneLiteral(String literal, String scene)
	{
		LiteralArgumentBuilder<CommandSourceStack> delete = Commands.literal("delete")
				.then(Commands.argument("index", IntegerArgumentType.integer(1))
						.executes(context -> ServerExternalPlaylistStore.delete(context.getSource(), scene,
								IntegerArgumentType.getInteger(context, "index") - 1)));
		LiteralArgumentBuilder<CommandSourceStack> move = Commands.literal("move")
				.then(Commands.argument("from", IntegerArgumentType.integer(1))
						.then(Commands.argument("to", IntegerArgumentType.integer(1))
								.executes(context -> ServerExternalPlaylistStore.move(context.getSource(), scene,
										IntegerArgumentType.getInteger(context, "from") - 1,
										IntegerArgumentType.getInteger(context, "to") - 1))));
		if ("aggressive".equals(scene) || "ambient".equals(scene))
			delete.then(sceneDeleteTypeArgument(scene))
					.then(sceneDeleteUuidArgument(scene))
					.then(sceneDeletePlayerArgument(scene));
		LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(literal)
				.then(Commands.literal("add")
						.then(Commands.argument("url", StringArgumentType.greedyString())
								.executes(context -> ServerExternalPlaylistStore.add(context.getSource(), scene,
										StringArgumentType.getString(context, "url")))))
				.then(delete)
				.then(move)
				.then(Commands.literal("list")
						.executes(context -> ServerExternalPlaylistStore.list(context.getSource(), scene)))
				.then(Commands.literal("order")
						.then(selectionModeArgument()
								.executes(context -> ServerExternalPlaylistStore.setSelectionMode(context.getSource(), scene,
										StringArgumentType.getString(context, "selection_mode")))))
				.then(Commands.literal("priority")
						.then(Commands.argument("priority", IntegerArgumentType.integer(-1000, 1000))
								.executes(context -> ServerExternalPlaylistStore.setPriority(context.getSource(), scene,
										IntegerArgumentType.getInteger(context, "priority")))));
		if ("aggressive".equals(scene) || "ambient".equals(scene))
			builder.then(sceneTypeArgument(scene))
					.then(sceneUuidArgument(scene))
					.then(sceneEntitiesArgument(scene));
		if ("idle".equals(scene))
			builder.then(idleRuleArgument());
		return builder;
	}
	
	private static LiteralArgumentBuilder<CommandSourceStack> sceneTypeArgument(String scene)
	{
		return Commands.literal("type")
				.then(Commands.argument("entity_type", ResourceLocationArgument.id())
						.suggests(MobBattleMusicCommands::suggestEntityTypes)
						.then(Commands.literal("add")
								.then(Commands.argument("url", StringArgumentType.greedyString())
										.executes(context -> ServerExternalPlaylistStore.addEntityType(context.getSource(), scene,
												ResourceLocationArgument.getId(context, "entity_type"),
												StringArgumentType.getString(context, "url")))))
						.then(Commands.literal("delete")
								.then(Commands.argument("index", IntegerArgumentType.integer(1))
										.executes(context -> ServerExternalPlaylistStore.deleteEntityType(context.getSource(), scene,
												ResourceLocationArgument.getId(context, "entity_type"),
												IntegerArgumentType.getInteger(context, "index") - 1))))
						.then(Commands.literal("move")
								.then(Commands.argument("from", IntegerArgumentType.integer(1))
										.then(Commands.argument("to", IntegerArgumentType.integer(1))
												.executes(context -> ServerExternalPlaylistStore.moveEntityType(context.getSource(), scene,
														ResourceLocationArgument.getId(context, "entity_type"),
														IntegerArgumentType.getInteger(context, "from") - 1,
														IntegerArgumentType.getInteger(context, "to") - 1)))))
						.then(Commands.literal("list")
								.executes(context -> ServerExternalPlaylistStore.listEntityType(context.getSource(), scene,
										ResourceLocationArgument.getId(context, "entity_type"))))
						.then(Commands.literal("order")
								.then(selectionModeArgument()
										.executes(context -> ServerExternalPlaylistStore.setEntityTypeSelectionMode(
												context.getSource(), scene,
												ResourceLocationArgument.getId(context, "entity_type"),
												StringArgumentType.getString(context, "selection_mode")))))
						.then(Commands.literal("priority")
								.then(Commands.argument("priority", IntegerArgumentType.integer(-1000, 1000))
										.executes(context -> ServerExternalPlaylistStore.setEntityTypePriority(
												context.getSource(), scene,
												ResourceLocationArgument.getId(context, "entity_type"),
												IntegerArgumentType.getInteger(context, "priority"))))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> sceneUuidArgument(String scene)
	{
		return Commands.literal("uuid")
				.then(Commands.argument("uuid", StringArgumentType.word())
						.suggests((context, builder) -> suggestRecordedEntityUuids(context, builder, scene))
						.then(Commands.literal("add")
								.then(Commands.argument("url", StringArgumentType.greedyString())
										.executes(context -> ServerExternalPlaylistStore.addEntityUuid(context.getSource(), scene,
												StringArgumentType.getString(context, "uuid"),
												StringArgumentType.getString(context, "url")))))
						.then(Commands.literal("delete")
								.then(Commands.argument("index", IntegerArgumentType.integer(1))
										.executes(context -> ServerExternalPlaylistStore.deleteEntityUuid(context.getSource(), scene,
												StringArgumentType.getString(context, "uuid"),
												IntegerArgumentType.getInteger(context, "index") - 1))))
						.then(Commands.literal("move")
								.then(Commands.argument("from", IntegerArgumentType.integer(1))
										.then(Commands.argument("to", IntegerArgumentType.integer(1))
												.executes(context -> ServerExternalPlaylistStore.moveEntityUuid(context.getSource(), scene,
														StringArgumentType.getString(context, "uuid"),
														IntegerArgumentType.getInteger(context, "from") - 1,
														IntegerArgumentType.getInteger(context, "to") - 1)))))
						.then(Commands.literal("list")
								.executes(context -> ServerExternalPlaylistStore.listEntityUuid(context.getSource(), scene,
										StringArgumentType.getString(context, "uuid"))))
						.then(Commands.literal("order")
								.then(selectionModeArgument()
										.executes(context -> ServerExternalPlaylistStore.setEntityUuidSelectionMode(
												context.getSource(), scene,
												StringArgumentType.getString(context, "uuid"),
												StringArgumentType.getString(context, "selection_mode")))))
						.then(Commands.literal("priority")
								.then(Commands.argument("priority", IntegerArgumentType.integer(-1000, 1000))
										.executes(context -> ServerExternalPlaylistStore.setEntityUuidPriority(
												context.getSource(), scene,
												StringArgumentType.getString(context, "uuid"),
												IntegerArgumentType.getInteger(context, "priority"))))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> idleRuleArgument()
	{
		return Commands.literal("rule")
				.then(Commands.argument("rule_id", ResourceLocationArgument.id())
						.suggests(MobBattleMusicCommands::suggestIdleRules)
						.then(Commands.literal("add")
								.then(Commands.argument("url", StringArgumentType.greedyString())
										.executes(context -> ServerExternalPlaylistStore.addIdleRule(context.getSource(),
												ResourceLocationArgument.getId(context, "rule_id").toString(),
												StringArgumentType.getString(context, "url")))))
						.then(Commands.literal("delete")
								.then(Commands.argument("index", IntegerArgumentType.integer(1))
										.executes(context -> ServerExternalPlaylistStore.deleteIdleRule(context.getSource(),
													ResourceLocationArgument.getId(context, "rule_id").toString(),
													IntegerArgumentType.getInteger(context, "index") - 1))))
						.then(Commands.literal("move")
								.then(Commands.argument("from", IntegerArgumentType.integer(1))
										.then(Commands.argument("to", IntegerArgumentType.integer(1))
												.executes(context -> ServerExternalPlaylistStore.moveIdleRule(context.getSource(),
														ResourceLocationArgument.getId(context, "rule_id").toString(),
														IntegerArgumentType.getInteger(context, "from") - 1,
														IntegerArgumentType.getInteger(context, "to") - 1)))))
						.then(Commands.literal("list")
								.executes(context -> ServerExternalPlaylistStore.listIdleRule(context.getSource(),
										ResourceLocationArgument.getId(context, "rule_id").toString())))
						.then(Commands.literal("order")
								.then(selectionModeArgument()
										.executes(context -> ServerExternalPlaylistStore.setIdleRuleSelectionMode(
												context.getSource(), ResourceLocationArgument.getId(context, "rule_id").toString(),
												StringArgumentType.getString(context, "selection_mode")))))
						.then(Commands.literal("priority")
								.then(Commands.argument("priority", IntegerArgumentType.integer(-1000, 1000))
										.executes(context -> ServerExternalPlaylistStore.setIdleRulePriority(context.getSource(),
												ResourceLocationArgument.getId(context, "rule_id").toString(),
												IntegerArgumentType.getInteger(context, "priority")))))
						.then(Commands.literal("interval")
								.then(Commands.argument("seconds", IntegerArgumentType.integer(0, 86400))
										.executes(context -> ServerExternalPlaylistStore.setIdleRuleInterval(context.getSource(),
												ResourceLocationArgument.getId(context, "rule_id").toString(),
												IntegerArgumentType.getInteger(context, "seconds")))))
						.then(idleConditionArgument()));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> idleConditionArgument()
	{
		return Commands.literal("condition")
				.then(Commands.literal("add")
						.then(Commands.argument("condition_type", ResourceLocationArgument.id())
								.suggests(MobBattleMusicCommands::suggestConditionTypes)
								.executes(context -> ServerExternalPlaylistStore.addIdleRuleCondition(context.getSource(),
										ResourceLocationArgument.getId(context, "rule_id").toString(),
										ResourceLocationArgument.getId(context, "condition_type").toString(), "", false))
								.then(Commands.argument("condition_argument", StringArgumentType.greedyString())
										.executes(context -> ServerExternalPlaylistStore.addIdleRuleCondition(context.getSource(),
												ResourceLocationArgument.getId(context, "rule_id").toString(),
												ResourceLocationArgument.getId(context, "condition_type").toString(),
												StringArgumentType.getString(context, "condition_argument"), false)))))
				.then(Commands.literal("add_not")
						.then(Commands.argument("condition_type", ResourceLocationArgument.id())
								.suggests(MobBattleMusicCommands::suggestConditionTypes)
								.executes(context -> ServerExternalPlaylistStore.addIdleRuleCondition(context.getSource(),
										ResourceLocationArgument.getId(context, "rule_id").toString(),
										ResourceLocationArgument.getId(context, "condition_type").toString(), "", true))
								.then(Commands.argument("condition_argument", StringArgumentType.greedyString())
										.executes(context -> ServerExternalPlaylistStore.addIdleRuleCondition(context.getSource(),
												ResourceLocationArgument.getId(context, "rule_id").toString(),
												ResourceLocationArgument.getId(context, "condition_type").toString(),
												StringArgumentType.getString(context, "condition_argument"), true)))))
				.then(Commands.literal("delete")
						.then(Commands.argument("index", IntegerArgumentType.integer(1))
								.executes(context -> ServerExternalPlaylistStore.deleteIdleRuleCondition(context.getSource(),
										ResourceLocationArgument.getId(context, "rule_id").toString(),
										IntegerArgumentType.getInteger(context, "index") - 1))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> sceneDeleteTypeArgument(String scene)
	{
		return Commands.literal("type")
				.then(Commands.argument("entity_type", ResourceLocationArgument.id())
						.suggests((context, builder) -> suggestRecordedEntityTypes(context, builder, scene))
						.then(Commands.argument("index", IntegerArgumentType.integer(1))
								.executes(context -> ServerExternalPlaylistStore.deleteEntityType(context.getSource(), scene,
										ResourceLocationArgument.getId(context, "entity_type"),
										IntegerArgumentType.getInteger(context, "index") - 1))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> sceneDeleteUuidArgument(String scene)
	{
		return Commands.literal("uuid")
				.then(Commands.argument("uuid", StringArgumentType.word())
						.suggests((context, builder) -> suggestRecordedEntityUuids(context, builder, scene))
						.then(Commands.argument("index", IntegerArgumentType.integer(1))
								.executes(context -> ServerExternalPlaylistStore.deleteEntityUuid(context.getSource(), scene,
										StringArgumentType.getString(context, "uuid"),
										IntegerArgumentType.getInteger(context, "index") - 1))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> sceneDeletePlayerArgument(String scene)
	{
		return Commands.literal("player")
				.then(Commands.argument("player", EntityArgument.player())
						.then(Commands.argument("index", IntegerArgumentType.integer(1))
								.executes(context -> ServerExternalPlaylistStore.deleteEntityUuid(context.getSource(), scene,
										EntityArgument.getPlayer(context, "player").getUUID().toString(),
										IntegerArgumentType.getInteger(context, "index") - 1))));
	}
	
	private static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> sceneEntitiesArgument(String scene)
	{
		return Commands.argument("entities", EntityArgument.entities())
				.then(Commands.literal("add")
						.then(Commands.argument("url", StringArgumentType.greedyString())
								.executes(context -> addEntityUuids(context.getSource(), scene,
										EntityArgument.getEntities(context, "entities"),
										StringArgumentType.getString(context, "url")))))
				.then(Commands.literal("delete")
						.then(Commands.argument("index", IntegerArgumentType.integer(1))
								.executes(context -> deleteEntityUuids(context.getSource(), scene,
										EntityArgument.getEntities(context, "entities"),
										IntegerArgumentType.getInteger(context, "index") - 1))))
				.then(Commands.literal("list")
						.executes(context -> listEntityUuids(context.getSource(), scene,
								EntityArgument.getEntities(context, "entities"))))
				.then(Commands.literal("type")
						.then(Commands.literal("add")
								.then(Commands.argument("url", StringArgumentType.greedyString())
										.executes(context -> addEntityTypes(context.getSource(), scene,
												EntityArgument.getEntities(context, "entities"),
												StringArgumentType.getString(context, "url")))))
						.then(Commands.literal("delete")
								.then(Commands.argument("index", IntegerArgumentType.integer(1))
										.executes(context -> deleteEntityTypes(context.getSource(), scene,
												EntityArgument.getEntities(context, "entities"),
												IntegerArgumentType.getInteger(context, "index") - 1))))
						.then(Commands.literal("list")
								.executes(context -> listEntityTypes(context.getSource(), scene,
										EntityArgument.getEntities(context, "entities")))));
	}
	
	private static RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> playlistArgument()
	{
		return Commands.argument("playlist", ResourceLocationArgument.id())
				.suggests(ExternalPlaylistCatalogServer::suggestPlaylists);
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> selectionModeArgument()
	{
		return Commands.argument("selection_mode", StringArgumentType.word())
				.suggests((context, builder) -> SharedSuggestionProvider.suggest(
						ServerExternalPlaylistStore.supportedSelectionModes(), builder));
	}
	
	private static CompletableFuture<Suggestions> suggestScenes(CommandContext<CommandSourceStack> context,
			SuggestionsBuilder builder)
	{
		return SharedSuggestionProvider.suggest(ServerExternalPlaylistStore.supportedScenes(), builder);
	}
	
	private static CompletableFuture<Suggestions> suggestEntityTypes(CommandContext<CommandSourceStack> context,
			SuggestionsBuilder builder)
	{
		return SharedSuggestionProvider.suggestResource(ForgeRegistries.ENTITY_TYPES.getKeys(), builder);
	}

	private static CompletableFuture<Suggestions> suggestIdleRules(CommandContext<CommandSourceStack> context,
			SuggestionsBuilder builder)
	{
		return SharedSuggestionProvider.suggestResource(ServerExternalPlaylistStore.recordedIdleRules(
				context.getSource().getServer()).stream().map(ResourceLocation::new), builder);
	}

	private static CompletableFuture<Suggestions> suggestConditionTypes(CommandContext<CommandSourceStack> context,
			SuggestionsBuilder builder)
	{
		return SharedSuggestionProvider.suggestResource(IdleConditionRegistry.descriptors().stream()
				.map(IdleConditionRegistry.Descriptor::id), builder);
	}

	private static CompletableFuture<Suggestions> suggestRecordedEntityTypes(CommandContext<CommandSourceStack> context,
			SuggestionsBuilder builder, String scene)
	{
		return SharedSuggestionProvider.suggestResource(
				ServerExternalPlaylistStore.recordedEntityTypes(context.getSource().getServer(), scene), builder);
	}

	private static CompletableFuture<Suggestions> suggestRecordedEntityUuids(CommandContext<CommandSourceStack> context,
			SuggestionsBuilder builder, String scene)
	{
		return SharedSuggestionProvider.suggest(
				ServerExternalPlaylistStore.recordedEntityUuids(context.getSource().getServer(), scene), builder);
	}
	
	private static int send(CommandSourceStack source, Collection<ServerPlayer> players, ResourceLocation playlistId,
			ExternalPlaylistControlPacket.Action action, String selection)
	{
		ExternalPlaylistControlPacket packet = new ExternalPlaylistControlPacket(playlistId, action, selection);
		for (ServerPlayer player : players)
			MobBattleMusicNetwork.sendExternalPlaylistControl(player, packet);
		MobBattleMusicCommandFeedback.success(source, Component.literal("Sent " + action.name().toLowerCase() +
				" playlist control for " + playlistId + " to " + players.size() + " player(s)"));
		return players.size();
	}
	
	private static int sendRandomNext(CommandSourceStack source, Collection<ServerPlayer> players, ResourceLocation playlistId)
	{
		String selection = ExternalPlaylistCatalogServer.chooseRandomEntry(source.getServer(), players, playlistId);
		if (selection != null) {
			int sent = send(source, players, playlistId, ExternalPlaylistControlPacket.Action.SET, selection);
			MobBattleMusicCommandFeedback.success(source, Component.literal("Server selected random playlist entry '" +
					selection + "' for " + playlistId));
			return sent;
		}
		source.sendFailure(Component.literal("No synced playlist catalog found for " + playlistId +
				"; falling back to client-side random selection"));
		return send(source, players, playlistId, ExternalPlaylistControlPacket.Action.RANDOM_NEXT, "");
	}
	
	private static int addEntityUuids(CommandSourceStack source, String scene, Collection<? extends Entity> entities, String url)
	{
		int changed = 0;
		for (Entity entity : entities)
			changed += ServerExternalPlaylistStore.addEntityUuid(source, scene, entity.getUUID().toString(), url);
		return changed;
	}
	
	private static int deleteEntityUuids(CommandSourceStack source, String scene, Collection<? extends Entity> entities, int index)
	{
		int changed = 0;
		for (Entity entity : entities)
			changed += ServerExternalPlaylistStore.deleteEntityUuid(source, scene, entity.getUUID().toString(), index);
		return changed;
	}
	
	private static int listEntityUuids(CommandSourceStack source, String scene, Collection<? extends Entity> entities)
	{
		int count = 0;
		for (Entity entity : entities)
			count += ServerExternalPlaylistStore.listEntityUuid(source, scene, entity.getUUID().toString());
		return count;
	}
	
	private static int addEntityTypes(CommandSourceStack source, String scene, Collection<? extends Entity> entities, String url)
	{
		int changed = 0;
		for (ResourceLocation type : entityTypes(entities))
			changed += ServerExternalPlaylistStore.addEntityType(source, scene, type, url);
		return changed;
	}
	
	private static int deleteEntityTypes(CommandSourceStack source, String scene, Collection<? extends Entity> entities, int index)
	{
		int changed = 0;
		for (ResourceLocation type : entityTypes(entities))
			changed += ServerExternalPlaylistStore.deleteEntityType(source, scene, type, index);
		return changed;
	}
	
	private static int listEntityTypes(CommandSourceStack source, String scene, Collection<? extends Entity> entities)
	{
		int count = 0;
		for (ResourceLocation type : entityTypes(entities))
			count += ServerExternalPlaylistStore.listEntityType(source, scene, type);
		return count;
	}
	
	private static Set<ResourceLocation> entityTypes(Collection<? extends Entity> entities)
	{
		Set<ResourceLocation> types = new LinkedHashSet<>();
		for (Entity entity : entities) {
			ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
			if (id != null)
				types.add(id);
		}
		return types;
	}
}
