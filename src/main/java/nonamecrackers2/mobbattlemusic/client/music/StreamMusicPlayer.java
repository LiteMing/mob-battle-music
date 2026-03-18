package nonamecrackers2.mobbattlemusic.client.music;

import java.io.BufferedInputStream;
import java.io.InputStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundSource;

/**
 * A simple MP3 stream player that uses JavaSound API directly
 * This bypasses Minecraft's audio system to support MP3 playback
 */
public class StreamMusicPlayer {
    private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/StreamMusicPlayer");
    private static final int BUFFER_SIZE = 4096;
    
    private Thread playbackThread;
    private volatile boolean playing = false;
    private volatile boolean paused = false;
    private volatile boolean gamePaused = false; // Track game pause state
    private volatile float targetVolume = 1.0f;
    private volatile float currentVolume = 0.0f;
    private volatile int fadeTime = 0; // Fade time in ticks (20 ticks = 1 second)
    private SourceDataLine line;
    private long lastVolumeUpdate = 0;
    
    /**
     * Play an MP3 stream with fade-in
     * @param inputStream The MP3 input stream
     * @param fadeTimeInTicks Fade-in time in ticks (20 ticks = 1 second)
     */
    public void play(InputStream inputStream, int fadeTimeInTicks) {
        this.fadeTime = fadeTimeInTicks;
        this.currentVolume = 0.0f; // Start from 0 for fade-in
        this.targetVolume = 1.0f;
        play(inputStream);
    }
    
