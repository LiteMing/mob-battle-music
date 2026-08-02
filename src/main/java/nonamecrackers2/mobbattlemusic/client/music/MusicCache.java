package nonamecrackers2.mobbattlemusic.client.music;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.client.Minecraft;

/**
 * Manages local caching of downloaded music files
 */
public class MusicCache {
    private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/MusicCache");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CACHE_DIR = "mobbattlemusic/cache/music";
    
    private final Path cacheDirectory;
    
    public MusicCache() {
        this.cacheDirectory = Minecraft.getInstance().gameDirectory.toPath().resolve(CACHE_DIR);
        initialize();
    }
    
    /**
     * Initialize the cache directory
     */
    public void initialize() {
        try {
            if (!Files.exists(cacheDirectory)) {
                Files.createDirectories(cacheDirectory);
                LOGGER.info("Created music cache directory at: {}", cacheDirectory);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create cache directory: {}", cacheDirectory, e);
        }
    }
    
    /**
     * Generate a hash for the given URL to use as cache key
     * @param url The URL to hash
     * @return The hash string
     */
    public String hashUrl(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(url.getBytes());
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            LOGGER.error("Failed to hash URL: {}", url, e);
            return String.valueOf(url.hashCode());
        }
    }
    
    /**
     * Get the cached file path for a URL
     * @param url The original URL
     * @return The cached file path, or null if not cached
     */
    public Path getCachedFile(String url) {
        String hash = hashUrl(url);
        Path cachedFile = cacheDirectory.resolve(hash + ".mp3");
        
        if (Files.exists(cachedFile) && isValid(cachedFile)) {
            return cachedFile;
        }
        
        return null;
    }
    
    /**
     * Save data to cache
     * @param url The original URL
     * @param data The audio data to cache (MP3 format)
     * @return The path where the file was saved
     * @throws IOException If saving fails
     */
    public Path saveToCache(String url, byte[] data) throws IOException {
        String hash = hashUrl(url);
        Path cachedFile = cacheDirectory.resolve(hash + ".mp3");
        
        // Write to temporary file first, then rename (atomic operation)
        Path tempFile = cacheDirectory.resolve(hash + ".tmp");
        Files.write(tempFile, data);
        Files.move(tempFile, cachedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        
        LOGGER.info("Cached music file: {} -> {}", url, cachedFile.getFileName());
        
        // Save metadata
        CacheMetadata metadata = new CacheMetadata();
        metadata.setUrl(url);
        metadata.setDownloadTimestamp(System.currentTimeMillis());
        metadata.setFileSize(data.length);
        metadata.setFormat("mp3");
        metadata.setHash(hash);
        saveMetadata(url, metadata);
        
        return cachedFile;
    }
    
    /**
     * Validate that a cached file is valid
     * @param path The file path to validate
     * @return true if the file is valid
     */
    public boolean isValid(Path path) {
        if (!Files.exists(path)) {
            return false;
        }
        
        try {
            long size = Files.size(path);
            if (size <= 1024)
                return false;
            try (var input = Files.newInputStream(path)) {
                return isLikelyMp3(input.readNBytes(4096));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to validate cached file: {}", path, e);
            return false;
        }
    }
    
    /**
     * Clear all cached files
     */
    public void clearCache() {
        try {
            if (Files.exists(cacheDirectory)) {
                Files.walk(cacheDirectory)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            Files.delete(file);
                            LOGGER.debug("Deleted cached file: {}", file);
                        } catch (IOException e) {
                            LOGGER.error("Failed to delete cached file: {}", file, e);
                        }
                    });
                LOGGER.info("Cleared music cache");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to clear cache", e);
        }
    }
    
    /**
     * Get metadata for a cached URL
     * @param url The original URL
     * @return The metadata, or null if not found
     */
    public CacheMetadata getMetadata(String url) {
        String hash = hashUrl(url);
        Path metadataFile = cacheDirectory.resolve(hash + ".meta.json");
        
        if (!Files.exists(metadataFile)) {
            return null;
        }
        
        try {
            String json = Files.readString(metadataFile);
            return GSON.fromJson(json, CacheMetadata.class);
        } catch (IOException e) {
            LOGGER.error("Failed to read metadata for URL: {}", url, e);
            return null;
        }
    }
    
    /**
     * Save metadata for a cached URL
     * @param url The original URL
     * @param metadata The metadata to save
     */
    public void saveMetadata(String url, CacheMetadata metadata) throws IOException {
        String hash = hashUrl(url);
        Path metadataFile = cacheDirectory.resolve(hash + ".meta.json");
        
        String json = GSON.toJson(metadata);
        Files.writeString(metadataFile, json);
    }
    
