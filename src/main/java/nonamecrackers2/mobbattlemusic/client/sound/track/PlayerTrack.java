package nonamecrackers2.mobbattlemusic.client.sound.track;

import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.sound.MobBattleMusicSounds;
import nonamecrackers2.mobbattlemusic.client.util.MobSelection;

public class PlayerTrack extends TrackType
{
	protected PlayerTrack()
	{
		super(MobBattleMusicSounds.PLAYER_TRACK);
	}
	
	@Override
	public boolean canPlay(MobSelection selection)
	{
		return MobBattleMusicConfig.CLIENT.playerTrackEnabled.get() && selection.playerCombatActive();
	}
	
	@Override
	public int getFadeTime()
	{
		return (int)(MobBattleMusicConfig.CLIENT.playerFadeTime.get() * 20.0D);
	}
}
