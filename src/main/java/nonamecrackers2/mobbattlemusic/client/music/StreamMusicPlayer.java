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
    private volatile int fadeTime = 0; // Fade time in ticks (20 ticks = 1 second)
    // AUD-54: play() invocation count for the probe ring (N2 forensics)
    private final java.util.concurrent.atomic.AtomicLong playCalls = new java.util.concurrent.atomic.AtomicLong();
    // AUD-44: independent gain envelopes; current is advanced only by the
    // playback thread (AUD-45). MUTE_ENV is shared with the built-in leg.
    private final Envelope trackEnv = new Envelope(0.0f);
    private final Envelope seekEnv = new Envelope(1.0f);
    public static final Envelope MUTE_ENV = new Envelope(1.0f);
    // AUD-49 #5/#8: fade-in duration for the next track start, set by a gated
    // switch whose action only stops the old source; consumed by play() and
    // expired after 2000ms if unconsumed. 0 = the default fadeTime applies.
    private static volatile long pendingTrackFadeInMillis;
    private static volatile long pendingTrackFadeInSetAtMillis;
    // AUD-49 #7: ownership check registered by the main playback channel;
    // other writers of a gate-owned envelope must be no-ops
    private static volatile java.util.function.Predicate<Envelope> gateOwnershipCheck = env -> false;
    private volatile SourceDataLine line;
    private final AtomicLong playbackGeneration = new AtomicLong();
    private volatile long playedPcmBytes;
    private volatile long totalDurationMillis;
    private volatile double decodedBytesPerSecond;
    // AUD-47: audible-position base (start/seek offset) and frame rate of the
    // current line; getLongFramePosition() counts frames already played
    private volatile long positionOffsetMillis;
    private volatile float lineFrameRate;
    // AUD-48 v1.4: adaptive output watermark. Starts at 60ms; each underrun
    // raises it by 20ms up to 150ms. The converged value is kept in a static
    // field so it survives startPlayback; reset() clears it.
    private static volatile long adaptiveWatermarkMillis;
    private volatile long lineWatermarkBytes;
    // AUD-30 v1.5: cumulative underrun count since the last playback start
    private volatile long underruns;
    // AUD-52 修订: first time the line fill reached the watermark in the
    // current generation (seek cost measurement)
    private volatile long lastWatermarkReachedAtMillis;
    
    /**
     * AUD-44/45: a single gain envelope, shape
     * (startValue, target, startMillis, durationMillis), advanced by linear
     * interpolation. AUD-45 v1.1: the current value is a pure function of
     * time - computed on read, no advancement role, no dependency on any
     * thread being alive or scheduled. setTarget starts a new fade only when
     * the target actually changes (judged inside the envelope).
     */
    public static final class Envelope {
        private volatile float target;
        private volatile float startValue;
        private volatile long startMillis;
        private volatile long durationMillis;
        
        public Envelope(float initial) {
            this.target = initial;
            this.startValue = initial;
            this.startMillis = System.currentTimeMillis();
            this.durationMillis = 0L;
        }
        
        public void setTarget(float newTarget, long fadeMillis) {
            float clamped = Math.max(0.0f, Math.min(1.0f, newTarget));
            // AUD-45: judgment stays inside the envelope
            if (clamped == this.target)
                return;
            // AUD-45 v1.1: startValue is the computed value at call time
            this.startValue = this.current();
            this.startMillis = System.currentTimeMillis();
            this.durationMillis = Math.max(0L, fadeMillis);
            this.target = clamped;
        }
        
        public float current() {
            long d = this.durationMillis;
            float t = this.target;
            if (d <= 0L)
                return t;
            float progress = (float)((System.currentTimeMillis() - this.startMillis) / (double)d);
            if (progress >= 1.0f)
                return t;
            return this.startValue + (t - this.startValue) * progress;
        }
        
        public float target() {
            return this.target;
        }
        
        /**
         * AUD-45 v1.2: contract fade - always restarts a fade of the given
         * duration, no target-equality early-out. Used where a determinate
         * audible fade is required (gate fade-out, unconditional start).
         */
        public void forceFade(float newTarget, long fadeMillis) {
            float clamped = Math.max(0.0f, Math.min(1.0f, newTarget));
            this.startValue = this.current();
            this.startMillis = System.currentTimeMillis();
            this.durationMillis = Math.max(0L, fadeMillis);
            this.target = clamped;
        }
        
        /**
         * AUD-49 #3: cancel-path reset of target AND start state. Pure
         * function values make this safe from any thread.
         */
        public void hardReset(float value) {
            float clamped = Math.max(0.0f, Math.min(1.0f, value));
            this.target = clamped;
            this.startValue = clamped;
            this.startMillis = System.currentTimeMillis();
            this.durationMillis = 0L;
        }
    }
    
    public Envelope trackEnv() {
        return this.trackEnv;
    }
    
    public Envelope seekEnv() {
        return this.seekEnv;
    }

    // AUD-49 #5: register the fade-in duration for the next track start
    // (consumed by play())
    public static void setPendingTrackFadeInMillis(long millis) {
        StreamMusicPlayer.pendingTrackFadeInMillis = Math.max(0L, millis);
        StreamMusicPlayer.pendingTrackFadeInSetAtMillis = System.currentTimeMillis();
    }

    public static void clearPendingTrackFadeInMillis() {
        StreamMusicPlayer.pendingTrackFadeInMillis = 0L;
        StreamMusicPlayer.pendingTrackFadeInSetAtMillis = 0L;
    }

    // AUD-49 #8: a pending start fade-in that is not consumed within 2000ms
    // is cleared; no indefinitely hanging cross-event state
    public static void expirePendingTrackFadeInMillis(long nowMillis) {
        if (StreamMusicPlayer.pendingTrackFadeInMillis > 0L
                && nowMillis - StreamMusicPlayer.pendingTrackFadeInSetAtMillis > 2000L) {
            StreamMusicPlayer.pendingTrackFadeInMillis = 0L;
            StreamMusicPlayer.pendingTrackFadeInSetAtMillis = 0L;
            LOGGER.debug("[MBM] AUD-49 #8 pending track fade-in expired");
        }
    }

    // AUD-49 #7: ownership predicate registered by the main playback channel
    public static void setGateOwnershipCheck(java.util.function.Predicate<Envelope> check) {
        StreamMusicPlayer.gateOwnershipCheck = check == null ? env -> false : check;
    }
    
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
        // AUD-54: count play() invocations (probe ring)
        this.playCalls.incrementAndGet();
        // AUD-44 v1.1: every start unconditionally restarts the track envelope
        // from zero; the fade-in duration is the gate-registered one, or the
        // player default fadeTime. Never depends on envelope history.
        long pendingFadeIn = StreamMusicPlayer.pendingTrackFadeInMillis;
        long fadeInMillis = pendingFadeIn > 0L ? pendingFadeIn : Math.max(0L, fadeTimeInTicks) * 50L;
        StreamMusicPlayer.pendingTrackFadeInMillis = 0L;
        StreamMusicPlayer.pendingTrackFadeInSetAtMillis = 0L;
        this.trackEnv.setTarget(0.0f, 0L);
        this.trackEnv.setTarget(1.0f, fadeInMillis);
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
        
        playbackThread = new Thread(() -> {
            SourceDataLine playbackLine = null;
            try {
                LOGGER.debug("Starting MP3 playback thread");
                
                // Use JLayer's MP3 SPI to decode MP3
                LOGGER.debug("Creating MpegAudioFileReader...");
                MpegAudioFileReader reader = new MpegAudioFileReader();
                LOGGER.debug("MpegAudioFileReader created successfully");
                
                LOGGER.debug("Reading audio input stream from MP3...");
                long detectedDuration = detectDurationMillis(reader, file);
                if (detectedDuration > 0L)
                    totalDurationMillis = detectedDuration;

                try (InputStream inputStream = Files.newInputStream(file);
                        AudioInputStream audioInputStream = reader.getAudioInputStream(new BufferedInputStream(inputStream))) {
                    LOGGER.debug("Audio input stream created successfully");
                
                    // Get the audio format
                    AudioFormat baseFormat = audioInputStream.getFormat();
                    LOGGER.debug("Base audio format: {}", baseFormat);
                
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
                    LOGGER.debug("Decoded audio format: {}", decodedFormat);
                    decodedBytesPerSecond = decodedFormat.getFrameRate() * decodedFormat.getFrameSize();
                
                    LOGGER.debug("Converting to PCM format...");
                    try (AudioInputStream decodedStream = AudioSystem.getAudioInputStream(decodedFormat, audioInputStream)) {
                    LOGGER.debug("PCM conversion successful");
                
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
                
                        LOGGER.debug("Getting audio line...");
                        playbackLine = (SourceDataLine) AudioSystem.getLine(info);
                        if (generation != playbackGeneration.get())
                            return;
                        line = playbackLine;
                        LOGGER.debug("Got audio line: {}", playbackLine);
                
                        LOGGER.debug("Opening audio line...");
                        // AUD-48 v1.4: explicit capacity (500ms) separated from
                        // the adaptive watermark (60ms start, +20ms per
                        // underrun, cap 150ms); capacity is the underrun
                        // reserve, the watermark decides actual latency
                        long capacityBytes = Math.max(1L, Math.round(
                                decodedFormat.getFrameRate() * decodedFormat.getFrameSize() * 0.5D));
                        playbackLine.open(decodedFormat, (int)Math.min(Integer.MAX_VALUE, capacityBytes));
                        LOGGER.debug("Audio line opened, buffer size: {}", playbackLine.getBufferSize());
                        long watermarkMillis = StreamMusicPlayer.adaptiveWatermarkMillis > 0L
                                ? StreamMusicPlayer.adaptiveWatermarkMillis : 60L;
                        lineWatermarkBytes = Math.max(1L, Math.round(
                                decodedFormat.getFrameRate() * decodedFormat.getFrameSize()
                                        * watermarkMillis / 1000.0D));
                        underruns = 0L;
                        lastWatermarkReachedAtMillis = 0L;
                        // AUD-47: a freshly opened line starts its frame counter at
                        // zero; record the frame rate for position derivation
                        lineFrameRate = decodedFormat.getFrameRate();
                        // Gain is applied by the playback loop (AUD-45 single entry)

                        LOGGER.debug("Starting audio line...");
                        playbackLine.start();
                        LOGGER.debug("Audio line started");
                
                        // Play the audio
                        byte[] buffer = new byte[BUFFER_SIZE];
                        int bytesRead;
                        long totalBytesWritten = 0;
                        long filterRevision = -1L;
                        PcmFilterChain filterChain = PcmFilterChain.create(List.of(), decodedFormat);
                
                        LOGGER.debug("Entering playback loop at {} ms...", getPositionMillis());
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

                            // AUD-30 v1.6: only count fully-drained writes
                            // after the first successful write
                            if (totalBytesWritten > 0L && playbackLine.available() >= playbackLine.getBufferSize()) {
                                underruns++;
                                // AUD-48 v1.4: adaptive watermark, +20ms per
                                // underrun, capped at 150ms; persists across
                                // startPlayback via the static field
                                long current = StreamMusicPlayer.adaptiveWatermarkMillis > 0L
                                        ? StreamMusicPlayer.adaptiveWatermarkMillis : 60L;
                                long next = Math.min(150L, current + 20L);
                                if (next != current) {
                                    StreamMusicPlayer.adaptiveWatermarkMillis = next;
                                    this.lineWatermarkBytes = Math.max(1L, Math.round(
                                            this.lineFrameRate * decodedFormat.getFrameSize()
                                                    * next / 1000.0D));
                                }
                            }
                            // AUD-48: write throttling to the target watermark.
                            // Yield briefly while the fill exceeds the
                            // watermark; never fill the buffer. When playback
                            // drains faster than we decode, the condition fails
                            // and we write immediately (no added underrun risk).
                            while (generation == playbackGeneration.get() && playing && !paused && !gamePaused) {
                                int capacity = playbackLine.getBufferSize();
                                int filled = capacity - playbackLine.available();
                                if (filled <= this.lineWatermarkBytes || filled < BUFFER_SIZE) {
                                    // AUD-52 修订: first time this generation's
                                    // fill reaches the watermark (seek cost)
                                    if (this.lastWatermarkReachedAtMillis == 0L
                                            && filled >= this.lineWatermarkBytes)
                                        this.lastWatermarkReachedAtMillis = System.currentTimeMillis();
                                    break;
                                }
                                Thread.sleep(2);
                            }
                            if (generation != playbackGeneration.get() || !playing)
                                break;

                            // AUD-45 v1.1: envelope values are pure functions
                            // of time, computed on read inside applyGain
                            applyGain();
                            long currentFilterRevision = AudioFilterManager.revision();
                            if (currentFilterRevision != filterRevision) {
                                // AUD-48: rebuild with state preservation
                                // (same type+params keep their state; otherwise
                                // a >=20ms crossfade runs)
                                filterChain = PcmFilterChain.create(AudioFilterManager.activeMbmFilters(),
                                        decodedFormat, filterChain);
                                filterRevision = currentFilterRevision;
                            }
                            filterChain.process(buffer, bytesRead);
                            // AUD-44: sample-zeroing is allowed only once the
                            // mute envelope has reached zero (no hard cut while
                            // fading)
                            if (MUTE_ENV.current() <= 0.001f)
                                Arrays.fill(buffer, 0, bytesRead, (byte)0);
                            playbackLine.write(buffer, 0, bytesRead);
                            totalBytesWritten += bytesRead;
                            playedPcmBytes += bytesRead;
                        }

                        LOGGER.debug("Playback loop ended. Total bytes written: {}, playing: {}", totalBytesWritten, playing);
                    }
                }
                LOGGER.debug("Finished playing MP3 stream");
                
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
                LOGGER.debug("Playback thread finished");
            }
        });
        
        playbackThread.setName("StreamMusicPlayer");
        playbackThread.setDaemon(true);
        playbackThread.start();
        LOGGER.debug("Playback thread started");
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
        lineWatermarkBytes = 0L;
        underruns = 0L;
        
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

    // AUD-48: line diagnostics for the probe (world.line)
    public int getLineBufferBytes() {        SourceDataLine activeLine = line;
        return activeLine == null || !activeLine.isOpen() ? 0 : activeLine.getBufferSize();
    }

    public int getLineAvailableBytes() {
        SourceDataLine activeLine = line;
        return activeLine == null || !activeLine.isOpen() ? 0 : activeLine.available();
    }

    public long getLineWatermarkBytes() {
        return this.lineWatermarkBytes;
    }

    // AUD-30 v1.5: cumulative underrun count since the last playback start
    public long getUnderruns() {
        return this.underruns;
    }

    // AUD-54: play() invocation count (probe ring forensics)
    public long getPlayCallCount() {
        return this.playCalls.get();
    }

    // AUD-52 修订: first watermark fill of the current generation (seek cost)
    public long getLastWatermarkReachedAtMillis() {
        return this.lastWatermarkReachedAtMillis;
    }

    // AUD-48 v1.4: has the adaptive watermark converged above the 60ms start?
    public static boolean isWatermarkAdaptive() {
        return StreamMusicPlayer.adaptiveWatermarkMillis > 0L;
    }

    // AUD-48 v1.4: reset the converged watermark (world unload)
    public static void resetAdaptiveWatermark() {
        StreamMusicPlayer.adaptiveWatermarkMillis = 0L;
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
        // AUD-42/45: pure target projection; no gain computation on this
        // thread. The mute envelope fades in/out over 200ms (AUD-46).
        this.gameMuted = muted;
        MUTE_ENV.setTarget(muted ? 0.0f : 1.0f, 200L);
    }

    public boolean isMutedForGame() {
        return this.gameMuted;
    }
    
    /**
     * Set target volume of the track envelope (track fade in/out, AUD-44).
     * The fade duration follows the playback fadeTime; a new fade starts only
     * when the target actually changes (judged inside the envelope).
     * AUD-49 #7: while the gate owns the track envelope, this writer is a
     * no-op with a log line.
     */
    public void setTargetVolume(float volume) {
        float clamped = Math.max(0.0f, Math.min(1.0f, volume));
        if (StreamMusicPlayer.gateOwnershipCheck.test(this.trackEnv)) {
            LOGGER.debug("[MBM] AUD-49 #7 setTargetVolume suppressed (gate owns trackEnv, requested={})", clamped);
            return;
        }
        this.trackEnv.setTarget(clamped, Math.max(0L, this.fadeTime) * 50L);
    }
    
    /**
     * Current track-envelope gain (0.0 to 1.0)
     */
    public float getCurrentVolume() {
        return this.trackEnv.current();
    }
    
    /**
     * AUD-45: the single gain computation of the process, called from the
     * playback thread main loop only. finalGain = master × music ×
     * trackEnv × muteEnv × seekEnv (AUD-44).
     */
    private void applyGain() {
        if (line == null || !line.isOpen())
            return;
        try {
            Minecraft mc = Minecraft.getInstance();
            float masterVolume = mc.options.getSoundSourceVolume(SoundSource.MASTER);
            float musicVolume = mc.options.getSoundSourceVolume(SoundSource.RECORDS);
            float finalGain = masterVolume * musicVolume
                    * this.trackEnv.current() * MUTE_ENV.current() * this.seekEnv.current();
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gainControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                float dB = (float) (Math.log(Math.max(0.0001f, finalGain)) / Math.log(10.0) * 20.0);
                dB = Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), dB));
                gainControl.setValue(dB);
            }
        } catch (Exception e) {
            // Ignore volume control errors
        }
    }
}