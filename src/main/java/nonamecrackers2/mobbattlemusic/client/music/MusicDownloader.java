package nonamecrackers2.mobbattlemusic.client.music;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;

/**
 * Handles downloading music files from external URLs
 */
public class MusicDownloader {
    private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/MusicDownloader");
    private static final long LARGE_FILE_THRESHOLD = 5 * 1024 * 1024; // 5MB
    
    private final HttpClient httpClient;
    
    public MusicDownloader() {
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }
    
    /**
     * Download a music file from the given URL
     * @param url The URL to download from
     * @param progressCallback Optional progress callback
     * @return CompletableFuture with the downloaded data
     */
    public CompletableFuture<byte[]> download(String url, Consumer<DownloadProgress> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            return downloadWithRetry(url, progressCallback, 0);
        });
    }
    
    /**
     * Download with retry logic
     */
    private byte[] downloadWithRetry(String url, Consumer<DownloadProgress> progressCallback, int attempt) {
        int maxRetries = MobBattleMusicConfig.CLIENT.maxRetries.get();
        int timeout = MobBattleMusicConfig.CLIENT.downloadTimeout.get();
        
        try {
            if (progressCallback != null) {
                DownloadProgress progress = new DownloadProgress();
                progress.setState(DownloadProgress.DownloadState.CONNECTING);
                progressCallback.accept(progress);
            }
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .GET()
                .build();
            
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                byte[] data = response.body();
                
                if (progressCallback != null) {
                    DownloadProgress progress = new DownloadProgress();
                    progress.setBytesDownloaded(data.length);
                    progress.setTotalBytes(data.length);
                    progress.setPercentage(100.0);
                    progress.setState(DownloadProgress.DownloadState.COMPLETED);
                    progressCallback.accept(progress);
                }
                
                LOGGER.info("Successfully downloaded music from: {} ({} bytes)", url, data.length);
                return data;
            } else {
                throw new IOException("HTTP error " + response.statusCode() + " for URL: " + url);
            }
            
        } catch (Exception e) {
            if (attempt < maxRetries) {
                long backoffTime = (long) Math.pow(2, attempt) * 1000; // Exponential backoff
                LOGGER.warn("Download failed for '{}': {} (attempt {}/{}). Retrying in {}ms...", 
                    url, e.getMessage(), attempt + 1, maxRetries + 1, backoffTime);
                
                try {
                    Thread.sleep(backoffTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Download interrupted", ie);
                }
                
                return downloadWithRetry(url, progressCallback, attempt + 1);
            } else {
                LOGGER.error("Download failed for '{}' after {} attempts: {}", url, maxRetries + 1, e.getMessage());
                
                if (progressCallback != null) {
                    DownloadProgress progress = new DownloadProgress();
                    progress.setState(DownloadProgress.DownloadState.FAILED);
                    progressCallback.accept(progress);
                }
                
                throw new RuntimeException("Failed to download music from: " + url, e);
            }
        }
    }
    
    /**
     * Download a large file with streaming support
     * @param url The URL to download from
     * @param outputStream The output stream to write to
     * @param progressCallback Optional progress callback
     * @return CompletableFuture that completes when download is done
     */
    public CompletableFuture<Void> downloadStreaming(String url, OutputStream outputStream, 
                                                     Consumer<DownloadProgress> progressCallback) {
        return CompletableFuture.runAsync(() -> {
            try {
                int timeout = MobBattleMusicConfig.CLIENT.downloadTimeout.get();
                
                if (progressCallback != null) {
                    DownloadProgress progress = new DownloadProgress();
                    progress.setState(DownloadProgress.DownloadState.CONNECTING);
                    progressCallback.accept(progress);
                }
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout))
                    .GET()
                    .build();
                
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    byte[] data = response.body();
                    outputStream.write(data);
                    outputStream.flush();
                    
                    if (progressCallback != null) {
                        DownloadProgress progress = new DownloadProgress();
                        progress.setBytesDownloaded(data.length);
                        progress.setTotalBytes(data.length);
                        progress.setPercentage(100.0);
                        progress.setState(DownloadProgress.DownloadState.COMPLETED);
                        progressCallback.accept(progress);
                    }
                    
                    LOGGER.info("Successfully streamed music from: {} ({} bytes)", url, data.length);
                } else {
                    throw new IOException("HTTP error " + response.statusCode() + " for URL: " + url);
                }
                
            } catch (Exception e) {
                LOGGER.error("Streaming download failed for '{}': {}", url, e.getMessage());
                
                if (progressCallback != null) {
                    DownloadProgress progress = new DownloadProgress();
                    progress.setState(DownloadProgress.DownloadState.FAILED);
                    progressCallback.accept(progress);
                }
                
                throw new RuntimeException("Failed to stream music from: " + url, e);
            }
        });
    }
    
    /**
     * Check if a URL should use streaming download
     * @param url The URL to check
     * @return true if streaming should be used
     */
    public boolean shouldUseStreaming(String url) {
        // For now, we'll use regular download for all files
        // Streaming can be implemented later if needed
        return false;
    }
}
