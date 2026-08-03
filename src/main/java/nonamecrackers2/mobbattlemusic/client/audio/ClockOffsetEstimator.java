package nonamecrackers2.mobbattlemusic.client.audio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * K6-B: connection-level clock calibration (CUE-4 minimal-RTT handshake).
 * Owns ONLY t1/t2/t3/t4, RTT and the offset - it never touches any track
 * anchor. A new window opens every 60s; each window requires at least
 * MIN_SAMPLES_PER_WINDOW finite samples and keeps the lowest-RTT sample as
 * the window's offset. world disconnect/reset clears everything.
 *
 * offset = ((t2-t1)+(t3-t4))/2 under the symmetric-delay assumption;
 * the server-receive moment t2 is captured on the network thread BEFORE the
 * server main-thread queue, so the queueing delay Q never enters the offset
 * (see the derivation in the PR deliverable).
 */
public final class ClockOffsetEstimator
{
	public static final long WINDOW_MILLIS = 60_000L;
	public static final int MIN_SAMPLES_PER_WINDOW = 5;

	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/ClockOffsetEstimator");

	private static final Object LOCK = new Object();
	private static volatile double currentOffsetMillis;
	private static volatile boolean calibrated;
	private static volatile long windowStartMillis;
	private static long windowSampleCount;
	private static long windowBestRttMillis = Long.MAX_VALUE;
	private static double windowBestOffsetMillis;

	private ClockOffsetEstimator() {}

	/**
	 * Feed one handshake sample (t1 client send, t2 server recv, t3 server
	 * send, t4 client recv). Finite samples only: a negative RTT is rejected.
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
				// new window - the previous window's best sample stays as the
				// current offset; a fresh window needs MIN_SAMPLES again
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
				// the lowest-RTT sample of this window becomes authoritative
				currentOffsetMillis = windowBestOffsetMillis;
				calibrated = true;
			}
		}
	}

	// K6-B: the calibrated offset (server clock - client clock), 0 when not
	// calibrated yet
	public static double offsetMillis()
	{
		return ClockOffsetEstimator.calibrated ? ClockOffsetEstimator.currentOffsetMillis : 0.0D;
	}

	public static boolean isCalibrated()
	{
		return ClockOffsetEstimator.calibrated;
	}

	// K6-B: world disconnect / reset - everything is cleared
	public static void reset()
	{
		synchronized (LOCK) {
			ClockOffsetEstimator.currentOffsetMillis = 0.0D;
			ClockOffsetEstimator.calibrated = false;
			ClockOffsetEstimator.windowStartMillis = 0L;
			ClockOffsetEstimator.windowSampleCount = 0;
			ClockOffsetEstimator.windowBestRttMillis = Long.MAX_VALUE;
			ClockOffsetEstimator.windowBestOffsetMillis = 0.0D;
		}
		LOGGER.debug("[MBM] clock offset estimator reset");
	}
}
