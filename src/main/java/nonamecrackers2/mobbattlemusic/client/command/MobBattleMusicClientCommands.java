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
		// AUD-54 追加: the numeric frame comes from the ring's last sample, so
		// debug session and the ring carry exactly the same field set
		ProbeRing.ProbeSample last = ProbeRing.lastSample();
		String prefix = "t=" + (last == null ? System.currentTimeMillis() : last.epochMillis())
				+ " tick=" + (last == null ? WorldPlaybackChannel.tickNumber() : last.tick()) + " | ";
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		StreamMusicPlayer main = handler.getPlayer();

		StringBuilder output = new StringBuilder();
		if (last != null)
			output.append(ProbeRing.formatLastSample());
		// Human-readable names for the numeric fields
		output.append(prefix).append("session.name=").append(MbmSessionState.current())
				.append(" state.name=").append(WorldPlaybackChannel.state().name())
				.append(" clock.name=").append(MarkerClock.state().name())
				.append(" track=").append(handler.getCurrentlyPlayingUrl() == null
						? "none" : handler.getCurrentlyPlayingUrl()).append('\n');

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

		java.util.List<String> activeFilters = AudioFilterManager.activeMbmFilters().stream()
				.map(definition -> definition.id().getPath())
				.toList();
		output.append(prefix).append("world.filter active=[").append(String.join(",", activeFilters))
				.append("]").append('\n');

		output.append(prefix).append("markers.next=").append(nextMarker());
		return output.toString();
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
}