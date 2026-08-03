package nonamecrackers2.mobbattlemusic.client.audio;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.sound.sampled.AudioFormat;

public final class PcmFilterChain
{
	// AUD-48: dry/wet mix envelope (in 150ms, out 350ms), shared by all chains
	private static final long MIX_IN_MILLIS = 150L;
	private static final long MIX_OUT_MILLIS = 350L;
	// AUD-48: crossfade between old and new chains when processor state cannot
	// be preserved
	private static final long CHAIN_TRANSITION_MILLIS = 20L;
	private static final MixEnvelope MIX = new MixEnvelope();

	private final List<Processor> processors;
	private final int channels;
	private final int frameSize;
	private @Nullable PcmFilterChain transitionFrom;
	private long transitionStartMillis;

	private PcmFilterChain(List<Processor> processors, AudioFormat format)
	{
		this.processors = processors;
		this.channels = format.getChannels();
		this.frameSize = format.getFrameSize();
		this.transitionStartMillis = System.currentTimeMillis();
	}

	public static PcmFilterChain create(List<AudioFilterDefinition> definitions, AudioFormat format)
	{
		return create(definitions, format, null);
	}

	/**
	 * AUD-48: rebuild a chain from definitions. Processors with the same
	 * definition (same type and parameters) at the same position keep their
	 * internal state (biquad x1/x2/y1/y2, lofi counters/held); when state
	 * cannot be preserved a >=20ms crossfade runs from the previous chain.
	 */
	public static PcmFilterChain create(List<AudioFilterDefinition> definitions, AudioFormat format,
			@Nullable PcmFilterChain previous)
	{
		if (format.isBigEndian() || format.getSampleSizeInBits() != 16 ||
				!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()))
			return new PcmFilterChain(List.of(), format);
		List<Processor> processors = new ArrayList<>();
		boolean preservedAll = previous != null;
		for (int i = 0; i < definitions.size(); i++) {
			AudioFilterDefinition definition = definitions.get(i);
			switch (definition.type()) {
				case LOW_PASS, HIGH_PASS, PEAK_EQ -> {
					Biquad biquad = new Biquad(definition, format);
					Biquad old = previous == null ? null : previous.biquadAt(i);
					if (old != null && old.matches(definition))
						biquad.copyStateFrom(old);
					else
						preservedAll = false;
					processors.add(biquad);
				}
				case LOFI -> {
					Lofi lofi = new Lofi(definition, format);
					Lofi old = previous == null ? null : previous.lofiAt(i);
					if (old != null && old.matches(definition))
						lofi.copyStateFrom(old);
					else
						preservedAll = false;
					processors.add(lofi);
				}
			}
		}
		PcmFilterChain chain = new PcmFilterChain(List.copyOf(processors), format);
		if (!preservedAll && previous != null && !previous.processors.isEmpty())
			chain.transitionFrom = previous;
		return chain;
	}

	public void process(byte[] buffer, int length)
	{
		// AUD-45 v1.1: envelope values are pure functions of time, computed on
		// read; no advancement step, no thread dependency
		if (this.processors.isEmpty() && this.transitionFrom == null && MIX.current() <= 0.001f)
			return;
		int alignedLength = length - length % this.frameSize;
		// AUD-48 v1.1: the crossfade progress is computed once per buffer
		PcmFilterChain transition = this.transitionFrom;
		double transitionProgress = transition == null ? 1.0D
				: (System.currentTimeMillis() - this.transitionStartMillis)
						/ (double)CHAIN_TRANSITION_MILLIS;
		for (int offset = 0; offset < alignedLength; offset += this.frameSize) {
			for (int channel = 0; channel < this.channels; channel++) {
				int sampleOffset = offset + channel * 2;
				int sample = (short)((buffer[sampleOffset] & 0xFF) | (buffer[sampleOffset + 1] << 8));
				double dry = sample / 32768.0D;
				double value = dry;
				for (Processor processor : this.processors)
					value = processor.process(channel, value);
				if (transition != null) {
					// AUD-48: crossfade from the previous chain while its state
					// drains
					double oldValue = dry;
					for (Processor processor : transition.processors)
						oldValue = processor.process(channel, oldValue);
					if (transitionProgress >= 1.0D) {
						this.transitionFrom = null;
						transition = null;
					} else {
						value = oldValue * (1.0D - transitionProgress) + value * transitionProgress;
					}
				}
				// AUD-48: dry/wet crossfade, out = dry x (1 - mix) + wet x mix
				double mix = MIX.current();
				value = dry * (1.0D - mix) + value * mix;
				int output = (int)Math.round(Math.max(-1.0D, Math.min(0.999969D, value)) * 32768.0D);
				buffer[sampleOffset] = (byte)output;
				buffer[sampleOffset + 1] = (byte)(output >>> 8);
			}
		}
	}

	// AUD-48: mix envelope accessors for the probe
	public static float mixCurrent()
	{
		return MIX.current();
	}

	public static float mixTarget()
	{
		return MIX.target();
	}

	// AUD-48: called on activation (in, 150ms) / deactivation (out, 350ms)
	public static void setMixTarget(float target, long fadeMillis)
	{
		MIX.setTarget(target, fadeMillis);
	}

	public static long mixInMillis()
	{
		return MIX_IN_MILLIS;
	}

	public static long mixOutMillis()
	{
		return MIX_OUT_MILLIS;
	}

	private @Nullable Biquad biquadAt(int index)
	{
		if (index >= 0 && index < this.processors.size() && this.processors.get(index) instanceof Biquad biquad)
			return biquad;
		return null;
	}

	private @Nullable Lofi lofiAt(int index)
	{
		if (index >= 0 && index < this.processors.size() && this.processors.get(index) instanceof Lofi lofi)
			return lofi;
		return null;
	}

	private interface Processor
	{
		double process(int channel, double input);
	}

	// AUD-44-shaped envelope (startValue, target, startMillis, durationMillis),
	// computed on read (AUD-45 v1.1). AUD-48 v1.1: steady-state default is
	// 0.0 (no filters).
	private static final class MixEnvelope
	{
		private volatile float target = 0.0f;
		private volatile float startValue = 0.0f;
		private volatile long startMillis;
		private volatile long durationMillis;

		private void setTarget(float newTarget, long fadeMillis)
		{
			float clamped = Math.max(0.0f, Math.min(1.0f, newTarget));
			if (clamped == this.target)
				return;
			this.startValue = this.current();
			this.startMillis = System.currentTimeMillis();
			this.durationMillis = Math.max(0L, fadeMillis);
			this.target = clamped;
		}

		private float current()
		{
			long d = this.durationMillis;
			float t = this.target;
			if (d <= 0L)
				return t;
			float progress = (float)((System.currentTimeMillis() - this.startMillis) / (double)d);
			if (progress >= 1.0f)
				return t;
			return this.startValue + (t - this.startValue) * progress;
		}

		private float target()
		{
			return this.target;
		}
	}

	private static final class Biquad implements Processor
	{
		private final AudioFilterDefinition definition;
		private final double b0;
		private final double b1;
		private final double b2;
		private final double a1;
		private final double a2;
		private final double[] x1;
		private final double[] x2;
		private final double[] y1;
		private final double[] y2;

		private Biquad(AudioFilterDefinition definition, AudioFormat format)
		{
			this.definition = definition;
			double sampleRate = format.getSampleRate();
			double frequency = Math.min(definition.frequencyHz(), sampleRate * 0.45D);
			double omega = 2.0D * Math.PI * frequency / sampleRate;
			double sin = Math.sin(omega);
			double cos = Math.cos(omega);
			double alpha = sin / (2.0D * definition.q());
			double rawB0;
			double rawB1;
			double rawB2;
			double rawA0;
			double rawA1;
			double rawA2;
			if (definition.type() == AudioFilterDefinition.Type.LOW_PASS) {
				rawB0 = (1.0D - cos) / 2.0D;
				rawB1 = 1.0D - cos;
				rawB2 = rawB0;
				rawA0 = 1.0D + alpha;
				rawA1 = -2.0D * cos;
				rawA2 = 1.0D - alpha;
			} else if (definition.type() == AudioFilterDefinition.Type.HIGH_PASS) {
				rawB0 = (1.0D + cos) / 2.0D;
				rawB1 = -(1.0D + cos);
				rawB2 = rawB0;
				rawA0 = 1.0D + alpha;
				rawA1 = -2.0D * cos;
				rawA2 = 1.0D - alpha;
			} else {
				double a = Math.pow(10.0D, definition.gainDb() / 40.0D);
				rawB0 = 1.0D + alpha * a;
				rawB1 = -2.0D * cos;
				rawB2 = 1.0D - alpha * a;
				rawA0 = 1.0D + alpha / a;
				rawA1 = -2.0D * cos;
				rawA2 = 1.0D - alpha / a;
			}
			this.b0 = rawB0 / rawA0;
			this.b1 = rawB1 / rawA0;
			this.b2 = rawB2 / rawA0;
			this.a1 = rawA1 / rawA0;
			this.a2 = rawA2 / rawA0;
			this.x1 = new double[format.getChannels()];
			this.x2 = new double[format.getChannels()];
			this.y1 = new double[format.getChannels()];
			this.y2 = new double[format.getChannels()];
		}

		// AUD-48: same type and parameters -> state can be preserved
		private boolean matches(AudioFilterDefinition other)
		{
			return this.definition.equals(other);
		}

		private void copyStateFrom(Biquad other)
		{
			System.arraycopy(other.x1, 0, this.x1, 0, this.x1.length);
			System.arraycopy(other.x2, 0, this.x2, 0, this.x2.length);
			System.arraycopy(other.y1, 0, this.y1, 0, this.y1.length);
			System.arraycopy(other.y2, 0, this.y2, 0, this.y2.length);
		}

		@Override
		public double process(int channel, double input)
		{
			double output = this.b0 * input + this.b1 * this.x1[channel] + this.b2 * this.x2[channel]
					- this.a1 * this.y1[channel] - this.a2 * this.y2[channel];
			this.x2[channel] = this.x1[channel];
			this.x1[channel] = input;
			this.y2[channel] = this.y1[channel];
			this.y1[channel] = output;
			return output;
		}
	}

	private static final class Lofi implements Processor
	{
		private final AudioFilterDefinition definition;
		private final int holdFrames;
		private final double quantizationLevels;
		private final int[] counters;
		private final double[] held;

		private Lofi(AudioFilterDefinition definition, AudioFormat format)
		{
			this.definition = definition;
			this.holdFrames = Math.max(1, Math.round(format.getSampleRate() / definition.sampleRateHz()));
			this.quantizationLevels = 1 << (definition.bitDepth() - 1);
			this.counters = new int[format.getChannels()];
			this.held = new double[format.getChannels()];
		}

		// AUD-48: same type and parameters -> state can be preserved
		private boolean matches(AudioFilterDefinition other)
		{
			return this.definition.equals(other);
		}

		private void copyStateFrom(Lofi other)
		{
			System.arraycopy(other.counters, 0, this.counters, 0, this.counters.length);
			System.arraycopy(other.held, 0, this.held, 0, this.held.length);
		}

		@Override
		public double process(int channel, double input)
		{
			if (this.counters[channel] <= 0) {
				this.held[channel] = Math.rint(input * this.quantizationLevels) / this.quantizationLevels;
				this.counters[channel] = this.holdFrames;
			}
			this.counters[channel]--;
			return this.held[channel];
		}
	}
}
