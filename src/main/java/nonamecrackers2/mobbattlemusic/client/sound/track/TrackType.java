package nonamecrackers2.mobbattlemusic.client.sound.track;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.music.IdleConditionStateClient;
import nonamecrackers2.mobbattlemusic.client.util.MobSelection;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;

public abstract class TrackType
{
	public static final TrackType AMBIENT = new AmbientTrack();
	public static final TrackType AGGRESSIVE = new AggressiveTrack();
	public static final TrackType PLAYER = new PlayerTrack();
	
	private final ResourceLocation track;
	// K16-B: playlist-level rule (歌单规则) - every dynamic playlist may carry
	// its own conditions that all its entries inherit by default. Only
	// non-idle kinds are evaluated here; ConfiguredIdleTrack evaluates its
	// own conditions internally (and keeps the unconditional isActive check).
	private boolean serverConditioned;
	private List<IdleCondition> playlistConditions = List.of();
	
	public TrackType(ResourceLocation track)
	{ 
		this.track = track;
	}
	
	public ResourceLocation getTrack()
	{
		return this.track;
	}

	public void setPlaylistConditions(boolean serverConditioned, List<IdleCondition> conditions)
	{
		this.serverConditioned = serverConditioned;
		this.playlistConditions = conditions == null ? List.of() : List.copyOf(conditions);
	}

	/**
	 * K16-B: the playlist-level rule gate. Empty conditions mean "no rule" -
	 * the track inherits nothing extra. Server-conditioned playlists are
	 * evaluated server-side and pushed via IdleConditionStateClient.
	 */
	public boolean playlistConditionsMatch()
	{
		if (this.playlistConditions.isEmpty())
			return true;
		if (this.serverConditioned)
			return IdleConditionStateClient.isActive(this.getTrack());
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && IdleConditionRegistry.test(mc.player, this.playlistConditions);
	}

	public float getVolume(MobSelection selection)
	{
		return 1.0F;
	}

	public boolean isIdlePlayback()
	{
		return false;
	}

	public int getPlaybackIntervalSeconds()
	{
		return 0;
	}
	
	public abstract int getFadeTime();
	
	public abstract boolean canPlay(MobSelection selection);
	
	@Override
	public String toString()
	{
		return String.format("%s[%s]", this.getClass().getSimpleName(), this.track);
	}
}
