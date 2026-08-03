package nonamecrackers2.mobbattlemusic.client.audio;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler;
import nonamecrackers2.mobbattlemusic.client.music.StreamMusicPlayer;
import nonamecrackers2.mobbattlemusic.client.music.TimelineMarkerStore;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.client.sound.MobBattleTrack;
import nonamecrackers2.mobbattlemusic.mixin.MixinSoundEngineAccessor;
import nonamecrackers2.mobbattlemusic.mixin.MixinSoundManagerAccessor;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;

/**
 * Main playback channel, bound to the session (AUD-1/AUD-2).
 * Driven by MbmSessionState transitions only (AUD-5/AUD-9/AUD-13).
 * Hosts the AUD-19 invalidation check and the AUD-24 clock corrections.
 */
public final class WorldPlaybackChannel
{
	public enum ChannelState
	{
		STOPPED,
		PLAYING,
		PAUSED,
		MUTED
	}
	
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/WorldPlaybackChannel");
	
	// AUD-35: this channel owns its own engine-track collection; scopes (mute,
	// stop, invalidation) are decided by collection membership, never by flags
	private static final java.util.Set<MobBattleTrack> ENGINE_TRACKS =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	
	// AUD-9 v1.3: the channel state is the idempotent convergence target
	// recomputed every tick from (session, focused) (AUD-41)
	private static volatile ChannelState state = ChannelState.STOPPED;
	private static volatile @Nullable PlaybackHandle handle;
	private static long lastClockReportMillis;
	private static long lastMarkerPosition = -1L;
	private static long firedThisTrack;
	// AUD-24 v1.2: fade-out/fade-in duration around a correction seek (100-150ms)
	private static final long SEEK_FADE_MILLIS = 120L;
	private static @Nullable PendingSeek pendingSeek;
	
	private static record PendingSeek(long targetMillis) {}
	
	private WorldPlaybackChannel() {}
	
	/**
	 * Called once per client tick (AUD-5). AUD-9 v1.3: computes the target
	 * state and converges idempotently every tick; edge detection is used only
	 * for logging and one-time side effects (stop, resync report).
	 */
	public static void update(SessionState newSession, boolean newFocused)
	{
		ChannelState target = targetOf(newSession, newFocused);
		ChannelState previous = WorldPlaybackChannel.state;
		boolean edge = target != previous;
		WorldPlaybackChannel.state = target;
		
		if (edge)
			LOGGER.debug("[MBM] channel target {} -> {}", previous, target);
		
		// AUD-41: unconditional projection of all four flags, every tick
		converge(target);
		
		// One-time side effects are allowed on the edge only (AUD-9 v1.3)
		if (edge && target == ChannelState.STOPPED)
			stopMusic();
		// AUD-25: leaving PAUSED requests a resync
		if (edge && previous == ChannelState.PAUSED
				&& (target == ChannelState.PLAYING || target == ChannelState.MUTED))
			reportPlaybackStart();
		
		// Handle observation only, never a playback action
		syncHandle();
		// AUD-19 + clock driving (AUD-24/AUD-25) + marker counting
		tickClockAndInvalidation();
	}
	
	// AUD-41: target state table, exactly as specified
	private static ChannelState targetOf(SessionState session, boolean focused)
	{
		return switch (session) {
			case NO_WORLD, DISCONNECTED -> ChannelState.STOPPED;
			case SINGLEPLAYER_PAUSED -> ChannelState.PAUSED;
			case SINGLEPLAYER_RUNNING -> ChannelState.PLAYING;
			case LAN_HOST, MULTIPLAYER -> focused ? ChannelState.PLAYING : ChannelState.MUTED;
		};
	}
	
	// AUD-41: convergence action table. Every tick, all four columns are
	// projected unconditionally; no current-state comparison, no incremental
	// dispatch. Idempotent by construction (flag writes and hardware ops are
	// no-ops when the value/state is already applied).
	private static void converge(ChannelState target)
	{
		StreamMusicPlayer player = ExternalMusicHandler.getInstance().getPlayer();
		switch (target) {
			case STOPPED -> {
				player.resumeFromGame();          // gamePaused = false
				player.setMutedForGame(false);    // gameMuted = false
				MobBattleTrack.setMainPlaybackMuted(false);
				MarkerClock.invalidate();
			}
			case PAUSED -> {
				player.pauseForGame();            // gamePaused = true
				player.setMutedForGame(false);
				MobBattleTrack.setMainPlaybackMuted(false);
				MarkerClock.setState(MarkerClock.ClockState.FROZEN);
			}
			case PLAYING -> {
				player.resumeFromGame();          // gamePaused = false
				player.setMutedForGame(false);
				MobBattleTrack.setMainPlaybackMuted(false);
				MarkerClock.setState(MarkerClock.ClockState.RUNNING);
			}
			case MUTED -> {
				// AUD-41: gamePaused must be explicitly false here; the v1.2
				// deadlock was unmute() omitting the resume
				player.resumeFromGame();
				player.setMutedForGame(true);
				MobBattleTrack.setMainPlaybackMuted(true);
				applyMuteToSoundEngineTracks();
				MarkerClock.setState(MarkerClock.ClockState.RUNNING);
			}
		}
	}
	
