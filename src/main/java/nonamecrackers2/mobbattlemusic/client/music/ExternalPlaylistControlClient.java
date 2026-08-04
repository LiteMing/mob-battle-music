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

	// K14-B: the server-authoritative Cue timeline drives this client's audio.
	// START/SNAPSHOT carry the logical position for mid-session joins (seek
	// instead of starting from zero). Marker firing is server-side; the client
	// only mirrors the timeline. A CUE session may continue while this client
	// has opted out (acceptServerCues=false) - the server timeline is
	// unaffected, we just do not play.
	public static void handleCueSessionSync(nonamecrackers2.mobbattlemusic.network.CueSessionSyncPacket packet)
	{
		boolean optOut = MobBattleMusicConfig.CLIENT.ignoreServerPlaylistRequests.get()
				|| !MobBattleMusicConfig.CLIENT.acceptServerCues.get();
		switch (packet.action()) {
			case START, SNAPSHOT -> {
				if (optOut)
					return;
				long startPosition = packet.logicalPositionMillis();
				if (packet.action() == nonamecrackers2.mobbattlemusic.network.CueSessionSyncPacket.Action.SNAPSHOT
						|| startPosition > 0L) {
					// mid-session join / continuing session - seek to the
					// logical position instead of replaying from zero
					ExternalMusicHandler.getInstance().playMusicFrom(packet.url(), 0, startPosition);
				} else {
					ExternalMusicHandler.getInstance().playMusic(packet.url(), 0);
				}
				WorldPlaybackChannel.setPlaybackOwner(WorldPlaybackChannel.PlaybackOwner.CUE);
			}
			case PAUSE -> {
				if (optOut)
					return;
				ExternalMusicHandler.getInstance().pauseMusic();
			}
			case RESUME -> {
				if (optOut)
					return;
				ExternalMusicHandler.getInstance().resumeMusic();
			}
			case STOP -> {
				// K12-A: STOP returns the CUE owner to AUTO (client-side
				// arbitration); the server timeline keeps running for other
				// players regardless
				if (WorldPlaybackChannel.playbackOwner() == WorldPlaybackChannel.PlaybackOwner.CUE)
					WorldPlaybackChannel.setPlaybackOwner(WorldPlaybackChannel.PlaybackOwner.AUTO);
				if (!optOut)
					ExternalMusicHandler.getInstance().stopMusic();
			}
		}
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
