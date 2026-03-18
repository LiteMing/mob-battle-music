package nonamecrackers2.mobbattlemusic.client.sound.track;

/**
 * Marker interface for track types that use external URLs
 */
public interface ExternalUrlTrack {
    /**
     * Get the external URL for this track
     * @return The URL string
     */
    String getExternalUrl();
    
    /**
     * Check if this track uses an external URL
     * @return true if this is an external URL track
     */
    default boolean isExternalUrl() {
        return true;
    }
}
