package nonamecrackers2.mobbattlemusic.client.audio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * AUD-54: fixed-size ring buffer of numeric probe snapshots (400 ticks,
 * ~20 seconds). Sampling performs one record allocation plus derived-value
 * computation per tick - no string formatting and no I/O on the tick thread;
 * those happen only at dump time (honest accounting, AUD-39 追加). Formatting
 * copies the array under the lock and formats outside it (AUD-54 追加). The
 * field set is a superset of the single-frame probe (debug session reuses the
 * last frame).
 */
public final class ProbeRing
{
	public static final int CAPACITY = 400;

	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/ProbeRing");
	private static final ProbeSample[] SAMPLES = new ProbeSample[CAPACITY];
	private static int nextIndex;
	private static int count;

	private ProbeRing() {}

	public record ProbeSample(long epochMillis, long tick,
			int worldState, int sessionState, boolean focused, boolean paused, boolean published,
			int clockState, boolean anchorValid,
			float trackGain, float muteGain, float seekGain, float trackTarget, int gateOwner,
			long audiblePosMillis, long decodedPosMillis, long driftMillis, long sinceAnchorMillis,
			long injectedTtlMillis, int seeks, long sinceSeekMillis, long seekCostMillis,
			long lineBufferBytes, int lineFillBytes, long watermarkMillis, boolean watermarkAdaptive, long underruns,
			float mixCurrent, float mixTarget, boolean removalPending,
			int handles, long markersFired, long playCalls,
			// K8-B: lifecycle generations and line/thread diagnostics
			long levelGen, long sessionGen, long playerGen,
			// K10-C: per-player line/thread counts - main and preview are
			// separate (legal parallel playback must not trip either player's
			// single-line invariant)
			int mainOpenLines, int mainPlaybackThreads,
			int previewOpenLines, int previewPlaybackThreads,
			long playRequests, long stopRequests,
			String currentIntent,
			// K9-3: persistent gains
			float userGain, float previewGain) {}

	public static synchronized void sample(ProbeSample sample)
	{
		SAMPLES[nextIndex] = sample;
		nextIndex = (nextIndex + 1) % CAPACITY;
		if (count < CAPACITY)
			count++;
	}

	/**
	 * AUD-54 追加: the most recent frame, reused by the debug session command
	 * so both outputs carry exactly the same field set.
	 */
	public static synchronized ProbeSample lastSample()
	{
		if (count == 0)
			return null;
		return SAMPLES[(nextIndex - 1 + CAPACITY) % CAPACITY];
	}

	/**
	 * AUD-54 追加: formatting happens outside the sampling lock - only the
	 * array copy is guarded.
	 */
	public static String format()
	{
		ProbeSample[] snapshot;
		int sampleCount;
		synchronized (ProbeRing.class) {
			snapshot = SAMPLES.clone();
			sampleCount = count;
		}
		StringBuilder builder = new StringBuilder(sampleCount * 260);
		int start = sampleCount < CAPACITY ? 0 : nextIndex;
		for (int i = 0; i < sampleCount; i++) {
			ProbeSample sample = snapshot[(start + i) % CAPACITY];
			if (sample != null)
				formatSample(builder, sample);
		}
		return builder.toString();
	}

	public static String formatLastSample()
	{
		ProbeSample last = lastSample();
		if (last == null)
			return "";
		StringBuilder builder = new StringBuilder(320);
		formatSample(builder, last);
		return builder.toString();
	}

