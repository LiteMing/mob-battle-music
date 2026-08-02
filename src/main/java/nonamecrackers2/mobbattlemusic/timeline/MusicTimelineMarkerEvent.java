package nonamecrackers2.mobbattlemusic.timeline;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.eventbus.api.Event;

public class MusicTimelineMarkerEvent extends Event
{
	private final ServerPlayer player;
	private final @Nullable Entity combatTarget;
	private final ResourceLocation playlist;
	private final String musicUrl;
	private final ResourceLocation markerId;
	private final long timeMillis;

	public MusicTimelineMarkerEvent(ServerPlayer player, @Nullable Entity combatTarget, ResourceLocation playlist,
			String musicUrl, ResourceLocation markerId, long timeMillis)
	{
		this.player = player;
		this.combatTarget = combatTarget;
		this.playlist = playlist;
		this.musicUrl = musicUrl;
		this.markerId = markerId;
		this.timeMillis = timeMillis;
	}

	public ServerPlayer getPlayer() { return this.player; }
	public @Nullable Entity getCombatTarget() { return this.combatTarget; }
	public ResourceLocation getPlaylist() { return this.playlist; }
	public String getMusicUrl() { return this.musicUrl; }
	public ResourceLocation getMarkerId() { return this.markerId; }
	public long getTimeMillis() { return this.timeMillis; }
}
