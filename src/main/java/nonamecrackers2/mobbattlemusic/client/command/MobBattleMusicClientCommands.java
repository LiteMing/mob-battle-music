package nonamecrackers2.mobbattlemusic.client.command;

import java.util.Locale;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import nonamecrackers2.mobbattlemusic.client.audio.AudioFilterManager;
import nonamecrackers2.mobbattlemusic.client.audio.MarkerClock;
import nonamecrackers2.mobbattlemusic.client.audio.MbmSessionState;
import nonamecrackers2.mobbattlemusic.client.audio.PcmFilterChain;
import nonamecrackers2.mobbattlemusic.client.audio.PlaybackHandle;
import nonamecrackers2.mobbattlemusic.client.audio.PreviewChannel;
import nonamecrackers2.mobbattlemusic.client.audio.ProbeRing;
import nonamecrackers2.mobbattlemusic.client.audio.SourceRef;
import nonamecrackers2.mobbattlemusic.client.audio.WorldPlaybackChannel;
import nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler;
import nonamecrackers2.mobbattlemusic.client.music.StreamMusicPlayer;
import nonamecrackers2.mobbattlemusic.client.music.TimelineMarkerStore;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;

/**
 * AUD-54: client-side probe commands, registered on the client command tree
 * (RegisterClientCommandsEvent). Root literal "mbm" - deliberately different
 * from the server root "mobbattlemusic" to avoid shadowing. No permission
 * predicate: available in every session state, including a paused singleplayer
 * world.
 */
public final class MobBattleMusicClientCommands
{
	private MobBattleMusicClientCommands() {}

	public static void register(RegisterClientCommandsEvent event)
	{
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mbm");
		root.then(Commands.literal("debug")
				.then(Commands.literal("session")
						.executes(context -> debugSession(context.getSource())))
				.then(Commands.literal("inject-drift")
						.then(Commands.argument("seconds", DoubleArgumentType.doubleArg())
								.executes(context -> injectDrift(context.getSource(),
										DoubleArgumentType.getDouble(context, "seconds")))))
				.then(Commands.literal("dump")
						.executes(context -> dump(context.getSource()))));
		event.getDispatcher().register(root);
	}

	private static int debugSession(CommandSourceStack source)
	{
		for (String line : buildDebugSessionOutput().split("\n", -1))
			source.sendSuccess(() -> Component.literal(line), false);
		return 1;
	}

	private static int injectDrift(CommandSourceStack source, double seconds)
	{
		MarkerClock.injectDrift(seconds);
		source.sendSuccess(() -> Component.literal("Injected " + seconds + "s of clock drift (S15 test hook)"), false);
		return 1;
	}

	private static int dump(CommandSourceStack source)
	{
		String path = ProbeRing.dumpToFile();
		source.sendSuccess(() -> Component.literal("Probe ring dumped to " + path), false);
		return 1;
	}

