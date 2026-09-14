package nonamecrackers2.mobbattlemusic.client.audio;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraftforge.network.ICustomPacket;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import nonamecrackers2.mobbattlemusic.network.ClockOffsetProbeRequestPacket;
import nonamecrackers2.mobbattlemusic.network.ClockOffsetProbeResponsePacket;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;

/**
 * Run with gradlew networkRegressionTest. Forge's actual encoder, decoder,
 * event bus, and registered consumers participate in dispatch checks.
 * No Minecraft client, server, audio device, or network socket is started.
 */
public final class NetworkRegressionChecks
{
	private static SimpleChannel channel;
	private static Object networkInstance;
	private static Method dispatch;
	private static int passed;

	public static void main(String[] args) throws Exception
	{
		// Populate listener metadata through real event instances. A plain
		// JVM has no ModLauncher transformer to add synthetic constructors.
		new NetworkEvent(() -> null).getListenerList();
		new NetworkEvent.GatherLoginPayloadsEvent(new ArrayList<>(), false).getListenerList();
		MobBattleMusicNetwork.register();
		channel = (SimpleChannel) field(MobBattleMusicNetwork.class, "CHANNEL").get(null);
		networkInstance = field(SimpleChannel.class, "instance").get(channel);
		dispatch = networkInstance.getClass().getDeclaredMethod("dispatch", NetworkDirection.class,
				ICustomPacket.class, Connection.class);
		dispatch.setAccessible(true);
		run("both registered probe consumers mark packets handled", NetworkRegressionChecks::handledPackets);
		run("discarded replies are handled without sampling", NetworkRegressionChecks::discardedReplies);
		run("burst slots and the 60-second limit", NetworkRegressionChecks::burstSlots);
		run("a delayed tick cannot exceed five requests", NetworkRegressionChecks::delayedTick);
		run("reset rejects replies before the next tick", NetworkRegressionChecks::resetRejectsReplies);
		run("reconnect rejects an old connection and nonce", NetworkRegressionChecks::reconnect);
		run("window rollover clears calibration and rejects old replies", NetworkRegressionChecks::rollover);
		run("expired replies are rejected without a main-thread tick", NetworkRegressionChecks::expiredReply);
		run("five dispatched replies yield five calibration samples", NetworkRegressionChecks::calibration);
		run("reset wins a race with a queued response", NetworkRegressionChecks::resetBeforeResponse);
		run("reset waits for an already validated sample", NetworkRegressionChecks::resetDuringSample);
		run("protocol 17 is required while missing peers remain optional", NetworkRegressionChecks::protocol);
		System.out.println("MBM network regression checks: " + passed + " passed");
	}

	private static void handledPackets() throws Exception
	{
		Connection connection = clientConnection();
		long now = System.currentTimeMillis();
		var burst = ClockOffsetProbeScheduler.prepareBurst(connection, now);
		check(deliver(reply(burst.nonce(), now), NetworkDirection.PLAY_TO_CLIENT, connection),
				"S2C response would fall through to vanilla and be dispatched twice");
		check(deliver(new ClockOffsetProbeRequestPacket(burst.nonce(), now),
				NetworkDirection.PLAY_TO_SERVER, new Connection(PacketFlow.SERVERBOUND)),
				"C2S request with a null sender must still be handled");
	}

	private static void discardedReplies() throws Exception
	{
		Connection connection = clientConnection();
		long now = System.currentTimeMillis();
		var burst = ClockOffsetProbeScheduler.prepareBurst(connection, now);
		ClockOffsetProbeScheduler.reset();
		check(deliver(reply(burst.nonce(), now), NetworkDirection.PLAY_TO_CLIENT, connection),
				"discarding a stale response must not produce vanilla fallback");
		unknown();
	}

	private static void burstSlots()
	{
		Connection connection = clientConnection();
		long start = System.currentTimeMillis();
		int[] slots = { 0, 100, 250, 500, 1000 };
		long nonce = 0;
		for (int i = 0; i < slots.length; i++) {
			if (i > 0)
				check(ClockOffsetProbeScheduler.prepareBurst(connection, start + slots[i] - 1) == null,
						"probe was reserved before its slot");
			var burst = ClockOffsetProbeScheduler.prepareBurst(connection, start + slots[i]);
			check(burst != null && burst.firstSlot() == i && burst.limit() == i + 1, "wrong due slots");
			if (i == 0)
				nonce = burst.nonce();
			check(burst.nonce() == nonce, "nonce changed inside a window");
			check(ClockOffsetProbeScheduler.prepareBurst(connection, start + slots[i]) == null,
					"a repeated tick reserved the same slot twice");
		}
		check(ClockOffsetProbeScheduler.prepareBurst(connection, start + 59_999) == null,
				"more than five requests in a window");
		var next = ClockOffsetProbeScheduler.prepareBurst(connection, start + 60_000);
		check(next != null && next.firstSlot() == 0 && next.limit() == 1 && next.nonce() != nonce,
				"the next window must have a new nonce and budget");
	}

