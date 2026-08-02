package nonamecrackers2.mobbattlemusic.client.sound.track;

import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.util.MobSelection;

public class ConfiguredAggressiveTrack extends TrackType
{
	private final int fadeTime;
	
	public ConfiguredAggressiveTrack(ResourceLocation track, int fadeTime)
	{
		super(track);
		this.fadeTime = fadeTime;
	}
	
	@Override
	public boolean canPlay(MobSelection selection)
	{
		return MobBattleMusicConfig.CLIENT.aggressiveTrackEnabled.get() &&
				(selection.group(MobSelection.GroupType.ATTACKING).count(MobSelection.Selector.ANY) > 0 ||
						selection.panicTarget() != null);
	}
	
	@Override
	public int getFadeTime()
	{
		return this.fadeTime;
	}
}
