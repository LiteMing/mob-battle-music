package nonamecrackers2.mobbattlemusic.timeline;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;

public final class MobBattleMusicTimeline
{
	private static final Map<ResourceLocation, List<ServerListener>> SERVER_LISTENERS = new ConcurrentHashMap<>();
	private static final Map<ResourceLocation, List<ClientListener>> CLIENT_LISTENERS = new ConcurrentHashMap<>();

	private MobBattleMusicTimeline() {}

	public static void registerServer(String markerId, ServerListener listener)
	{
		ResourceLocation id = new ResourceLocation(markerId);
		SERVER_LISTENERS.computeIfAbsent(id, key -> new CopyOnWriteArrayList<>()).add(listener);
	}

	public static void clearServer(String markerId)
	{
		ResourceLocation id = ResourceLocation.tryParse(markerId);
		if (id != null)
			SERVER_LISTENERS.remove(id);
	}

	public static void registerClient(String markerId, ClientListener listener)
	{
		ResourceLocation id = new ResourceLocation(markerId);
		CLIENT_LISTENERS.computeIfAbsent(id, key -> new CopyOnWriteArrayList<>()).add(listener);
	}

	public static void clearClient(String markerId)
	{
		ResourceLocation id = ResourceLocation.tryParse(markerId);
		if (id != null)
			CLIENT_LISTENERS.remove(id);
	}

	public static void fireClient(Player player, @Nullable Entity target, ResourceLocation playlist, String url,
			TimelineMarker marker)
	{
		for (ClientListener listener : CLIENT_LISTENERS.getOrDefault(marker.eventId(), List.of()))
			listener.onMarker(player, target, playlist, url, marker.eventId(), marker.timeMillis());
	}

	public static void fireServer(ServerPlayer player, @Nullable Entity target, ResourceLocation playlist,
			String url, TimelineMarker marker)
	{
		MusicTimelineMarkerEvent event = new MusicTimelineMarkerEvent(player, target, playlist, url,
				marker.eventId(), marker.timeMillis());
		MinecraftForge.EVENT_BUS.post(event);
		for (ServerListener listener : SERVER_LISTENERS.getOrDefault(marker.eventId(), List.of()))
			listener.onMarker(player, target, playlist, url, marker.eventId(), marker.timeMillis());
	}

	@FunctionalInterface
	public interface ServerListener
	{
		void onMarker(ServerPlayer player, @Nullable Entity combatTarget, ResourceLocation playlist, String musicUrl,
				ResourceLocation markerId, long timeMillis);
	}

	@FunctionalInterface
	public interface ClientListener
	{
		void onMarker(Player player, @Nullable Entity combatTarget, ResourceLocation playlist, String musicUrl,
				ResourceLocation markerId, long timeMillis);
	}
}
