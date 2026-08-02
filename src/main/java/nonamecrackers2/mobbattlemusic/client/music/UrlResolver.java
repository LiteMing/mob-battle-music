package nonamecrackers2.mobbattlemusic.client.music;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Resolves music platform URLs to direct audio URLs
 */
public class UrlResolver {
    private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/UrlResolver");
    
    // Pattern to match Netease Cloud Music song URLs
    private static final Pattern NETEASE_SONG_PATTERN = Pattern.compile(
        "(?:https?://)?music\\.163\\.com/(?:song\\?id=|song/media/outer/url\\?id=)(\\d+)"
    );
    
    /**
     * Resolve a URL to a direct audio URL
     * @param url The original URL
     * @return The resolved direct audio URL, or the original URL if no resolution is needed
     */
    public static String resolveUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        
        // Check if it's a Netease Cloud Music song page URL
        Matcher neteaseMatcher = NETEASE_SONG_PATTERN.matcher(url);
        if (neteaseMatcher.find()) {
            String songId = neteaseMatcher.group(1);
            String directUrl = "http://music.163.com/song/media/outer/url?id=" + songId;
            LOGGER.info("Resolved Netease Cloud Music URL: {} -> {}", url, directUrl);
            return directUrl;
        }
        
        // If no special handling is needed, return the original URL
        return url;
    }
    
    /**
     * Check if a URL needs resolution
     * @param url The URL to check
     * @return true if the URL needs resolution
     */
    public static boolean needsResolution(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        
        // Check if it's a Netease Cloud Music song page URL
        return NETEASE_SONG_PATTERN.matcher(url).find();
    }

    public static String neteaseSongId(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        Matcher neteaseMatcher = NETEASE_SONG_PATTERN.matcher(url);
        return neteaseMatcher.find() ? neteaseMatcher.group(1) : null;
    }
}
