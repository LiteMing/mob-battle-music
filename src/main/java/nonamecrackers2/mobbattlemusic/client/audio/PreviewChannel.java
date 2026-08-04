package nonamecrackers2.mobbattlemusic.client.audio;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler;
import nonamecrackers2.mobbattlemusic.client.music.StreamMusicPlayer;
import nonamecrackers2.mobbattlemusic.client.sound.MobBattleTrack;

/**
 * Preview (audition) channel, bound to the client GUI (AUD-1/AUD-3).
 * This class must never reference ClientLevel, Minecraft#level,
 * Minecraft#isPaused, MinecraftServer, MbmSessionState, MusicTracksManager,
 * playlist data structures or any network packet (AUD-3, AUD-20 v1.1).
 * Invalidation is driven from the outside: data/GUI layers compare
 * {@link #currentPreviewKey()} and call {@link #stop()} (AUD-20 v1.1).
 */
public final class PreviewChannel
{
	private static volatile @Nullable MobBattleTrack soundTrack;
	private static volatile @Nullable PlaybackHandle handle;
	private static volatile @Nullable String previewKey;
	// K13-C: client takeover - when true, the preview player covers the main
	// channel (main playback + sound leg muted, server process untouched)
	// and continues after the playlist GUI closes. AUD-3 is relaxed for this
	// explicit cover mode: the GUI handoff keeps the channel alive.
	private static volatile boolean coverMain;

	private PreviewChannel() {}

	public static void playUrl(String url, int fadeTime, long durationHintMillis)
	{
		playUrl(url, fadeTime, durationHintMillis, false);
	}

	// K13-C: coverMain=true is the manual-song-selection path - the preview
	// player takes over the audible output; the main channel is muted and the
	// server-side playback process (URL/session/CUE) is left untouched.
	public static void playUrl(String url, int fadeTime, long durationHintMillis, boolean coverMain)
	{
		stop();
		PreviewChannel.coverMain = coverMain;
		ExternalMusicHandler.getInstance().playPreviewMusic(url, fadeTime, durationHintMillis);
		PreviewChannel.handle = PlaybackHandle.create(url);
		PreviewChannel.previewKey = url;
		if (coverMain)
			StreamMusicPlayer.setMainCovered(true);
	}

	public static void playSound(ResourceLocation sound, int fadeTime, String key)
	{
		stop();
		MobBattleTrack track = new MobBattleTrack(sound, fadeTime, true, 0L);
		PreviewChannel.soundTrack = track;
		Minecraft.getInstance().getSoundManager().play(track);
		PreviewChannel.handle = PlaybackHandle.create(sound.toString());
		PreviewChannel.previewKey = key;
	}

	public static void stop()
	{
		ExternalMusicHandler.getInstance().stopPreviewMusic();
		MobBattleTrack track = PreviewChannel.soundTrack;
		PreviewChannel.soundTrack = null;
		if (track != null)
			track.stop();
		PlaybackHandle active = PreviewChannel.handle;
		if (active != null)
			active.markStopped();
		PreviewChannel.handle = null;
		PreviewChannel.previewKey = null;
		// K13-C: releasing the cover restores the main channel envelope
		if (PreviewChannel.coverMain) {
			PreviewChannel.coverMain = false;
			StreamMusicPlayer.setMainCovered(false);
		}
	}

	// K13-C: is the preview currently covering the main channel?
	public static boolean isCoveringMain()
	{
		return PreviewChannel.coverMain;
	}
	
	// AUD-20 v1.1: read-only key for outside invalidation comparison;
	// null while nothing is being previewed
	public static @Nullable String currentPreviewKey()
	{
		return PreviewChannel.previewKey;
	}
	
	public static boolean isPlaying()
	{
		return PreviewChannel.isActive();
	}
	
	public static boolean isActive()
	{
		return ExternalMusicHandler.getInstance().isPreviewing() || PreviewChannel.soundTrack != null;
	}
	
	public static long positionMillis()
	{
		return ExternalMusicHandler.getInstance().getPreviewPositionMillis();
	}
	
	public static long durationMillis()
	{
		return ExternalMusicHandler.getInstance().getPreviewDurationMillis();
	}
	
	public static boolean isSoundTrackActive()
	{
		return PreviewChannel.soundTrack != null;
	}
	
	public static @Nullable ResourceLocation soundTrackLocation()
	{
		MobBattleTrack track = PreviewChannel.soundTrack;
		return track == null ? null : track.getTrackLocation();
	}
	
	public static @Nullable String currentTrack()
	{
		String url = ExternalMusicHandler.getInstance().getPreviewUrl();
		if (url != null)
			return url;
		MobBattleTrack track = PreviewChannel.soundTrack;
		return track == null ? null : track.getTrackLocation().toString();
	}
	
	public static @Nullable PlaybackHandle handle()
	{
		return PreviewChannel.handle;
	}
}
