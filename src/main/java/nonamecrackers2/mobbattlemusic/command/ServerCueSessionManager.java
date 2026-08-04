package nonamecrackers2.mobbattlemusic.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import nonamecrackers2.mobbattlemusic.network.CueSessionSyncPacket;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;
import nonamecrackers2.mobbattlemusic.timeline.MobBattleMusicTimeline;

/**
 * K14-B: the authoritative server-side Cue session. The server does NOT play
 * audio - it advances a logical timeline (game ticks, 50ms each) for the
 * currently cued track and fires timeline markers AT MOST ONCE per session.
 * Clients with MBM receive CueSessionSyncPacket notifications (start/snapshot/
 * stop) and play the audio themselves; clients without MBM receive nothing but
 * the authoritative markers still fire (Boss/弹幕 logic unaffected).
 *
 * Marker authority lives HERE: TimelineMarkerHitPacket from clients is only
 * diagnostics now. A marker fires once per session regardless of how many
 * MBM players are in the fight.
 */
public final class ServerCueSessionManager
{
	private static final Map<UUID, CueSession> SESSIONS = new LinkedHashMap<>();
	private static long nextSessionId;

	private ServerCueSessionManager() {}

	public static void onServerTick(TickEvent.ServerTickEvent event)
	{
		if (event.phase != TickEvent.Phase.END)
			return;
		for (CueSession session : List.copyOf(SESSIONS.values()))
			session.tick();
		SESSIONS.values().removeIf(session -> session.state() == CueState.STOPPED && session.finished());
	}

	/**
	 * K14-B: start (or replace) the authoritative cue session for a track.
	 * @return the session, or null when the URL cannot be resolved
	 */
	@Nullable
	public static CueSession start(MinecraftServer server, ResourceLocation playlistId, String entryKey,
			String url, long revision)
	{
		CueSession session = new CueSession(++nextSessionId, playlistId, entryKey, url, revision,
				server.getTickCount());
		SESSIONS.values().removeIf(existing -> existing.playlistId().equals(playlistId)
				&& existing.trackKey().equals(entryKey));
		SESSIONS.put(session.sessionId(), session);
		for (ServerPlayer player : server.getPlayerList().getPlayers())
			MobBattleMusicNetwork.sendCueSessionSync(player,
					CueSessionSyncPacket.start(session.sessionId(), playlistId, entryKey, url, revision,
							session.startServerTick(), session.logicalPositionMillis()));
		return session;
	}

	public static void stop(MinecraftServer server, ResourceLocation playlistId)
	{
		for (CueSession session : List.copyOf(SESSIONS.values())) {
			if (session.playlistId().equals(playlistId)) {
				session.stop();
				for (ServerPlayer player : server.getPlayerList().getPlayers())
					MobBattleMusicNetwork.sendCueSessionSync(player,
							CueSessionSyncPacket.stop(session.sessionId()));
			}
		}
	}

	public static void pause(MinecraftServer server, ResourceLocation playlistId)
	{
		for (CueSession session : List.copyOf(SESSIONS.values())) {
			if (session.playlistId().equals(playlistId) && session.state() == CueState.PLAYING) {
				session.pause();
				for (ServerPlayer player : server.getPlayerList().getPlayers())
					MobBattleMusicNetwork.sendCueSessionSync(player,
							CueSessionSyncPacket.pause(session.sessionId()));
			}
		}
	}

	public static void resume(MinecraftServer server, ResourceLocation playlistId)
	{
		for (CueSession session : List.copyOf(SESSIONS.values())) {
			if (session.playlistId().equals(playlistId) && session.state() == CueState.PAUSED) {
				session.resume();
				for (ServerPlayer player : server.getPlayerList().getPlayers())
					MobBattleMusicNetwork.sendCueSessionSync(player,
							CueSessionSyncPacket.resume(session.sessionId()));
			}
		}
	}

	/**
	 * K14-B: a player joins mid-session - send the current snapshot so the
	 * client seeks to the logical position instead of starting from zero.
	 */
	public static void join(ServerPlayer player)
	{
		for (CueSession session : SESSIONS.values()) {
			if (session.state() == CueState.PLAYING) {
				MobBattleMusicNetwork.sendCueSessionSync(player,
						CueSessionSyncPacket.start(session.sessionId(), session.playlistId(), session.trackKey(),
								session.url(), session.revision(), session.startServerTick(),
								session.logicalPositionMillis()));
			}
		}
	}

	// K14-B: a player logs in - if a Cue session is running, send the
	// snapshot so they join mid-session (seek to logical position)
	public static void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event)
	{
		if (event.getEntity() instanceof ServerPlayer player)
			join(player);
	}

	public static void onPlayerLoggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event)
	{
		// sessions are shared across players - a single logout never ends the
		// authoritative timeline; nothing to remove here
	}

	public enum CueState
	{
		PLAYING,
		PAUSED,
		STOPPED
	}

	public static final class CueSession
	{
		private final UUID sessionId;
		private final ResourceLocation playlistId;
		private final String trackKey;
		private final String url;
		private final long revision;
		private final long startServerTick;
		private CueState state = CueState.PLAYING;
		private long pausedAtTick;
		private long logicalPositionMillis;
		private final List<TimelineMarker> fired = new ArrayList<>();
		private boolean finished;

		private CueSession(long numericId, ResourceLocation playlistId, String trackKey, String url, long revision,
				long startServerTick)
		{
			this.sessionId = new UUID(0x43554545L, numericId); // "CUE" prefix
			this.playlistId = playlistId;
			this.trackKey = trackKey;
			this.url = url;
			this.revision = revision;
			this.startServerTick = startServerTick;
		}

		public UUID sessionId()
		{
			return this.sessionId;
		}

		public ResourceLocation playlistId()
		{
			return this.playlistId;
		}

		public String trackKey()
		{
			return this.trackKey;
		}

		public String url()
		{
			return this.url;
		}

		public long revision()
		{
			return this.revision;
		}

		public long startServerTick()
		{
			return this.startServerTick;
		}

		public CueState state()
		{
			return this.state;
		}

		public long logicalPositionMillis()
		{
			return this.logicalPositionMillis;
		}

		public boolean finished()
		{
			return this.finished;
		}

		private void tick()
		{
			if (this.state != CueState.PLAYING)
				return;
			// 50ms per game tick - the authoritative logical position
			this.logicalPositionMillis += 50L;
			MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
			if (server == null)
				return;
			List<TimelineMarker> markers = ServerTimelineMarkerStore.markers(server, this.playlistId, this.url);
			for (TimelineMarker marker : markers) {
				if (this.fired.contains(marker))
					continue;
				if (marker.timeMillis() > this.logicalPositionMillis)
					break;
				this.fired.add(marker);
				// K14-B: authoritative fire - once per session, independent of
				// how many MBM clients are present (no client packet involved).
				// The player context is informational: the first online player
				// (any mod status) or null when nobody is online - the Boss/
				// marker logic must not depend on a specific client.
				ServerPlayer owner = null;
				var players = server.getPlayerList().getPlayers();
				if (!players.isEmpty())
					owner = players.get(0);
				MobBattleMusicTimeline.fireServer(owner, null, this.playlistId, this.url, marker);
			}
		}

		private void pause()
		{
			if (this.state != CueState.PLAYING)
				return;
			this.state = CueState.PAUSED;
			this.pausedAtTick = System.currentTimeMillis();
		}

		private void resume()
		{
			if (this.state != CueState.PAUSED)
				return;
			this.state = CueState.PLAYING;
		}

		private void stop()
		{
			this.state = CueState.STOPPED;
			this.finished = true;
		}
	}
}
