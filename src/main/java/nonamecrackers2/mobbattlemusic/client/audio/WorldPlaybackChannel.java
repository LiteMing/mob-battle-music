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
	// AUD-54: client tick counter for the probe ring
	private static long tickCounter;
	// AUD-52: seek rate limit / hysteresis / give-up accounting
	private static long lastSeekAtMillis;
	private static int consecutiveSeeks;
	private static long seekSettleUntilMillis;
	// AUD-46/AUD-49: unified gated transition (fade out -> gain-zero poll ->
	// action -> fade in). Single slot; new requests fail explicitly (AUD-49 #4).
	private static @Nullable PendingGate pendingGate;
	// AUD-49 #2: deadline default = fadeOutMillis + 500ms
	private static final long GATE_TIMEOUT_GRACE_MILLIS = 500L;
	// AUD-52: minimum interval between correction seeks and the settle window
	private static final long SEEK_MIN_INTERVAL_MILLIS = 5000L;
	private static final long SEEK_SETTLE_MILLIS = 2000L;
	private static final int SEEK_MAX_CONSECUTIVE = 3;
	
	private static record PendingGate(StreamMusicPlayer.Envelope env, Runnable action, long fadeInMillis,
			long createdAtMillis, long deadlineMillis, String actionName) {}
	
	private WorldPlaybackChannel() {}
	
	static {
		// AUD-49 #7: the ownership predicate is registered once on the player;
		// consumers must not work around the dependency by moving the check
		StreamMusicPlayer.setGateOwnershipCheck(WorldPlaybackChannel::gateOwns);
	}
	
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
		
		// AUD-49 #1: gate advancement runs unconditionally right after
		// converge(), before any early-returning method; it must never live
		// inside a guarded method
		advancePendingGate();
		// AUD-49 #8: a pending start fade-in expires after 2000ms
		StreamMusicPlayer.expirePendingTrackFadeInMillis(System.currentTimeMillis());
		// AUD-50: FROZEN -> RUNNING re-anchors on the migration edge; checked
		// idempotently every tick (AUD-9 v1.3), never an event-driven seek
		if (WorldPlaybackChannel.state() == ChannelState.PLAYING
				&& MarkerClock.state() == MarkerClock.ClockState.FROZEN
				&& MarkerClock.isActive()) {
			PlaybackHandle active = WorldPlaybackChannel.handle;
			if (active != null) {
				long localPosition = ExternalMusicHandler.getInstance().getPositionMillis();
				long now = System.currentTimeMillis();
				// AUD-50: re-anchor from the local position, never seek
				MarkerClock.realign(active.track(), now - localPosition, now);
			}
		}
		
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
		
		// AUD-54: ring-buffer snapshot - raw numbers only, no allocation or
		// string work on the tick thread; formatting happens at dump time
		WorldPlaybackChannel.tickCounter++;
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		StreamMusicPlayer player = handler.getPlayer();
		ProbeRing.sample(new ProbeRing.ProbeSample(System.currentTimeMillis(),
				WorldPlaybackChannel.tickCounter,
				WorldPlaybackChannel.state().ordinal(),
				MarkerClock.state().ordinal(),
				MbmSessionState.isFocused(),
				player.trackEnv().current(),
				StreamMusicPlayer.MUTE_ENV.current(),
				player.seekEnv().current(),
				handler.getPositionMillis(),
				handler.getDecodedPositionMillis(),
				player.getUnderruns(),
				WorldPlaybackChannel.consecutiveSeeks,
				player.getPlayCallCount()));
	}
	
	public static long tickNumber()
	{
		return WorldPlaybackChannel.tickCounter;
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
				// AUD-49 G1.6: the built-in leg follows the mute envelope in
				// every target state
				applyMuteToSoundEngineTracks();
			}
			case PAUSED -> {
				player.pauseForGame();            // gamePaused = true
				player.setMutedForGame(false);
				MobBattleTrack.setMainPlaybackMuted(false);
				// AUD-50: the clock freezes outside PLAYING
				MarkerClock.setState(MarkerClock.ClockState.FROZEN);
				applyMuteToSoundEngineTracks();
			}
			case PLAYING -> {
				player.resumeFromGame();          // gamePaused = false
				player.setMutedForGame(false);
				MobBattleTrack.setMainPlaybackMuted(false);
				MarkerClock.setState(MarkerClock.ClockState.RUNNING);
				applyMuteToSoundEngineTracks();
			}
			case MUTED -> {
				// AUD-41: gamePaused must be explicitly false here; the v1.2
				// deadlock was unmute() omitting the resume
				player.resumeFromGame();
				player.setMutedForGame(true);
				MobBattleTrack.setMainPlaybackMuted(true);
				applyMuteToSoundEngineTracks();
				// AUD-50: MUTED is not audibly playing - the clock freezes
				MarkerClock.setState(MarkerClock.ClockState.FROZEN);
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
		MobBattleTrack.setMainPlaybackMuted(false);
		handler.stopMusic();
		clearHandle();
		MarkerClock.invalidate();
		WorldPlaybackChannel.firedThisTrack = 0L;
		WorldPlaybackChannel.lastMarkerPosition = -1L;
		// AUD-49 #3: cancel-reset - the gate and its envelopes go back to 1.0
		clearPendingGate();
		resetEnvelopes();
		LOGGER.debug("[MBM] world channel -> STOPPED (audio stopped)");
	}
	
	public static void reset()
	{
		// Force the next update() to treat the first tick as an edge
		WorldPlaybackChannel.state = null;
		WorldPlaybackChannel.lastClockReportMillis = 0L;
		// AUD-53: world unload clears debug injection unconditionally
		MarkerClock.clearInjectedDrift();
		WorldPlaybackChannel.lastSeekAtMillis = 0L;
		WorldPlaybackChannel.consecutiveSeeks = 0;
		WorldPlaybackChannel.seekSettleUntilMillis = 0L;
		stopMusic();
	}
	
	/**
	 * AUD-46/AUD-49: unified gated transition - fade out to gain zero (polled,
	 * not fixed-tick waited), run the action, fade in. Used by correction seeks
	 * (AUD-24) and track switches (AUD-46 durations). Not used by AUD-19
	 * invalidation stops.
	 *
	 * @return false when the gate slot is busy (AUD-49 #4: explicit failure,
	 *         never a silent drop); callers must roll back their local state
	 */
	public static boolean gatedTransition(StreamMusicPlayer.Envelope env, long fadeOutMillis,
			String actionName, Runnable action, long fadeInMillis)
	{
		if (WorldPlaybackChannel.pendingGate != null) {
			LOGGER.debug("[MBM] gated transition rejected (slot busy): {}", actionName);
			return false;
		}
		// AUD-49 #7: the gate takes exclusive ownership; the fade-out restarts
		// unconditionally (AUD-45 v1.2) even if another writer left the target
		// at the same value
		env.forceFade(0.0f, fadeOutMillis);
		long createdAt = System.currentTimeMillis();
		WorldPlaybackChannel.pendingGate = new PendingGate(env, action, fadeInMillis, createdAt,
				createdAt + fadeOutMillis + GATE_TIMEOUT_GRACE_MILLIS, actionName);
		LOGGER.debug("[MBM] gated transition queued (fadeOut={}ms, action={})", fadeOutMillis, actionName);
		return true;
	}
	
	public static boolean isGatedTransitionActive()
	{
		return WorldPlaybackChannel.pendingGate != null;
	}
	
	// AUD-49 #7: does the gate currently own this envelope?
	public static boolean gateOwns(StreamMusicPlayer.Envelope env)
	{
		PendingGate gate = WorldPlaybackChannel.pendingGate;
		return gate != null && gate.env() == env;
	}
	
	// AUD-46/AUD-49: poll the envelope gain; the action runs only after it is
	// at zero, or on the deadline fallback. Advancement is unconditional
	// (AUD-49 #1).
	private static void advancePendingGate()
	{
		PendingGate gate = WorldPlaybackChannel.pendingGate;
		if (gate == null)
			return;
		if (gate.env().current() > 0.001f) {
			// AUD-49 #2: timeout fallback
			if (System.currentTimeMillis() > gate.deadlineMillis())
				finishGate(gate, true);
			return;
		}
		finishGate(gate, false);
	}
	
	// AUD-49 #2: the timeout path and the normal completion path share the
	// exact same reset behaviour - fade-in per source presence, or register
	// the fade-in duration for the next source's start. The timeout only adds
	// a fixed-format warning log.
	private static void finishGate(PendingGate gate, boolean timedOut)
	{
		WorldPlaybackChannel.pendingGate = null;
		if (timedOut)
			LOGGER.warn("[MBM] AUD-49 gate timeout after {}ms (gain={}) action={}",
					System.currentTimeMillis() - gate.createdAtMillis(),
					String.format(java.util.Locale.ROOT, "%.3f", gate.env().current()),
					gate.actionName());
		gate.action().run();
		// AUD-49 #5: the fade-in must not fade silence. When the action
		// started a source (e.g. seek), fade in now; when it only stopped the
		// old source, the fade-in is triggered by the new source's start.
		if (ExternalMusicHandler.getInstance().getPlayer().hasActiveTrack())
			gate.env().setTarget(1.0f, gate.fadeInMillis());
		else
			StreamMusicPlayer.setPendingTrackFadeInMillis(gate.fadeInMillis());
	}
	
	// AUD-49 #3: every path that clears the gate must also reset the occupied
	// envelopes' target AND current to 1.0
	private static void clearPendingGate()
	{
		PendingGate gate = WorldPlaybackChannel.pendingGate;
		WorldPlaybackChannel.pendingGate = null;
		StreamMusicPlayer.clearPendingTrackFadeInMillis();
		if (gate != null) {
			gate.env().hardReset(1.0f);
			LOGGER.debug("[MBM] gated transition cancelled (env reset to 1.0): {}", gate.actionName());
		}
	}
	
	private static void resetEnvelopes()
	{
		// AUD-49 #3: target AND current back to 1.0 for both envelopes
		ExternalMusicHandler.getInstance().getPlayer().trackEnv().hardReset(1.0f);
		ExternalMusicHandler.getInstance().getPlayer().seekEnv().hardReset(1.0f);
	}
	
	/**
	 * AUD-11/AUD-44: with the engine paused (tick(true) does nothing in
	 * 1.20.1), channel volumes are no longer refreshed by the mixin, so mute()
	 * must reach the built-in sound leg directly. The applied volume is the
	 * shared mute envelope's current value (never a constant). Scope: this
	 * channel's own track collection (AUD-35).
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
		float muteGain = StreamMusicPlayer.MUTE_ENV.current();
		for (MobBattleTrack track : WorldPlaybackChannel.ENGINE_TRACKS) {
			ChannelAccess.ChannelHandle channelHandle = instanceToChannel.get(track);
			if (channelHandle != null)
				channelHandle.execute(channel -> channel.setVolume(muteGain));
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
		// AUD-52: track switch resets the seek accounting
		WorldPlaybackChannel.lastSeekAtMillis = 0L;
		WorldPlaybackChannel.consecutiveSeeks = 0;
		WorldPlaybackChannel.seekSettleUntilMillis = 0L;
		// AUD-22: report the playback start so the server can anchor the clock
		reportPlaybackStart();
	}
	
	// AUD-52/AUD-30 v1.8: seek accounting accessors for the probe
	public static int seekCount()
	{
		return WorldPlaybackChannel.consecutiveSeeks;
	}
	
	public static long millisSinceSeek()
	{
		long last = WorldPlaybackChannel.lastSeekAtMillis;
		return last <= 0L ? -1L : System.currentTimeMillis() - last;
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
		
		// AUD-24/AUD-25: clock correction when the server has anchored this
		// track. AUD-50: correction participates only while the clock is
		// RUNNING and the player is actually audible
		if (MarkerClock.isActive() && MarkerClock.state() == MarkerClock.ClockState.RUNNING
				&& handler.getPlayer().isPlaying())
			MarkerClock.tick(url, positionMillis, CORRECTION_SINK);
		
		// AUD-49 #1: gate advancement lives in update()'s unconditional section
		// (after converge), never inside early-returning methods like this one
		
		// Marker counting for the probe (AUD-27: the actual firing stays in the
		// main-playback selection engine; this is observation only)
		countFiredMarkers(ref, url, positionMillis);
	}
	
	// AUD-24 v1.2/AUD-46/AUD-52: the correction seek is a gatedTransition
	// using the seek envelope (120ms fade out, gain-zero poll, seek, 120ms
	// fade in), rate-limited with hysteresis and a give-up threshold.
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
			// AUD-52: triple rejection - settle window, minimum interval,
			// consecutive-seek limit
			long now = System.currentTimeMillis();
			if (now < WorldPlaybackChannel.seekSettleUntilMillis
					|| now - WorldPlaybackChannel.lastSeekAtMillis < SEEK_MIN_INTERVAL_MILLIS
					|| WorldPlaybackChannel.consecutiveSeeks >= SEEK_MAX_CONSECUTIVE) {
				WorldPlaybackChannel.consecutiveSeeks++;
				if (WorldPlaybackChannel.consecutiveSeeks >= SEEK_MAX_CONSECUTIVE) {
					// AUD-52: give up on this track
					MarkerClock.setState(MarkerClock.ClockState.FROZEN);
					LOGGER.warn("[MBM] AUD-52 correction disabled for this track ({} consecutive seeks)", 
							WorldPlaybackChannel.consecutiveSeeks);
				} else {
					LOGGER.debug("[MBM] AUD-52 seek suppressed (settle/rate/limit, seeks={})",
							WorldPlaybackChannel.consecutiveSeeks);
				}
				return;
			}
			long targetMillis = Math.round(serverPositionSeconds * 1000.0D);
			ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
			// AUD-49 #4: an explicit failure here just means the drift is
			// re-evaluated on the next tick; no state to roll back
			WorldPlaybackChannel.gatedTransition(handler.getPlayer().seekEnv(), 120L, "clock-seek", () -> {
				// AUD-51: the action only accounts state; the audio seek is
				// offloaded to the audio-I/O executor (no blocking work here)
				handler.seekMusicAsync(targetMillis);
				MarkerClock.clearInjectedDrift();
				WorldPlaybackChannel.lastMarkerPosition = -1L;
				WorldPlaybackChannel.firedThisTrack = 0L;
				// AUD-52: seek accounting
				WorldPlaybackChannel.lastSeekAtMillis = System.currentTimeMillis();
				WorldPlaybackChannel.seekSettleUntilMillis =
						WorldPlaybackChannel.lastSeekAtMillis + SEEK_SETTLE_MILLIS;
				WorldPlaybackChannel.consecutiveSeeks++;
				LOGGER.debug("[MBM] AUD-24 seek to {}ms queued to audio IO (gain confirmed 0.00)", targetMillis);
			}, 120L);
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
		// AUD-49 #3: cancel-reset - the gate and its envelopes go back to 1.0
		clearPendingGate();
		resetEnvelopes();
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