    /**
     * Get the cache directory path
     * @return The cache directory path
     */
    public Path getCacheDirectory() {
        return cacheDirectory;
    }

    public static boolean isLikelyMp3(byte[] data) {
        if (data == null || data.length < 4)
            return false;
        if (data[0] == 'I' && data[1] == 'D' && data[2] == '3')
            return true;
        int limit = Math.min(data.length - 1, 4096);
        for (int i = 0; i < limit; i++) {
            int first = data[i] & 0xFF;
            int second = data[i + 1] & 0xFF;
            if (first == 0xFF && (second & 0xE0) == 0xE0)
                return true;
        }
        return false;
    }

    public List<String> getCachedUrls() {
        List<String> urls = new ArrayList<>();
        if (!Files.exists(cacheDirectory)) {
            return urls;
        }
        try (var files = Files.list(cacheDirectory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".meta.json"))
                .forEach(path -> {
                    try {
                        CacheMetadata metadata = GSON.fromJson(Files.readString(path), CacheMetadata.class);
                        if (metadata != null && metadata.getUrl() != null && !metadata.getUrl().isBlank()) {
                            urls.add(metadata.getUrl());
                        }
                    } catch (Exception e) {
                        LOGGER.debug("Failed to read cache metadata: {}", path, e);
                    }
                });
        } catch (IOException e) {
            LOGGER.debug("Failed to list music cache metadata", e);
        }
        return urls;
    }
    
    /**
     * Validate all cached files on startup
     */
    public void validateCache() {
        try {
            if (!Files.exists(cacheDirectory)) {
                return;
            }
            
            final long[] totalSize = {0};
            final int[] validFiles = {0};
            final int[] invalidFiles = {0};
            
            Files.walk(cacheDirectory)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".mp3"))
                .forEach(file -> {
                    try {
                        if (isValid(file)) {
                            validFiles[0]++;
                            totalSize[0] += Files.size(file);
                        } else {
                            invalidFiles[0]++;
                            LOGGER.warn("Invalid cached file found: {}", file);
                            Files.delete(file);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Error validating cached file: {}", file, e);
                    }
                });
            
            LOGGER.info("Cache validation complete: {} valid files, {} invalid files removed, total size: {} MB", 
                validFiles[0], invalidFiles[0], totalSize[0] / (1024 * 1024));
                
        } catch (Exception e) {
            LOGGER.error("Failed to validate cache", e);
        }
    }
    
    /**
     * Mark old files for potential re-download
     * @param days Number of days to consider a file old
     */
    public void markOldFiles(int days) {
        try {
            if (!Files.exists(cacheDirectory)) {
                return;
            }
            
            final int[] markedCount = {0};
            
            Files.walk(cacheDirectory)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".meta.json"))
                .forEach(metaFile -> {
                    try {
                        String json = Files.readString(metaFile);
                        CacheMetadata metadata = GSON.fromJson(json, CacheMetadata.class);
                        
                        if (metadata != null && metadata.isOlderThan(days)) {
                            LOGGER.debug("Marked old cached file: {} (age: {} days)", 
                                metadata.getUrl(), 
                                (System.currentTimeMillis() - metadata.getDownloadTimestamp()) / (24 * 60 * 60 * 1000));
                            markedCount[0]++;
                        }
                    } catch (Exception e) {
                        LOGGER.error("Error checking file age: {}", metaFile, e);
                    }
                });
            
            if (markedCount[0] > 0) {
                LOGGER.info("Marked {} old cached files (older than {} days)", markedCount[0], days);
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to mark old files", e);
        }
    }
    
    /**
     * Calculate and log total cache size
     */
    public void logCacheSize() {
        try {
            if (!Files.exists(cacheDirectory)) {
                LOGGER.info("Cache directory does not exist");
                return;
            }
            
            long totalSize = Files.walk(cacheDirectory)
                .filter(Files::isRegularFile)
                .mapToLong(file -> {
                    try {
                        return Files.size(file);
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .sum();
            
            long fileCount = Files.walk(cacheDirectory)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".mp3"))
                .count();
            
            LOGGER.info("Music cache: {} files, total size: {} MB", 
                fileCount, totalSize / (1024 * 1024));
                
        } catch (Exception e) {
            LOGGER.error("Failed to calculate cache size", e);
        }
    }
}
