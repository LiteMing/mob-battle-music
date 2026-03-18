package nonamecrackers2.mobbattlemusic.client.music;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Diagnostic utility for audio system issues
 */
public class AudioSystemDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/AudioDiagnostics");
    
    /**
     * Run full audio system diagnostics
     */
    public static void runDiagnostics() {
        LOGGER.info("=== Audio System Diagnostics ===");
        
        // List all available mixers
        listMixers();
        
        // Test a simple audio format
        testAudioFormat();
        
        LOGGER.info("=== Diagnostics Complete ===");
    }
    
    /**
     * List all available audio mixers
     */
    private static void listMixers() {
        LOGGER.info("Available Audio Mixers:");
        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        
        if (mixerInfos.length == 0) {
            LOGGER.warn("No audio mixers found!");
            return;
        }
        
        for (int i = 0; i < mixerInfos.length; i++) {
            Mixer.Info info = mixerInfos[i];
            LOGGER.info("  [{}] {} - {}", i, info.getName(), info.getDescription());
            
            Mixer mixer = AudioSystem.getMixer(info);
            
            // Check source lines (playback)
            Line.Info[] sourceLines = mixer.getSourceLineInfo();
            if (sourceLines.length > 0) {
                LOGGER.info("    Source lines (playback): {}", sourceLines.length);
                for (Line.Info lineInfo : sourceLines) {
                    LOGGER.info("      - {}", lineInfo);
                }
            }
            
            // Check target lines (recording)
            Line.Info[] targetLines = mixer.getTargetLineInfo();
            if (targetLines.length > 0) {
                LOGGER.info("    Target lines (recording): {}", targetLines.length);
            }
        }
    }
    
    /**
     * Test if a standard audio format is supported
     */
    private static void testAudioFormat() {
        LOGGER.info("Testing standard audio format support:");
        
        // Test CD-quality stereo format
        AudioFormat testFormat = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            44100.0f,  // 44.1 kHz
            16,        // 16-bit
            2,         // stereo
            4,         // frame size (2 channels * 2 bytes)
            44100.0f,  // frame rate
            false      // little-endian
        );
        
        LOGGER.info("Test format: {}", testFormat);
        
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, testFormat);
        
        if (AudioSystem.isLineSupported(info)) {
            LOGGER.info("✓ Format is supported");
            
            try {
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                LOGGER.info("✓ Successfully obtained audio line: {}", line.getClass().getName());
                
                line.open(testFormat);
                LOGGER.info("✓ Successfully opened audio line, buffer size: {}", line.getBufferSize());
                
                line.close();
                LOGGER.info("✓ Audio line test completed successfully");
                
            } catch (Exception e) {
                LOGGER.error("✗ Failed to open audio line", e);
            }
        } else {
            LOGGER.error("✗ Format is NOT supported!");
            
            // Try to find what formats are supported
            LOGGER.info("Attempting to find supported formats...");
            Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
            for (Mixer.Info mixerInfo : mixerInfos) {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                Line.Info[] lineInfos = mixer.getSourceLineInfo();
                for (Line.Info lineInfo : lineInfos) {
                    if (lineInfo instanceof DataLine.Info) {
                        DataLine.Info dataLineInfo = (DataLine.Info) lineInfo;
                        AudioFormat[] formats = dataLineInfo.getFormats();
                        if (formats.length > 0) {
                            LOGGER.info("Mixer '{}' supports {} formats", mixerInfo.getName(), formats.length);
                            for (int i = 0; i < Math.min(3, formats.length); i++) {
                                LOGGER.info("  Format {}: {}", i, formats[i]);
                            }
                        }
                    }
                }
            }
        }
    }
}
