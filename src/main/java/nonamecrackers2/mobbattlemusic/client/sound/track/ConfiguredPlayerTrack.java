package nonamecrackers2.mobbattlemusic.client.sound.track;

import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.util.MobSelection;

public class ConfiguredPlayerTrack extends TrackType
{
	private final int fadeTime;
	
	public ConfiguredPlayerTrack(ResourceLocation track, int fadeTime)
	{
		super(track);
		this.fadeTime = fadeTime;
	}
	
	@Override
	public boolean canPlay(MobSelection selection)
	{
		return MobBattleMusicConfig.CLIENT.playerTrackEnabled.get() && selection.playerCombatActive();
	}
	
	@Override
	public int getFadeTime()
	{
		return this.fadeTime;
	}
}
