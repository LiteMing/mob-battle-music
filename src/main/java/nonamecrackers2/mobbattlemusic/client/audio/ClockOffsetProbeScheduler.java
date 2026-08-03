package nonamecrackers2.mobbattlemusic.client.audio;

import java.util.concurrent.ThreadLocalRandom;

import nonamecrackers2.mobbattlemusic.network.ClockOffsetProbeRequestPacket;
import nonamecrackers2.mobbattlemusic.network.ClockOffsetProbeResponsePacket;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;

/**
 * K7-B: the bounded clock-probe burst - at most 5 requests per 60s window,
 * sent at 0/100/250/500/1000ms after the window opens. Strict maximum
 * network burden: 5 C2S + 5 S2C packets per minute. The probe is connection-
 * level: it does not care about playback and NEVER touches track anchors
 * (responses are consumed by the estimator only).
 *
 * Each window rolls a fresh connection-level nonce; a response whose nonce
 * does not match the current window is discarded before the estimator.
 */
public final class ClockOffsetProbeScheduler
{
	public static final long WINDOW_MILLIS = 60_000L;
	public static final int MAX_PROBES_PER_WINDOW = 5;
	private static final long[] SEND_SLOTS_MILLIS = { 0L, 100L, 250L, 500L, 1000L };

	private static long windowStartMillis;
	private static int probesSentThisWindow;
	private static long currentNonce;

	private ClockOffsetProbeScheduler() {}

	/**
	 * K7-B: called every client tick (main thread). Opens a window lazily
	 * (first tick) and fires each slot exactly once per 60s window - a burst
	 * of at most 5 requests at 0/100/250/500/1000ms, then silence until the
	 * window expires and a new burst (with a fresh nonce) starts.
	 */
	public static void tick()
	{
		long now = System.currentTimeMillis();
		if (ClockOffsetProbeScheduler.windowStartMillis <= 0L
				|| now - ClockOffsetProbeScheduler.windowStartMillis >= ClockOffsetProbeScheduler.WINDOW_MILLIS) {
			// new window - fresh nonce, fresh budget
			ClockOffsetProbeScheduler.windowStartMillis = now;
			ClockOffsetProbeScheduler.probesSentThisWindow = 0;
			ClockOffsetProbeScheduler.currentNonce = ThreadLocalRandom.current().nextLong();
		}
		int slotIndex = ClockOffsetProbeScheduler.probesSentThisWindow;
		while (slotIndex < ClockOffsetProbeScheduler.MAX_PROBES_PER_WINDOW
				&& now - ClockOffsetProbeScheduler.windowStartMillis >= ClockOffsetProbeScheduler.SEND_SLOTS_MILLIS[slotIndex]) {
			// t1 is captured immediately before the actual send
			long t1 = System.currentTimeMillis();
			MobBattleMusicNetwork.sendClockProbeRequest(
					new ClockOffsetProbeRequestPacket(ClockOffsetProbeScheduler.currentNonce, t1));
			ClockOffsetProbeScheduler.probesSentThisWindow++;
			slotIndex++;
			LOGGER.debug("[MBM] clock probe #{} sent (window at {}ms)", slotIndex,
					now - ClockOffsetProbeScheduler.windowStartMillis);
		}
	}

	/**
	 * K7-B: consume a probe response (t4 captured by the network-thread
	 * consumer). The nonce must match the current window - a response from an
	 * old connection or an old window never reaches the estimator. The
	 * estimator is thread-safe for direct consumption. NEVER calls any
	 * MarkerClock.realign*().
	 */
	public static void handleProbeResponse(ClockOffsetProbeResponsePacket packet)
	{
		if (packet.probeNonce() != ClockOffsetProbeScheduler.currentNonce) {
			LOGGER.debug("[MBM] clock probe response dropped (stale nonce)");
			return;
		}
		long t4 = System.currentTimeMillis();
		ClockOffsetEstimator.sample(packet.clientSendEpochMillis(), packet.serverRecvEpochMillis(),
				packet.sendServerEpochMillis(), t4);
		LOGGER.debug("[MBM] clock probe sample fed: rtt={}ms offset={}ms state={}",
				(packet.sendServerEpochMillis() - packet.clientSendEpochMillis()) - (t4 - packet.serverRecvEpochMillis()),
				ClockOffsetEstimator.offsetMillis(), ClockOffsetEstimator.state());
	}

	// K7-B: world disconnect / reset - stop the burst, roll the nonce
	public static void reset()
	{
		ClockOffsetProbeScheduler.windowStartMillis = 0L;
		ClockOffsetProbeScheduler.probesSentThisWindow = 0;
	}

	private static final org.apache.logging.log4j.Logger LOGGER =
			org.apache.logging.log4j.LogManager.getLogger("mobbattlemusic/ClockOffsetProbeScheduler");
}
