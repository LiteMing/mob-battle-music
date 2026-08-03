package nonamecrackers2.mobbattlemusic.client.audio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * K7-B: connection-level clock calibration (CUE-4 minimal-RTT handshake) fed
 * ONLY by the dedicated ClockOffsetProbeRequest/Response pair - playback
 * start/resume packets are no longer probes. Owns ONLY t1/t2/t3/t4, RTT and
 * the offset; it never touches any track anchor and never calls
 * MarkerClock.realign*(). A bounded probe burst (at most 5 requests per 60s
 * window, sent at 0/100/250/500/1000ms) feeds at most 5 C2S + 5 S2C packets
 * per minute.
 *
 * Offset state:
 *   UNKNOWN     - no valid sample in the current window; offsetMillis() is
 *                 NaN and must never be treated as 0.0
 *   PROVISIONAL - 1-4 valid samples; the current lowest-RTT sample is used
 *   CALIBRATED  - >=5 valid samples; the window's lowest-RTT sample is used
 *
 * offset = ((t2-t1)+(t3-t4))/2 under the symmetric-delay assumption;
 * t2 is captured on the server network thread BEFORE the server main-thread
 * queue and t4 on the client network thread BEFORE the client main-thread
 * queue, so both queueing delays (server Qs, client Qc) exit the formula
 * (see the derivation in the PR deliverable).
 */
public final class ClockOffsetEstimator
{
	public static final long WINDOW_MILLIS = 60_000L;
	public static final int MIN_SAMPLES_PER_WINDOW = 5;

	public enum OffsetState
	{
		// no legal sample in the current window - offsetMillis() returns NaN
		UNKNOWN,
		// 1-4 legal samples - the current lowest-RTT sample is used
		PROVISIONAL,
		// >=5 legal samples - the window's lowest-RTT sample is used
		CALIBRATED
	}

	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/ClockOffsetEstimator");

	private static final Object LOCK = new Object();
	private static volatile double currentOffsetMillis = Double.NaN;
	private static volatile OffsetState currentState = OffsetState.UNKNOWN;
	private static volatile long windowStartMillis;
	private static long windowSampleCount;
	private static long windowBestRttMillis = Long.MAX_VALUE;
	private static double windowBestOffsetMillis;

	private ClockOffsetEstimator() {}

	/**
	 * K7-B: feed one probe response (t1 client send, t2 server recv, t3
	 * server send, t4 client recv - all captured pre-queue on network
	 * threads). Finite samples only: a negative RTT is rejected. Thread-safe
	 * for direct consumption by the client network thread.
	 */
	public static void sample(long t1, long t2, long t3, long t4)
	{
		long rtt = (t4 - t1) - (t3 - t2);
		if (rtt < 0L)
			return;
		double offset = ((t2 - t1) + (t3 - t4)) / 2.0D;
		synchronized (LOCK) {
			if (windowStartMillis <= 0L)
				windowStartMillis = t4;
			if (t4 - windowStartMillis > WINDOW_MILLIS) {
				// a stale window's samples must never leak into the next one
				windowStartMillis = t4;
				windowSampleCount = 0;
				windowBestRttMillis = Long.MAX_VALUE;
				windowBestOffsetMillis = 0.0D;
			}
			windowSampleCount++;
			if (rtt < windowBestRttMillis) {
				windowBestRttMillis = rtt;
				windowBestOffsetMillis = offset;
			}
			if (windowSampleCount >= MIN_SAMPLES_PER_WINDOW) {
				currentOffsetMillis = windowBestOffsetMillis;
				currentState = OffsetState.CALIBRATED;
			} else {
				currentOffsetMillis = windowBestOffsetMillis;
				currentState = OffsetState.PROVISIONAL;
			}
		}
	}

	/**
	 * K7-B: the current offset (server clock - client clock). UNKNOWN (no
	 * legal sample in the current window) returns NaN - never 0.0.
	 */
	public static double offsetMillis()
	{
		synchronized (LOCK) {
			if (ClockOffsetEstimator.windowStartMillis <= 0L
					|| System.currentTimeMillis() - ClockOffsetEstimator.windowStartMillis > WINDOW_MILLIS)
				return Double.NaN;
			return ClockOffsetEstimator.currentOffsetMillis;
		}
	}

	public static OffsetState state()
	{
		synchronized (LOCK) {
			if (ClockOffsetEstimator.windowStartMillis <= 0L
					|| System.currentTimeMillis() - ClockOffsetEstimator.windowStartMillis > WINDOW_MILLIS)
				return OffsetState.UNKNOWN;
			return ClockOffsetEstimator.currentState;
		}
	}

	// K7-B: the non-NaN value is only usable in PROVISIONAL/CALIBRATED states
	public static boolean isCalibrated()
	{
		return ClockOffsetEstimator.state() == OffsetState.CALIBRATED;
	}

	// K7-B: world disconnect / reset - everything is cleared back to UNKNOWN
	public static void reset()
	{
		synchronized (LOCK) {
			ClockOffsetEstimator.currentOffsetMillis = Double.NaN;
			ClockOffsetEstimator.currentState = OffsetState.UNKNOWN;
			ClockOffsetEstimator.windowStartMillis = 0L;
			ClockOffsetEstimator.windowSampleCount = 0;
			ClockOffsetEstimator.windowBestRttMillis = Long.MAX_VALUE;
			ClockOffsetEstimator.windowBestOffsetMillis = 0.0D;
		}
		LOGGER.debug("[MBM] clock offset estimator reset");
	}
}
