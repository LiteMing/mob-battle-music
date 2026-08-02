package nonamecrackers2.mobbattlemusic.client.audio;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;

import com.mojang.blaze3d.audio.Channel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import nonamecrackers2.mobbattlemusic.client.sound.MobBattleTrack;
import nonamecrackers2.mobbattlemusic.mixin.MixinChannelAccessor;
import nonamecrackers2.mobbattlemusic.mixin.MixinSoundEngineAccessor;
import nonamecrackers2.mobbattlemusic.mixin.MixinSoundManagerAccessor;

public final class GlobalAudioFilterManager
{
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/GlobalAudioFilterManager");
	private static volatile double lowPassHz;
	private static volatile double highPassHz;
	private static volatile double mbmLowPassHz;
	private static volatile double mbmHighPassHz;
	private static volatile long revision;
	private static int configuredFilter;
	private static int configuredMbmFilter;
	private static long configuredRevision = -1L;
	private static long configuredMbmRevision = -1L;
	private static final Map<SoundInstance, Long> MBM_APPLIED_REVISIONS = new WeakHashMap<>();
	private static boolean unsupported;

	private GlobalAudioFilterManager() {}

	public static void onDefinitionsChanged(List<AudioFilterDefinition> definitions)
	{
		double lowPass = 0.0D;
		double highPass = 0.0D;
		double mbmLowPass = 0.0D;
		double mbmHighPass = 0.0D;
		for (AudioFilterDefinition definition : definitions) {
			if (definition.type() == AudioFilterDefinition.Type.LOW_PASS) {
				if (definition.scope() == AudioFilterDefinition.Scope.GLOBAL)
					lowPass = minPositive(lowPass, definition.frequencyHz());
				else
					mbmLowPass = minPositive(mbmLowPass, definition.frequencyHz());
			} else if (definition.type() == AudioFilterDefinition.Type.HIGH_PASS) {
				if (definition.scope() == AudioFilterDefinition.Scope.GLOBAL)
					highPass = Math.max(highPass, definition.frequencyHz());
				else
					mbmHighPass = Math.max(mbmHighPass, definition.frequencyHz());
			}
		}
		if (lowPass != lowPassHz || highPass != highPassHz || mbmLowPass != mbmLowPassHz ||
				mbmHighPass != mbmHighPassHz) {
			lowPassHz = lowPass;
			highPassHz = highPass;
			mbmLowPassHz = mbmLowPass;
			mbmHighPassHz = mbmHighPass;
			revision++;
			applyToExistingChannels();
		}
	}

	public static void apply(Channel channel)
	{
		apply(((MixinChannelAccessor)(Object)channel).mobbattlemusic$getSource(), false);
	}

	public static void refreshMbmChannels(Map<SoundInstance, ChannelAccess.ChannelHandle> channels)
	{
		MBM_APPLIED_REVISIONS.keySet().removeIf(instance -> !channels.containsKey(instance));
		for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry : channels.entrySet()) {
			if (!(entry.getKey() instanceof MobBattleTrack) ||
					MBM_APPLIED_REVISIONS.getOrDefault(entry.getKey(), -1L) == revision)
				continue;
			entry.getValue().execute(channel -> apply(
					((MixinChannelAccessor)(Object)channel).mobbattlemusic$getSource(), true));
			MBM_APPLIED_REVISIONS.put(entry.getKey(), revision);
		}
	}

	public static void reset()
	{
		configuredFilter = 0;
		configuredMbmFilter = 0;
		configuredRevision = -1L;
		configuredMbmRevision = -1L;
		MBM_APPLIED_REVISIONS.clear();
		unsupported = false;
	}

	private static void apply(int source, boolean mbm)
	{
		try {
			double lowPass = mbm ? minPositive(lowPassHz, mbmLowPassHz) : lowPassHz;
			double highPass = mbm ? Math.max(highPassHz, mbmHighPassHz) : highPassHz;
			if (lowPass <= 0.0D && highPass <= 0.0D) {
				AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
				return;
			}
			if (unsupported || !supportsEfx())
				return;
			int filter = ensureConfiguredFilter(mbm, lowPass, highPass);
			AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter);
		} catch (Throwable e) {
			if (!unsupported)
				LOGGER.warn("OpenAL EFX is unavailable; global MBM filters were disabled", e);
			unsupported = true;
		}
	}

	private static int ensureConfiguredFilter(boolean mbm, double lowPass, double highPass)
	{
		int filter = mbm ? configuredMbmFilter : configuredFilter;
		long configured = mbm ? configuredMbmRevision : configuredRevision;
		if (filter == 0) {
			filter = EXTEfx.alGenFilters();
			if (mbm)
				configuredMbmFilter = filter;
			else
				configuredFilter = filter;
		}
		if (configured == revision)
			return filter;
		if (lowPass > 0.0D && highPass > 0.0D) {
			EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_BANDPASS);
			EXTEfx.alFilterf(filter, EXTEfx.AL_BANDPASS_GAIN, 1.0F);
			EXTEfx.alFilterf(filter, EXTEfx.AL_BANDPASS_GAINHF, cutoffGain(lowPass));
			EXTEfx.alFilterf(filter, EXTEfx.AL_BANDPASS_GAINLF, cutoffGain(highPass));
		} else if (lowPass > 0.0D) {
			EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
			EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, 1.0F);
			EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, cutoffGain(lowPass));
		} else {
			EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_HIGHPASS);
			EXTEfx.alFilterf(filter, EXTEfx.AL_HIGHPASS_GAIN, 1.0F);
			EXTEfx.alFilterf(filter, EXTEfx.AL_HIGHPASS_GAINLF, cutoffGain(highPass));
		}
		if (mbm)
			configuredMbmRevision = revision;
		else
			configuredRevision = revision;
		return filter;
	}

	private static double minPositive(double first, double second)
	{
		if (first <= 0.0D)
			return second;
		if (second <= 0.0D)
			return first;
		return Math.min(first, second);
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
		for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry :
				((MixinSoundEngineAccessor)engine).mobbattlemusic$getInstanceToChannel().entrySet()) {
			boolean mbm = entry.getKey() instanceof MobBattleTrack;
			entry.getValue().execute(channel -> apply(
					((MixinChannelAccessor)(Object)channel).mobbattlemusic$getSource(), mbm));
			if (mbm)
				MBM_APPLIED_REVISIONS.put(entry.getKey(), revision);
		}
	}
}
