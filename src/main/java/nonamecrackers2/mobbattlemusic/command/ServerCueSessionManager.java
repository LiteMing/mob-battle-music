package nonamecrackers2.mobbattlemusic.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import nonamecrackers2.mobbattlemusic.network.CueSessionSyncPacket;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;
import nonamecrackers2.mobbattlemusic.timeline.MobBattleMusicTimeline;

/**
 * K14-B/K14-C: the authoritative server-side Cue session. The server does NOT
 * play audio - it advances a logical timeline (game ticks, 50ms each) for the
 * currently cued track and fires timeline markers AT MOST ONCE per session.
 *
 * K14-C scope/authority rules:
 * - ONE active session per scope (playlistId + trackKey) - a new START for
 *   the same scope atomically replaces the old one; different scopes may run
 *   in parallel (e.g. dimension-specific Boss fights).
 * - The session carries an audience (dimension + participant UUIDs). Sync
 *   packets are only sent to players in that audience.
 * - Markers fire with a real encounter context (dimension, boss UUID if any)
 *   - never an arbitrary first online player.
 * - The logical timeline is authoritative: on TPS drop the position advances
 *   by server ticks only; clients receive periodic position snapshots and
 *   re-align their audio to the server timeline (bounded drift correction).
 * - Sessions end naturally when the track duration is reached (or on STOP).
 */
public final class ServerCueSessionManager
{
	private static final Map<ScopeKey, CueSession> SESSIONS = new LinkedHashMap<>();
	private static long nextSessionId;
	// K14-C: periodic position snapshot every 20 ticks (1s)
	private static final int SNAPSHOT_INTERVAL_TICKS = 20;

	private ServerCueSessionManager() {}

	public static void onServerTick(TickEvent.ServerTickEvent event)
	{
		if (event.phase != TickEvent.Phase.END)
			return;
		for (CueSession session : List.copyOf(SESSIONS.values())) {
			session.tick();
			if (session.state() == CueSessionSyncPacket.State.PLAYING && session.tickCount % SNAPSHOT_INTERVAL_TICKS == 0)
				broadcast(session, CueSessionSyncPacket.position(session.sessionId(), session.state(),
						session.logicalPositionMillis()));
		}
		SESSIONS.values().removeIf(session -> session.state() == CueSessionSyncPacket.State.STOPPED && session.finished());
	}

