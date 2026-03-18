package nonamecrackers2.mobbattlemusic.client.sound;

import java.nio.file.Path;

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
    private float targetVolume = 1.0f;
    
    public ExternalUrlMusicTrack(String url, int fadeTime) {
        this.url = url;
        this.fadeTime = fadeTime;
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
        
        // Check if already cached
        if (handler.isCached(url)) {
            Path cachedPath = handler.getCachedFilePath(url);
            if (cachedPath != null) {
                LOGGER.info("Playing cached external URL music with fade-in ({}ms): {}", fadeTime * 50, url);
                try {
                    java.io.InputStream inputStream = new java.io.FileInputStream(cachedPath.toFile());
                    player.play(inputStream, fadeTime);
                } catch (Exception e) {
                    LOGGER.error("Failed to play cached music: {}", url, e);
                }
            }
        } else {
            // Download and play
            LOGGER.info("Downloading and playing external URL music: {}", url);
            handler.playMusic(url);
        }
    }
    
    /**
     * Set target volume for fade effect (0.0 to 1.0)
     * @param volume Target volume
     */
    public void setTargetVolume(float volume) {
        this.targetVolume = volume;
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
            player.stop();
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
        return stopped || (!player.isPlaying() && started);
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
    
    /**
     * Set volume (0.0 to 1.0) - deprecated, use setTargetVolume instead
     * Note: Volume is controlled by Minecraft's settings in StreamMusicPlayer
     */
    @Deprecated
    public void setVolume(float volume) {
        setTargetVolume(volume);
    }
}