	private static String buildDebugSessionOutput()
	{
		// AUD-54: every probe line carries t=<epochMillis> tick=<n>
		String prefix = "t=" + System.currentTimeMillis() + " tick=" + WorldPlaybackChannel.tickNumber() + " | ";
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		StreamMusicPlayer main = handler.getPlayer();

		StringBuilder output = new StringBuilder();
		output.append(prefix).append("session=").append(MbmSessionState.current())
				.append(" focused=").append(MbmSessionState.isFocused())
				.append(" paused=").append(MbmSessionState.isPausedNow())
				.append(" published=").append(MbmSessionState.isPublishedNow()).append('\n');

		WorldPlaybackChannel.ChannelState worldChannelState = WorldPlaybackChannel.state();
		// AUD-44: gain envelope currents; final = track x mute x seek
		float trackGain = main.trackEnv().current();
		float muteGain = StreamMusicPlayer.MUTE_ENV.current();
		float seekGain = main.seekEnv().current();
		float finalGain = trackGain * muteGain * seekGain;
		output.append(prefix).append("world.state=").append(worldChannelState.name())
				.append(" gain=").append(String.format(Locale.ROOT, "%.2f", finalGain))
				.append(" track=").append(handler.getCurrentlyPlayingUrl() == null
						? "none" : handler.getCurrentlyPlayingUrl())
				.append(" pos=").append(formatSeconds(handler.getPositionMillis())).append('\n');
		output.append(prefix).append("world.gain final=").append(String.format(Locale.ROOT, "%.2f", finalGain))
				.append(" track=").append(String.format(Locale.ROOT, "%.2f", trackGain))
				.append(" mute=").append(String.format(Locale.ROOT, "%.2f", muteGain))
				.append(" seek=").append(String.format(Locale.ROOT, "%.2f", seekGain))
				// AUD-30 v1.7: who is writing the envelopes
				.append(" trackTarget=").append(String.format(Locale.ROOT, "%.2f", main.trackEnv().target()))
				.append(" gateOwner=").append(gateOwner()).append('\n');

		// AUD-30 v1.3/AUD-47: audible vs decoded position and their latency gap
		long audiblePos = handler.getPositionMillis();
		long decodedPos = handler.getDecodedPositionMillis();
		output.append(prefix).append("world.pos audible=").append(formatSeconds(audiblePos))
				.append(" decoded=").append(formatSeconds(decodedPos))
				.append(" outLatency=").append(Math.max(0L, decodedPos - audiblePos)).append("ms").append('\n');

		// AUD-30 v1.3/v1.5: output line capacity, fill, watermark, underruns
		int lineBuffer = main.getLineBufferBytes();
		int lineAvailable = main.getLineAvailableBytes();
		long lineWatermark = main.getLineWatermarkBytes();
		output.append(prefix).append("world.line buffer=").append(lineBuffer)
				.append("B fill=").append(Math.max(0, lineBuffer - lineAvailable)).append("B")
				.append(" watermark=").append(lineWatermark).append("B")
				.append(" underruns=").append(main.getUnderruns()).append('\n');

		// AUD-30 v1.3/AUD-48: active filters and dry/wet mix envelope
		java.util.List<String> activeFilters = AudioFilterManager.activeMbmFilters().stream()
				.map(definition -> definition.id().getPath())
				.toList();
		output.append(prefix).append("world.filter active=[").append(String.join(",", activeFilters))
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
		output.append(prefix).append("world.clock=").append(clockState)
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
				.append(" injectedTtl=").append(MarkerClock.injectedTtlMillis())
				// AUD-52 修订: measured seek cost (queue -> watermark fill)
				.append(" seekCost=").append(WorldPlaybackChannel.seekCostMillis()).append('\n');

		PlaybackHandle worldHandle = WorldPlaybackChannel.handle();
		String playlistRef = "n/a";
		String entryRef = "n/a";
		String revRef = "n/a";
		if (worldHandle != null && worldHandle.sourceRef() != null) {
			SourceRef ref = worldHandle.sourceRef();
			playlistRef = ref.playlistId();
			entryRef = ref.entryKey();
			revRef = String.valueOf(ref.revision());
		}
		output.append(prefix).append("world.ref=playlist=").append(playlistRef)
				.append(" entry=").append(entryRef)
				.append(" rev=").append(revRef).append('\n');

		String previewState;
		if (!PreviewChannel.isActive())
			previewState = "STOPPED";
		else if (handler.getPreviewPlayer().isPlaying())
			previewState = "PLAYING";
		else
			// AUD-30 v1.2: download/buffering phase of an external preview
			previewState = "PREPARING";
		output.append(prefix).append("preview.state=").append(previewState)
				.append(" pos=").append(formatSeconds(PreviewChannel.positionMillis()))
				.append(" track=").append(PreviewChannel.currentTrack() == null
						? "none" : PreviewChannel.currentTrack()).append('\n');

		int handles = (WorldPlaybackChannel.handle() == null ? 0 : 1) + (PreviewChannel.handle() == null ? 0 : 1);
		output.append(prefix).append("handles=").append(handles).append(" orphaned=0").append('\n');

		output.append(prefix).append("markers.fired=").append(MarkerClock.firedMarkers())
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

	private static String formatSeconds(long millis)
	{
		return String.format(Locale.ROOT, "%.2fs", millis / 1000.0D);
	}

	private static String formatSignedSeconds(double seconds)
	{
		return String.format(Locale.ROOT, "%+.2fs", seconds);
	}
}
