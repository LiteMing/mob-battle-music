package nonamecrackers2.mobbattlemusic.playlist;

import net.minecraft.resources.ResourceLocation;

public record TimelineMarker(long timeMillis, ResourceLocation eventId)
{
	public TimelineMarker
	{
		timeMillis = Math.max(0L, Math.min(86_400_000L, timeMillis));
	}
}
