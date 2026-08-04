package nonamecrackers2.mobbattlemusic.client.music;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import nonamecrackers2.mobbattlemusic.client.audio.WorldPlaybackChannel;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.gui.MusicPlaylistScreen;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.network.ExternalPlaylistControlPacket;
import nonamecrackers2.mobbattlemusic.network.ServerExternalPlaylistSyncPacket;

public class ExternalPlaylistControlClient
{
	public static void handle(ExternalPlaylistControlPacket packet)
	{
		if (MobBattleMusicConfig.CLIENT.ignoreServerPlaylistRequests.get()) {
			message("Ignored server playlist request: " + packet.action().name().toLowerCase());
			return;
		}
		MusicTracksManager.PlaylistControlResult result = switch (packet.action())
		{
			case SET -> MusicTracksManager.getInstance().setExternalPlaylistSelection(packet.playlistId(), packet.selection());
			case RANDOM_NEXT -> MusicTracksManager.getInstance().randomizeExternalPlaylistSelection(packet.playlistId());
			case CLEAR -> MusicTracksManager.getInstance().clearExternalPlaylistSelection(packet.playlistId());
			case PLAY_SELECTION -> playSelection(packet.playlistId(), packet.selection());
			case PLAY_URL -> playUrl(packet.selection());
			case STOP -> stop();
		};
		// K9-4: server-driven playback is marked CUE so the player dock shows
		// the true selection owner (and refuses seeks on CUE tracks)
		if (packet.action() == ExternalPlaylistControlPacket.Action.PLAY_SELECTION
				|| packet.action() == ExternalPlaylistControlPacket.Action.PLAY_URL)
			WorldPlaybackChannel.setPlaybackOwner(WorldPlaybackChannel.PlaybackOwner.CUE);
		// K12-A: STOP must release the CUE owner, otherwise the manager keeps
		// its auto-hold (owner != AUTO) and AUTO can never resume -> the GUI
		// cannot demote CUE, so a server STOP would deadlock into permanent
		// silence. Server protocol: PLAY acquires CUE, STOP returns AUTO.
		if (packet.action() == ExternalPlaylistControlPacket.Action.STOP
				&& WorldPlaybackChannel.playbackOwner() == WorldPlaybackChannel.PlaybackOwner.CUE)
			WorldPlaybackChannel.setPlaybackOwner(WorldPlaybackChannel.PlaybackOwner.AUTO);
		message(result.message());
	}
	
	public static void handleServerSync(ServerExternalPlaylistSyncPacket packet)
	{
		if (MobBattleMusicConfig.CLIENT.ignoreServerPlaylistRequests.get()) {
			MusicTracksManager.getInstance().applyServerExternalPlaylists(java.util.List.of());
			TimelineMarkerStore.clearServer();
			MusicPlaylistScreen.refreshOpenScreen();
			message("Ignored server playlist sync");
			return;
		}
		MusicTracksManager.getInstance().applyServerExternalPlaylists(packet.tracks());
		TimelineMarkerStore.applyServerSync(packet);
		MusicPlaylistScreen.refreshOpenScreen();
		message("Synced " + packet.tracks().size() + " server playlist binding(s)");
	}

	// K14-B/K14-C: the server-authoritative Cue timeline drives this client's
	// audio. START/SNAPSHOT carry the logical position and state; control
	// packets (PAUSE/RESUME/STOP/POSITION) are applied ONLY when their
	// sessionId matches the active session - a late packet from a replaced
	// session is dropped (CUE_STALE_DROP). Marker firing is server-side; the
	// client only mirrors the timeline.
	private static volatile java.util.UUID activeCueSessionId;
	private static volatile long activeCueRevision;
	private static volatile long lastCuePositionMillis;
	private static volatile long lastCuePositionAtMillis;

