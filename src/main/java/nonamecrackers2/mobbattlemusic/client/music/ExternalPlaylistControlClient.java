package nonamecrackers2.mobbattlemusic.client.music;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
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
			mc.player.displayClientMessage(Component.literal("[Mob Battle Music] " + message), false);
	}
}