	private static void delayedTick()
	{
		Connection connection = clientConnection();
		long start = System.currentTimeMillis();
		ClockOffsetProbeScheduler.prepareBurst(connection, start);
		var remaining = ClockOffsetProbeScheduler.prepareBurst(connection, start + 5000);
		check(remaining != null && remaining.firstSlot() == 1 && remaining.limit() == 5,
				"a delayed tick must reserve only the four remaining probes");
		check(ClockOffsetProbeScheduler.prepareBurst(connection, start + 5001) == null,
				"delayed-tick catch-up exceeded the budget");
	}

	private static void resetRejectsReplies()
	{
		Connection connection = clientConnection();
		long now = System.currentTimeMillis();
		var burst = ClockOffsetProbeScheduler.prepareBurst(connection, now);
		accept(reply(burst.nonce(), now), connection, now + 10);
		check(ClockOffsetEstimator.state() == ClockOffsetEstimator.OffsetState.PROVISIONAL, "sample missing");
		ClockOffsetProbeScheduler.reset();
		accept(reply(burst.nonce(), now), connection, now + 20);
		unknown();
		check(ClockOffsetProbeScheduler.prepareBurst(null, now + 30) == null, "disconnected probe scheduled");
	}

	private static void reconnect()
	{
		Connection oldConnection = clientConnection();
		Connection connection = clientConnection();
		long now = System.currentTimeMillis();
		var old = ClockOffsetProbeScheduler.prepareBurst(oldConnection, now);
		accept(reply(old.nonce(), now), oldConnection, now + 10);
		var next = ClockOffsetProbeScheduler.prepareBurst(connection, now + 20);
		check(next.nonce() != old.nonce(), "reconnect reused a nonce");
		unknown();
		accept(reply(next.nonce(), now + 20), oldConnection, now + 30);
		accept(reply(old.nonce(), now + 20), connection, now + 30);
		unknown();
		accept(reply(next.nonce(), now + 20), connection, now + 30);
		check(ClockOffsetEstimator.state() == ClockOffsetEstimator.OffsetState.PROVISIONAL,
				"the new connection's sample was rejected");
	}

	private static void rollover()
	{
		Connection connection = clientConnection();
		long start = System.currentTimeMillis();
		var old = ClockOffsetProbeScheduler.prepareBurst(connection, start);
		for (int i = 0; i < 5; i++)
			accept(reply(old.nonce(), start), connection, start + 10 + i);
		check(ClockOffsetEstimator.isCalibrated(), "initial window did not calibrate");
		ClockOffsetProbeScheduler.prepareBurst(connection, start + 60_000);
		unknown();
		accept(reply(old.nonce(), start), connection, start + 60_010);
		unknown();
	}

	private static void expiredReply()
	{
		Connection connection = clientConnection();
		long start = System.currentTimeMillis();
		var burst = ClockOffsetProbeScheduler.prepareBurst(connection, start);
		accept(reply(burst.nonce(), start), connection, start + 60_000);
		unknown();
	}

	private static void calibration() throws Exception
	{
		Connection connection = clientConnection();
		long start = System.currentTimeMillis() - 1000;
		var burst = ClockOffsetProbeScheduler.prepareBurst(connection, start);
		ClockOffsetProbeScheduler.prepareBurst(connection, start + 1000);
		for (int i = 1; i <= 5; i++) {
			check(deliver(reply(burst.nonce(), System.currentTimeMillis() - 10),
					NetworkDirection.PLAY_TO_CLIENT, connection), "response was not consumed by Forge");
			check(ClockOffsetEstimator.state() == (i < 5 ? ClockOffsetEstimator.OffsetState.PROVISIONAL
					: ClockOffsetEstimator.OffsetState.CALIBRATED), "duplicated or lost calibration sample " + i);
		}
	}

