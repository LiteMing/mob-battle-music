package nonamecrackers2.mobbattlemusic.client.audio;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server-authoritative playback clock (AUD-22/23/24/25).
 * The anchor is expressed in the SERVER clock domain (startEpochServer,
 * sendServerEpoch): the client never subtracts two machines' wall clocks
 * directly - a CUE-4 minimal-RTT handshake supplies the clock offset, and the
 * lowest-RTT sample within a 60s window is kept (AUD-24 K5).
 * drift is local position minus the server-derived position.
 */
public final class MarkerClock
{
	public enum ClockState
	{
		STOPPED,
		RUNNING,
		FROZEN
	}

	// AUD-24 v1.1 thresholds - fixed, must not be adjusted
	public static final double DRIFT_TOLERANCE_SECONDS = 1.0D;
	// K5: the lowest-RTT offset sample is kept within this window after start
	private static final long OFFSET_SAMPLE_WINDOW_MILLIS = 60_000L;

	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/MarkerClock");

	private static volatile @Nullable ClockSource source;
	private static volatile long lastAnchorMillis;
	private static volatile double injectedDriftSeconds;
	private static volatile long injectedDriftSetAtMillis;
	private static volatile long firedMarkers;
	private static volatile ClockState state = ClockState.STOPPED;
	// AUD-50 v1.1: explicit anchor validity - a migration edge carried by its
	// own boolean state, never inferred from the converged ClockState
	private static volatile boolean anchorValid;
	// AUD-50 v1.2: explicit re-anchor request, set only on the PAUSED ->
	// PLAYING migration edge, cleared after the re-anchor executes
	private static volatile boolean reanchorRequested;
	// AUD-52 v1.3: give-up state, independent of the clock state - never
	// expressed via FROZEN, never clears the anchor, never triggers a
	// re-anchor; cleared only by beginPlayback (track switch) and invalidate
	private static volatile boolean correctionDisabled;
	// K5: CUE-4 clock offset (server clock - client clock), from the
	// lowest-RTT handshake sample in the window
	private static volatile double clockOffsetMillis;
	private static volatile long bestSampleRttMillis = Long.MAX_VALUE;
	private static volatile long bestSampleAtMillis;
	private static volatile long offsetWindowStartMillis;

	private MarkerClock() {}

	// K5: the anchor is stored in the SERVER clock domain - no cross-machine
	// wall-clock subtraction anywhere in the position math
	private record ClockSource(String trackId, long startEpochServerMillis, long sendServerEpochMillis,
			long recvClientEpochMillis) {}

	/**
	 * AUD-22/K5: server-anchored resync from a CUE-4 handshake sample.
	 * t1 = clientSendEpoch, t2 = serverRecvEpoch, t3 = serverSendEpoch,
	 * t4 = now. Computes offset = ((t2-t1)+(t3-t4))/2 and keeps the
	 * lowest-RTT sample within the window; the anchor is converted to the
	 * server clock domain (startEpochServer = startEpochClient + offset).
	 */
	public static void realign(String trackId, long startEpochMillis, long sendServerEpochMillis,
			long clientSendEpochMillis, long serverRecvEpochMillis)
	{
		long t4 = System.currentTimeMillis();
		long rtt = (t4 - clientSendEpochMillis) - (sendServerEpochMillis - serverRecvEpochMillis);
		double offset = ((serverRecvEpochMillis - clientSendEpochMillis)
				+ (sendServerEpochMillis - t4)) / 2.0D;
		if (MarkerClock.offsetWindowStartMillis <= 0L)
			MarkerClock.offsetWindowStartMillis = t4;
		if (rtt >= 0L && rtt < MarkerClock.bestSampleRttMillis
				&& t4 - MarkerClock.offsetWindowStartMillis <= OFFSET_SAMPLE_WINDOW_MILLIS) {
			MarkerClock.bestSampleRttMillis = rtt;
			MarkerClock.bestSampleAtMillis = t4;
			MarkerClock.clockOffsetMillis = offset;
		}
		MarkerClock.source = new ClockSource(trackId,
				startEpochMillis + Math.round(MarkerClock.clockOffsetMillis),
				sendServerEpochMillis, t4);
		MarkerClock.lastAnchorMillis = t4;
		MarkerClock.state = ClockState.RUNNING;
		// AUD-50 v1.1: a realign establishes a valid anchor
		MarkerClock.anchorValid = true;
		LOGGER.debug("[MBM] clock realign track={} startEpochServer={}ms offset={}ms rtt={}ms",
				trackId, startEpochMillis + Math.round(MarkerClock.clockOffsetMillis),
				Math.round(MarkerClock.clockOffsetMillis), rtt);
	}

