package nonamecrackers2.mobbattlemusic.client.audio;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server-clock-normalized client anchor (AUD-22/23/24/25, K6-B naming).
 * The anchor is expressed in the SERVER clock domain (startEpochServer,
 * sendServerEpoch): the client never subtracts two machines' wall clocks
 * directly - the connection-level ClockOffsetEstimator supplies the offset,
 * and MarkerClock only ever accepts already-normalized anchors.
 * Honest boundary (AUD-23): machine clock calibration is implemented; a
 * shared cross-client playback axis (multi-client ±1s) is NOT - anchors are
 * per-client self-anchors.
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
	// K6-A: unforgeable source version - bumped whenever the source is
	// replaced or invalidated; a seek-failure anchor restore only succeeds
	// when the version is unchanged. K7-A: increments happen only inside
	// synchronized methods (atomic), reads are volatile
	private static volatile long sourceVersion;

	private MarkerClock() {}

	// K7-A: immutable seek-anchor token - source version, track identity and
	// the playback session generation that captured it
	public record AnchorToken(long sourceVersion, String trackId, long playbackSessionGeneration) {}

	// K7-B: the anchor is a LOCAL self-anchor - the clock domain is the
	// client's own wall clock. There is no shared server canonical anchor;
	// the connection offset exists only for the future CUE canonical server
	// anchor and never enters this class. No cross-machine subtraction.
	private record ClockSource(String trackId, long startEpochLocalMillis, long recvLocalEpochMillis,
			long recvClientEpochMillis) {}

	/**
	 * K7-B/K7-A: the only anchor entry - a LOCAL self-anchor
	 * (realignLocalSelfAnchor(trackId, localPosition, localNow)). All source
	 * mutations are synchronized so version increments are atomic. AUD-50:
	 * re-anchors resume the clock. PAUSED-leave may realign exactly once;
	 * clock-probe responses never reach this method.
	 */
	public static synchronized void realignLocalSelfAnchor(String trackId, long localPositionMillis,
			long localNowMillis)
	{
		MarkerClock.sourceVersion++;
		MarkerClock.source = new ClockSource(trackId, localNowMillis - localPositionMillis,
				localNowMillis, localNowMillis);
		MarkerClock.lastAnchorMillis = localNowMillis;
		MarkerClock.state = ClockState.RUNNING;
		MarkerClock.anchorValid = true;
		LOGGER.debug("[MBM] clock local self-anchor track={} startLocal={}ms", trackId,
				localNowMillis - localPositionMillis);
	}

	public static synchronized void invalidate()
	{
		MarkerClock.sourceVersion++;
		MarkerClock.source = null;
		MarkerClock.lastAnchorMillis = 0L;
		MarkerClock.state = ClockState.STOPPED;
		// AUD-50 v1.1: invalidating the clock invalidates the anchor
		MarkerClock.anchorValid = false;
		// AUD-52 v1.3: invalidation also clears the give-up state
		MarkerClock.correctionDisabled = false;
		MarkerClock.reanchorRequested = false;
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

	// R4: a track switch invalidates the anchor - only the anchor bit is
	// touched (the source version is NOT bumped - a seek-failure restore on
	// the SAME source must succeed)
	public static void invalidateAnchor()
	{
		MarkerClock.anchorValid = false;
	}

	/**
	 * K7-A: atomic capture-and-invalidate for a seek gate. Runs under the
	 * same lock as every source mutation, so the returned token is consistent
	 * with the invalidated state; callers must never hand-assemble a token
	 * from three volatile reads.
	 *
	 * @return the token for restoreAfterFailedSeek(), or null when no source
	 *         exists
	 */
	public static synchronized @Nullable AnchorToken captureAndInvalidateForSeek(String trackId,
			long playbackSessionGeneration)
	{
		ClockSource clockSource = MarkerClock.source;
		if (clockSource == null)
			return null;
		AnchorToken token = new AnchorToken(MarkerClock.sourceVersion, clockSource.trackId(),
				playbackSessionGeneration);
		MarkerClock.anchorValid = false;
		return token;
	}

	/**
	 * K7-A: atomic restore after a failed seek - the token's track identity
	 * and source version must both still match the current source; otherwise
	 * the old anchor is gone (track switch, new S2C anchor, world unload,
	 * successful seek all bump the version and must fail this check).
	 */
	public static synchronized boolean restoreAfterFailedSeek(AnchorToken token)
	{
		if (token == null)
			return false;
		ClockSource clockSource = MarkerClock.source;
		if (clockSource == null || !clockSource.trackId().equals(token.trackId())
				|| MarkerClock.sourceVersion != token.sourceVersion())
			return false;
		MarkerClock.anchorValid = true;
		return true;
	}

	/**
	 * K7-A: beginPlayback calls this - version bump, source cleared and the
	 * anchor invalidated in one atomic step, so an in-flight seek's old token
	 * can never restore across a track switch.
	 */
	public static synchronized void invalidateForTrackSwitch()
	{
		MarkerClock.sourceVersion++;
		MarkerClock.source = null;
		MarkerClock.anchorValid = false;
	}

	// K6-A: unforgeable source version for seek-failure restore checks
	public static long sourceVersion()
	{
		return MarkerClock.sourceVersion;
	}

	// K6-A: the current source's track id (null when no source)
	public static @Nullable String sourceTrackId()
	{
		ClockSource clockSource = MarkerClock.source;
		return clockSource == null ? null : clockSource.trackId();
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
		// K6-A: no matching source is NO DATA - NaN, never a fabricated 0
		if (clockSource == null || !clockSource.trackId().equals(trackId))
			return Double.NaN;
		return serverPositionSeconds(clockSource);
	}

	private static double serverPositionSeconds(ClockSource clockSource)
	{
		// K7-B: the anchor is a LOCAL self-anchor - all four terms live in the
		// client's own wall-clock domain: (now - recv) is local elapsed time
		// and (recv - start) is the anchor's local position; no cross-machine
		// subtraction anywhere
		long elapsed = System.currentTimeMillis() - clockSource.recvClientEpochMillis()
				+ clockSource.recvLocalEpochMillis() - clockSource.startEpochLocalMillis();
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
			// AUD-24 v1.1: |drift| > 1s -> direct seek to the normalized
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