	private static void resetBeforeResponse() throws Exception
	{
		Connection connection = clientConnection();
		long now = System.currentTimeMillis();
		var burst = ClockOffsetProbeScheduler.prepareBurst(connection, now);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread response = worker(() -> accept(reply(burst.nonce(), now), connection, now + 10), failure);
		synchronized (field(ClockOffsetProbeScheduler.class, "LOCK").get(null)) {
			response.start();
			awaitBlocked(response);
			ClockOffsetProbeScheduler.reset();
		}
		join(response, failure);
		unknown();
	}

	private static void resetDuringSample() throws Exception
	{
		Connection connection = clientConnection();
		long now = System.currentTimeMillis();
		var burst = ClockOffsetProbeScheduler.prepareBurst(connection, now);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread response = worker(() -> accept(reply(burst.nonce(), now), connection, now + 10), failure);
		Thread reset = worker(ClockOffsetProbeScheduler::reset, failure);
		synchronized (field(ClockOffsetEstimator.class, "LOCK").get(null)) {
			response.start();
			awaitBlocked(response);
			reset.start();
			awaitBlocked(reset);
		}
		join(response, failure);
		join(reset, failure);
		unknown();
	}

	private static void protocol() throws Exception
	{
		for (String name : new String[] { "tryServerVersionOnClient", "tryClientVersionOnServer" }) {
			Method predicate = networkInstance.getClass().getDeclaredMethod(name, String.class);
			predicate.setAccessible(true);
			check((boolean) predicate.invoke(networkInstance, "17"), name + " rejects current protocol");
			check(!(boolean) predicate.invoke(networkInstance, "16"), name + " accepts the old packet set");
			check((boolean) predicate.invoke(networkInstance, NetworkRegistry.ABSENT.version()), "missing peer rejected");
			check((boolean) predicate.invoke(networkInstance, NetworkRegistry.ACCEPTVANILLA), "vanilla peer rejected");
		}
	}

	private static Connection clientConnection()
	{
		return new Connection(PacketFlow.CLIENTBOUND);
	}

	private static ClockOffsetProbeResponsePacket reply(long nonce, long t1)
	{
		return new ClockOffsetProbeResponsePacket(nonce, t1, t1 + 502, t1 + 503);
	}

	private static void accept(ClockOffsetProbeResponsePacket packet, Connection connection, long t4)
	{
		ClockOffsetProbeScheduler.handleProbeResponse(packet, connection, t4);
	}

	private static boolean deliver(Object packet, NetworkDirection direction, Connection connection) throws Exception
	{
		return (boolean) dispatch.invoke(networkInstance, direction, channel.toVanillaPacket(packet, direction), connection);
	}

	private static Field field(Class<?> type, String name) throws Exception
	{
		Field result = type.getDeclaredField(name);
		result.setAccessible(true);
		return result;
	}

	private static Thread worker(Runnable action, AtomicReference<Throwable> failure)
	{
		Thread thread = new Thread(() -> {
			try {
				action.run();
			} catch (Throwable error) {
				failure.compareAndSet(null, error);
			}
		}, "mbm-probe-regression");
		thread.setDaemon(true);
		return thread;
	}

	private static void awaitBlocked(Thread thread) throws Exception
	{
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (thread.getState() != Thread.State.BLOCKED && thread.isAlive() && System.nanoTime() < deadline)
			Thread.sleep(1L);
		check(thread.getState() == Thread.State.BLOCKED, "worker did not reach the synchronization boundary");
	}

	private static void join(Thread thread, AtomicReference<Throwable> failure) throws Exception
	{
		thread.join(5000L);
		check(!thread.isAlive(), "probe lifecycle deadlocked");
		if (failure.get() != null)
			throw new AssertionError("worker failed", failure.get());
	}

	private static void unknown()
	{
		check(ClockOffsetEstimator.state() == ClockOffsetEstimator.OffsetState.UNKNOWN, "stale calibration survived");
		check(Double.isNaN(ClockOffsetEstimator.offsetMillis()), "unknown offset must remain NaN");
	}

	private static void check(boolean condition, String message)
	{
		if (!condition)
			throw new AssertionError(message);
	}

	private static void run(String name, Check action) throws Exception
	{
		ClockOffsetProbeScheduler.reset();
		try {
			action.run();
			passed++;
			System.out.println("PASS " + name);
		} finally {
			ClockOffsetProbeScheduler.reset();
		}
	}

	@FunctionalInterface
	private interface Check
	{
		void run() throws Exception;
	}
}
