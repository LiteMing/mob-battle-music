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
	
	private static volatile ChannelState state = ChannelState.STOPPED;
	private static volatile @Nullable PlaybackHandle handle;
	private static SessionState session = SessionState.NO_WORLD;
	private static boolean focused = true;
	private static boolean initialized;
	private static long lastClockReportMillis;
	private static long lastMarkerPosition = -1L;
	private static long firedThisTrack;
	// AUD-24 v1.2: fade-out/fade-in duration around a correction seek (100-150ms)
	private static final long SEEK_FADE_MILLIS = 120L;
	private static @Nullable PendingSeek pendingSeek;
	
	private static record PendingSeek(long targetMillis) {}
	
	private WorldPlaybackChannel() {}
	
	/**
	 * Called once per client tick (AUD-5). Audio actions only run on state
	 * transitions (AUD-9); handle observation, invalidation and clock
	 * correction run every tick.
	 */
	public static void update(SessionState newSession, boolean newFocused)
	{
		boolean edge = !initialized || newSession != WorldPlaybackChannel.session
				|| newFocused != WorldPlaybackChannel.focused;
		WorldPlaybackChannel.initialized = true;
		WorldPlaybackChannel.session = newSession;
		WorldPlaybackChannel.focused = newFocused;
		
		if (edge) {
			switch (newSession) {
				// AUD-13: NO_WORLD / DISCONNECTED -> stop() (+ DISCONNECTED clears handles)
				case NO_WORLD, DISCONNECTED -> stop();
				// AUD-13: SINGLEPLAYER_PAUSED -> pause()
				case SINGLEPLAYER_PAUSED -> pause();
				// AUD-13: SINGLEPLAYER_RUNNING -> play() / maintain
				case SINGLEPLAYER_RUNNING -> play();
				// AUD-13: LAN_HOST / MULTIPLAYER -> keep playing while focused,
				// mute() when unfocused or a pause screen is open
				case LAN_HOST, MULTIPLAYER -> {
					if (WorldPlaybackChannel.focused)
						unmute();
					else
						mute();
				}
			}
		}
		
		// Handle observation only, never a playback action
		syncHandle();
		// AUD-19 + clock driving (AUD-24/AUD-25) + marker counting
		tickClockAndInvalidation();
	}
	
	public static void play()
	{
		// AUD-13: SINGLEPLAYER_RUNNING -> play() / maintain. Track selection and
		// starting playback stays with the existing selection engine; this method
		// only restores a paused or muted channel. Actual playback state is
		// observed every tick by syncHandle().
		switch (WorldPlaybackChannel.state) {
			case PAUSED -> resume();
			case MUTED -> unmute();
			default -> { }
		}
	}
	
	public static void pause()
	{
		// AUD-10: pause freezes the clock (audio output suspended, position held)
		if (WorldPlaybackChannel.state == ChannelState.STOPPED)
			return;
		ExternalMusicHandler.getInstance().getPlayer().pauseForGame();
		// Pause and mute are mutually exclusive; clear any mute residue
		ExternalMusicHandler.getInstance().getPlayer().setMutedForGame(false);
		MobBattleTrack.setMainPlaybackMuted(false);
		WorldPlaybackChannel.state = ChannelState.PAUSED;
		// AUD-25: pause freezes the clock; markers must not fire
		MarkerClock.setState(MarkerClock.ClockState.FROZEN);
		LOGGER.debug("[MBM] world channel -> PAUSED");
	}
	
	public static void resume()
	{
		// AUD-10: unfreezes the clock
		if (WorldPlaybackChannel.state == ChannelState.STOPPED)
			return;
		ExternalMusicHandler.getInstance().getPlayer().resumeFromGame();
		WorldPlaybackChannel.state = ChannelState.PLAYING;
		MarkerClock.setState(MarkerClock.ClockState.RUNNING);
		// AUD-25: resume must request a resync from the server
		reportPlaybackStart();
		LOGGER.debug("[MBM] world channel -> PLAYING");
	}
	
	public static void mute()
	{
		// AUD-10: mute keeps the clock running - unfreeze first, then silence
		if (WorldPlaybackChannel.state == ChannelState.STOPPED)
			return;
		ExternalMusicHandler.getInstance().getPlayer().resumeFromGame();
		// External URL leg: self-managed gain (AUD-11), effective immediately
		ExternalMusicHandler.getInstance().getPlayer().setMutedForGame(true);
		// Built-in sound leg: flag consumed by the tickNonPaused mixin...
		MobBattleTrack.setMainPlaybackMuted(true);
		// ...and direct per-channel gain for the paused-engine window, where the
		// engine no longer refreshes volumes (AUD-11 verification conclusion)
		applyMuteToSoundEngineTracks();
		WorldPlaybackChannel.state = ChannelState.MUTED;
		// AUD-25: mute keeps the clock running
		MarkerClock.setState(MarkerClock.ClockState.RUNNING);
		LOGGER.debug("[MBM] world channel -> MUTED");
	}
	
	public static void unmute()
	{
		if (WorldPlaybackChannel.state == ChannelState.STOPPED)
			return;
		ExternalMusicHandler.getInstance().getPlayer().setMutedForGame(false);
		MobBattleTrack.setMainPlaybackMuted(false);
		// Built-in leg volume is recalculated by the tickNonPaused mixin on the
		// next frame; unmute() only runs while the engine is unpaused
		WorldPlaybackChannel.state = ChannelState.PLAYING;
		MarkerClock.setState(MarkerClock.ClockState.RUNNING);
		LOGGER.debug("[MBM] world channel -> PLAYING");
	}
	
	public static void stop()
	{
		ExternalMusicHandler.getInstance().stopMusic();
		ExternalMusicHandler.getInstance().getPlayer().resumeFromGame();
		ExternalMusicHandler.getInstance().getPlayer().setMutedForGame(false);
		ExternalMusicHandler.getInstance().getPlayer().setTargetVolume(1.0F);
		MobBattleTrack.setMainPlaybackMuted(false);
		clearHandle();
		MarkerClock.invalidate();
		WorldPlaybackChannel.firedThisTrack = 0L;
		WorldPlaybackChannel.lastMarkerPosition = -1L;
		WorldPlaybackChannel.pendingSeek = null;
		if (WorldPlaybackChannel.state != ChannelState.STOPPED) {
			WorldPlaybackChannel.state = ChannelState.STOPPED;
			LOGGER.debug("[MBM] world channel -> STOPPED");
		}
	}
	
	public static void reset()
	{
		stop();
		WorldPlaybackChannel.initialized = false;
		WorldPlaybackChannel.session = SessionState.NO_WORLD;
		WorldPlaybackChannel.focused = true;
		WorldPlaybackChannel.lastClockReportMillis = 0L;
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
		// reflects reality. PAUSED/MUTED are migration-controlled and untouched.
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		boolean playing = handler.isPlaying() || handler.isPreparingCurrentMusic();
		switch (WorldPlaybackChannel.state) {
			case STOPPED -> {
				if (playing) {
					beginPlayback(handler);
					WorldPlaybackChannel.state = ChannelState.PLAYING;
				}
			}
			case PLAYING -> {
				if (playing) {
					if (WorldPlaybackChannel.handle == null)
						beginPlayback(handler);
				} else {
					WorldPlaybackChannel.state = ChannelState.STOPPED;
					clearHandle();
				}
			}
			case MUTED -> {
				// mute keeps the clock running; playback starting while muted
				// still needs a handle for invalidation and marker counting
				if (playing && WorldPlaybackChannel.handle == null)
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
		MarkerClock.setState(MarkerClock.ClockState.RUNNING);
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
			int currentRevision = MusicTracksManager.playlistRevision(
					ResourceLocation.tryParse(ref.playlistId()));
			if (ref.revision() != currentRevision
					|| !manager.isPlaybackTargetActive(ResourceLocation.tryParse(ref.playlistId()), ref.entryKey())) {
				LOGGER.debug("[MBM] AUD-19 invalidation: {} (current rev {})", ref, currentRevision);
				invalidatePlayback();
				return;
			}
		}
		
		if (WorldPlaybackChannel.state != ChannelState.PLAYING && WorldPlaybackChannel.state != ChannelState.MUTED)
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
	
	private static void invalidatePlayback()
	{
		// AUD-19: immediate stop, no fade-out; the selection engine decides the
		// next track within the same tick
		ExternalMusicHandler.getInstance().stopMusic();
		clearHandle();
		MarkerClock.invalidate();
		WorldPlaybackChannel.firedThisTrack = 0L;
		WorldPlaybackChannel.lastMarkerPosition = -1L;
		WorldPlaybackChannel.state = ChannelState.STOPPED;
		LOGGER.debug("[MBM] world channel -> STOPPED (invalidated)");
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
		return WorldPlaybackChannel.state;
	}
	
	public static @Nullable PlaybackHandle handle()
	{
		return WorldPlaybackChannel.handle;
	}
}
