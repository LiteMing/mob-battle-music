package nonamecrackers2.mobbattlemusic.client.music;

/**
 * Represents the progress of a music download
 */
public class DownloadProgress {
    private long bytesDownloaded;
    private long totalBytes;
    private double percentage;
    private DownloadState state;
    
    public enum DownloadState {
        CONNECTING,
        DOWNLOADING,
        COMPLETED,
        FAILED
    }
    
    public DownloadProgress() {
        this.bytesDownloaded = 0;
        this.totalBytes = 0;
        this.percentage = 0.0;
        this.state = DownloadState.CONNECTING;
    }
    
    public long getBytesDownloaded() {
        return bytesDownloaded;
    }
    
    public void setBytesDownloaded(long bytesDownloaded) {
        this.bytesDownloaded = bytesDownloaded;
        updatePercentage();
    }
    
    public long getTotalBytes() {
        return totalBytes;
    }
    
    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
        updatePercentage();
    }
    
    public double getPercentage() {
        return percentage;
    }
    
    public void setPercentage(double percentage) {
        this.percentage = percentage;
    }
    
    public DownloadState getState() {
        return state;
    }
    
    public void setState(DownloadState state) {
        this.state = state;
    }
    
    private void updatePercentage() {
        if (totalBytes > 0) {
            this.percentage = (double) bytesDownloaded / totalBytes * 100.0;
        }
    }
    
    @Override
    public String toString() {
        return String.format("DownloadProgress[state=%s, bytes=%d/%d, %.1f%%]", 
            state, bytesDownloaded, totalBytes, percentage);
    }
}
