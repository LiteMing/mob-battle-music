package nonamecrackers2.mobbattlemusic.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.audio.Channel;

@Mixin(Channel.class)
public interface MixinChannelAccessor
{
	@Accessor("source")
	int mobbattlemusic$getSource();
}
