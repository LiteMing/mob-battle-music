package nonamecrackers2.mobbattlemusic.client.music;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Converts MP3 files to OGG Vorbis format using ffmpeg
 * Note: Minecraft only supports OGG Vorbis format for audio files
 */
public class AudioConverter {
    private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/AudioConverter");
    private float quality = 0.6f;
    private static Boolean ffmpegAvailable = null;
    
    /**
     * Check if ffmpeg is available on the system
     * @return true if ffmpeg is available
     */
    private static boolean isFfmpegAvailable() {
        if (ffmpegAvailable != null) {
            return ffmpegAvailable;
        }
        
        try {
            Process process = Runtime.getRuntime().exec("ffmpeg -version");
            int exitCode = process.waitFor();
            ffmpegAvailable = (exitCode == 0);
            LOGGER.info("ffmpeg availability check: {}", ffmpegAvailable);
            return ffmpegAvailable;
        } catch (Exception e) {
            LOGGER.warn("ffmpeg not found on system: {}", e.getMessage());
            ffmpegAvailable = false;
            return false;
        }
    }
    
    /**
     * Convert MP3 file to OGG Vorbis format using ffmpeg
     * @param mp3Data The MP3 file data
     * @param tempDir Temporary directory for conversion
     * @return OGG file data
     * @throws ConversionException If conversion fails
     */
    public byte[] convertMp3ToOgg(byte[] mp3Data, File tempDir) throws ConversionException {
        if (!isFfmpegAvailable()) {
            throw new ConversionException("ffmpeg is not available on this system. Please install ffmpeg to use external URL music playback.");
        }
        
        File tempMp3 = null;
        File tempOgg = null;
        
        try {
            // Create temporary files
            tempMp3 = File.createTempFile("music_", ".mp3", tempDir);
            tempOgg = File.createTempFile("music_", ".ogg", tempDir);
            
            // Write MP3 data to temp file
            try (FileOutputStream fos = new FileOutputStream(tempMp3)) {
                fos.write(mp3Data);
            }
            
            // Convert using ffmpeg
            String[] command = {
                "ffmpeg",
                "-i", tempMp3.getAbsolutePath(),
                "-c:a", "libvorbis",
                "-q:a", String.valueOf((int)(quality * 10)), // Quality 0-10
                "-y", // Overwrite output file
                tempOgg.getAbsolutePath()
            };
            
            LOGGER.info("Converting MP3 to OGG using ffmpeg...");
            Process process = Runtime.getRuntime().exec(command);
            
            // Capture output for debugging
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder errorOutput = new StringBuilder();
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }
            
            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                LOGGER.error("ffmpeg conversion failed with exit code {}: {}", exitCode, errorOutput.toString());
                throw new ConversionException("ffmpeg conversion failed with exit code " + exitCode);
            }
            
            // Read OGG file
            byte[] oggData = Files.readAllBytes(tempOgg.toPath());
            LOGGER.info("Successfully converted MP3 to OGG: {} bytes -> {} bytes", mp3Data.length, oggData.length);
            
            return oggData;
            
        } catch (ConversionException e) {
            throw e;
        } catch (Exception e) {
            throw new ConversionException("Failed to convert MP3 to OGG: " + e.getMessage(), e);
        } finally {
            // Clean up temporary files
            if (tempMp3 != null && tempMp3.exists()) {
                tempMp3.delete();
            }
            if (tempOgg != null && tempOgg.exists()) {
                tempOgg.delete();
            }
        }
    }
    
    /**
     * Convert PCM data to OGG format (legacy method, not implemented)
     * @param audioData The PCM audio data
     * @return OGG file data
     * @throws ConversionException If conversion fails
     */
    public byte[] convertToOgg(AudioData audioData) throws ConversionException {
        throw new ConversionException("Direct PCM to OGG conversion is not supported. Use convertMp3ToOgg instead.");
    }
    
    /**
     * Get the conversion quality setting
     * @return Quality value (0.0 - 1.0)
     */
    public float getQuality() {
        return quality;
    }
    
    /**
     * Set the conversion quality
     * @param quality Quality value (0.0 - 1.0)
     */
    public void setQuality(float quality) {
        this.quality = Math.max(0.0f, Math.min(1.0f, quality));
    }
    
    /**
     * Exception thrown when audio conversion fails
     */
    public static class ConversionException extends Exception {
        public ConversionException(String message) {
            super(message);
        }
        
        public ConversionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
