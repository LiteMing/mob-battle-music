package nonamecrackers2.mobbattlemusic.client.audio;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server-authoritative playback clock (AUD-22/23/24/25).
 * The server anchors a track at (trackId, startEpochMillis, sendServerEpochMillis);
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
	// AUD-24 v1.1: resync period is fixed at 5 seconds
	public static final long SYNC_INTERVAL_MILLIS = 5000L;

	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/MarkerClock");

	private static volatile @Nullable ClockSource source;
	private static volatile long lastSyncMillis;
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

	private MarkerClock() {}

	private record ClockSource(String trackId, long startEpochMillis, long sendServerEpochMillis,
			long recvClientEpochMillis) {}

	/**
	 * AUD-22: server-anchored resync. (trackId, startEpochMillis, serverEpochMillis)
	 * AUD-50: a realign re-anchors and resumes the clock (FROZEN -> RUNNING).
	 */
	public static void realign(String trackId, long startEpochMillis, long sendServerEpochMillis)
	{
		MarkerClock.source = new ClockSource(trackId, startEpochMillis, sendServerEpochMillis,
				System.currentTimeMillis());
		MarkerClock.lastSyncMillis = System.currentTimeMillis();
		MarkerClock.state = ClockState.RUNNING;
		// AUD-50 v1.1: a realign establishes a valid anchor
		MarkerClock.anchorValid = true;
		LOGGER.debug("[MBM] clock realign track={} startEpoch={}ms", trackId, startEpochMillis);
	}

	public static void invalidate()
	{
		MarkerClock.source = null;
		MarkerClock.lastSyncMillis = 0L;
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
		long elapsed = System.currentTimeMillis() - clockSource.recvClientEpochMillis()
				+ clockSource.sendServerEpochMillis() - clockSource.startEpochMillis();
		return Math.max(0.0D, elapsed / 1000.0D);
	}

	public static long millisSinceLastSync()
	{
		long last = MarkerClock.lastSyncMillis;
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
