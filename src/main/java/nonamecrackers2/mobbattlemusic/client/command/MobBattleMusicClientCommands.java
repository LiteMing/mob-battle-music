package nonamecrackers2.mobbattlemusic.client.command;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import nonamecrackers2.mobbattlemusic.network.MBMDebugPacket;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;

/**
 * K16-K: the probe actions that used to live under the client-only /mbm debug
 * subtree. They are now invoked on the client via MBMDebugPacket when the
 * server-side /mbmplaylist debug command runs - all probe state is client-local
 * (MarkerClock, ProbeRing, playback channel, session). runProbe runs on the
 * client main thread and prints its output to the local chat.
 */
public final class MobBattleMusicClientCommands
{
	private MobBattleMusicClientCommands() {}

	public static void runProbe(int action, double value)
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null)
			return;
		switch (action) {
			case MBMDebugPacket.ACTION_SESSION -> {
				for (String line : buildDebugSessionOutput().split("\n", -1))
					mc.player.sendSystemMessage(Component.literal(line));
			}
			case MBMDebugPacket.ACTION_INJECT_DRIFT -> {
				MarkerClock.injectDrift(value);
				mc.player.sendSystemMessage(Component.literal("Injected " + value + "s of clock drift (S15 test hook)"));
			}
			case MBMDebugPacket.ACTION_DUMP -> {
				String path = ProbeRing.dumpToFile();
				mc.player.sendSystemMessage(Component.literal("Probe ring dumped to " + path));
			}
			default -> {}
		}
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
		// K3 P0-a: unknown position (-1) must not be treated as zero
		if (position < 0L)
			return "n/a";
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