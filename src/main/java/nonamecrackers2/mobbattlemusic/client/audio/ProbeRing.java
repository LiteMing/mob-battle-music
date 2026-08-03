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
 * ~20 seconds). Sampling stores raw numbers only - no allocation, no string
 * formatting, no I/O on the tick thread. Formatting happens only at dump time,
 * and the file write runs on a one-off daemon thread.
 */
public final class ProbeRing
{
	public static final int CAPACITY = 400;

	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/ProbeRing");
	private static final ProbeSample[] SAMPLES = new ProbeSample[CAPACITY];
	private static int nextIndex;
	private static int count;

	private ProbeRing() {}

	public record ProbeSample(long epochMillis, long tick, int worldState, int clockState, boolean focused,
			float trackGain, float muteGain, float seekGain, long audiblePosMillis, long decodedPosMillis,
			long underruns, int seeks, long playCalls) {}

	public static synchronized void sample(ProbeSample sample)
	{
		SAMPLES[nextIndex] = sample;
		nextIndex = (nextIndex + 1) % CAPACITY;
		if (count < CAPACITY)
			count++;
	}

	public static synchronized String format()
	{
		StringBuilder builder = new StringBuilder(count * 160);
		int start = count < CAPACITY ? 0 : nextIndex;
		for (int i = 0; i < count; i++) {
			ProbeSample sample = SAMPLES[(start + i) % CAPACITY];
			builder.append("t=").append(sample.epochMillis())
					.append(" tick=").append(sample.tick())
					.append(" | world=").append(sample.worldState())
					.append(" clock=").append(sample.clockState())
					.append(" focused=").append(sample.focused())
					.append(" track=").append(String.format(Locale.ROOT, "%.2f", sample.trackGain()))
					.append(" mute=").append(String.format(Locale.ROOT, "%.2f", sample.muteGain()))
					.append(" seek=").append(String.format(Locale.ROOT, "%.2f", sample.seekGain()))
					.append(" audible=").append(sample.audiblePosMillis())
					.append(" decoded=").append(sample.decodedPosMillis())
					.append(" underruns=").append(sample.underruns())
					.append(" seeks=").append(sample.seeks())
					.append(" playCalls=").append(sample.playCalls()).append('\n');
		}
		return builder.toString();
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
