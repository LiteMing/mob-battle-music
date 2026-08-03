package nonamecrackers2.mobbattlemusic.client.audio;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler;
import nonamecrackers2.mobbattlemusic.client.sound.MobBattleTrack;

/**
 * Preview (audition) channel, bound to the client GUI (AUD-1/AUD-3).
 * This class must never reference ClientLevel, Minecraft#level,
 * Minecraft#isPaused, MinecraftServer, MbmSessionState or any network packet.
 */
public final class PreviewChannel
{
	private static volatile @Nullable MobBattleTrack soundTrack;
	private static volatile @Nullable PlaybackHandle handle;
	
	private PreviewChannel() {}
	
	public static void playUrl(String url, int fadeTime, long durationHintMillis)
	{
		stop();
		ExternalMusicHandler.getInstance().playPreviewMusic(url, fadeTime, durationHintMillis);
		PreviewChannel.handle = PlaybackHandle.preview(url);
	}
	
	public static void playSound(ResourceLocation sound, int fadeTime)
	{
		stop();
		MobBattleTrack track = MobBattleTrack.preview(sound, fadeTime);
		PreviewChannel.soundTrack = track;
		Minecraft.getInstance().getSoundManager().play(track);
		PreviewChannel.handle = PlaybackHandle.preview(sound.toString());
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