	/**
	 * AUD-50/K5: local re-anchor (PAUSED-leave, seek completion) - the anchor
	 * is expressed directly in the server clock domain (the caller converts
	 * local times with the current offset).
	 */
	public static void realignServerDomain(String trackId, long startEpochServerMillis,
			long sendServerEpochMillis)
	{
		MarkerClock.source = new ClockSource(trackId, startEpochServerMillis, sendServerEpochMillis,
				System.currentTimeMillis());
		MarkerClock.lastAnchorMillis = System.currentTimeMillis();
		MarkerClock.state = ClockState.RUNNING;
		MarkerClock.anchorValid = true;
		LOGGER.debug("[MBM] clock re-anchor track={} startEpochServer={}ms", trackId, startEpochServerMillis);
	}

	// K5: the client-clock-domain offset accessor for anchor conversions
	public static double clockOffsetMillis()
	{
		return MarkerClock.clockOffsetMillis;
	}

	public static void invalidate()
	{
		MarkerClock.source = null;
		MarkerClock.lastAnchorMillis = 0L;
		MarkerClock.state = ClockState.STOPPED;
		// AUD-50 v1.1: invalidating the clock invalidates the anchor
		MarkerClock.anchorValid = false;
		// AUD-52 v1.3: invalidation also clears the give-up state
		MarkerClock.correctionDisabled = false;
		MarkerClock.reanchorRequested = false;
		// K5: a fresh session restarts the offset-sample window
		MarkerClock.offsetWindowStartMillis = System.currentTimeMillis();
		MarkerClock.bestSampleRttMillis = Long.MAX_VALUE;
		MarkerClock.bestSampleAtMillis = 0L;
		MarkerClock.clockOffsetMillis = 0.0D;
	}

	public static void setState(ClockState state)
	{
		// AUD-50 v1.1: freezing invalidates the anchor; the migration edge is
		// carried by anchorValid, not by the converged state
		if (state == ClockState.FROZEN)
			MarkerClock.anchorValid = false;
		MarkerClock.state = state;
	}

	public static boolean anchorValid()
	{
		return MarkerClock.anchorValid;
	}

	// R4: a track switch invalidates the anchor - the new track is not yet
	// anchored; only the anchor bit is touched
	public static void invalidateAnchor()
	{
		MarkerClock.anchorValid = false;
	}

	// AUD-50 v1.2: explicit re-anchor request API
	public static void requestReanchor()
	{
		MarkerClock.reanchorRequested = true;
	}

	public static boolean reanchorRequested()
	{
		return MarkerClock.reanchorRequested;
	}

	public static void clearReanchorRequest()
	{
		MarkerClock.reanchorRequested = false;
	}

	// AUD-52 v1.3: give-up state accessors
	public static void disableCorrection()
	{
		MarkerClock.correctionDisabled = true;
	}

	public static void enableCorrection()
	{
		MarkerClock.correctionDisabled = false;
	}

	public static boolean isCorrectionDisabled()
	{
		return MarkerClock.correctionDisabled;
	}

	public static ClockState state()
	{
		return MarkerClock.state;
	}

	public static boolean isActive()
	{
		return MarkerClock.source != null;
	}

	/**
	 * Drift in seconds: positive = local playback ahead of the server anchor.
	 * R4: "no usable anchor for this track" is NO DATA - reported as NaN,
	 * never as zero drift (zero is a legal, and the healthiest-looking, value).
	 */
	public static double driftSeconds(String trackId, long localPositionMillis)
	{
		ClockSource clockSource = MarkerClock.source;
		if (clockSource == null || !clockSource.trackId().equals(trackId))
			return Double.NaN;
		double serverPosition = serverPositionSeconds(clockSource);
		return localPositionMillis / 1000.0D - serverPosition + MarkerClock.effectiveInjectedDrift();
	}

	public static double serverPositionSeconds(String trackId)
	{
		ClockSource clockSource = MarkerClock.source;
		if (clockSource == null || !clockSource.trackId().equals(trackId))
			return 0.0D;
		return serverPositionSeconds(clockSource);
	}

