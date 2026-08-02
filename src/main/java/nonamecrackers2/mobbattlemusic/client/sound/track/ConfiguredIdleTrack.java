package nonamecrackers2.mobbattlemusic.client.sound.track;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.music.IdleConditionStateClient;
import nonamecrackers2.mobbattlemusic.client.util.MobSelection;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;

public class ConfiguredIdleTrack extends TrackType
{
	private final int fadeTime;
	private final boolean serverConditioned;
	private final List<IdleCondition> conditions;
	private final int intervalSeconds;

	public ConfiguredIdleTrack(ResourceLocation track, int fadeTime)
	{
		this(track, fadeTime, false, List.of(), 0);
	}

	public ConfiguredIdleTrack(ResourceLocation track, int fadeTime, boolean serverConditioned,
			List<IdleCondition> conditions)
	{
		this(track, fadeTime, serverConditioned, conditions, 0);
	}

	public ConfiguredIdleTrack(ResourceLocation track, int fadeTime, boolean serverConditioned,
			List<IdleCondition> conditions, int intervalSeconds)
	{
		super(track);
		this.fadeTime = fadeTime;
		this.serverConditioned = serverConditioned;
		this.conditions = List.copyOf(conditions);
		this.intervalSeconds = Math.max(0, intervalSeconds);
	}

	@Override
	public boolean canPlay(MobSelection selection)
	{
		if (this.serverConditioned)
			return IdleConditionStateClient.isActive(this.getTrack());
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && IdleConditionRegistry.test(mc.player, this.conditions);
	}

	@Override
	public int getFadeTime()
	{
		return this.fadeTime;
	}

	@Override
	public boolean isIdlePlayback()
	{
		return true;
	}

	@Override
	public int getPlaybackIntervalSeconds()
	{
		return this.intervalSeconds;
	}
}
