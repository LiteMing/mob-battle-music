package nonamecrackers2.mobbattlemusic.client.music;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;

/**
 * Handles external URL music loading and preparation
 * Uses a stream-based player to play MP3 files directly without conversion
 */
public class ExternalMusicHandler {
    private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/ExternalMusicHandler");
    private static ExternalMusicHandler INSTANCE;
    
    private final MusicCache cache;
    private final MusicDownloader downloader;
    private final StreamMusicPlayer player;
    private final Map<String, CompletableFuture<Path>> ongoingDownloads;
    private String currentlyPlayingUrl;
    
    private ExternalMusicHandler() {
        this.cache = new MusicCache();
        this.downloader = new MusicDownloader();
        this.player = new StreamMusicPlayer();
        this.ongoingDownloads = new ConcurrentHashMap<>();
        this.currentlyPlayingUrl = null;
    }
    
    public static ExternalMusicHandler getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ExternalMusicHandler();
        }
        return INSTANCE;
    }
    
    /**
     * Prepare a music file from an external URL
     * @param url The external music URL
     * @return CompletableFuture with the Path of the cached file, or null if failed
     */
    public CompletableFuture<Path> prepareMusicFile(String url) {
        // Check if already downloading
        CompletableFuture<Path> ongoing = ongoingDownloads.get(url);
        if (ongoing != null) {
            LOGGER.debug("Download already in progress for: {}", url);
            return ongoing;
        }
        
        // Check cache first
        if (isCached(url)) {
            LOGGER.debug("Using cached file for: {}", url);
            Path cachedPath = getCachedFilePath(url);
            return CompletableFuture.completedFuture(cachedPath);
        }
        
        // Start new download
        CompletableFuture<Path> future = CompletableFuture.supplyAsync(() -> {
            try {
                LOGGER.info("Preparing music file from URL: {}", url);
                
                // Download the file
                byte[] mp3Data = downloader.download(url, progress -> {
                    LOGGER.debug("Download progress for {}: {}", url, progress);
                }).join();
                
                // Save MP3 directly to cache (no conversion needed!)
                Path cachedFile = cache.saveToCache(url, mp3Data);
                
                LOGGER.info("Successfully cached music file: {} -> {}", url, cachedFile);
                return cachedFile;
                
            } catch (Exception e) {
                LOGGER.error("Failed to prepare music file from URL: {}", url, e);
                
                // Try to use cached file if available and config allows
                if (MobBattleMusicConfig.CLIENT.useCacheOnError.get()) {
                    Path cachedFile = cache.getCachedFile(url);
                    if (cachedFile != null) {
                        LOGGER.warn("Using stale cached file for: {}", url);
                        return cachedFile;
                    }
                }
                
                return null;
            } finally {
                ongoingDownloads.remove(url);
            }
        });
        
        ongoingDownloads.put(url, future);
        return future;
    }
    
    /**
     * Play music from an external URL
     * @param url The external music URL
     */
    public void playMusic(String url) {
        prepareMusicFile(url).thenAccept(cachedPath -> {
            if (cachedPath == null) {
                LOGGER.error("Failed to prepare music file for playback: {}", url);
                return;
            }
            
            try {
                // Stop any currently playing music
                stopMusic();
                
                // Play the cached MP3 file
                InputStream inputStream = new FileInputStream(cachedPath.toFile());
                player.play(inputStream);
                currentlyPlayingUrl = url;
                
                LOGGER.info("Started playing music from: {}", url);
                
            } catch (Exception e) {
                LOGGER.error("Failed to play music from: {}", url, e);
            }
        });
    }
    
    /**
     * Stop currently playing music
     */
    public void stopMusic() {
        player.stop();
        currentlyPlayingUrl = null;
    }
    
    /**
     * Pause currently playing music
     */
    public void pauseMusic() {
        player.pause();
    }
    
    /**
     * Resume currently playing music
     */
    public void resumeMusic() {
        player.resume();
    }
    
    /**
     * Check if music is currently playing
     */
    public boolean isPlaying() {
        return player.isPlaying();
    }
    
    /**
     * Get the URL of currently playing music
     */
    public String getCurrentlyPlayingUrl() {
        return currentlyPlayingUrl;
    }
    
    /**
     * Check if a URL is cached
     * @param url The URL to check
     * @return true if cached
     */
    public boolean isCached(String url) {
        Path cachedFile = cache.getCachedFile(url);
        return cachedFile != null && cache.isValid(cachedFile);
    }
    
    /**
     * Get the cache instance
     * @return The MusicCache instance
     */
    public MusicCache getCache() {
        return cache;
    }
    
    /**
     * Get the actual file path for a cached URL
     * @param url The original URL
     * @return The file path, or null if not cached
     */
    public Path getCachedFilePath(String url) {
        return cache.getCachedFile(url);
    }
    
    /**
     * Get the stream player instance
     * @return The StreamMusicPlayer instance
     */
    public StreamMusicPlayer getPlayer() {
        return player;
    }
}
