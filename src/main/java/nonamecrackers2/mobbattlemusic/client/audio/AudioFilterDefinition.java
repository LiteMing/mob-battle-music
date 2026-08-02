package nonamecrackers2.mobbattlemusic.client.audio;

import java.util.List;
import java.util.Locale;

import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;

public record AudioFilterDefinition(ResourceLocation id, Scope scope, Type type, double frequencyHz, double q,
		double gainDb, int bitDepth, int sampleRateHz, List<IdleCondition> conditions)
{
	public AudioFilterDefinition
	{
		frequencyHz = clamp(frequencyHz, 20.0D, 20_000.0D);
		q = clamp(q, 0.1D, 10.0D);
		gainDb = clamp(gainDb, -12.0D, 12.0D);
		bitDepth = Math.max(8, Math.min(16, bitDepth));
		sampleRateHz = Math.max(8_000, Math.min(48_000, sampleRateHz));
		conditions = List.copyOf(conditions);
	}

	private static double clamp(double value, double min, double max)
	{
		return Math.max(min, Math.min(max, value));
	}

	public enum Scope
	{
		MBM,
		GLOBAL;

		public static Scope parse(String value)
		{
			return "global".equalsIgnoreCase(value) ? GLOBAL : MBM;
		}
	}

	public enum Type
	{
		LOW_PASS,
		HIGH_PASS,
		PEAK_EQ,
		LOFI;

		public static Type parse(String value)
		{
			return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
		}
	}
}
