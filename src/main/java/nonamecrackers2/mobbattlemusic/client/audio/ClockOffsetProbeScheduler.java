package nonamecrackers2.mobbattlemusic.client.audio;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
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

	// All window state and estimator writes are guarded by LOCK. In
	// particular, reset cannot run between nonce validation and sampling.
	private static final Object LOCK = new Object();
	// Read by prepareBurst (connection changes) and handleProbeResponse
	// (response provenance); null means no active remote probe window.
	private static Connection activeConnection;
	private static long windowStartMillis;
	private static int probesSentThisWindow;
	private static long currentNonce = ThreadLocalRandom.current().nextLong();

	// Immutable send reservation: tick consumes it after releasing LOCK.
	record ProbeBurst(long nonce, int firstSlot, int limit, long windowAgeMillis) {}

	private ClockOffsetProbeScheduler() {}

	/**
	 * K7-B: called every client tick (main thread). Opens a window lazily
	 * (first tick) and fires each slot exactly once per 60s window - a burst
	 * of at most 5 requests at 0/100/250/500/1000ms, then silence until the
	 * window expires and a new burst (with a fresh nonce) starts.
	 */
	public static void tick()
	{
		Minecraft mc = Minecraft.getInstance();
		var listener = mc.getConnection();
		Connection connection = listener == null ? null : listener.getConnection();
		// K16-A: a local loopback connection (singleplayer world, including
		// the LAN host's own client) shares its server's clock. Remote LAN
		// clients still probe. Missing MBM peers have no probe window either.
		if (mc.isLocalServer() || !MobBattleMusicNetwork.channelIsRemotePresent(connection))
			connection = null;
		ProbeBurst burst = prepareBurst(connection, System.currentTimeMillis());
		if (burst == null)
			return;
		for (int slotIndex = burst.firstSlot(); slotIndex < burst.limit(); slotIndex++) {
			// t1 is captured immediately before the actual send
			long t1 = System.currentTimeMillis();
			MobBattleMusicNetwork.sendClockProbeRequest(
					new ClockOffsetProbeRequestPacket(burst.nonce(), t1));
			LOGGER.debug("[MBM] clock probe #{} sent (window at {}ms)", slotIndex + 1,
					burst.windowAgeMillis());
		}
	}

	/**
	 * Reserve due slots atomically; networking stays outside the state lock.
	 * A null connection closes the window. A new connection or window clears
	 * the estimator before any response with its new nonce can be accepted.
	 */
	static ProbeBurst prepareBurst(Connection connection, long now)
	{
		synchronized (LOCK) {
			if (connection == null) {
				if (activeConnection != null)
					reset();
				return null;
			}
			if (activeConnection != connection || windowStartMillis <= 0L
					|| now - windowStartMillis >= WINDOW_MILLIS) {
				activeConnection = connection;
				windowStartMillis = now;
				probesSentThisWindow = 0;
				currentNonce++;
				ClockOffsetEstimator.reset();
			}
			int firstSlot = probesSentThisWindow;
			long age = now - windowStartMillis;
			while (probesSentThisWindow < MAX_PROBES_PER_WINDOW
					&& age >= SEND_SLOTS_MILLIS[probesSentThisWindow])
				probesSentThisWindow++;
			return firstSlot == probesSentThisWindow ? null
					: new ProbeBurst(currentNonce, firstSlot, probesSentThisWindow, age);
		}
	}

	/**
	 * K7-B: t4 is captured by the network-thread consumer before this lock.
	 * Connection and nonce validation share the estimator reset/sample lock,
	 * so an old response cannot repopulate a reset estimator. No main-thread
	 * queue and no MarkerClock.realign*() calls participate in this path.
	 */
	public static void handleProbeResponse(ClockOffsetProbeResponsePacket packet, Connection connection, long t4)
	{
		synchronized (LOCK) {
			if (connection == null || connection != activeConnection || windowStartMillis <= 0L
					|| packet.probeNonce() != currentNonce || t4 < windowStartMillis
					|| t4 - windowStartMillis >= WINDOW_MILLIS) {
				LOGGER.debug("[MBM] clock probe response dropped (inactive connection or stale window)");
				return;
			}
			ClockOffsetEstimator.sample(packet.clientSendEpochMillis(), packet.serverRecvEpochMillis(),
					packet.sendServerEpochMillis(), t4);
		}
		LOGGER.debug("[MBM] clock probe sample fed: rtt={}ms offset={}ms state={}",
				(t4 - packet.clientSendEpochMillis()) - (packet.sendServerEpochMillis() - packet.serverRecvEpochMillis()),
				ClockOffsetEstimator.offsetMillis(), ClockOffsetEstimator.state());
	}

	// K7-B: world disconnect / reset - stop the burst, roll the nonce
	public static void reset()
	{
		synchronized (LOCK) {
			activeConnection = null;
			windowStartMillis = 0L;
			probesSentThisWindow = 0;
			currentNonce++;
			ClockOffsetEstimator.reset();
		}
	}

	private static final org.apache.logging.log4j.Logger LOGGER =
			org.apache.logging.log4j.LogManager.getLogger("mobbattlemusic/ClockOffsetProbeScheduler");
}