    /**
     * Play an MP3 stream without fade-in
     * @param inputStream The MP3 input stream
     */
    public void play(InputStream inputStream) {
        stop(); // Stop any currently playing music
        
        playing = true;
        paused = false;
        lastVolumeUpdate = System.currentTimeMillis();
        
        playbackThread = new Thread(() -> {
            try {
                LOGGER.info("Starting MP3 playback thread");
                
                // Use JLayer's MP3 SPI to decode MP3
                LOGGER.info("Creating MpegAudioFileReader...");
                MpegAudioFileReader reader = new MpegAudioFileReader();
                LOGGER.info("MpegAudioFileReader created successfully");
                
                LOGGER.info("Reading audio input stream from MP3...");
                AudioInputStream audioInputStream = null;
                try {
                    audioInputStream = reader.getAudioInputStream(new BufferedInputStream(inputStream));
                    LOGGER.info("Audio input stream created successfully");
                } catch (Exception e) {
                    LOGGER.error("Failed to create audio input stream from MP3", e);
                    throw e;
                }
                
                // Get the audio format
                AudioFormat baseFormat = audioInputStream.getFormat();
                LOGGER.info("Base audio format: {}", baseFormat);
                
                // Convert to PCM
                AudioFormat decodedFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.getSampleRate(),
                    16,
                    baseFormat.getChannels(),
                    baseFormat.getChannels() * 2,
                    baseFormat.getSampleRate(),
                    false
                );
                LOGGER.info("Decoded audio format: {}", decodedFormat);
                
                LOGGER.info("Converting to PCM format...");
                AudioInputStream decodedStream = null;
                try {
                    decodedStream = AudioSystem.getAudioInputStream(decodedFormat, audioInputStream);
                    LOGGER.info("PCM conversion successful");
                } catch (Exception e) {
                    LOGGER.error("Failed to convert to PCM format", e);
                    throw e;
                }
                
                // Get a line to play the audio
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, decodedFormat);
                if (!AudioSystem.isLineSupported(info)) {
                    LOGGER.error("Audio line not supported: {}", info);
                    return;
                }
                
                LOGGER.info("Getting audio line...");
                line = (SourceDataLine) AudioSystem.getLine(info);
                LOGGER.info("Got audio line: {}", line);
                
                LOGGER.info("Opening audio line...");
                line.open(decodedFormat);
                LOGGER.info("Audio line opened, buffer size: {}", line.getBufferSize());
                
                // Set initial volume
                updateVolume();
                
                LOGGER.info("Starting audio line...");
                line.start();
                LOGGER.info("Audio line started");
                
                // Play the audio
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                long totalBytesWritten = 0;
                
                LOGGER.info("Entering playback loop...");
                while (playing && (bytesRead = decodedStream.read(buffer)) != -1) {
                    // Handle pause (manual or game pause)
                    while ((paused || gamePaused) && playing) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    
                    if (!playing) break;
                    
                    // Update volume with fade effect
                    updateVolumeWithFade();
                    
                    line.write(buffer, 0, bytesRead);
                    totalBytesWritten += bytesRead;
                    
                    // Log progress every 1MB
                    if (totalBytesWritten % (1024 * 1024) == 0) {
                        LOGGER.debug("Played {} MB, line available: {}, active: {}", 
                            totalBytesWritten / (1024 * 1024), line.available(), line.isActive());
                    }
                }
                
                LOGGER.info("Playback loop ended. Total bytes written: {}, playing: {}", totalBytesWritten, playing);
                
                // Cleanup
                line.drain();
                line.stop();
                line.close();
                decodedStream.close();
                audioInputStream.close();
                inputStream.close();
                
                LOGGER.info("Finished playing MP3 stream");
                
            } catch (Throwable e) {
                LOGGER.error("Error playing MP3 stream: {}", e.getMessage(), e);
                LOGGER.error("Exception class: {}", e.getClass().getName());
                LOGGER.error("Stack trace:", e);
                if (e.getCause() != null) {
                    LOGGER.error("Caused by: {}", e.getCause().getMessage(), e.getCause());
                }
            } finally {
                playing = false;
                paused = false;
                LOGGER.info("Playback thread finished");
            }
        });
        
        playbackThread.setName("StreamMusicPlayer");
        playbackThread.setDaemon(true);
        playbackThread.start();
        LOGGER.info("Playback thread started");
    }
    
    /**
     * Stop playback
     */
    public void stop() {
        playing = false;
        paused = false;
        
        if (playbackThread != null && playbackThread.isAlive()) {
            try {
                playbackThread.join(1000);
            } catch (InterruptedException e) {
                LOGGER.warn("Interrupted while waiting for playback thread to stop");
            }
        }
        
        if (line != null && line.isOpen()) {
            line.stop();
            line.close();
        }
    }
    
    /**
     * Pause playback
     */
    public void pause() {
        if (playing && !paused) {
            paused = true;
            if (line != null && line.isOpen()) {
                line.stop();
            }
        }
    }
    
    /**
     * Resume playback
     */
    public void resume() {
        if (playing && paused) {
            paused = false;
            if (line != null && line.isOpen()) {
                line.start();
            }
        }
    }
    
    /**
     * Pause playback due to game pause
     */
    public void pauseForGame() {
        if (playing && !gamePaused) {
            gamePaused = true;
            if (line != null && line.isOpen()) {
                line.stop();
            }
            LOGGER.debug("Paused music due to game pause");
        }
    }
    
    /**
     * Resume playback after game unpause
     */
    public void resumeFromGame() {
        if (playing && gamePaused) {
            gamePaused = false;
            if (line != null && line.isOpen()) {
                line.start();
            }
            LOGGER.debug("Resumed music after game unpause");
        }
    }
    
    /**
     * Check if currently playing
     */
    public boolean isPlaying() {
        return playing && !paused && !gamePaused;
    }
    
    /**
     * Check if paused (manually or by game)
     */
    public boolean isPaused() {
        return paused || gamePaused;
    }
    
    /**
     * Check if paused by game
     */
    public boolean isGamePaused() {
        return gamePaused;
    }
    
    /**
     * Set target volume for fade effect (0.0 to 1.0)
     * @param volume Target volume
     */
    public void setTargetVolume(float volume) {
        this.targetVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }
    
    /**
     * Get current volume
     * @return Current volume (0.0 to 1.0)
     */
    public float getCurrentVolume() {
        return currentVolume;
    }
    
    /**
     * Update volume with fade effect based on Minecraft's sound settings
     */
    private void updateVolumeWithFade() {
        if (line == null || !line.isOpen()) return;
        
        long currentTime = System.currentTimeMillis();
        long deltaTime = currentTime - lastVolumeUpdate;
        lastVolumeUpdate = currentTime;
        
        // Calculate fade step based on fade time
        if (fadeTime > 0 && currentVolume != targetVolume) {
            // Convert ticks to milliseconds (1 tick = 50ms)
            float fadeTimeMs = fadeTime * 50.0f;
            float fadeStep = (deltaTime / fadeTimeMs);
            
            if (currentVolume < targetVolume) {
                // Fade in
                currentVolume = Math.min(currentVolume + fadeStep, targetVolume);
            } else {
                // Fade out
                currentVolume = Math.max(currentVolume - fadeStep, targetVolume);
            }
        } else {
            currentVolume = targetVolume;
        }
        
        try {
            // Get Minecraft's master and music volume
            Minecraft mc = Minecraft.getInstance();
            float masterVolume = mc.options.getSoundSourceVolume(SoundSource.MASTER);
            float musicVolume = mc.options.getSoundSourceVolume(SoundSource.RECORDS);
            
            // Apply fade volume
            float finalVolume = masterVolume * musicVolume * currentVolume;
            
            // Apply volume to the line
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gainControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                float dB = (float) (Math.log(Math.max(0.0001f, finalVolume)) / Math.log(10.0) * 20.0);
                dB = Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), dB));
                gainControl.setValue(dB);
            }
        } catch (Exception e) {
            // Ignore volume control errors
        }
    }
    
    /**
     * Update volume based on Minecraft's sound settings (without fade)
     */
    private void updateVolume() {
        if (line == null || !line.isOpen()) return;
        
        try {
            // Get Minecraft's master and record volume
            Minecraft mc = Minecraft.getInstance();
            SoundManager soundManager = mc.getSoundManager();
            float masterVolume = mc.options.getSoundSourceVolume(SoundSource.MASTER);
            float musicVolume = mc.options.getSoundSourceVolume(SoundSource.RECORDS);
            
            float targetVolume = masterVolume * musicVolume;
            
            // Apply volume to the line
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gainControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                float dB = (float) (Math.log(Math.max(0.0001f, targetVolume)) / Math.log(10.0) * 20.0);
                dB = Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), dB));
                gainControl.setValue(dB);
            }
        } catch (Exception e) {
            // Ignore volume control errors
        }
    }
}
