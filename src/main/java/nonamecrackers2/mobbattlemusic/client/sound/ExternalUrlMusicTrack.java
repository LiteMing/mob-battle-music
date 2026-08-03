package nonamecrackers2.mobbattlemusic.client.sound;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler;
import nonamecrackers2.mobbattlemusic.client.music.StreamMusicPlayer;

/**
 * A special track type for external URL music that uses StreamMusicPlayer
 * instead of Minecraft's sound system
 */
public class ExternalUrlMusicTrack {
    private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/ExternalUrlMusicTrack");
    
    private final String url;
    private final int fadeTime;
    private final ExternalMusicHandler handler;
    private final StreamMusicPlayer player;
    private boolean started = false;
    private boolean stopped = false;
    private final long startPositionMillis;
    
    public ExternalUrlMusicTrack(String url, int fadeTime) {
        this(url, fadeTime, 0L);
    }

    public ExternalUrlMusicTrack(String url, int fadeTime, long startPositionMillis) {
        this.url = url;
        this.fadeTime = fadeTime;
        this.startPositionMillis = Math.max(0L, startPositionMillis);
        this.handler = ExternalMusicHandler.getInstance();
        this.player = handler.getPlayer();
    }
    
    /**
     * Start playing the track with fade-in
     */
    public void play() {
        if (started || stopped) {
            return;
        }
        
        started = true;
        LOGGER.info("Playing external URL music with fade-in ({}ms): {}", fadeTime * 50, url);
        if (this.startPositionMillis > 0L)
            handler.playMusicFrom(url, fadeTime, this.startPositionMillis);
        else
            handler.playMusic(url, fadeTime);
    }
    
    /**
     * Set target volume for fade effect (0.0 to 1.0)
     * @param volume Target volume
     */
    public void setTargetVolume(float volume) {
        // AUD-44: the track envelope owns the gain; this is a pure projection
        player.setTargetVolume(volume);
    }
    
    /**
     * Get current volume
     * @return Current volume (0.0 to 1.0)
     */
    public float getCurrentVolume() {
        return player.getCurrentVolume();
    }
    
    /**
     * Stop playing the track
     */
    public void stop() {
        if (!stopped) {
            stopped = true;
            handler.stopMusic();
            LOGGER.debug("Stopped external URL music: {}", url);
        }
    }
    
    /**
     * Pause the track
     */
    public void pause() {
        player.pause();
    }
    
    /**
     * Resume the track
     */
    public void resume() {
        player.resume();
    }
    
    /**
     * Check if the track is currently playing
     */
    public boolean isPlaying() {
        return started && !stopped && player.isPlaying();
    }
    
    /**
     * Check if the track has been stopped
     */
    public boolean isStopped() {
        if (stopped || !started) {
            return stopped;
        }
        if (!url.equals(handler.getCurrentlyPlayingUrl()))
            return true;
        // AUD-43: liveness predicate; isPlaying() (audible) would treat a
        // paused track as stopped
        return !handler.isPreparingCurrentMusic() && !player.hasActiveTrack();
    }
    
    /**
     * Get the URL of this track
     */
    public String getUrl() {
        return url;
    }
    
    /**
     * Get the fade time
     */
    public int getFadeTime() {
        return fadeTime;
    }

    public long getStartPositionMillis() {
        return this.startPositionMillis;
    }
    
    /**
     * Set volume (0.0 to 1.0) - deprecated, use setTargetVolume instead
     * Note: Volume is controlled by Minecraft's settings in StreamMusicPlayer
     */
    @Deprecated
    public void setVolume(float volume) {
        setTargetVolume(volume);
    }
}
