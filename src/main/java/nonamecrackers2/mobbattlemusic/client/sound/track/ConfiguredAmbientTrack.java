package nonamecrackers2.mobbattlemusic.client.sound.track;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.util.MobSelection;

public class ConfiguredAmbientTrack extends TrackType
{
	private final int fadeTime;
	
	public ConfiguredAmbientTrack(ResourceLocation track, int fadeTime)
	{
		super(track);
		this.fadeTime = fadeTime;
	}
	
	@Override
	public boolean canPlay(MobSelection selection)
	{
		return MobBattleMusicConfig.CLIENT.nonAggressiveTrackEnabled.get() &&
				selection.group(MobSelection.GroupType.ENEMIES).count(MobSelection.Selector.LINE_OF_SIGHT) > 0;
	}
	
	@Override
	public int getFadeTime()
	{
		return this.fadeTime;
	}
	
	@Override
	public float getVolume(MobSelection selection)
	{
		return Mth.clamp((float)selection.group(MobSelection.GroupType.ENEMIES).count(MobSelection.defaultSelector()) /
				(float)MobBattleMusicConfig.CLIENT.maxMobsForMaxVolume.get(), 0.0F, 1.0F);
	}
}
