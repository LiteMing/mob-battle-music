package nonamecrackers2.mobbattlemusic.mixin;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

import com.google.common.collect.Multimap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import nonamecrackers2.mobbattlemusic.client.init.MobBattleMusicClientCapabilities;
import nonamecrackers2.mobbattlemusic.client.audio.GlobalAudioFilterManager;
import nonamecrackers2.mobbattlemusic.client.sound.MobBattleTrack;
import nonamecrackers2.mobbattlemusic.mixin.MixinChannelAccessor;

@Mixin(SoundEngine.class)
public abstract class MixinSoundEngine
{
	@Shadow
	private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;
	@Shadow
	private Map<SoundInstance, Integer> soundDeleteTime;
	@Shadow
	private Multimap<SoundSource, SoundInstance> instanceBySource;
	@Shadow
	private List<TickableSoundInstance> tickingSounds;
	
	//Minecraft likes to just clear sounds when it's SoundSource volume is at 0 and not properly clear things. This mixin properly clears things, but
	//only for custom SoundInstances implemented in this mod just to make sure we don't break anything else.
	@Inject(method = "tickNonPaused", at = @At(value = "INVOKE", target = "Ljava/util/Iterator;remove()V", ordinal = 0), locals = LocalCapture.CAPTURE_FAILHARD)
	public void mobbattlemusic$properlyClearTrack_tickNonPaused(CallbackInfo ci, Iterator<Map.Entry<SoundInstance, ChannelAccess.ChannelHandle>> iterator, Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry, ChannelAccess.ChannelHandle channelaccess$channelhandle1, SoundInstance soundinstance)
	{
		if (soundinstance instanceof MobBattleTrack track)
		{
			this.soundDeleteTime.remove(soundinstance);
			try {
				this.instanceBySource.remove(soundinstance.getSource(), soundinstance);
			} catch (RuntimeException runtimeexception){
			}
			this.tickingSounds.remove(track);
		}
	}
	
	//Update music tracks volume when the mob battle music is playing, so we can make it fade out instead of cutting out abruptly
	@Inject(method = "tickNonPaused", at = @At("TAIL"))
	public void mobbattlemusic$tail_tickNonPaused(CallbackInfo ci)
	{
		GlobalAudioFilterManager.refreshMbmChannels(this.instanceToChannel);
		for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry : this.instanceToChannel.entrySet()) {
			if (entry.getKey() instanceof MobBattleTrack track && !track.isPreview()) {
				long startPosition = track.beginStartPositionAttempt();
				if (startPosition > 0L) {
					entry.getValue().execute(channel -> {
						boolean succeeded = false;
						try {
							int source = ((MixinChannelAccessor)(Object)channel).mobbattlemusic$getSource();
							float offsetSeconds = startPosition / 1000.0F;
							int buffer = AL10.alGetSourcei(source, AL10.AL_BUFFER);
							if (buffer != 0) {
								int size = AL10.alGetBufferi(buffer, AL10.AL_SIZE);
								int channels = AL10.alGetBufferi(buffer, AL10.AL_CHANNELS);
								int bits = AL10.alGetBufferi(buffer, AL10.AL_BITS);
								int frequency = AL10.alGetBufferi(buffer, AL10.AL_FREQUENCY);
								float duration = channels <= 0 || bits <= 0 || frequency <= 0 ? 0.0F :
										size / (channels * (bits / 8.0F) * frequency);
								if (duration > 0.0F)
									offsetSeconds %= duration;
							}
							while (AL10.alGetError() != AL10.AL_NO_ERROR) {}
							AL10.alSourcef(source, AL11.AL_SEC_OFFSET, offsetSeconds);
							succeeded = AL10.alGetError() == AL10.AL_NO_ERROR;
						} catch (Throwable ignored) {
						} finally {
							track.completeStartPositionAttempt(succeeded);
						}
					});
				}
				float volume = MobBattleTrack.isMainPlaybackMuted() ? 0.0F : this.calculateVolume(entry.getKey());
				entry.getValue().execute(channel -> channel.setVolume(volume));
			}
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null)
		{
			mc.level.getCapability(MobBattleMusicClientCapabilities.MUSIC_MANAGER).ifPresent(manager -> 
			{
				if (manager.isPlaying())
				{
					for (SoundInstance instance : this.instanceBySource.get(SoundSource.MUSIC))
					{
						var channel = this.instanceToChannel.get(instance);
						if (channel != null)
						{
							float volume = this.calculateVolume(instance);
							channel.execute(c -> {
								c.setVolume(volume);
							});
						}
					}
				}
			});
		}
	}
	
	@Shadow
	protected abstract float calculateVolume(SoundInstance instance);
}
