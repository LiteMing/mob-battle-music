package nonamecrackers2.mobbattlemusic.client.audio;

import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;

public final class PcmFilterChain
{
	private final List<Processor> processors;
	private final int channels;
	private final int frameSize;

	private PcmFilterChain(List<Processor> processors, AudioFormat format)
	{
		this.processors = processors;
		this.channels = format.getChannels();
		this.frameSize = format.getFrameSize();
	}

	public static PcmFilterChain create(List<AudioFilterDefinition> definitions, AudioFormat format)
	{
		if (format.isBigEndian() || format.getSampleSizeInBits() != 16 ||
				!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()))
			return new PcmFilterChain(List.of(), format);
		List<Processor> processors = new ArrayList<>();
		for (AudioFilterDefinition definition : definitions) {
			switch (definition.type()) {
				case LOW_PASS, HIGH_PASS, PEAK_EQ -> processors.add(new Biquad(definition, format));
				case LOFI -> processors.add(new Lofi(definition, format));
			}
		}
		return new PcmFilterChain(List.copyOf(processors), format);
	}

	public void process(byte[] buffer, int length)
	{
		if (this.processors.isEmpty())
			return;
		int alignedLength = length - length % this.frameSize;
		for (int offset = 0; offset < alignedLength; offset += this.frameSize) {
			for (int channel = 0; channel < this.channels; channel++) {
				int sampleOffset = offset + channel * 2;
				int sample = (short)((buffer[sampleOffset] & 0xFF) | (buffer[sampleOffset + 1] << 8));
				double value = sample / 32768.0D;
				for (Processor processor : this.processors)
					value = processor.process(channel, value);
				int output = (int)Math.round(Math.max(-1.0D, Math.min(0.999969D, value)) * 32768.0D);
				buffer[sampleOffset] = (byte)output;
				buffer[sampleOffset + 1] = (byte)(output >>> 8);
			}
		}
	}

	private interface Processor
	{
		double process(int channel, double input);
	}

	private static final class Biquad implements Processor
	{
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
		private final int holdFrames;
		private final double quantizationLevels;
		private final int[] counters;
		private final double[] held;

		private Lofi(AudioFilterDefinition definition, AudioFormat format)
		{
			this.holdFrames = Math.max(1, Math.round(format.getSampleRate() / definition.sampleRateHz()));
			this.quantizationLevels = 1 << (definition.bitDepth() - 1);
			this.counters = new int[format.getChannels()];
			this.held = new double[format.getChannels()];
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