	public static void handleCueSessionSync(nonamecrackers2.mobbattlemusic.network.CueSessionSyncPacket packet)
	{
		boolean optOut = MobBattleMusicConfig.CLIENT.ignoreServerPlaylistRequests.get()
				|| !MobBattleMusicConfig.CLIENT.acceptServerCues.get();
		switch (packet.action()) {
			case START -> {
				// K14-C: a START (fresh or mid-session snapshot) establishes
				// or replaces the active session; a stale control packet for
				// the OLD session can no longer affect the new one
				activeCueSessionId = packet.sessionId();
				activeCueRevision = packet.revision();
				lastCuePositionMillis = packet.logicalPositionMillis();
				lastCuePositionAtMillis = System.currentTimeMillis();
				if (optOut) {
					// K14-C: opt-out means we do not play CUE audio - but the
					// session is still tracked so its STOP can clean up
					LOGGER().info("[MBM] CUE_START ignored (opt-out) session={}", packet.sessionId());
					return;
				}
				if (packet.state() == nonamecrackers2.mobbattlemusic.network.CueSessionSyncPacket.State.PAUSED) {
					// K14-C: restore a PAUSED session as paused - prepare/seek
					// to the position but do not start
					ExternalMusicHandler.getInstance().playMusicFrom(packet.url(), 0, packet.logicalPositionMillis());
					ExternalMusicHandler.getInstance().pauseMusic();
				} else if (packet.logicalPositionMillis() > 0L) {
					// mid-session join / continuing session - seek to the
					// logical position instead of replaying from zero
					ExternalMusicHandler.getInstance().playMusicFrom(packet.url(), 0, packet.logicalPositionMillis());
				} else {
					ExternalMusicHandler.getInstance().playMusic(packet.url(), 0);
				}
				WorldPlaybackChannel.setPlaybackOwner(WorldPlaybackChannel.PlaybackOwner.CUE);
			}
			case POSITION -> {
				// K14-C: periodic server-tick position - apply only to the
				// active session, bounded drift correction
				if (!isActive(packet)) {
					logStale(packet);
					return;
				}
				long serverPos = packet.logicalPositionMillis();
				long clientPos = ExternalMusicHandler.getInstance().getPositionMillis();
				if (clientPos < 0L)
					return;
				long drift = serverPos - clientPos;
				if (Math.abs(drift) <= 250L)
					return; // small drift: keep
				if (Math.abs(drift) <= 1500L) {
					// medium drift: smooth by a single bounded seek
					ExternalMusicHandler.getInstance().seekMusic(serverPos);
				} else {
					// large drift: re-seek to the authoritative position
					ExternalMusicHandler.getInstance().seekMusic(serverPos);
				}
				lastCuePositionMillis = serverPos;
				lastCuePositionAtMillis = System.currentTimeMillis();
			}
			case PAUSE -> {
				if (!isActive(packet)) { logStale(packet); return; }
				ExternalMusicHandler.getInstance().pauseMusic();
			}
			case RESUME -> {
				if (!isActive(packet)) { logStale(packet); return; }
				ExternalMusicHandler.getInstance().resumeMusic();
			}
			case STOP -> {
				// K14-C: STOP always cleans up the matching active session's
				// audio (opt-out does not skip cleanup - only new STARTS are
				// opt-out-gated); a stale STOP for a replaced session is dropped
				if (!isActive(packet)) { logStale(packet); return; }
				activeCueSessionId = null;
				activeCueRevision = 0L;
				if (WorldPlaybackChannel.playbackOwner() == WorldPlaybackChannel.PlaybackOwner.CUE)
					WorldPlaybackChannel.setPlaybackOwner(WorldPlaybackChannel.PlaybackOwner.AUTO);
				ExternalMusicHandler.getInstance().stopMusic();
			}
		}
	}

	// K14-C: control packets apply only to the active session
	private static boolean isActive(nonamecrackers2.mobbattlemusic.network.CueSessionSyncPacket packet)
	{
		return packet.sessionId() != null && packet.sessionId().equals(activeCueSessionId);
	}

	private static void logStale(nonamecrackers2.mobbattlemusic.network.CueSessionSyncPacket packet)
	{
		LOGGER().debug("[MBM] CUE_STALE_DROP action={} session={} active={}", packet.action(),
				packet.sessionId(), activeCueSessionId);
	}

	private static org.apache.logging.log4j.Logger LOGGER()
	{
		return org.apache.logging.log4j.LogManager.getLogger("mobbattlemusic/ExternalPlaylistControlClient");
	}
	
	private static MusicTracksManager.PlaylistControlResult playSelection(net.minecraft.resources.ResourceLocation playlistId,
			String selection)
	{
		String url = MusicTracksManager.getInstance().getExternalPlaylistUrl(playlistId, selection);
		if (url == null)
			return MusicTracksManager.PlaylistControlResult.failure("Unknown playlist entry '" + selection + "' for " + playlistId);
		ExternalMusicHandler.getInstance().playMusic(url, 20);
		return MusicTracksManager.PlaylistControlResult.success("Playing " + playlistId + " / " + selection);
	}
	
	private static MusicTracksManager.PlaylistControlResult playUrl(String url)
	{
		ExternalMusicHandler.getInstance().playMusic(url, 20);
		return MusicTracksManager.PlaylistControlResult.success("Playing URL");
	}
	
	private static MusicTracksManager.PlaylistControlResult stop()
	{
		ExternalMusicHandler.getInstance().stopMusic();
		return MusicTracksManager.PlaylistControlResult.success("Stopped external playback");
	}
	
	private static void message(String message)
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null)
			mc.player.displayClientMessage(Component.literal("[Mob Battle Music] " + message), true);
	}
}