	private static void formatSample(StringBuilder builder, ProbeSample s)
	{
		builder.append("t=").append(s.epochMillis())
				.append(" tick=").append(s.tick())
				.append(" | world=").append(s.worldState())
				.append(" session=").append(s.sessionState())
				.append(" focused=").append(s.focused())
				.append(" paused=").append(s.paused())
				.append(" published=").append(s.published())
				.append(" clock=").append(s.clockState())
				.append(" anchor=").append(s.anchorValid())
				.append(" track=").append(String.format(Locale.ROOT, "%.2f", s.trackGain()))
				.append(" mute=").append(String.format(Locale.ROOT, "%.2f", s.muteGain()))
				.append(" seek=").append(String.format(Locale.ROOT, "%.2f", s.seekGain()))
				.append(" trackTarget=").append(String.format(Locale.ROOT, "%.2f", s.trackTarget()))
				.append(" gateOwner=").append(s.gateOwner())
				// K3 P0-a: unknown audible position (-1) is n/a, not 0
				.append(" audible=").append(s.audiblePosMillis() < 0L
						? "n/a" : String.valueOf(s.audiblePosMillis()))
				// K4 P1: unknown decoded position is n/a, not 0
				.append(" decoded=").append(s.decodedPosMillis() < 0L
						? "n/a" : String.valueOf(s.decodedPosMillis()))
				// R4: no anchor -> drift is n/a, never a fabricated zero
				.append(" drift=").append(s.driftMillis() == Long.MIN_VALUE
						? "n/a" : String.valueOf(s.driftMillis()))
				.append(" sinceAnchor=").append(s.sinceAnchorMillis())
				.append(" injectedTtl=").append(s.injectedTtlMillis())
				.append(" seeks=").append(s.seeks())
				.append(" sinceSeek=").append(s.sinceSeekMillis())
				.append(" seekCost=").append(s.seekCostMillis())
				.append(" lineBuf=").append(s.lineBufferBytes())
				.append(" lineFill=").append(s.lineFillBytes())
				.append(" watermark=").append(s.watermarkMillis())
				.append(" wmAdaptive=").append(s.watermarkAdaptive())
				.append(" underruns=").append(s.underruns())
				.append(" mix=").append(String.format(Locale.ROOT, "%.2f", s.mixCurrent()))
				.append(" mixTarget=").append(String.format(Locale.ROOT, "%.2f", s.mixTarget()))
				.append(" pendingRemoval=").append(s.removalPending())
				.append(" handles=").append(s.handles())
				.append(" markersFired=").append(s.markersFired())
				.append(" playCalls=").append(s.playCalls())
				// K8-B: lifecycle generations and line/thread diagnostics;
				// openLines > 1 violates the single-line invariant and is
				// emitted as a fixed-format error
				.append(" levelGen=").append(s.levelGen())
				.append(" sessionGen=").append(s.sessionGen())
				.append(" playerGen=").append(s.playerGen())
				.append(" mainPlaybackThreads=").append(s.mainPlaybackThreads())
				.append(" mainOpenLines=").append(s.mainOpenLines())
				.append(" previewPlaybackThreads=").append(s.previewPlaybackThreads())
				.append(" previewOpenLines=").append(s.previewOpenLines())
				.append(" playRequests=").append(s.playRequests())
				.append(" stopRequests=").append(s.stopRequests())
				.append(" intent=").append(s.currentIntent() == null ? "-" : s.currentIntent())
				.append(" userGain=").append(String.format(Locale.ROOT, "%.2f", s.userGain()))
				.append(" previewGain=").append(String.format(Locale.ROOT, "%.2f", s.previewGain()));
		// K10-C/K11-C: per-player invariant checks - parallel main+preview
		// playback is legal and must NOT trip either check; thread counts
		// are checked too (a refused-generation event shows up here)
		if (s.mainOpenLines() > 1)
			builder.append(" ERROR mainOpenLines>1 single-line invariant violated");
		if (s.previewOpenLines() > 1)
			builder.append(" ERROR previewOpenLines>1 single-line invariant violated");
		if (s.mainPlaybackThreads() > 1)
			builder.append(" ERROR mainPlaybackThreads>1 single-thread invariant violated");
		if (s.previewPlaybackThreads() > 1)
			builder.append(" ERROR previewPlaybackThreads>1 single-thread invariant violated");
		builder.append('\n');
	}

	/**
	 * AUD-54: dump the ring to .minecraft/mobbattlemusic_probe_<epoch>.txt on
	 * a one-off daemon thread. Returns the file path for chat feedback.
	 */
	public static String dumpToFile()
	{
		String content = format();
		Path path = Minecraft.getInstance().gameDirectory.toPath()
				.resolve("mobbattlemusic_probe_" + System.currentTimeMillis() + ".txt");
		Thread thread = new Thread(() -> {
			try {
				Files.writeString(path, content, StandardCharsets.UTF_8);
				LOGGER.info("[MBM] probe dump written to {} ({} lines)", path, content.split("\n", -1).length);
			} catch (Exception e) {
				LOGGER.error("[MBM] failed to write probe dump to {}", path, e);
			}
		}, "MBM-ProbeDump");
		thread.setDaemon(true);
		thread.start();
		return path.toString();
	}
}
