package nonamecrackers2.mobbattlemusic.client.audio;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;

public final class MobBattleMusicAudioFilters
{
	private MobBattleMusicAudioFilters() {}

	public static Builder lowPass(String id, String scope, double cutoffHz, double q)
	{
		return new Builder(id, scope, AudioFilterDefinition.Type.LOW_PASS, cutoffHz, q, 0.0D, 16, 48_000);
	}

	public static Builder highPass(String id, String scope, double cutoffHz, double q)
	{
		return new Builder(id, scope, AudioFilterDefinition.Type.HIGH_PASS, cutoffHz, q, 0.0D, 16, 48_000);
	}

	public static Builder peakEq(String id, String scope, double centerHz, double q, double gainDb)
	{
		return new Builder(id, scope, AudioFilterDefinition.Type.PEAK_EQ, centerHz, q, gainDb, 16, 48_000);
	}

	public static Builder lofi(String id, String scope, int bitDepth, int sampleRateHz)
	{
		return new Builder(id, scope, AudioFilterDefinition.Type.LOFI, 1_000.0D, 0.707D, 0.0D,
				bitDepth, sampleRateHz);
	}

	/** Adds a semitone transposition filter; positive values raise pitch. */
	public static Builder pitchShift(String id, String scope, double semitones)
	{
		return new Builder(id, scope, AudioFilterDefinition.Type.PITCH_SHIFT, 1_000.0D, 0.707D, 0.0D,
				16, 48_000).pitchSemitones(semitones);
	}

	public static Builder pitchUp(String id, String scope, double semitones)
	{
		return pitchShift(id, scope, Math.abs(semitones));
	}

	public static Builder pitchDown(String id, String scope, double semitones)
	{
		return pitchShift(id, scope, -Math.abs(semitones));
	}

	public static boolean remove(String id)
	{
		return AudioFilterManager.remove(id);
	}

	public static void reloadConfig()
	{
		AudioFilterManager.loadConfig();
	}

	public static final class Builder
	{
		private final ResourceLocation id;
		private final AudioFilterDefinition.Scope scope;
		private final AudioFilterDefinition.Type type;
		private final double frequencyHz;
		private final double q;
		private final double gainDb;
		private final int bitDepth;
		private final int sampleRateHz;
		private double pitchSemitones;
		private long transitionMillis = 20L;
		private final List<IdleCondition> conditions = new ArrayList<>();

		private Builder(String id, String scope, AudioFilterDefinition.Type type, double frequencyHz, double q,
				double gainDb, int bitDepth, int sampleRateHz)
		{
			this.id = new ResourceLocation(id);
			this.scope = AudioFilterDefinition.Scope.parse(scope);
			this.type = type;
			this.frequencyHz = frequencyHz;
			this.q = q;
			this.gainDb = gainDb;
			this.bitDepth = bitDepth;
			this.sampleRateHz = sampleRateHz;
		}

		public Builder condition(String type, String argument)
		{
			return condition(type, argument, false);
		}

		public Builder condition(String type, String argument, boolean inverted)
		{
			this.conditions.add(new IdleCondition(type, argument, inverted));
			return this;
		}

		public Builder pitchSemitones(double semitones)
		{
			this.pitchSemitones = semitones;
			return this;
		}

		public Builder transitionMillis(long millis)
		{
			this.transitionMillis = millis;
			return this;
		}

		public void register()
		{
			AudioFilterManager.register(new AudioFilterDefinition(this.id, true, this.scope, this.type,
					this.frequencyHz, this.q, this.gainDb, this.bitDepth, this.sampleRateHz,
					this.pitchSemitones, this.transitionMillis, this.conditions));
		}
	}
}
