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
    // K8-B: single-lifecycle primitive for one playback generation. Invariant:
    // open SourceDataLine count <= 1 AND live playback generation count <= 1
    // PER PLAYER. closeLineOnce() is the only closer and is idempotent
    // (closeRequested gates it), so stop() and the playback thread's finally
    // never race into a double close.
    private static final class PlaybackGeneration {
        final long id;
        volatile SourceDataLine line;
        volatile boolean closeRequested;
        volatile boolean closed;
        PlaybackGeneration(long id) { this.id = id; }
    }

    private Thread playbackThread;
    private volatile boolean playing = false;
    private volatile boolean paused = false;
    private volatile boolean gamePaused = false; // Track game pause state
    private volatile boolean gameMuted = false;
    private volatile int fadeTime = 0; // Fade time in ticks (20 ticks = 1 second)
    // AUD-54: play() invocation count for the probe ring (N2 forensics)
    private final java.util.concurrent.atomic.AtomicLong playCalls = new java.util.concurrent.atomic.AtomicLong();
    // AUD-44: independent gain envelopes; current is advanced only by the
    // playback thread (AUD-45). MUTE_ENV is the main channel's shared mute
    // envelope (sound leg + main player); K10-C: the preview player owns a
    // PRIVATE mute envelope so the main channel's mute never silences it.
    private final Envelope trackEnv = new Envelope(0.0f);
    private final Envelope seekEnv = new Envelope(1.0f);
    public static final Envelope MUTE_ENV = new Envelope(1.0f);
    private final Envelope muteEnv;
    // K9-3: independent persistent user gain (main playback) and preview gain.
    // These are NOT trackEnv - they are pure multipliers in the gain chain and
    // survive track switches, seeks and gates untouched. The preview player's
    // chain uses both (preview follows main multiplies both).
    private final Envelope userGainEnv = new Envelope(1.0f);
    private final Envelope previewGainEnv = new Envelope(1.0f);
    // K10-C: per-player open-line and playback-thread counters - the probe
    // reports main and preview separately (legal parallel playback must not
    // trip the single-line invariant of either player)
    private final java.util.concurrent.atomic.AtomicInteger openLines = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger playbackThreads = new java.util.concurrent.atomic.AtomicInteger();
    // AUD-49 #5/#8: fade-in duration for the next track start, set by a gated
    // switch whose action only stops the old source; consumed by play() and
    // expired after 2000ms if unconsumed. 0 = the default fadeTime applies.
    private static volatile long pendingTrackFadeInMillis;
    private static volatile long pendingTrackFadeInSetAtMillis;
    // AUD-49 #7: ownership check registered by the main playback channel;
    // other writers of a gate-owned envelope must be no-ops
    private static volatile java.util.function.Predicate<Envelope> gateOwnershipCheck = env -> false;
    // K8-B: the line reference lives on the PlaybackGeneration - pause/resume/
    // position reads take it from the current generation, so an old playback
    // thread can never operate a newer generation's line through a shared
    // field
    private volatile PlaybackGeneration currentGeneration;
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
    // AUD-52 v1.3/R5: the cross-generation watermark timestamp is published
    // together with its generation tag as one immutable record, written with
    // the local generation variable of startPlayback (never with a re-read of
    // playbackGeneration, which stop() may have advanced already)
    public record WatermarkStamp(long generation, long millis) {
        public static final WatermarkStamp EMPTY = new WatermarkStamp(-1L, 0L);
    }
    private volatile WatermarkStamp watermarkStamp = WatermarkStamp.EMPTY;
    // AUD-48 v1.5: underrun transient-window suppression and the downward
    // adaptive path (consecutive 10s without an underrun -> -10ms, floor 60ms)
    private static final long UNDERRUN_TRANSIENT_BUFFERS = 8L;
    private static final long WATERMARK_DOWN_MILLIS = 10L;
    private static final long WATERMARK_DOWN_PERIOD_MILLIS = 10_000L;
    private volatile long bufferIndex;
    private volatile long resumeBufferIndex = -1L;
    // AUD-48 v1.6: the last-underrun timestamp shares the lifecycle of the
    // adaptive watermark (static, cleared only by resetAdaptiveWatermark)

    // K3 P2-1: the down-path timing base, one bit one meaning - an underrun
    // restarts it, a lowering restarts it; cleared only with the watermark
    private static volatile long lastWatermarkDownAtMillis;
    
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
    
    public StreamMusicPlayer() {
        this(true);
    }

    /**
     * K10-C: sharedMute=true binds this player's mute envelope to the static
     * MUTE_ENV (main playback + sound leg); sharedMute=false gives the player
     * its own mute envelope so the world channel's mute never silences it
     * (preview player).
     */
    public StreamMusicPlayer(boolean sharedMute) {
        this.muteEnv = sharedMute ? MUTE_ENV : new Envelope(1.0f);
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
        // K8-B: the new generation must not open a line while the old one is
        // still alive - cancel the old generation, close its line (close-once)
        // and wait for the old playback thread to exit. K11-C: a thread that
        // does not exit within the bound REFUSES the new generation - the
        // single-line/single-thread invariant is strict.
        if (!stopAndAwaitThreadExit())
            return;
        long generationId = playbackGeneration.incrementAndGet();
        PlaybackGeneration generation = new PlaybackGeneration(generationId);
        this.currentGeneration = generation;
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
        
        this.playbackThreads.incrementAndGet();
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
                        // K8-B: generation check BEFORE the potentially
                        // blocking line acquisition
                        if (generation != this.currentGeneration)
                            return;
                        playbackLine = (SourceDataLine) AudioSystem.getLine(info);
                        if (generation != this.currentGeneration)
                            return;
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
                        // K8-B: the line joins THIS generation only; the
                        // open-lines counter is incremented here and
                        // decremented exactly once by closeLineOnce
                        generation.line = playbackLine;
                        this.openLines.incrementAndGet();
                        long watermarkMillis = StreamMusicPlayer.adaptiveWatermarkMillis > 0L
                                ? StreamMusicPlayer.adaptiveWatermarkMillis : 60L;
                        lineWatermarkBytes = Math.max(1L, Math.round(
                                decodedFormat.getFrameRate() * decodedFormat.getFrameSize()
                                        * watermarkMillis / 1000.0D));
                        underruns = 0L;
                        // AUD-52 v1.3/R5: the watermark stamp is reset for the
                        // new generation; it will be published with the local
                        // generation variable once the fill reaches the
                        // watermark
                        this.watermarkStamp = WatermarkStamp.EMPTY;
                        // AUD-48 v1.5: transient-window tracking starts per generation
                        bufferIndex = 0L;
                        resumeBufferIndex = -1L;
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
                        outerLoop:
                        while (generation == this.currentGeneration && playing &&
                                (bytesRead = decodedStream.read(buffer)) != -1) {
                            // Handle pause (manual or game pause)
                            // AUD-48 v1.6: paused and gamePaused are treated
                            // alike for the underrun transient window.
                            // K3 P1-3: park instead of sleep - resume latency
                            // drops from up to 100ms (sleep quantum) to ~0
                            boolean suspended = false;
                            while ((paused || gamePaused) && generation == this.currentGeneration && playing) {
                                suspended = true;
                                java.util.concurrent.locks.LockSupport.park(this);
                                if (Thread.interrupted()) {
                                    // K4 P2-3: an interrupt must leave the
                                    // decode loop entirely - never keep
                                    // writing audio while paused
                                    Thread.currentThread().interrupt();
                                    break outerLoop;
                                }
                            }
                            // AUD-48 v1.5: remember the resume point for the
                            // underrun transient window
                            if (suspended && !(paused || gamePaused))
                                this.resumeBufferIndex = this.bufferIndex;

                            if (generation != this.currentGeneration || !playing)
                                break;

                            // AUD-48 v1.5/v1.6: the adaptive watermark has a
                            // downward path - 10s without an underrun lowers it
                            // by 10ms. K3 P2-1: the timing base is a separate
                            // flag (lastWatermarkDownAtMillis), not the
                            // underrun moment - one bit, one meaning. This
                            // runs on the playback thread, so the watermark
                            // only adapts while music is actually playing.
                            long nowMillis = System.currentTimeMillis();
                            long currentWatermark = StreamMusicPlayer.adaptiveWatermarkMillis > 0L
                                    ? StreamMusicPlayer.adaptiveWatermarkMillis : 60L;
                            if (currentWatermark > 60L && StreamMusicPlayer.lastWatermarkDownAtMillis > 0L
                                    && nowMillis - StreamMusicPlayer.lastWatermarkDownAtMillis
                                            >= WATERMARK_DOWN_PERIOD_MILLIS) {
                                long lowered = Math.max(60L, currentWatermark - WATERMARK_DOWN_MILLIS);
                                StreamMusicPlayer.adaptiveWatermarkMillis = lowered;
                                this.lineWatermarkBytes = Math.max(1L, Math.round(
                                        this.lineFrameRate * decodedFormat.getFrameSize()
                                                * lowered / 1000.0D));
                                StreamMusicPlayer.lastWatermarkDownAtMillis = nowMillis;
                                LOGGER.debug("[MBM] AUD-48 v1.5 watermark lowered to {}ms", lowered);
                            }

                            // AUD-30 v1.6/v1.5: count fully-drained writes only
                            // after the first successful write and outside the
                            // transient windows (startup, pause resume, seek -
                            // the latter resets bufferIndex via startPlayback)
                            boolean transientWindow = this.bufferIndex < UNDERRUN_TRANSIENT_BUFFERS
                                    || (this.resumeBufferIndex >= 0L
                                            && this.bufferIndex - this.resumeBufferIndex < UNDERRUN_TRANSIENT_BUFFERS);
                            if (totalBytesWritten > 0L && !transientWindow
                                    && playbackLine.available() >= playbackLine.getBufferSize()) {
                                underruns++;

                                // K3 P2-1: an underrun restarts the down-path
                                // timing base
                                StreamMusicPlayer.lastWatermarkDownAtMillis = System.currentTimeMillis();
                                // AUD-48 v1.4: adaptive watermark, +20ms per
                                // underrun, capped at 150ms; persists across
                                // startPlayback via the static field
                                long next = Math.min(150L, currentWatermark + 20L);
                                if (next != currentWatermark) {
                                    StreamMusicPlayer.adaptiveWatermarkMillis = next;
                                    this.lineWatermarkBytes = Math.max(1L, Math.round(
                                            this.lineFrameRate * decodedFormat.getFrameSize()
                                                    * next / 1000.0D));
                                }
                            }
                            this.bufferIndex++;
                            // AUD-48: write throttling to the target watermark.
                            // Yield briefly while the fill exceeds the
                            // watermark; never fill the buffer. When playback
                            // drains faster than we decode, the condition fails
                            // and we write immediately (no added underrun risk).
                            while (generation == this.currentGeneration && playing && !paused && !gamePaused) {
                                int capacity = playbackLine.getBufferSize();
                                int filled = capacity - playbackLine.available();
                                if (filled <= this.lineWatermarkBytes || filled < BUFFER_SIZE) {
                                    // AUD-52 v1.3/R5: publish the watermark
                                    // stamp once per generation, using the
                                    // local generation variable - never a
                                    // re-read of playbackGeneration (stop()
                                    // advances it before the new line opens)
                                    if (this.watermarkStamp.generation() < 0L
                                            && filled >= this.lineWatermarkBytes)
                                        this.watermarkStamp = new WatermarkStamp(generation.id,
                                                System.currentTimeMillis());
                                    break;
                                }
                                Thread.sleep(2);
                            }
                            if (generation != this.currentGeneration || !playing)
                                break;

                            // AUD-45 v1.1: envelope values are pure functions
                            // of time, computed on read inside applyGain
                            applyGain(playbackLine, generation);
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
                            if (this.muteEnv.current() <= 0.001f)
                                Arrays.fill(buffer, 0, bytesRead, (byte)0);
                            playbackLine.write(buffer, 0, bytesRead);
                            // K8-B: generation check after the potentially
                            // blocking write
                            if (generation != this.currentGeneration)
                                break;
                            totalBytesWritten += bytesRead;
                            playedPcmBytes += bytesRead;
                        }

                        LOGGER.debug("Playback loop ended. Total bytes written: {}, playing: {}", totalBytesWritten, playing);
                    }
                }
                LOGGER.debug("Finished playing MP3 stream");
                
            } catch (Throwable e) {
                if (generation == this.currentGeneration) {
                    LOGGER.error("Error playing MP3 stream: {}", e.getMessage(), e);
                } else if (isExpectedCancel(e)) {
                    // K8-B: an expected line-closed exception from a
                    // generation cancelled by stop()/switch is debug-level
                    LOGGER.debug("[MBM] stale generation {} line closed as expected: {}", generation.id, e.toString());
                } else {
                    // K8-B: an unexpected throwable from an old generation is
                    // NEVER swallowed - report it with the full lifecycle
                    // context
                    SourceDataLine staleLine = generation.line;
                    LOGGER.error("[MBM] stale-generation throwable gen={} currentGen={} lineOpen={} "
                                    + "sessionGen={} levelGen={} exceptionClass={}",
                            generation.id, this.playbackGeneration.get(),
                            staleLine != null && staleLine.isOpen(),
                            nonamecrackers2.mobbattlemusic.client.audio.WorldPlaybackChannel.sessionGeneration(),
                            nonamecrackers2.mobbattlemusic.client.audio.WorldPlaybackChannel.levelGeneration(),
                            e.getClass().getName(), e);
                }
            } finally {
                // K8-B: close-once - idempotent with stop()'s close
                closeLineOnce(generation);
                if (generation == this.currentGeneration) {
                    playing = false;
                    paused = false;
                    this.currentGeneration = null;
                }
                this.playbackThreads.decrementAndGet();
                LOGGER.debug("Playback thread finished");
            }
        });
        
        playbackThread.setName("StreamMusicPlayer");
        playbackThread.setDaemon(true);
        playbackThread.start();
        LOGGER.debug("Playback thread started");
    }
    
    /**
     * K8-B/K11-C: stop playback and wait for the old playback thread to exit
     * so a new generation can open its line safely (open-lines invariant
     * <= 1). The wait is bounded (2s). If the old thread does not exit in
     * time, the new generation is REFUSED (never started) - the invariant is
     * strict, not best-effort. Must only be invoked from MBM-Audio-IO or
     * another background thread, never from the client main thread.
     *
     * @return true when the old thread has exited and a new generation may
     *         start; false when the old thread is still alive
     */
    private boolean stopAndAwaitThreadExit() {
        this.stop();
        Thread oldThread = this.playbackThread;
        if (oldThread != null && oldThread != Thread.currentThread() && oldThread.isAlive()) {
            try {
                oldThread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (oldThread.isAlive()) {
                LOGGER.error("[MBM] K11-C refusing to start a new generation: "
                        + "previous playback thread {} is still alive after 2000ms "
                        + "(openLines={} threads={})", oldThread.getName(),
                        this.openLines.get(), this.playbackThreads.get());
                return false;
            }
        }
        return true;
    }

    /**
     * K8-B: idempotent close-once for a playback generation's line - the only
     * closer. Both stop() and the playback thread's finally call this; the
     * closeRequested flag arbitrates the race. The open-lines counter is
     * decremented exactly once here.
     */
    private void closeLineOnce(PlaybackGeneration generation) {
        if (generation == null)
            return;
        synchronized (generation) {
            if (generation.closeRequested)
                return;
            generation.closeRequested = true;
        }
        SourceDataLine activeLine = generation.line;
        if (activeLine != null && activeLine.isOpen()) {
            try {
                activeLine.stop();
            } catch (Exception ignored) {
            }
            try {
                activeLine.close();
            } catch (Exception ignored) {
            }
        }
        synchronized (generation) {
            if (generation.line != null) {
                this.openLines.decrementAndGet();
                generation.line = null;
            }
            generation.closed = true;
        }
    }

    // K8-B: expected cancellation of a stale generation - the line was closed
    // by stop() while the playback thread was mid-write/read
    private static boolean isExpectedCancel(Throwable e) {
        return e instanceof IllegalStateException
                || e instanceof javax.sound.sampled.LineUnavailableException
                || e instanceof java.io.IOException;
    }

    /**
     * Stop playback - non-blocking (a bounded join happens only inside
     * stopAndAwaitThreadExit on the audio-I/O thread).
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
        // AUD-52 v1.3/R5: stop() invalidates the cross-generation stamp
        this.watermarkStamp = WatermarkStamp.EMPTY;
        // AUD-48 v1.6: transient-window bases reset with the generation
        bufferIndex = 0L;
        resumeBufferIndex = -1L;
        // K3 P1-3: a parked playback thread must be woken so it can observe
        // the generation change and exit its pause spin
        java.util.concurrent.locks.LockSupport.unpark(this.playbackThread);
        // K8-B: close the current generation's line once, idempotently
        PlaybackGeneration activeGeneration = this.currentGeneration;
        if (activeGeneration != null) {
            this.currentGeneration = null;
            closeLineOnce(activeGeneration);
        }
    }

    /**
     * AUD-47: audible position, derived from the frames the line has actually
     * played (SourceDataLine.getLongFramePosition), plus the start/seek offset.
     * A stopped (paused) line does not advance its frame counter.
     * K3 P0-a: with no usable line the position is UNKNOWN (-1), never a
     * fabricated offset - consumers must not treat -1 as zero.
     * K8-B: the line is read from the current generation - a stale generation
     * never contributes its line here.
     */
    public long getPositionMillis() {
        PlaybackGeneration activeGeneration = this.currentGeneration;
        SourceDataLine activeLine = activeGeneration == null ? null : activeGeneration.line;
        float rate = this.lineFrameRate;
        if (activeLine == null || !activeLine.isOpen() || rate <= 0.0F)
            return -1L;
        long frames = activeLine.getLongFramePosition();
        return positionOffsetMillis + Math.round(frames * 1000.0D / rate);
    }

    /**
     * AUD-47: decoded (written) position, kept as a diagnostic quantity for
     * the probe and buffer diagnostics only. K4 P1: no decoded data -> -1,
     * never a fabricated zero.
     */
    public long getDecodedPositionMillis() {
        double bytesPerSecond = decodedBytesPerSecond;
        return bytesPerSecond <= 0.0D ? -1L : Math.max(0L, Math.round(playedPcmBytes * 1000.0D / bytesPerSecond));
    }

    // AUD-48: line diagnostics for the probe (world.line)
    public int getLineBufferBytes() {
        PlaybackGeneration activeGeneration = this.currentGeneration;
        SourceDataLine activeLine = activeGeneration == null ? null : activeGeneration.line;
        return activeLine == null || !activeLine.isOpen() ? 0 : activeLine.getBufferSize();
    }

    public int getLineAvailableBytes() {
        PlaybackGeneration activeGeneration = this.currentGeneration;
        SourceDataLine activeLine = activeGeneration == null ? null : activeGeneration.line;
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

    // AUD-52 v1.3/R5: the watermark stamp (generation + millis), published as
    // one immutable record; EMPTY means no stamp for the current generation
    public WatermarkStamp getWatermarkStamp() {
        return this.watermarkStamp;
    }

    // AUD-52 v1.3/R5: playbackGeneration accessor for generation checks
    public long getPlaybackGeneration() {
        return this.playbackGeneration.get();
    }

    // AUD-48 v1.4: has the adaptive watermark converged above the 60ms start?
    public static boolean isWatermarkAdaptive() {
        return StreamMusicPlayer.adaptiveWatermarkMillis > 0L;
    }

    // AUD-48 v1.4/v1.6: reset the converged watermark and its co-state (world
    // unload); both share the same lifecycle scope
    public static void resetAdaptiveWatermark() {
        StreamMusicPlayer.adaptiveWatermarkMillis = 0L;

        StreamMusicPlayer.lastWatermarkDownAtMillis = 0L;
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
        // checks. K8-B: the hardware op targets the current generation's line
        paused = true;
        PlaybackGeneration activeGeneration = this.currentGeneration;
        if (activeGeneration != null && activeGeneration.line != null && activeGeneration.line.isOpen()) {
            activeGeneration.line.stop();
        }
    }
    
    /**
     * Resume playback
     */
    public void resume() {
        // AUD-42: unconditional flag write; only hardware ops keep liveness
        // checks
        paused = false;
        PlaybackGeneration activeGeneration = this.currentGeneration;
        if (activeGeneration != null && activeGeneration.line != null && activeGeneration.line.isOpen()) {
            activeGeneration.line.start();
        }
        // K3 P1-3: wake the paused playback thread immediately
        java.util.concurrent.locks.LockSupport.unpark(this.playbackThread);
    }
    
    /**
     * Pause playback due to game pause. K5 P0-3: the flag is written
     * unconditionally every tick (AUD-41 projection); the hardware action
     * (line.stop) runs only when the pause state actually changed.
     */
    public void pauseForGame() {
        boolean wasPaused = this.gamePaused;
        this.gamePaused = true;
        if (!wasPaused) {
            PlaybackGeneration activeGeneration = this.currentGeneration;
            if (activeGeneration != null && activeGeneration.line != null && activeGeneration.line.isOpen()) {
                activeGeneration.line.stop();
            }
            // K6-A: the log fires only on the state edge
            LOGGER.debug("Paused music due to game pause");
        }
    }
    
    /**
     * Resume playback after game unpause. K5 P0-3: the flag is written
     * unconditionally; the hardware actions (line.start, unpark) run only when
     * the pause state actually changed.
     */
    public void resumeFromGame() {
        boolean wasPaused = this.gamePaused;
        this.gamePaused = false;
        if (wasPaused) {
            PlaybackGeneration activeGeneration = this.currentGeneration;
            if (activeGeneration != null && activeGeneration.line != null && activeGeneration.line.isOpen()) {
                activeGeneration.line.start();
            }
            // K3 P1-3: wake the paused playback thread immediately
            java.util.concurrent.locks.LockSupport.unpark(this.playbackThread);
            // K6-A: the log fires only on the state edge
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
     * playback thread main loop only with ITS OWN generation and line -
     * finalGain = master × music × trackEnv × muteEnv × seekEnv × userGain ×
     * previewGain (AUD-44/K9-3).
     * K8-B: the operation targets the caller's local line only and aborts
     * when the generation is no longer current, so a stale thread can never
     * touch a newer generation's line.
     */
    private void applyGain(SourceDataLine playbackLine, PlaybackGeneration generation) {
        if (playbackLine == null || !playbackLine.isOpen() || generation != this.currentGeneration)
            return;
        try {
            Minecraft mc = Minecraft.getInstance();
            float masterVolume = mc.options.getSoundSourceVolume(SoundSource.MASTER);
            float musicVolume = mc.options.getSoundSourceVolume(SoundSource.RECORDS);
            float finalGain = masterVolume * musicVolume
                    * this.trackEnv.current() * this.muteEnv.current() * this.seekEnv.current()
                    * this.userGainEnv.current() * this.previewGainEnv.current();
            if (playbackLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gainControl = (FloatControl) playbackLine.getControl(FloatControl.Type.MASTER_GAIN);
                float dB = (float) (Math.log(Math.max(0.0001f, finalGain)) / Math.log(10.0) * 20.0);
                dB = Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), dB));
                gainControl.setValue(dB);
            }
        } catch (Exception e) {
            // Ignore volume control errors
        }
    }

    // K9-3: persistent user/preview gain - immediate effect via the envelope
    // (no fade; the slider is a user action, not a music transition)
    public void setUserGain(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        this.userGainEnv.forceFade(clamped, 0L);
    }

    public float getUserGain() {
        return this.userGainEnv.current();
    }

    public void setPreviewGain(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        this.previewGainEnv.forceFade(clamped, 0L);
    }

    public float getPreviewGain() {
        return this.previewGainEnv.current();
    }

    // K10-C: live diagnostics for the probe ring - open SourceDataLine count
    // and running playback thread count of THIS player (main and preview are
    // reported separately; each must stay <= 1 independently)
    public int getOpenLines()
    {
        return this.openLines.get();
    }

    public int getPlaybackThreads()
    {
        return this.playbackThreads.get();
    }
}