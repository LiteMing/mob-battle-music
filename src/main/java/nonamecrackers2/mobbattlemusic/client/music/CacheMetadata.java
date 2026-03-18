package nonamecrackers2.mobbattlemusic.client.music;

/**
 * Metadata for cached music files
 */
public class CacheMetadata {
    private String url;
    private long downloadTimestamp;
    private long fileSize;
    private String format;
    private String hash;
    private int accessCount;
    private long lastAccessTimestamp;
    
    public CacheMetadata() {
        this.accessCount = 0;
        this.lastAccessTimestamp = System.currentTimeMillis();
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }
    
    public long getDownloadTimestamp() {
        return downloadTimestamp;
    }
    
    public void setDownloadTimestamp(long downloadTimestamp) {
        this.downloadTimestamp = downloadTimestamp;
    }
    
    public long getFileSize() {
        return fileSize;
    }
    
    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }
    
    public String getFormat() {
        return format;
    }
    
    public void setFormat(String format) {
        this.format = format;
    }
    
    public String getHash() {
        return hash;
    }
    
    public void setHash(String hash) {
        this.hash = hash;
    }
    
    public int getAccessCount() {
        return accessCount;
    }
    
    public void setAccessCount(int accessCount) {
        this.accessCount = accessCount;
    }
    
    public void incrementAccessCount() {
        this.accessCount++;
        this.lastAccessTimestamp = System.currentTimeMillis();
    }
    
    public long getLastAccessTimestamp() {
        return lastAccessTimestamp;
    }
    
    public void setLastAccessTimestamp(long lastAccessTimestamp) {
        this.lastAccessTimestamp = lastAccessTimestamp;
    }
    
    /**
     * Check if the cached file is older than the specified number of days
     * @param days Number of days
     * @return true if the file is older than the specified days
     */
    public boolean isOlderThan(int days) {
        long ageInMillis = System.currentTimeMillis() - downloadTimestamp;
        long daysInMillis = days * 24L * 60L * 60L * 1000L;
        return ageInMillis > daysInMillis;
    }
}
