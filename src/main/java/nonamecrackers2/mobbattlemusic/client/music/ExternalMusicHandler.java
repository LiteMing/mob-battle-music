package nonamecrackers2.mobbattlemusic.client.music;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import nonamecrackers2.mobbattlemusic.client.audio.WorldPlaybackChannel;
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
    private final StreamMusicPlayer previewPlayer;
    private final Map<String, CompletableFuture<Path>> ongoingDownloads;
    private final Object playbackLock = new Object();
    private final Object previewPlaybackLock = new Object();
    private final AtomicLong playbackRequest = new AtomicLong();
    private final AtomicLong previewPlaybackRequest = new AtomicLong();
    // AUD-51: single-threaded audio-I/O executor; all line open/close, decode
    // start and thread creation run here, never on the client tick thread
    private final java.util.concurrent.ExecutorService audioIo =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MBM-Audio-IO");
                thread.setDaemon(true);
                return thread;
            });
    // K8-B: cumulative stopMusic() invocations for the probe ring
    private final java.util.concurrent.atomic.AtomicLong stopRequests =
            new java.util.concurrent.atomic.AtomicLong();
    // AUD-51: monotonic seek request number; stale tasks are dropped
    private final AtomicLong seekRequest = new AtomicLong();
    // AUD-51 追加: sync-visible intent flag - stopMusic() sets it on the
    // caller thread before dispatch, predicates read it instead of observing
    // the async side effect
    private volatile boolean stopRequested;
    // K3 P0-b: a correction seek is in flight (queued on the audio-I/O
    // executor, not yet completed); set on the caller thread before dispatch,
    // cleared by the newest request's completion only
    private volatile boolean seekInFlight;
    private volatile String currentlyPlayingUrl;
    private volatile Path currentlyPlayingPath;
    private volatile long currentDurationHintMillis;
    private volatile String previewUrl;
    private volatile Path previewPath;
    private volatile long previewDurationHintMillis;
    
    private ExternalMusicHandler() {
        this.cache = new MusicCache();
        this.downloader = new MusicDownloader();
        // K10-C: the main player shares the static MUTE_ENV (world channel
        // mute reaches it); the preview player gets a PRIVATE mute envelope
        // so main-channel mutes never silence previews
        this.player = new StreamMusicPlayer(true);
        this.previewPlayer = new StreamMusicPlayer(false);
        this.ongoingDownloads = new ConcurrentHashMap<>();
        this.currentlyPlayingUrl = null;
        this.previewUrl = null;
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
        playMusic(url, fadeTime, 0L, durationHintMillis);
    }

    public void playMusicFrom(String url, int fadeTime, long startPositionMillis) {
        playMusic(url, fadeTime, Math.max(0L, startPositionMillis), 0L);
    }

    public void playPreviewMusic(String url, int fadeTime, long durationHintMillis) {
        long request;
        synchronized (this.previewPlaybackLock) {
            request = this.previewPlaybackRequest.incrementAndGet();
            this.previewUrl = url;
            this.previewPath = null;
            this.previewDurationHintMillis = Math.max(0L, durationHintMillis);
        }
        prepareMusicFile(url).thenAccept(cachedPath -> {
            synchronized (this.previewPlaybackLock) {
                if (request != this.previewPlaybackRequest.get())
                    return;
                if (cachedPath == null) {
                    clearPreviewState();
                    LOGGER.error("Failed to prepare preview music file for playback: {}", url);
                    return;
                }

                try {
                    this.previewPlayer.play(cachedPath, fadeTime, 0L, durationHintMillis);
                    this.previewPath = cachedPath;
                    LOGGER.info("Started previewing music from: {}", url);
                } catch (Exception e) {
                    clearPreviewState();
                    LOGGER.error("Failed to preview music from: {}", url, e);
                }
            }
        });
    }

    private void playMusic(String url, int fadeTime, long startPositionMillis, long durationHintMillis) {
        long request;
        synchronized (this.playbackLock) {
            request = this.playbackRequest.incrementAndGet();
            this.currentlyPlayingUrl = url;
            this.currentlyPlayingPath = null;
            this.currentDurationHintMillis = Math.max(0L, durationHintMillis);
        }
        prepareMusicFile(url).thenAccept(cachedPath -> {
            synchronized (this.playbackLock) {
                if (request != this.playbackRequest.get())
                    return;
                if (cachedPath == null) {
                    clearPlaybackState();
                    // K12-B: the handle must not claim a track whose source
                    // never downloaded - mark it FAILED explicitly
                    WorldPlaybackChannel.markCurrentHandleFailed(url);
                    LOGGER.error("Failed to prepare music file for playback: {}", url);
                    return;
                }
            }
            // AUD-51: playback start runs on the audio-I/O executor, ordered
            // with stop/seek; never on the client tick thread
            this.audioIo.execute(() -> {
                synchronized (this.playbackLock) {
                    if (request != this.playbackRequest.get())
                        return;
                    try {
                        // K12-B: the async generation handoff refuses (old
                        // thread alive) - the refusal must reach the handle
                        // instead of the dock pretending playback is active
                        this.player.setStartResultListener((generation, result, failure) -> {
                            synchronized (this.playbackLock) {
                                if (request != this.playbackRequest.get())
                                    return;
                                if (result == StreamMusicPlayer.PlayResult.STARTED)
                                    WorldPlaybackChannel.markCurrentHandleActive(url);
                                else {
                                    WorldPlaybackChannel.markCurrentHandleFailed(url);
                                    LOGGER.error("[MBM] playback of {} failed during start: {}", url,
                                            failure == null ? result : failure.toString());
                                }
                            }
                        });
                        StreamMusicPlayer.PlayResult playResult = this.player.play(cachedPath, fadeTime,
                                startPositionMillis, durationHintMillis);
                        if (playResult == StreamMusicPlayer.PlayResult.REFUSED_OLD_THREAD_ALIVE) {
                            clearPlaybackState();
                            WorldPlaybackChannel.markCurrentHandleFailed(url);
                            LOGGER.error("[MBM] playback of {} refused: previous playback generation still alive", url);
                            return;
                        }
                        this.currentlyPlayingPath = cachedPath;
                        LOGGER.debug("Started playing music from: {}", url);
                    } catch (Exception e) {
                        clearPlaybackState();
                        WorldPlaybackChannel.markCurrentHandleFailed(url);
                        LOGGER.error("Failed to play music from: {}", url, e);
                    }
                }
            });
        });
    }
    
    /**
     * Stop currently playing music. AUD-51: runs on the audio-I/O executor to
     * preserve ordering with play/seek; never blocks the caller. The intent is
     * visible synchronously via {@link #isStopRequested()}.
     */
    public void stopMusic() {
        this.stopRequests.incrementAndGet();
        this.stopRequested = true;
        this.audioIo.execute(() -> {
            try {
                synchronized (this.playbackLock) {
                    this.playbackRequest.incrementAndGet();
                    this.player.stop();
                    clearPlaybackState();
                }
            } finally {
                this.stopRequested = false;
            }
        });
    }

    // AUD-51 追加: sync-visible stop intent
    public boolean isStopRequested() {
        return this.stopRequested;
    }

    public void stopPreviewMusic() {
        synchronized (this.previewPlaybackLock) {
            this.previewPlaybackRequest.incrementAndGet();
            this.previewPlayer.stop();
            clearPreviewState();
        }
    }

    public boolean seekMusic(long positionMillis) {
        synchronized (this.playbackLock) {
            Path path = this.currentlyPlayingPath;
            if (path == null || this.currentlyPlayingUrl == null)
                return false;
            long duration = getDurationMillis();
            long clamped = clampPosition(positionMillis, duration);
            this.playbackRequest.incrementAndGet();
            // K12-B: a refused generation makes the seek fail (not silently
            // leave the old position) - the caller can retry
            StreamMusicPlayer.PlayResult result = this.player.play(path, 0, clamped, this.currentDurationHintMillis);
            return result == StreamMusicPlayer.PlayResult.STARTED;
        }
    }

    public boolean seekPreviewMusic(long positionMillis) {
        synchronized (this.previewPlaybackLock) {
            Path path = this.previewPath;
            if (path == null || this.previewUrl == null)
                return false;
            long duration = getPreviewDurationMillis();
            long clamped = clampPosition(positionMillis, duration);
            this.previewPlaybackRequest.incrementAndGet();
            return this.previewPlayer.play(path, 0, clamped, this.previewDurationHintMillis)
                    == StreamMusicPlayer.PlayResult.STARTED;
        }
    }

    /**
     * AUD-51: queue a main-playback seek on the audio-I/O executor. Stale
     * requests (superseded by a newer seek) are dropped at execution time.
     * AUD-52 v1.2: onComplete runs on the executor thread with the measured
     * watermark-fill time of the new line as completedAt (0 when the watermark
     * was not reached within the timeout).
     */
    public void seekMusicAsync(long positionMillis, java.util.function.LongConsumer onComplete) {
        long request = this.seekRequest.incrementAndGet();
        // K3 P0-b: sync-visible in-flight intent, set on the caller thread
        // before dispatch
        this.seekInFlight = true;
        this.audioIo.execute(() -> {
            try {
                if (request != this.seekRequest.get())
                    return;
                // AUD-52 v1.3/R5: record the playback generation before the seek;
                // only a watermark stamp from a strictly newer generation counts
                long generationBefore = this.player.getPlaybackGeneration();
                this.seekMusic(positionMillis);
                // AUD-52 v1.2: wait for the new line's first watermark fill (the
                // output actually reached the target position); capped at 1500ms
                long deadline = System.currentTimeMillis() + 1500L;
                long watermarkReached = 0L;
                while (watermarkReached == 0L && System.currentTimeMillis() < deadline) {
                    StreamMusicPlayer.WatermarkStamp stamp = this.player.getWatermarkStamp();
                    if (stamp.generation() > generationBefore)
                        watermarkReached = stamp.millis();
                    if (watermarkReached == 0L) {
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (onComplete != null)
                    onComplete.accept(watermarkReached);
            } catch (Throwable t) {
                // K5 P0-2: an unexpected failure must still run the recovery
                // path (the failure branch of onComplete) - a seek that threw
                // is a failed seek, never a silently skipped one
                LOGGER.error("[MBM] seekMusicAsync task failed", t);
                if (onComplete != null)
                    onComplete.accept(0L);
            } finally {
                // K3 P0-b: only the newest seek request clears the flag
                if (request == this.seekRequest.get())
                    this.seekInFlight = false;
            }
        });
    }

    // K3 P0-b: is a correction seek currently in flight?
    public boolean isSeekInFlight() {
        return this.seekInFlight;
    }

    public long getPositionMillis() {
        return player.getPositionMillis();
    }

    // AUD-47: decoded (written) position, diagnostic only
    public long getDecodedPositionMillis() {
        return player.getDecodedPositionMillis();
    }

    public long getDurationMillis() {
        return Math.max(player.getDurationMillis(), currentDurationHintMillis);
    }

    public long getPreviewPositionMillis() {
        return this.previewPlayer.getPositionMillis();
    }

    public long getPreviewDurationMillis() {
        return Math.max(this.previewPlayer.getDurationMillis(), this.previewDurationHintMillis);
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
        synchronized (this.previewPlaybackLock) {
            if (this.previewUrl != null && this.previewPath != null &&
                    !this.previewPlayer.isPlaying() && !this.previewPlayer.isPaused()) {
                clearPreviewState();
            }
            return this.previewUrl != null;
        }
    }

    public boolean isPreparingCurrentMusic() {
        // AUD-51 追加: a pending stop is intent, not preparation
        return this.currentlyPlayingUrl != null && this.currentlyPlayingPath == null && !this.stopRequested;
    }
    
    /**
     * Get the URL of currently playing music
     */
    public String getCurrentlyPlayingUrl() {
        return currentlyPlayingUrl;
    }

    public String getPreviewUrl() {
        return this.previewUrl;
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
    
    // K9-3/K10-C/K11-C/K12-B: apply the persisted gains from config to both
    // players. The final preview chain is userGain x previewGain, so the two
    // runtime factors must never BOTH carry a volume when follows-main is on:
    // following mirrors mainGain into userGain and pins previewGain to 1.0;
    // independent mode pins userGain to 1.0 and uses the stored previewGain.
    // The persisted previewGain config value is NEVER overwritten here - it is
    // only restored when follows-main is off (same rule as the GUI toggle, so
    // every entry point reaches the same effective gain).
    public void applyGainConfig() {
        double mainGain = MobBattleMusicConfig.CLIENT.mbmUserGain.get();
        double previewGain = MobBattleMusicConfig.CLIENT.previewGain.get();
        boolean follows = MobBattleMusicConfig.CLIENT.previewFollowsMain.get();
        this.player.setUserGain((float) mainGain);
        this.previewPlayer.setUserGain(follows ? (float) mainGain : 1.0F);
        this.previewPlayer.setPreviewGain(follows ? 1.0F : (float) previewGain);
    }

    public float getMainUserGain() {
        return this.player.getUserGain();
    }

    // the audible preview gain (user x preview factors, one of which is 1.0
    // by construction)
    public float getPreviewGainNow() {
        return this.previewPlayer.getPreviewGain() * this.previewPlayer.getUserGain();
    }

    /**
     * Get the stream player instance
     * @return The StreamMusicPlayer instance
     */
    public StreamMusicPlayer getPlayer() {
        return player;
    }

    // K8-B: cumulative playback requests (playbackRequest counter) and stop
    // requests for the probe ring
    public long getPlaybackRequestCount() {
        return this.playbackRequest.get();
    }

    public long getStopRequestCount() {
        return this.stopRequests.get();
    }

    public StreamMusicPlayer getPreviewPlayer() {
        return this.previewPlayer;
    }

    private void clearPlaybackState() {
        this.currentlyPlayingUrl = null;
        this.currentlyPlayingPath = null;
        this.currentDurationHintMillis = 0L;
    }

    private void clearPreviewState() {
        this.previewUrl = null;
        this.previewPath = null;
        this.previewDurationHintMillis = 0L;
    }

    private static long clampPosition(long positionMillis, long durationMillis) {
        return durationMillis > 0L
                ? Math.max(0L, Math.min(positionMillis, durationMillis - 1L))
                : Math.max(0L, positionMillis);
    }
}