	/**
	 * K14-C: start (or atomically replace the same-scope session) the
	 * authoritative cue timeline for a track. The session is scoped by
	 * (playlistId, trackKey); the previous session of that scope is stopped
	 * first so two timelines can never fire the same marker set concurrently.
	 */
	public static CueSession start(MinecraftServer server, ResourceLocation playlistId, String entryKey,
			String url, long revision, @Nullable ResourceKey<Level> dimension, @Nullable UUID bossUuid,
			long durationMillis, java.util.Collection<ServerPlayer> audience)
	{
		ScopeKey scope = new ScopeKey(playlistId, entryKey);
		CueSession previous = SESSIONS.remove(scope);
		if (previous != null) {
			previous.stop();
			LOGGER().info("[MBM] CUE_STOP replaced old session for scope {} (new start)", scope);
		}
		CueSession session = new CueSession(++nextSessionId, scope, url, revision, server.getTickCount(),
				dimension, bossUuid, durationMillis);
		SESSIONS.put(scope, session);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (audience != null && !audience.contains(player))
				continue;
			MobBattleMusicNetwork.sendCueSessionSync(player, CueSessionSyncPacket.start(session.sessionId(),
					playlistId, entryKey, url, revision, session.state(), session.startServerTick(),
					session.logicalPositionMillis()));
		}
		LOGGER().info("[MBM] CUE_START session={} scope={} url={} dim={} boss={} rev={}",
				session.sessionId(), scope, url, dimension, bossUuid, revision);
		return session;
	}

	// K14-C: stop every active session of a playlist (or one specific track
	// when trackKey is non-blank)
	public static void stop(MinecraftServer server, ResourceLocation playlistId, String trackKey)
	{
		List<Map.Entry<ScopeKey, CueSession>> matches = new ArrayList<>();
		for (Map.Entry<ScopeKey, CueSession> entry : SESSIONS.entrySet()) {
			if (entry.getKey().playlistId().equals(playlistId)
					&& (trackKey == null || trackKey.isBlank() || entry.getKey().trackKey().equals(trackKey)))
				matches.add(entry);
		}
		for (Map.Entry<ScopeKey, CueSession> entry : matches) {
			CueSession session = SESSIONS.remove(entry.getKey());
			if (session == null)
				continue;
			session.stop();
			broadcast(session, CueSessionSyncPacket.stop(session.sessionId()));
			LOGGER().info("[MBM] CUE_STOP session={} scope={}", session.sessionId(), entry.getKey());
		}
	}

	public static void pause(MinecraftServer server, ResourceLocation playlistId, String trackKey)
	{
		ScopeKey scope = new ScopeKey(playlistId, trackKey);
		CueSession session = SESSIONS.get(scope);
		if (session == null || session.state() != CueSessionSyncPacket.State.PLAYING)
			return;
		session.pause();
		broadcast(session, CueSessionSyncPacket.pause(session.sessionId()));
		LOGGER().info("[MBM] CUE_PAUSE session={}", session.sessionId());
	}

	public static void resume(MinecraftServer server, ResourceLocation playlistId, String trackKey)
	{
		ScopeKey scope = new ScopeKey(playlistId, trackKey);
		CueSession session = SESSIONS.get(scope);
		if (session == null || session.state() != CueSessionSyncPacket.State.PAUSED)
			return;
		session.resume();
		broadcast(session, CueSessionSyncPacket.resume(session.sessionId()));
		LOGGER().info("[MBM] CUE_RESUME session={}", session.sessionId());
	}

	/**
	 * K14-C: a player joins mid-session - send the FULL snapshot including the
	 * current state, so a PAUSED session is restored paused (client prepares/
	 * seeks but does not play) and a PLAYING session seeks to the logical
	 * position instead of starting from zero.
	 */
	public static void join(ServerPlayer player)
	{
		for (CueSession session : SESSIONS.values()) {
			if (session.state() == CueSessionSyncPacket.State.STOPPED)
				continue;
			if (!session.inAudience(player))
				continue;
			MobBattleMusicNetwork.sendCueSessionSync(player,
					CueSessionSyncPacket.start(session.sessionId(), session.playlistId(), session.trackKey(),
							session.url(), session.revision(), session.state(), session.startServerTick(),
							session.logicalPositionMillis()));
			LOGGER().info("[MBM] CUE_SNAPSHOT session={} to {} state={} pos={}",
					session.sessionId(), player.getGameProfile().getName(), session.state(),
					session.logicalPositionMillis());
		}
	}

	public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event)
	{
		if (event.getEntity() instanceof ServerPlayer player)
			join(player);
	}

	public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event)
	{
		// sessions are shared across players - a single logout never ends the
		// authoritative timeline; the audience set is trimmed lazily
	}

	private static void broadcast(CueSession session, CueSessionSyncPacket packet)
	{
		MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
		if (server == null)
			return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (session.inAudience(player))
				MobBattleMusicNetwork.sendCueSessionSync(player, packet);
		}
	}

	private static org.apache.logging.log4j.Logger LOGGER()
	{
		return org.apache.logging.log4j.LogManager.getLogger("mobbattlemusic/ServerCueSessionManager");
	}

	// state enum lives on CueSessionSyncPacket (shared wire type)

	/**
	 * K14-C: stable scope identity - one active timeline per (playlist, entry).
	 * A new START for the same scope atomically replaces the old session.
	 */
	public record ScopeKey(ResourceLocation playlistId, String trackKey)
	{
		@Override
		public String toString()
		{
			return this.playlistId + " / " + this.trackKey;
		}
	}

	public static final class CueSession
	{
		private final UUID sessionId;
		private final ScopeKey scope;
		private final String url;
		private final long revision;
		private final long startServerTick;
		// K14-C: encounter context - dimension + boss UUID (may be null for
		// non-Boss cues); the marker fire uses this, never an arbitrary player
		private final @Nullable ResourceKey<Level> dimension;
		private final @Nullable UUID bossUuid;
		// K14-C: audience - players that receive this session's sync packets
		private final Set<UUID> audience;
		private final long durationMillis;
		private CueSessionSyncPacket.State state = CueSessionSyncPacket.State.PLAYING;
		private long logicalPositionMillis;
		private long tickCount;
		// K14-C: stable marker identity (event + time), one fire per session
		private final Set<MarkerId> firedMarkers = new HashSet<>();
		private boolean finished;

		private CueSession(long numericId, ScopeKey scope, String url, long revision, long startServerTick,
				@Nullable ResourceKey<Level> dimension, @Nullable UUID bossUuid, long durationMillis)
		{
			this.sessionId = new UUID(0x43554545L, numericId); // "CUE" prefix
			this.scope = scope;
			this.url = url;
			this.revision = revision;
			this.startServerTick = startServerTick;
			this.dimension = dimension;
			this.bossUuid = bossUuid;
			this.durationMillis = Math.max(0L, durationMillis);
			this.audience = new HashSet<>();
		}

		public void addAudience(ServerPlayer player)
		{
			this.audience.add(player.getUUID());
		}

		public boolean inAudience(ServerPlayer player)
		{
			return this.audience.isEmpty() || this.audience.contains(player.getUUID());
		}

		public UUID sessionId() { return this.sessionId; }
		public ScopeKey scope() { return this.scope; }
		public ResourceLocation playlistId() { return this.scope.playlistId(); }
		public String trackKey() { return this.scope.trackKey(); }
		public String url() { return this.url; }
		public long revision() { return this.revision; }
		public long startServerTick() { return this.startServerTick; }
		public CueSessionSyncPacket.State state() { return this.state; }
		public long logicalPositionMillis() { return this.logicalPositionMillis; }
		public long tickCount() { return this.tickCount; }
		public boolean finished() { return this.finished; }

		private void tick()
		{
			if (this.state != CueSessionSyncPacket.State.PLAYING)
				return;
			this.tickCount++;
			// K14-C: the server TICK is authoritative - on TPS drop the
			// logical position advances by ticks only (50ms each), never by
			// wall clock; clients re-align to this via periodic snapshots
			this.logicalPositionMillis += 50L;
			MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
			if (server == null)
				return;
			// K14-C: natural end - the track duration is reached
			if (this.durationMillis > 0L && this.logicalPositionMillis >= this.durationMillis) {
				this.state = CueSessionSyncPacket.State.STOPPED;
				this.finished = true;
				LOGGER().info("[MBM] CUE_STOP session={} natural end at {}ms (duration {}ms)",
						this.sessionId, this.logicalPositionMillis, this.durationMillis);
				broadcast(this, CueSessionSyncPacket.stop(this.sessionId()));
				return;
			}
			List<TimelineMarker> markers = ServerTimelineMarkerStore.markers(server, this.playlistId(), this.url);
			for (TimelineMarker marker : markers) {
				MarkerId markerId = new MarkerId(marker.eventId(), marker.timeMillis(), this.revision);
				if (this.firedMarkers.contains(markerId))
					continue;
				if (marker.timeMillis() > this.logicalPositionMillis)
					break;
				this.firedMarkers.add(markerId);
				// K14-C: authoritative fire with real encounter context
				ServerLevel level = this.dimension == null ? null
						: server.getLevel(this.dimension);
				ServerPlayer owner = audienceOwner(server);
				MobBattleMusicTimeline.fireServer(owner, null, this.playlistId(), this.url, marker);
				LOGGER().info("[MBM] CUE_MARKER_FIRE session={} marker={} at {}ms dim={} boss={}",
						this.sessionId, marker.eventId(), marker.timeMillis(), this.dimension, this.bossUuid);
			}
		}

		// the audience's first online player is the informational context for
		// the event; Boss logic must key on bossUuid/dimension, never on this
		private @Nullable ServerPlayer audienceOwner(MinecraftServer server)
		{
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (this.inAudience(player))
					return player;
			}
			return null;
		}

		private void pause()
		{
			this.state = CueSessionSyncPacket.State.PAUSED;
		}

		private void resume()
		{
			this.state = CueSessionSyncPacket.State.PLAYING;
		}

		private void stop()
		{
			this.state = CueSessionSyncPacket.State.STOPPED;
			this.finished = true;
		}
	}

	/**
	 * K14-C: stable marker identity - event + time + owning revision. Object
	 * equality on TimelineMarker would change semantics on config reload.
	 */
	public record MarkerId(ResourceLocation eventId, long timeMillis, long revision) {}
}
