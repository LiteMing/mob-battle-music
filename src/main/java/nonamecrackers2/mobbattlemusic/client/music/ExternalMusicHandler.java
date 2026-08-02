package nonamecrackers2.mobbattlemusic.client.music;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicLong playbackRequest = new AtomicLong();
    private volatile String currentlyPlayingUrl;
    private volatile Path currentlyPlayingPath;
    private volatile long currentDurationHintMillis;
    private volatile boolean previewing;
    
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
        MusicMetadataCache.getInstance().prepare(url);
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
                String resolvedUrl = UrlResolver.resolveUrl(url);
                byte[] mp3Data = downloader.download(resolvedUrl, progress -> {
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
        playMusic(url, 0);
    }
    
    /**
     * Play music from an external URL
     * @param url The external music URL
     * @param fadeTime Fade-in time in ticks
     */
    public void playMusic(String url, int fadeTime) {
        playMusic(url, fadeTime, 0L);
    }

    public void playMusic(String url, int fadeTime, long durationHintMillis) {
        playMusic(url, fadeTime, 0L, durationHintMillis, false);
    }

    public void playMusicFrom(String url, int fadeTime, long startPositionMillis) {
        playMusic(url, fadeTime, Math.max(0L, startPositionMillis), 0L, false);
    }

    public void playPreviewMusic(String url, int fadeTime, long durationHintMillis) {
        playMusic(url, fadeTime, 0L, durationHintMillis, true);
    }

    private void playMusic(String url, int fadeTime, long startPositionMillis, long durationHintMillis, boolean preview) {
        long request = playbackRequest.incrementAndGet();
        this.previewing = preview;
        this.currentlyPlayingUrl = url;
        this.currentlyPlayingPath = null;
        this.currentDurationHintMillis = Math.max(0L, durationHintMillis);
        prepareMusicFile(url).thenAccept(cachedPath -> {
            if (request != playbackRequest.get())
                return;
            if (cachedPath == null) {
                if (preview)
                    previewing = false;
                currentlyPlayingUrl = null;
                currentlyPlayingPath = null;
                currentDurationHintMillis = 0L;
                LOGGER.error("Failed to prepare music file for playback: {}", url);
                return;
            }
            
            try {
                player.play(cachedPath, fadeTime, startPositionMillis, durationHintMillis);
                currentlyPlayingPath = cachedPath;
                
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
        playbackRequest.incrementAndGet();
        player.stop();
        currentlyPlayingUrl = null;
        currentlyPlayingPath = null;
        currentDurationHintMillis = 0L;
        previewing = false;
    }

    public void stopPreviewMusic() {
        if (this.previewing)
            stopMusic();
    }

    public boolean seekMusic(long positionMillis) {
        Path path = currentlyPlayingPath;
        if (path == null || currentlyPlayingUrl == null)
            return false;
        long duration = getDurationMillis();
        long clamped = duration > 0L ? Math.max(0L, Math.min(positionMillis, duration - 1L)) : Math.max(0L, positionMillis);
        playbackRequest.incrementAndGet();
        player.play(path, 0, clamped, currentDurationHintMillis);
        return true;
    }

    public long getPositionMillis() {
        return player.getPositionMillis();
    }

    public long getDurationMillis() {
        return Math.max(player.getDurationMillis(), currentDurationHintMillis);
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

    public boolean isPreviewing() {
        if (this.previewing && this.currentlyPlayingPath != null && !this.player.isPlaying() && !this.player.isPaused()) {
            this.previewing = false;
            this.currentlyPlayingUrl = null;
            this.currentlyPlayingPath = null;
            this.currentDurationHintMillis = 0L;
        }
        return this.previewing;
    }

    public boolean isPreparingCurrentMusic() {
        return this.currentlyPlayingUrl != null && this.currentlyPlayingPath == null;
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
