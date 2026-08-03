package nonamecrackers2.mobbattlemusic.client.music;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.sound.sampled.AudioFileFormat;
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
import net.minecraft.sounds.SoundSource;
import nonamecrackers2.mobbattlemusic.client.audio.AudioFilterManager;
import nonamecrackers2.mobbattlemusic.client.audio.PcmFilterChain;

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
    private volatile boolean gameMuted = false;
    private volatile float targetVolume = 1.0f;
    private volatile float currentVolume = 0.0f;
    private volatile int fadeTime = 0; // Fade time in ticks (20 ticks = 1 second)
    // AUD-24 v1.2: per-call fade (fadeTo), independent of the default fadeTime
    private volatile float fadeFromVolume;
    private volatile float fadeToVolume;
    private volatile long fadeStartMillis;
    private volatile long fadeDurationMillis; // 0 = not active, use default fadeTime
    private volatile SourceDataLine line;
    private final AtomicLong playbackGeneration = new AtomicLong();
    private volatile long playedPcmBytes;
    private volatile long totalDurationMillis;
    private volatile double decodedBytesPerSecond;
    private long lastVolumeUpdate = 0;
    // AUD-47: audible-position base (start/seek offset) and frame rate of the
    // current line; getLongFramePosition() counts frames already played
    private volatile long positionOffsetMillis;
    private volatile float lineFrameRate;
    
    /**
     * Play an MP3 stream with fade-in
     * @param inputStream The MP3 input stream
     * @param fadeTimeInTicks Fade-in time in ticks (20 ticks = 1 second)
     */
    public void play(Path file, int fadeTimeInTicks) {
        play(file, fadeTimeInTicks, 0L, 0L);
    }

    public void play(Path file, int fadeTimeInTicks, long startPositionMillis, long durationHintMillis) {
        this.fadeTime = fadeTimeInTicks;
        this.currentVolume = 0.0f; // Start from 0 for fade-in
        this.targetVolume = 1.0f;
        startPlayback(file, startPositionMillis, durationHintMillis);
    }
    
    /**
     * Play an MP3 stream without fade-in
     * @param inputStream The MP3 input stream
     */
    public void play(Path file) {
        play(file, 0);
    }

    private void startPlayback(Path file, long startPositionMillis, long durationHintMillis) {
        stop();
        long generation = playbackGeneration.incrementAndGet();
        // AUD-42: a new generation must not inherit the previous playback's
        // pause/mute intent; the channel re-applies its target state the same
        // tick (AUD-41)
        gamePaused = false;
        gameMuted = false;
        playing = true;
        paused = false;
        playedPcmBytes = 0L;
        totalDurationMillis = Math.max(0L, durationHintMillis);
        decodedBytesPerSecond = 0.0D;
        lastVolumeUpdate = System.currentTimeMillis();
        
        playbackThread = new Thread(() -> {
            SourceDataLine playbackLine = null;
            try {
                LOGGER.info("Starting MP3 playback thread");
                
                // Use JLayer's MP3 SPI to decode MP3
                LOGGER.info("Creating MpegAudioFileReader...");
                MpegAudioFileReader reader = new MpegAudioFileReader();
                LOGGER.info("MpegAudioFileReader created successfully");
                
                LOGGER.info("Reading audio input stream from MP3...");
                long detectedDuration = detectDurationMillis(reader, file);
                if (detectedDuration > 0L)
                    totalDurationMillis = detectedDuration;

                try (InputStream inputStream = Files.newInputStream(file);
                        AudioInputStream audioInputStream = reader.getAudioInputStream(new BufferedInputStream(inputStream))) {
                    LOGGER.info("Audio input stream created successfully");
                
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
                    decodedBytesPerSecond = decodedFormat.getFrameRate() * decodedFormat.getFrameSize();
                
                    LOGGER.info("Converting to PCM format...");
                    try (AudioInputStream decodedStream = AudioSystem.getAudioInputStream(decodedFormat, audioInputStream)) {
                    LOGGER.info("PCM conversion successful");
                
                        if (totalDurationMillis <= 0L && audioInputStream.getFrameLength() > 0L &&
                                baseFormat.getFrameRate() > 0.0F) {
                            totalDurationMillis = Math.round(audioInputStream.getFrameLength() * 1000.0D /
                                    baseFormat.getFrameRate());
                        }

                        long effectiveStartMillis = totalDurationMillis > 0L
                                ? Math.floorMod(startPositionMillis, totalDurationMillis) : startPositionMillis;
                        long targetBytes = millisToPcmBytes(effectiveStartMillis, decodedFormat);
                        playedPcmBytes = skipDecodedBytes(decodedStream, targetBytes, decodedFormat.getFrameSize());
                        // AUD-47: audible-position base for this generation
                        positionOffsetMillis = effectiveStartMillis;

                        // Get a line to play the audio
                        DataLine.Info info = new DataLine.Info(SourceDataLine.class, decodedFormat);
                        if (!AudioSystem.isLineSupported(info)) {
                            LOGGER.error("Audio line not supported: {}", info);
                            return;
                        }
                
                        LOGGER.info("Getting audio line...");
                        playbackLine = (SourceDataLine) AudioSystem.getLine(info);
                        if (generation != playbackGeneration.get())
                            return;
                        line = playbackLine;
                        LOGGER.info("Got audio line: {}", playbackLine);
                
                        LOGGER.info("Opening audio line...");
                        playbackLine.open(decodedFormat);
                        LOGGER.info("Audio line opened, buffer size: {}", playbackLine.getBufferSize());
                        // AUD-47: a freshly opened line starts its frame counter at
                        // zero; record the frame rate for position derivation
                        lineFrameRate = decodedFormat.getFrameRate();
                
                        // Set initial volume
                        updateVolume();
                
                        LOGGER.info("Starting audio line...");
                        playbackLine.start();
                        LOGGER.info("Audio line started");
                
                        // Play the audio
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int bytesRead;
                        long totalBytesWritten = 0;
                        long filterRevision = -1L;
                        PcmFilterChain filterChain = PcmFilterChain.create(List.of(), decodedFormat);
                
                        LOGGER.info("Entering playback loop at {} ms...", getPositionMillis());
                        while (generation == playbackGeneration.get() && playing &&
                                (bytesRead = decodedStream.read(buffer)) != -1) {
                            // Handle pause (manual or game pause)
                            while ((paused || gamePaused) && generation == playbackGeneration.get() && playing) {
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }

                            if (generation != playbackGeneration.get() || !playing)
                                break;

                            updateVolumeWithFade();
                            long currentFilterRevision = AudioFilterManager.revision();
                            if (currentFilterRevision != filterRevision) {
                                filterChain = PcmFilterChain.create(AudioFilterManager.activeMbmFilters(), decodedFormat);
                                filterRevision = currentFilterRevision;
                            }
                            filterChain.process(buffer, bytesRead);
                            if (gameMuted)
                                Arrays.fill(buffer, 0, bytesRead, (byte)0);
                            playbackLine.write(buffer, 0, bytesRead);
                            totalBytesWritten += bytesRead;
                            playedPcmBytes += bytesRead;
                        }

                        LOGGER.info("Playback loop ended. Total bytes written: {}, playing: {}", totalBytesWritten, playing);
                    }
                }
                LOGGER.info("Finished playing MP3 stream");
                
            } catch (Throwable e) {
                if (generation == playbackGeneration.get())
                    LOGGER.error("Error playing MP3 stream: {}", e.getMessage(), e);
            } finally {
                if (playbackLine != null) {
                    try {
                        playbackLine.stop();
                        playbackLine.close();
                    } catch (Exception ignored) {
                    }
                }
                if (generation == playbackGeneration.get()) {
                    playing = false;
                    paused = false;
                    line = null;
                }
                LOGGER.info("Playback thread finished");
            }
        });
        
        playbackThread.setName("StreamMusicPlayer");
        playbackThread.setDaemon(true);
        playbackThread.start();
        LOGGER.info("Playback thread started");
    }
    
    /**
     * Stop playback - non-blocking
     */
    public void stop() {
        playbackGeneration.incrementAndGet();
        // AUD-42: stop() resets the game pause/mute intent so no future
        // generation inherits it
        gamePaused = false;
        gameMuted = false;
        playing = false;
        paused = false;
        playedPcmBytes = 0L;
        totalDurationMillis = 0L;
        decodedBytesPerSecond = 0.0D;
        // AUD-47: reset the audible-position base with the generation
        positionOffsetMillis = 0L;
        lineFrameRate = 0.0F;
        
        SourceDataLine activeLine = line;
        line = null;
        if (activeLine != null && activeLine.isOpen()) {
            activeLine.stop();
            activeLine.close();
        }
    }

    /**
     * AUD-47: audible position, derived from the frames the line has actually
     * played (SourceDataLine.getLongFramePosition), plus the start/seek offset.
     * A stopped (paused) line does not advance its frame counter.
     */
    public long getPositionMillis() {
        SourceDataLine activeLine = line;
        float rate = this.lineFrameRate;
        if (activeLine == null || !activeLine.isOpen() || rate <= 0.0F)
            return positionOffsetMillis;
        long frames = activeLine.getLongFramePosition();
        return positionOffsetMillis + Math.round(frames * 1000.0D / rate);
    }

    /**
     * AUD-47: decoded (written) position, kept as a diagnostic quantity for
     * the probe and buffer diagnostics only.
     */
    public long getDecodedPositionMillis() {
        double bytesPerSecond = decodedBytesPerSecond;
        return bytesPerSecond <= 0.0D ? 0L : Math.max(0L, Math.round(playedPcmBytes * 1000.0D / bytesPerSecond));
    }

    public long getDurationMillis() {
        return totalDurationMillis;
    }

    private static long detectDurationMillis(MpegAudioFileReader reader, Path file) {
        try {
            AudioFileFormat format = reader.getAudioFileFormat(file.toFile());
            Object duration = format.properties().get("duration");
            if (duration instanceof Number number)
                return Math.max(0L, number.longValue() / 1000L);
            if (format.getFrameLength() > 0 && format.getFormat().getFrameRate() > 0.0F)
                return Math.round(format.getFrameLength() * 1000.0D / format.getFormat().getFrameRate());
        } catch (Exception e) {
            LOGGER.debug("Unable to read MP3 duration for {}", file, e);
        }
        return 0L;
    }

    private static long millisToPcmBytes(long millis, AudioFormat format) {
        if (millis <= 0L)
            return 0L;
        long bytes = Math.round(millis / 1000.0D * format.getFrameRate() * format.getFrameSize());
        return bytes - bytes % format.getFrameSize();
    }

    private static long skipDecodedBytes(AudioInputStream stream, long targetBytes, int frameSize) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        long skipped = 0L;
        while (skipped < targetBytes) {
            int requested = (int)Math.min(buffer.length, targetBytes - skipped);
            requested -= requested % frameSize;
            if (requested <= 0)
                break;
            int read = stream.read(buffer, 0, requested);
            if (read < 0)
                break;
            skipped += read;
        }
        return skipped;
    }
    
    /**
     * Pause playback
     */
    public void pause() {
        // AUD-42: unconditional flag write; only hardware ops keep liveness
        // checks
        paused = true;
        if (line != null && line.isOpen()) {
            line.stop();
        }
    }
    
    /**
     * Resume playback
     */
    public void resume() {
        // AUD-42: unconditional flag write; only hardware ops keep liveness
        // checks
        paused = false;
        if (line != null && line.isOpen()) {
            line.start();
        }
    }
    
    /**
     * Pause playback due to game pause
     */
    public void pauseForGame() {
        // AUD-42: gamePaused is a projection of channel intent, not the
        // player's own state - the write is unconditional
        gamePaused = true;
        if (line != null && line.isOpen()) {
            line.stop();
        }
        LOGGER.debug("Paused music due to game pause");
    }
    
    /**
     * Resume playback after game unpause
     */
    public void resumeFromGame() {
        // AUD-42: unconditional flag write; the old `if (playing && gamePaused)`
        // guard swallowed cleanup because stop() clears playing first
        gamePaused = false;
        if (line != null && line.isOpen()) {
            line.start();
        }
        LOGGER.debug("Resumed music after game unpause");
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
     * AUD-43: liveness predicate, independent of pause/mute. "Is there an
     * active track" vs. isPlaying()'s "is it currently audible".
     */
    public boolean hasActiveTrack() {
        return playing;
    }

    public void setMutedForGame(boolean muted) {
        // AUD-42: unconditional intent projection; the gain is applied via
        // updateVolumeOutput() regardless of playback state
        this.gameMuted = muted;
        updateVolumeOutput();
    }

    public boolean isMutedForGame() {
        return this.gameMuted;
    }
    
    /**
     * Set target volume for fade effect (0.0 to 1.0)
     * @param volume Target volume
     */
    public void setTargetVolume(float volume) {
        this.targetVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }
    
    /**
     * AUD-24 v1.2: fade the gain to the given target over the given duration,
     * independent of the default fadeTime used at playback start/stop. The
     * default fadeTime behaviour is unchanged. Completion is observable via
     * {@link #getCurrentVolume()} (exactly 0.0F for a zero target) or
     * {@link #isFadeToActive()}.
     */
    public void fadeTo(float target, long durationMillis) {
        float clamped = Math.max(0.0f, Math.min(1.0f, target));
        this.fadeFromVolume = this.currentVolume;
        this.fadeToVolume = clamped;
        this.fadeStartMillis = System.currentTimeMillis();
        this.fadeDurationMillis = Math.max(1L, durationMillis);
        this.targetVolume = clamped;
    }
    
    /**
     * True while a fadeTo() fade is still in progress.
     */
    public boolean isFadeToActive() {
        return this.fadeDurationMillis != 0L;
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
        
        // AUD-24 v1.2: per-call fade takes precedence over the default fadeTime
        long activeFade = this.fadeDurationMillis;
        if (activeFade > 0L) {
            float progress = (float)((currentTime - this.fadeStartMillis) / (double)activeFade);
            if (progress >= 1.0F) {
                this.currentVolume = this.fadeToVolume;
                this.fadeDurationMillis = 0L;
            } else {
                this.currentVolume = this.fadeFromVolume
                        + (this.fadeToVolume - this.fadeFromVolume) * progress;
            }
        } else if (fadeTime > 0 && currentVolume != targetVolume) {
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
            float finalVolume = this.gameMuted ? 0.0F : masterVolume * musicVolume * currentVolume;
            
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
            float masterVolume = mc.options.getSoundSourceVolume(SoundSource.MASTER);
            float musicVolume = mc.options.getSoundSourceVolume(SoundSource.RECORDS);
            
            float targetVolume = this.gameMuted ? 0.0F : masterVolume * musicVolume;
            
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

    private void updateVolumeOutput() {
        if (this.fadeTime > 0)
            updateVolumeWithFade();
        else
            updateVolume();
    }
}
