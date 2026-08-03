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
	private static volatile long firedMarkers;
	private static volatile ClockState state = ClockState.STOPPED;

	private MarkerClock() {}

	private record ClockSource(String trackId, long startEpochMillis, long sendServerEpochMillis,
			long recvClientEpochMillis) {}

	/**
	 * AUD-22: server-anchored resync. (trackId, startEpochMillis, serverEpochMillis)
	 */
	public static void realign(String trackId, long startEpochMillis, long sendServerEpochMillis)
	{
		MarkerClock.source = new ClockSource(trackId, startEpochMillis, sendServerEpochMillis,
				System.currentTimeMillis());
		MarkerClock.lastSyncMillis = System.currentTimeMillis();
		LOGGER.debug("[MBM] clock realign track={} startEpoch={}ms", trackId, startEpochMillis);
	}

	public static void invalidate()
	{
		MarkerClock.source = null;
		MarkerClock.lastSyncMillis = 0L;
		MarkerClock.state = ClockState.STOPPED;
	}

	public static void setState(ClockState state)
	{
		MarkerClock.state = state;
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
	 */
	public static double driftSeconds(String trackId, long localPositionMillis)
	{
		ClockSource clockSource = MarkerClock.source;
		if (clockSource == null || !clockSource.trackId().equals(trackId))
			return 0.0D;
		double serverPosition = serverPositionSeconds(clockSource);
		return localPositionMillis / 1000.0D - serverPosition + MarkerClock.injectedDriftSeconds;
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
	 */
	public static void tick(String trackId, long localPositionMillis, CorrectionSink sink)
	{
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
		LOGGER.debug("[MBM] clock injected drift +{}s (total {})", seconds, MarkerClock.injectedDriftSeconds);
	}

	public static void clearInjectedDrift()
	{
		MarkerClock.injectedDriftSeconds = 0.0D;
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
