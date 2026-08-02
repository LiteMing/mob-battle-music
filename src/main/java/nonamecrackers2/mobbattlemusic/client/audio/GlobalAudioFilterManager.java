package nonamecrackers2.mobbattlemusic.client.audio;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;

import com.mojang.blaze3d.audio.Channel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import nonamecrackers2.mobbattlemusic.mixin.MixinChannelAccessor;
import nonamecrackers2.mobbattlemusic.mixin.MixinSoundEngineAccessor;
import nonamecrackers2.mobbattlemusic.mixin.MixinSoundManagerAccessor;

public final class GlobalAudioFilterManager
{
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/GlobalAudioFilterManager");
	private static volatile double lowPassHz;
	private static volatile double highPassHz;
	private static volatile long revision;
	private static int configuredFilter;
	private static long configuredRevision = -1L;
	private static boolean unsupported;

	private GlobalAudioFilterManager() {}

	public static void onDefinitionsChanged(List<AudioFilterDefinition> definitions)
	{
		double lowPass = 0.0D;
		double highPass = 0.0D;
		for (AudioFilterDefinition definition : definitions) {
			if (definition.scope() != AudioFilterDefinition.Scope.GLOBAL)
				continue;
			if (definition.type() == AudioFilterDefinition.Type.LOW_PASS)
				lowPass = lowPass == 0.0D ? definition.frequencyHz() : Math.min(lowPass, definition.frequencyHz());
			else if (definition.type() == AudioFilterDefinition.Type.HIGH_PASS)
				highPass = Math.max(highPass, definition.frequencyHz());
		}
		if (lowPass != lowPassHz || highPass != highPassHz) {
			lowPassHz = lowPass;
			highPassHz = highPass;
			revision++;
			applyToExistingChannels();
		}
	}

	public static void apply(Channel channel)
	{
		apply(((MixinChannelAccessor)(Object)channel).mobbattlemusic$getSource());
	}

	public static void reset()
	{
		configuredFilter = 0;
		configuredRevision = -1L;
		unsupported = false;
	}

	private static void apply(int source)
	{
		try {
			if (lowPassHz <= 0.0D && highPassHz <= 0.0D) {
				AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
				return;
			}
			if (unsupported || !supportsEfx())
				return;
			ensureConfiguredFilter();
			AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, configuredFilter);
		} catch (Throwable e) {
			if (!unsupported)
				LOGGER.warn("OpenAL EFX is unavailable; global MBM filters were disabled", e);
			unsupported = true;
		}
	}

	private static void ensureConfiguredFilter()
	{
		if (configuredFilter == 0)
			configuredFilter = EXTEfx.alGenFilters();
		if (configuredRevision == revision)
			return;
		if (lowPassHz > 0.0D && highPassHz > 0.0D) {
			EXTEfx.alFilteri(configuredFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_BANDPASS);
			EXTEfx.alFilterf(configuredFilter, EXTEfx.AL_BANDPASS_GAIN, 1.0F);
			EXTEfx.alFilterf(configuredFilter, EXTEfx.AL_BANDPASS_GAINHF, cutoffGain(lowPassHz));
			EXTEfx.alFilterf(configuredFilter, EXTEfx.AL_BANDPASS_GAINLF, cutoffGain(highPassHz));
		} else if (lowPassHz > 0.0D) {
			EXTEfx.alFilteri(configuredFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
			EXTEfx.alFilterf(configuredFilter, EXTEfx.AL_LOWPASS_GAIN, 1.0F);
			EXTEfx.alFilterf(configuredFilter, EXTEfx.AL_LOWPASS_GAINHF, cutoffGain(lowPassHz));
		} else {
			EXTEfx.alFilteri(configuredFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_HIGHPASS);
			EXTEfx.alFilterf(configuredFilter, EXTEfx.AL_HIGHPASS_GAIN, 1.0F);
			EXTEfx.alFilterf(configuredFilter, EXTEfx.AL_HIGHPASS_GAINLF, cutoffGain(highPassHz));
		}
		configuredRevision = revision;
	}

	private static float cutoffGain(double frequencyHz)
	{
		return (float)Math.max(0.05D, Math.min(1.0D, Math.sqrt(frequencyHz / 20_000.0D)));
	}

	private static boolean supportsEfx()
	{
		long context = ALC10.alcGetCurrentContext();
		if (context == 0L)
			return false;
		long device = ALC10.alcGetContextsDevice(context);
		return device != 0L && ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX");
	}

	private static void applyToExistingChannels()
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSoundManager() == null)
			return;
		SoundEngine engine = ((MixinSoundManagerAccessor)mc.getSoundManager()).mobbattlemusic$getSoundEngine();
		for (ChannelAccess.ChannelHandle handle :
				((MixinSoundEngineAccessor)engine).mobbattlemusic$getInstanceToChannel().values())
			handle.execute(GlobalAudioFilterManager::apply);
	}
}
