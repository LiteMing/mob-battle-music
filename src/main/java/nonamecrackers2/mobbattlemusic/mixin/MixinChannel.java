package nonamecrackers2.mobbattlemusic.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.audio.Channel;

import nonamecrackers2.mobbattlemusic.client.audio.GlobalAudioFilterManager;

@Mixin(Channel.class)
public class MixinChannel
{
	@Inject(method = "play", at = @At("HEAD"))
	private void mobbattlemusic$applyGlobalFilter(CallbackInfo callback)
	{
		GlobalAudioFilterManager.apply((Channel)(Object)this);
	}
}