	private static double serverPositionSeconds(ClockSource clockSource)
	{
		// K5: all terms are in a single clock domain - the anchor is stored in
		// the server domain (startEpochServer, sendServerEpoch) and recvClient
		// is a client-domain receive moment, so (now - recvClient) is local
		// elapsed time while (sendServer - startEpochServer) is server-elapsed
		// time; no cross-machine wall-clock subtraction remains
		long elapsed = System.currentTimeMillis() - clockSource.recvClientEpochMillis()
				+ clockSource.sendServerEpochMillis() - clockSource.startEpochServerMillis();
		return Math.max(0.0D, elapsed / 1000.0D);
	}

	public static long millisSinceAnchor()
	{
		long last = MarkerClock.lastAnchorMillis;
		return last <= 0L ? -1L : System.currentTimeMillis() - last;
	}

	/**
	 * AUD-24 v1.1 two-tier correction decision, evaluated per tick by the main
	 * playback channel: |drift| <= 1s no intervention, |drift| > 1s seek.
	 * AUD-50: the clock participates in correction only while RUNNING - the
	 * guard is checked here, never delegated to the caller.
	 */
	public static void tick(String trackId, long localPositionMillis, CorrectionSink sink)
	{
		// AUD-50/AUD-50 v1.1/AUD-52 v1.3: state guard, anchor validity and
		// give-up guard, first line
		if (MarkerClock.state != ClockState.RUNNING || !MarkerClock.anchorValid
				|| MarkerClock.correctionDisabled)
			return;
		ClockSource clockSource = MarkerClock.source;
		if (clockSource == null || !clockSource.trackId().equals(trackId))
			return;
		double drift = driftSeconds(trackId, localPositionMillis);
		if (Math.abs(drift) <= MarkerClock.DRIFT_TOLERANCE_SECONDS) {
			// AUD-24 v1.1: |drift| <= 1s -> no intervention
			sink.noCorrection(drift);
		} else {
			// AUD-24 v1.1: |drift| > 1s -> direct seek to the server-anchored
			// position, wrapped in a ~120ms fade-out/fade-in
			sink.seek(serverPositionSeconds(clockSource), drift);
		}
	}

	/**
	 * Correction executor interface implemented by the main playback channel.
	 */
	public interface CorrectionSink
	{
		void noCorrection(double drift);

		void seek(double serverPositionSeconds, double drift);
	}

	public static void injectDrift(double seconds)
	{
		MarkerClock.injectedDriftSeconds += seconds;
		MarkerClock.injectedDriftSetAtMillis = System.currentTimeMillis();
		LOGGER.debug("[MBM] clock injected drift +{}s (total {})", seconds, MarkerClock.injectedDriftSeconds);
	}

	public static void clearInjectedDrift()
	{
		MarkerClock.injectedDriftSeconds = 0.0D;
		MarkerClock.injectedDriftSetAtMillis = 0L;
	}

	// AUD-53: debug-injection values expire after 60s
	private static double effectiveInjectedDrift()
	{
		double injected = MarkerClock.injectedDriftSeconds;
		if (injected != 0.0D) {
			long setAt = MarkerClock.injectedDriftSetAtMillis;
			if (setAt > 0L && System.currentTimeMillis() - setAt > 60_000L) {
				MarkerClock.injectedDriftSeconds = 0.0D;
				MarkerClock.injectedDriftSetAtMillis = 0L;
				return 0.0D;
			}
		}
		return injected;
	}

	// AUD-53: remaining TTL of the injected drift for the probe (0 = none)
	public static long injectedTtlMillis()
	{
		if (MarkerClock.injectedDriftSeconds == 0.0D)
			return 0L;
		long setAt = MarkerClock.injectedDriftSetAtMillis;
		if (setAt <= 0L)
			return 0L;
		return Math.max(0L, 60_000L - (System.currentTimeMillis() - setAt));
	}

	// AUD-30 v1.2: consumed by the probe's world.clock injected= field (S15);
	// AUD-53: expires after 60s
	public static double injectedDriftSeconds()
	{
		return MarkerClock.effectiveInjectedDrift();
	}

	public static void recordFiredMarkers(long count)
	{
		MarkerClock.firedMarkers = count;
	}

	public static long firedMarkers()
	{
		return MarkerClock.firedMarkers;
	}
}