	// AUD-28 contract methods: single-step convergence projections. The tick
	// entry drives them continuously via update(); these remain as contract
	// surfaces and are idempotent.
	public static void play()
	{
		converge(ChannelState.PLAYING);
	}
	
	public static void pause()
	{
		converge(ChannelState.PAUSED);
	}
	
	public static void resume()
	{
		converge(ChannelState.PLAYING);
	}
	
	public static void mute()
	{
		converge(ChannelState.MUTED);
	}
	
	public static void unmute()
	{
		converge(ChannelState.PLAYING);
	}
	
	public static void stop()
	{
		stopMusic();
	}
	
	/**
	 * One-time audio stop: destroys the playback source and clears handles.
	 * Invoked on the STOPPED edge (AUD-9 v1.3); safe to call repeatedly.
	 */
	private static void stopMusic()
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		// AUD-42 #4: flags are cleared before stopMusic() and independently of
		// it; never rely on post-stop guards (the setters are unconditional)
		handler.getPlayer().resumeFromGame();
		handler.getPlayer().setMutedForGame(false);
		handler.getPlayer().setTargetVolume(1.0F);
		MobBattleTrack.setMainPlaybackMuted(false);
		handler.stopMusic();
		clearHandle();
		MarkerClock.invalidate();
		WorldPlaybackChannel.firedThisTrack = 0L;
		WorldPlaybackChannel.lastMarkerPosition = -1L;
		WorldPlaybackChannel.pendingSeek = null;
		LOGGER.debug("[MBM] world channel -> STOPPED (audio stopped)");
	}
	
	public static void reset()
	{
		// Force the next update() to treat the first tick as an edge
		WorldPlaybackChannel.state = null;
		WorldPlaybackChannel.lastClockReportMillis = 0L;
		stopMusic();
	}
	
	/**
	 * AUD-11: with the engine paused (tick(true) does nothing in 1.20.1), channel
	 * volumes are no longer refreshed by the mixin, so mute() must reach the
	 * built-in sound leg directly. Scope: this channel's own track collection
	 * (AUD-35); the preview channel's tracks are never members.
	 */
	private static void applyMuteToSoundEngineTracks()
	{
		Minecraft mc = Minecraft.getInstance();
		SoundManager manager = mc.getSoundManager();
		if (manager == null)
			return;
		SoundEngine engine = ((MixinSoundManagerAccessor) manager).mobbattlemusic$getSoundEngine();
		Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel =
				((MixinSoundEngineAccessor) engine).mobbattlemusic$getInstanceToChannel();
		for (MobBattleTrack track : WorldPlaybackChannel.ENGINE_TRACKS) {
			ChannelAccess.ChannelHandle channelHandle = instanceToChannel.get(track);
			if (channelHandle != null)
				channelHandle.execute(channel -> channel.setVolume(0.0F));
		}
	}
	
	// AUD-35: registration API for the selection engine (BattleMusicManager)
	public static void registerEngineTrack(MobBattleTrack track)
	{
		WorldPlaybackChannel.ENGINE_TRACKS.add(track);
	}
	
	public static void unregisterEngineTrack(MobBattleTrack track)
	{
		WorldPlaybackChannel.ENGINE_TRACKS.remove(track);
	}
	
	public static boolean isEngineTrack(MobBattleTrack track)
	{
		return WorldPlaybackChannel.ENGINE_TRACKS.contains(track);
	}
	
	private static void syncHandle()
	{
		// Observation only: track the actual playback so the probe (AUD-30)
		// reflects reality. The channel state is the convergence target and is
		// never modified here.
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		// AUD-43: liveness is independent of pause/mute; isPlaying() (audible)
		// would report a paused track as ended
		boolean alive = handler.getPlayer().hasActiveTrack() || handler.isPreparingCurrentMusic();
		switch (WorldPlaybackChannel.state()) {
			case STOPPED -> {
				if (alive)
					beginPlayback(handler);
			}
			case PLAYING -> {
				if (alive) {
					if (WorldPlaybackChannel.handle == null)
						beginPlayback(handler);
				} else {
					clearHandle();
				}
			}
			case MUTED -> {
				// mute keeps the clock running; playback starting while muted
				// still needs a handle for invalidation and marker counting
				if (alive && WorldPlaybackChannel.handle == null)
					beginPlayback(handler);
			}
			case PAUSED -> { }
		}
	}
	
	private static void beginPlayback(ExternalMusicHandler handler)
	{
		String url = handler.getCurrentlyPlayingUrl();
		PlaybackHandle created = PlaybackHandle.create(url);
		MusicTracksManager manager = MusicTracksManager.getInstance();
		MusicTracksManager.PlaybackTarget target = manager.resolvePlaybackTarget(url);
		created.setSourceRef(target == null ? SourceRef.direct(url == null ? "" : url)
				: new SourceRef(target.playlistId().toString(), target.entryKey(),
						MusicTracksManager.playlistRevision(target.playlistId())));
		WorldPlaybackChannel.handle = created;
		WorldPlaybackChannel.firedThisTrack = 0L;
		WorldPlaybackChannel.lastMarkerPosition = -1L;
		// AUD-22: report the playback start so the server can anchor the clock
		reportPlaybackStart();
	}
	
	private static void reportPlaybackStart()
	{
		PlaybackHandle active = WorldPlaybackChannel.handle;
		if (active == null)
			return;
		MobBattleMusicNetwork.sendPlaybackStartReport(active.track(), active.startedEpochMillis());
		WorldPlaybackChannel.lastClockReportMillis = System.currentTimeMillis();
	}
	
	private static void tickClockAndInvalidation()
	{
		PlaybackHandle active = WorldPlaybackChannel.handle;
		if (active == null)
			return;
		
		// AUD-19: invalidate without fade, within the same tick
		SourceRef ref = active.sourceRef();
		if (ref != null && !ref.isDirect()) {
			MusicTracksManager manager = MusicTracksManager.getInstance();
			ResourceLocation playlistId = ResourceLocation.tryParse(ref.playlistId());
			int currentRevision = MusicTracksManager.playlistRevision(playlistId);
			if (ref.revision() != currentRevision
					|| !manager.isPlaybackTargetActive(playlistId, ref.entryKey())) {
				invalidatePlayback(ref);
				return;
			}
		}
		
		if (WorldPlaybackChannel.state() != ChannelState.PLAYING && WorldPlaybackChannel.state() != ChannelState.MUTED)
			return;
		
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		String url = handler.getCurrentlyPlayingUrl();
		if (url == null || !url.equals(active.track()))
			return;
		long positionMillis = handler.getPositionMillis();
		
		// AUD-24: periodic resync every 5 seconds
		long now = System.currentTimeMillis();
		if (now - WorldPlaybackChannel.lastClockReportMillis >= MarkerClock.SYNC_INTERVAL_MILLIS)
			reportPlaybackStart();
		
		// AUD-24/AUD-25: clock correction when the server has anchored this track
		if (MarkerClock.isActive())
			MarkerClock.tick(url, positionMillis, CORRECTION_SINK);
		
		// AUD-24 v1.1: execute a pending fade-out/seek/fade-in sequence
		advancePendingSeek(handler);
		
		// Marker counting for the probe (AUD-27: the actual firing stays in the
		// main-playback selection engine; this is observation only)
		countFiredMarkers(ref, url, positionMillis);
	}
	
	// AUD-24 v1.2: a correction seek only runs after the fade-out has driven
	// the gain to zero (verified, not assumed) - see advancePendingSeek().
	private static void advancePendingSeek(ExternalMusicHandler handler)
	{
		PendingSeek pending = WorldPlaybackChannel.pendingSeek;
		if (pending == null)
			return;
		StreamMusicPlayer player = handler.getPlayer();
		// AUD-24 v1.2: no fixed-tick waiting; the seek is gated on the gain
		// having reached zero (playback thread writes currentVolume = 0.0F
		// when the fade completes)
		if (player.getCurrentVolume() > 0.001F)
			return;
		WorldPlaybackChannel.pendingSeek = null;
		if (handler.seekMusic(pending.targetMillis())) {
			MarkerClock.clearInjectedDrift();
			WorldPlaybackChannel.lastMarkerPosition = -1L;
			WorldPlaybackChannel.firedThisTrack = 0L;
			LOGGER.debug("[MBM] AUD-24 seek to {}ms (gain confirmed 0.00)", pending.targetMillis());
		}
		// AUD-24 v1.2: fade back in from silence
		player.fadeTo(1.0F, WorldPlaybackChannel.SEEK_FADE_MILLIS);
	}
	
	// AUD-24 v1.2: |drift| > 1s seeks, wrapped in a 120ms fade-out (completed
	// before the seek) and fade-in afterwards. Rate correction is forbidden.
	private static final MarkerClock.CorrectionSink CORRECTION_SINK = new MarkerClock.CorrectionSink()
	{
		@Override
		public void noCorrection(double drift)
		{
			// AUD-24 v1.2: |drift| <= 1s -> no intervention
		}
		
		@Override
		public void seek(double serverPositionSeconds, double drift)
		{
			long targetMillis = Math.round(serverPositionSeconds * 1000.0D);
			if (WorldPlaybackChannel.pendingSeek != null)
				return;
			// AUD-24 v1.2: start the fade-out; the seek itself is deferred until
			// the gain reaches zero
			ExternalMusicHandler.getInstance().getPlayer().fadeTo(0.0F, WorldPlaybackChannel.SEEK_FADE_MILLIS);
			WorldPlaybackChannel.pendingSeek = new PendingSeek(targetMillis);
			LOGGER.debug("[MBM] AUD-24 correction seek to {}ms queued (fade out, drift={}s)", targetMillis,
					String.format(java.util.Locale.ROOT, "%+.2f", drift));
		}
	};
	
	private static void countFiredMarkers(@Nullable SourceRef ref, String url, long positionMillis)
	{
		if (ref == null || ref.isDirect())
			return;
		ResourceLocation playlistId = ResourceLocation.tryParse(ref.playlistId());
		if (playlistId == null)
			return;
		MusicTracksManager manager = MusicTracksManager.getInstance();
		int selectedIndex = manager.getExternalPlaylistSelectedIndex(playlistId);
		List<TimelineMarker> markers = TimelineMarkerStore.markers(playlistId, url, selectedIndex);
		if (markers.isEmpty()) {
			MarkerClock.recordFiredMarkers(0L);
			return;
		}
		if (positionMillis < WorldPlaybackChannel.lastMarkerPosition) {
			// Loop wrap / track restart: restart the per-track counting
			WorldPlaybackChannel.firedThisTrack = 0L;
		} else {
			long crossed = 0L;
			for (TimelineMarker marker : markers) {
				if (marker.timeMillis() > WorldPlaybackChannel.lastMarkerPosition
						&& marker.timeMillis() <= positionMillis)
					crossed++;
			}
			WorldPlaybackChannel.firedThisTrack += crossed;
		}
		WorldPlaybackChannel.lastMarkerPosition = positionMillis;
		MarkerClock.recordFiredMarkers(WorldPlaybackChannel.firedThisTrack);
	}
	
	private static void invalidatePlayback(@Nullable SourceRef ref)
	{
		// AUD-19: immediate stop, no fade-out; the selection engine decides the
		// next track within the same tick. The convergence target is not
		// touched - update() recomputes it from (session, focused) next tick.
		ExternalMusicHandler.getInstance().stopMusic();
		clearHandle();
		MarkerClock.invalidate();
		WorldPlaybackChannel.firedThisTrack = 0L;
		WorldPlaybackChannel.lastMarkerPosition = -1L;
		LOGGER.debug("[MBM] world channel -> STOPPED (invalidated)");
		// AUD-30 v1.2: fixed-format invalidation log. deltaMs is measured in
		// code from the data-layer commit stamp to the audio stop above;
		// no manual log-timestamp subtraction.
		if (ref != null) {
			long dataChange = MusicTracksManager.lastDataChangeMillis();
			long deltaMs = dataChange <= 0L ? -1L : System.currentTimeMillis() - dataChange;
			LOGGER.debug("[MBM] AUD-19 invalidation playlist={} entry={} deltaMs={}",
					ref.playlistId(), ref.entryKey(), deltaMs);
		}
	}
	
	private static void clearHandle()
	{
		PlaybackHandle active = WorldPlaybackChannel.handle;
		if (active != null)
			active.markStopped();
		WorldPlaybackChannel.handle = null;
	}
	
	public static ChannelState state()
	{
		// reset() may null the field to force an edge on the next update()
		ChannelState current = WorldPlaybackChannel.state;
		return current == null ? ChannelState.STOPPED : current;
	}
	
	public static @Nullable PlaybackHandle handle()
	{
		return WorldPlaybackChannel.handle;
	}
}
