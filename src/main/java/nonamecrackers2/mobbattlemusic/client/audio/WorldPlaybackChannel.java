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
import nonamecrackers2.mobbattlemusic.network.PlaybackClockSyncPacket;
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
	private static long lastMarkerPosition = -1L;
	private static long firedThisTrack;
	// AUD-54: client tick counter for the probe ring
	private static long tickCounter;
	// AUD-52: seek rate limit / hysteresis accounting
	private static long lastSeekAtMillis;
	private static long seekSettleUntilMillis;
	// AUD-52 v1.3/R3/K4 P0: give-up uses a sliding window of the last three
	// seek ATTEMPTS (queued gates), not completions - the line rebuild and
	// Thread.start() cost is fully paid at attempt time; limiting on
	// completions would make failures free. Correction is disabled only when
	// all three attempts fall within 20s. Written on the caller (tick) thread
	// at queue time - closing the K2 cross-thread visibility residue. Recovery
	// is automatic with backoff 60s -> 40s -> 80s -> ... (cap 300s).
	private static final long GIVE_UP_WINDOW_MILLIS = 20_000L;
	private static final long GIVE_UP_BACKOFF_FIRST_MILLIS = 60_000L;
	private static final long GIVE_UP_BACKOFF_STEP_MILLIS = 40_000L;
	private static final long GIVE_UP_BACKOFF_CAP_MILLIS = 300_000L;
	private static final long[] RECENT_SEEK_ATTEMPTS = new long[3];
	private static int recentSeekIndex;
	private static int recentSeekCount;
	private static boolean giveUpWarned;
	private static long correctionDisabledAtMillis;
	private static long correctionBackoffMillis = GIVE_UP_BACKOFF_FIRST_MILLIS;
	// AUD-52 修订: seek cost measurement (queue time -> first watermark fill)
	private static long seekQueuedAtMillis;
	// K6-B: session generation - bumped per playback session; a stale S2C
	// response (older generation) must never overwrite a newer track's anchor
	private static volatile long playbackSessionGeneration;
	// K8-A: level generation - bumped per client level load; async tasks
	// carrying a level token from an older level are stale
	private static volatile long levelGeneration;
	// K8-A: the current playback intent (selected URL) and its version. The
	// version is bumped on every intent change; gated actions verify it at
	// execution time so a gate queued for an old intent cannot act on a new
	// one (or restart playback in a new dimension)
	private static volatile String currentIntentUrl;
	private static volatile long intentVersion;
	// AUD-46/AUD-49: unified gated transition (fade out -> gain-zero poll ->
	// action -> fade in). Single slot; new requests fail explicitly (AUD-49 #4).
	private static @Nullable PendingGate pendingGate;
	// AUD-49 #2: deadline default = fadeOutMillis + 500ms
	private static final long GATE_TIMEOUT_GRACE_MILLIS = 500L;
	// AUD-52: minimum interval between correction seeks and the settle window
	private static final long SEEK_MIN_INTERVAL_MILLIS = 5000L;
	private static final long SEEK_SETTLE_MILLIS = 2000L;

	
	private static record PendingGate(StreamMusicPlayer.Envelope env, Runnable action, long fadeInMillis,
			long createdAtMillis, long deadlineMillis, String actionName, long intentVersionAtQueue) {}
	
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
		// K7-B: the bounded clock-probe burst advances every client tick -
		// connection-level, independent of playback state
		ClockOffsetProbeScheduler.tick();
		
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
		// K3 P1-1: automatic recovery from correction give-up with backoff;
		// re-enabling clears the frequency window so it cannot re-trigger
		// immediately
		long nowMillis = System.currentTimeMillis();
		if (MarkerClock.isCorrectionDisabled() && WorldPlaybackChannel.correctionDisabledAtMillis > 0L
				&& nowMillis - WorldPlaybackChannel.correctionDisabledAtMillis
						>= WorldPlaybackChannel.correctionBackoffMillis) {
			MarkerClock.enableCorrection();
			WorldPlaybackChannel.recentSeekCount = 0;
			long nextBackoff = WorldPlaybackChannel.correctionBackoffMillis == GIVE_UP_BACKOFF_FIRST_MILLIS
					? GIVE_UP_BACKOFF_STEP_MILLIS
					: Math.min(GIVE_UP_BACKOFF_CAP_MILLIS,
							WorldPlaybackChannel.correctionBackoffMillis + GIVE_UP_BACKOFF_STEP_MILLIS);
			WorldPlaybackChannel.correctionBackoffMillis = nextBackoff;
			LOGGER.info("[MBM] AUD-52 correction re-enabled (backoff {}ms)", nextBackoff);
		}
		// AUD-50 v1.2/R1: the re-anchor request is set when LEAVING a state in
		// which the local position stalls - the physical meaning of the source
		// state, not an enumeration of (from, to) pairs
		if (previous == ChannelState.PAUSED && target != ChannelState.PAUSED)
			MarkerClock.requestReanchor();
		
		// One-time side effects are allowed on the edge only (AUD-9 v1.3)
		if (edge && target == ChannelState.STOPPED)
			stopMusic();
		// K6-A: the early PAUSED-leave resync is removed - it reported
		// handle.startedEpochMillis() which is meaningless after a pause; the
		// resume report happens below, only after the re-anchor succeeded
		
		// Handle observation only, never a playback action
		syncHandle();
		
		// K3 P1-2: the re-anchor block runs AFTER syncHandle() - the handle
		// must be in its post-observation state (a just-created handle for a
		// new track must not be re-anchored against the previous track's
		// position). The track identity is checked explicitly.
		if (MarkerClock.reanchorRequested()) {
			PlaybackHandle active = WorldPlaybackChannel.handle;
			ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
			long localPosition = handler.getPositionMillis();
			// K5: state()==RUNNING needs no extra check here - the request is
			// only set when leaving PAUSED (previous==PAUSED && target!=PAUSED),
			// and converge() sets RUNNING for both PLAYING and MUTED
			// (WorldPlaybackChannel.java converge PLAYING/MUTED branches)
			boolean prerequisites = active != null
					&& MarkerClock.isActive()
					&& !WorldPlaybackChannel.isGatedTransitionActive()
					&& !handler.isSeekInFlight()
					&& handler.getPlayer().hasActiveTrack()
					&& !handler.isStopRequested()
					// K3 P1-2: the anchor target must be the currently playing
					// track, not a stale handle from the previous one
					&& active.track().equals(handler.getCurrentlyPlayingUrl())
					&& localPosition > 0L;
			if (prerequisites) {
				// AUD-50: re-anchor from the local position, never seek.
				// K7-B: a LOCAL self-anchor - no server-domain conversion, no
				// clock offset in the anchor path. The PAUSED-leave realigns
				// exactly once; clock-probe responses never realign.
				long now = System.currentTimeMillis();
				MarkerClock.realignLocalSelfAnchor(active.track(), localPosition, now);
				MarkerClock.clearReanchorRequest();
				// K6-A: the resume report uses the true resume moment
				// (now - localPosition) - covering both PAUSED->PLAYING and
				// PAUSED->MUTED, since the request fires for any PAUSED-leave
				reportPlaybackResume(now - localPosition);
			}
			// prerequisites not met: the request survives to the next tick
		}
		// AUD-19 + clock driving (AUD-24/AUD-25) + marker counting
		tickClockAndInvalidation();
		
		// AUD-54: ring-buffer snapshot. Honest accounting: one record
		// allocation plus derived-value computation per tick; no string
		// formatting and no I/O here - those happen only at dump time.
		// The field set is the superset of the single-frame probe (AUD-54 追加).
		WorldPlaybackChannel.tickCounter++;
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		StreamMusicPlayer player = handler.getPlayer();
		long audiblePos = handler.getPositionMillis();
		// K4 P1: an unknown position (-1) must never enter arithmetic; the
		// drift is NaN directly, without calling driftSeconds at all
		long decodedPos = handler.getDecodedPositionMillis();
		String currentUrl = handler.getCurrentlyPlayingUrl();
		double driftSeconds = MarkerClock.isActive() && currentUrl != null && audiblePos >= 0L
				? MarkerClock.driftSeconds(currentUrl, audiblePos) : Double.NaN;
		long driftMillis = (!MarkerClock.anchorValid() || Double.isNaN(driftSeconds))
				? Long.MIN_VALUE : Math.round(driftSeconds * 1000.0D);
		ProbeRing.sample(new ProbeRing.ProbeSample(System.currentTimeMillis(),
				WorldPlaybackChannel.tickCounter,
				WorldPlaybackChannel.state().ordinal(),
				MbmSessionState.current().ordinal(),
				MbmSessionState.isFocused(),
				MbmSessionState.isPausedNow(),
				MbmSessionState.isPublishedNow(),
				MarkerClock.state().ordinal(),
				MarkerClock.anchorValid(),
				player.trackEnv().current(),
				StreamMusicPlayer.MUTE_ENV.current(),
				player.seekEnv().current(),
				player.trackEnv().target(),
				gateOwnerOrdinal(),
				audiblePos,
				decodedPos,
				driftMillis,
				MarkerClock.millisSinceAnchor(),
				MarkerClock.injectedTtlMillis(),
				WorldPlaybackChannel.recentSeekCount,
				WorldPlaybackChannel.millisSinceSeek(),
				WorldPlaybackChannel.seekCostMillis(),
				player.getLineBufferBytes(),
				Math.max(0, player.getLineBufferBytes() - player.getLineAvailableBytes()),
				player.getLineWatermarkBytes(),
				StreamMusicPlayer.isWatermarkAdaptive(),
				player.getUnderruns(),
				PcmFilterChain.mixCurrent(),
				PcmFilterChain.mixTarget(),
				AudioFilterManager.isRemovalPending(),
				(WorldPlaybackChannel.handle() == null ? 0 : 1) + (PreviewChannel.handle() == null ? 0 : 1),
				MarkerClock.firedMarkers(),
				player.getPlayCallCount()));
	}
	
	private static int gateOwnerOrdinal()
	{
		StreamMusicPlayer player = ExternalMusicHandler.getInstance().getPlayer();
		if (WorldPlaybackChannel.gateOwns(player.trackEnv()))
			return 1;
		if (WorldPlaybackChannel.gateOwns(player.seekEnv()))
			return 2;
		return 0;
	}
	
	public static long tickNumber()
	{
		return WorldPlaybackChannel.tickCounter;
	}
	
	// AUD-41: target state table, exactly as specified. K8-A: LEVEL_TRANSITION
	// preserves the current source - the channel picks PLAYING/MUTED by focus
	// and never stops or restarts
	private static ChannelState targetOf(SessionState session, boolean focused)
	{
		return switch (session) {
			case NO_WORLD, DISCONNECTED -> ChannelState.STOPPED;
			case SINGLEPLAYER_PAUSED -> ChannelState.PAUSED;
			case SINGLEPLAYER_RUNNING -> ChannelState.PLAYING;
			case LAN_HOST, MULTIPLAYER, LEVEL_TRANSITION -> focused ? ChannelState.PLAYING : ChannelState.MUTED;
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
				// AUD-50 v1.2: MUTED is inaudible but the clock keeps running
				// and correction participates - silence is the best time to
				// correct drift
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
		// AUD-53: world unload clears debug injection unconditionally
		MarkerClock.clearInjectedDrift();
		// AUD-48 v1.4: world unload resets the converged watermark
		StreamMusicPlayer.resetAdaptiveWatermark();
		// K6-B: world unload resets the connection-level estimator and the
		// session generation
		ClockOffsetEstimator.reset();
		ClockOffsetProbeScheduler.reset();
		WorldPlaybackChannel.playbackSessionGeneration = 0L;
		// K8-A: a real logout invalidates every in-flight gate and intent
		WorldPlaybackChannel.currentIntentUrl = null;
		WorldPlaybackChannel.intentVersion++;
		WorldPlaybackChannel.lastSeekAtMillis = 0L;
		WorldPlaybackChannel.recentSeekCount = 0;
		WorldPlaybackChannel.recentSeekIndex = 0;
		WorldPlaybackChannel.giveUpWarned = false;
		WorldPlaybackChannel.seekQueuedAtMillis = 0L;
		WorldPlaybackChannel.correctionDisabledAtMillis = 0L;
		WorldPlaybackChannel.correctionBackoffMillis = GIVE_UP_BACKOFF_FIRST_MILLIS;
		WorldPlaybackChannel.seekSettleUntilMillis = 0L;
		stopMusic();
	}

	// K8-A: a client level loaded - every async task carrying an older level
	// token is stale from now on
	public static void bumpLevelGeneration()
	{
		WorldPlaybackChannel.levelGeneration++;
		LOGGER.debug("[MBM] level generation bumped to {}", WorldPlaybackChannel.levelGeneration);
	}

	// K8-A: the selection engine (or the adoption path) declares the current
	// playback intent; every intent change invalidates in-flight gates
	public static void setCurrentIntent(String url)
	{
		WorldPlaybackChannel.currentIntentUrl = url;
		WorldPlaybackChannel.intentVersion++;
		LOGGER.debug("[MBM] playback intent set to {} (intentVersion={})", url,
				WorldPlaybackChannel.intentVersion);
	}

	public static long levelGeneration()
	{
		return WorldPlaybackChannel.levelGeneration;
	}

	public static long intentVersion()
	{
		return WorldPlaybackChannel.intentVersion;
	}

	public static @Nullable String currentIntentUrl()
	{
		return WorldPlaybackChannel.currentIntentUrl;
	}

	// K8-B: session generation accessor for lifecycle-tagged diagnostics
	public static long sessionGeneration()
	{
		return WorldPlaybackChannel.playbackSessionGeneration;
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
		// K3 P0-c: a correction-seek gate invalidates the anchor - while a
		// seek is queued the local position cannot be used for server-time
		// conversion (spec requires this explicitly; no behavioural-equivalence
		// substitute)
		if (env == ExternalMusicHandler.getInstance().getPlayer().seekEnv())
			MarkerClock.invalidateAnchor();
		long createdAt = System.currentTimeMillis();
		WorldPlaybackChannel.pendingGate = new PendingGate(env, action, fadeInMillis, createdAt,
				createdAt + fadeOutMillis + GATE_TIMEOUT_GRACE_MILLIS, actionName,
				WorldPlaybackChannel.intentVersion);
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
		// K8-A: the gate action carries the intent version it was queued for;
		// an intent change while gated (track switch, new dimension adopt)
		// must not let the old action run (it would restart or stop playback
		// for a stale target)
		if (gate.intentVersionAtQueue() != WorldPlaybackChannel.intentVersion) {
			LOGGER.warn("[MBM] gate '{}' skipped (intent changed while gated)", gate.actionName());
			gate.env().hardReset(1.0f);
			return;
		}
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
		// would report a paused track as ended. AUD-51 追加: a pending stop is
		// intent, not liveness - visible synchronously via stopRequested
		boolean alive = (handler.getPlayer().hasActiveTrack() || handler.isPreparingCurrentMusic())
				&& !handler.isStopRequested();
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
		// K6-B: a new playback session - stale S2C responses are rejected
		WorldPlaybackChannel.playbackSessionGeneration++;
		// AUD-52 v1.3: a track switch re-enables correction and clears the
		// frequency window and the backoff state
		MarkerClock.enableCorrection();
		WorldPlaybackChannel.recentSeekCount = 0;
		WorldPlaybackChannel.correctionDisabledAtMillis = 0L;
		WorldPlaybackChannel.correctionBackoffMillis = GIVE_UP_BACKOFF_FIRST_MILLIS;
		// K7-A: track switch - version bump, source cleared and the anchor
		// invalidated atomically; an in-flight seek's old token can never
		// restore across this boundary
		MarkerClock.invalidateForTrackSwitch();
		// K7-B: a new playback session starts with a LOCAL self-anchor at
		// position 0 - the track is immediately anchored without waiting for
		// any network round trip (the anchor is local by definition)
		MarkerClock.realignLocalSelfAnchor(url == null ? "" : url, 0L, System.currentTimeMillis());
		// AUD-52: track switch resets the seek accounting
		WorldPlaybackChannel.lastSeekAtMillis = 0L;
		WorldPlaybackChannel.seekSettleUntilMillis = 0L;
		WorldPlaybackChannel.giveUpWarned = false;
		// AUD-52 �޶�/O9: track switch resets the seek-cost queue stamp
		WorldPlaybackChannel.seekQueuedAtMillis = 0L;
		// AUD-22: report the playback start so the server can anchor the clock
		reportPlaybackStart();
	}
	
	// AUD-52/AUD-30 v1.8: seek accounting accessors for the probe
	public static int seekCount()
	{
		return WorldPlaybackChannel.recentSeekCount;
	}
	
	public static long millisSinceSeek()
	{
		long last = WorldPlaybackChannel.lastSeekAtMillis;
		return last <= 0L ? -1L : System.currentTimeMillis() - last;
	}
	
	// AUD-52 修订/R5: measured position cost of the last seek (queue time ->
	// the new line's watermark fill, from the generation-tagged stamp); -1
	// when not measurable
	public static long seekCostMillis()
	{
		long queued = WorldPlaybackChannel.seekQueuedAtMillis;
		if (queued <= 0L)
			return -1L;
		long reached = ExternalMusicHandler.getInstance().getPlayer().getWatermarkStamp().millis();
		if (reached <= 0L || reached < queued)
			return -1L;
		return reached - queued;
	}
	
	// K6-A: distinct names - start report (new playback) vs resume report
	// (after a pause, carrying the true resume epoch)
	private static void reportPlaybackStart()
	{
		PlaybackHandle active = WorldPlaybackChannel.handle;
		if (active == null)
			return;
		// K7-B: the report is a pure anchor notification - it carries the
		// session generation for staleness, and is NOT an offset probe (the
		// dedicated ClockOffsetProbeRequestPacket owns probing)
		MobBattleMusicNetwork.sendPlaybackStartReport(active.track(), active.startedEpochMillis(),
				WorldPlaybackChannel.playbackSessionGeneration);
	}
	
	private static void reportPlaybackResume(long resumeEpochMillis)
	{
		PlaybackHandle active = WorldPlaybackChannel.handle;
		if (active == null)
			return;
		MobBattleMusicNetwork.sendPlaybackStartReport(active.track(), resumeEpochMillis,
				WorldPlaybackChannel.playbackSessionGeneration);
	}
	
	// K7-B: the S2C anchor confirmation - the anchor is a LOCAL self-anchor,
	// so this packet only confirms the server saw the start report. It never
	// realigns (the probe response owns calibration and never realigns either)
	public static void handleClockSync(PlaybackClockSyncPacket packet)
	{
		if (packet.sessionGeneration() != WorldPlaybackChannel.playbackSessionGeneration) {
			LOGGER.debug("[MBM] clock sync confirmation dropped (stale session generation)");
			return;
		}
		LOGGER.debug("[MBM] clock sync confirmation for track {} (no re-anchor; local self-anchor)", packet.trackId());
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
		// K3 P0-a: no usable line -> position unknown (-1); correction and
		// marker counting must not participate with -1 in arithmetic
		if (positionMillis < 0L)
			return;
		
		// AUD-24: K4 P1-7: the periodic 5s C2S resync is removed - the server
		// PlaybackStartReportPacket handler only relays the client's own epoch
		// back (PlaybackStartReportPacket.java:52) and never anchors with it,
		// so the periodic report added nothing but latency. Anchors now come
		// from start reports, PAUSED-leave re-anchors and seek re-anchors.
		// (AUD-24's 5s period is superseded by event-driven anchoring.)
		
		// AUD-24/AUD-25: clock correction when the server has anchored this
		// track. AUD-50: correction participates only while the clock is
		// RUNNING, the anchor is valid and the player is actually audible
		// (AUD-51 追加: never during a pending stop). R6: while MUTED, only
		// once the mute envelope has fully reached zero - the fade-out window
		// must never be layered under a correction fade. K3 P0-c: never while
		// a seek is in flight or a gate is active
		boolean mutedNotSilent = WorldPlaybackChannel.state() == ChannelState.MUTED
				&& StreamMusicPlayer.MUTE_ENV.current() > 0.001f;
		if (MarkerClock.isActive() && MarkerClock.state() == MarkerClock.ClockState.RUNNING
				&& MarkerClock.anchorValid()
				&& handler.getPlayer().isPlaying()
				&& !handler.isStopRequested()
				&& !handler.isSeekInFlight()
				&& !WorldPlaybackChannel.isGatedTransitionActive()
				&& !mutedNotSilent)
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
			// AUD-24 v1.2: |drift| <= 1s -> no intervention.
			// AUD-52 v1.3/R3: noCorrection writes NOTHING - the realign after a
			// seek forces drift back into tolerance, so drift cannot tell
			// whether correction is effective; only the seek-completion
			// frequency window can
		}
		
		@Override
		public void seek(double serverPositionSeconds, double drift)
		{
			long now = System.currentTimeMillis();
			// AUD-52 v1.3/R3/K4 P0: give-up is decided by the frequency window
			// of ATTEMPTS - the last three queued seeks all within 20s
			if (WorldPlaybackChannel.recentSeekCount >= 3) {
				// K4 P2-2: the write index is the next slot to be written,
				// which is exactly the oldest of the current three - no
				// arithmetic offset
				long oldest = WorldPlaybackChannel.RECENT_SEEK_ATTEMPTS[WorldPlaybackChannel.recentSeekIndex];
				if (now - oldest <= GIVE_UP_WINDOW_MILLIS) {
					if (!WorldPlaybackChannel.giveUpWarned) {
						WorldPlaybackChannel.giveUpWarned = true;
						MarkerClock.disableCorrection();
						WorldPlaybackChannel.correctionDisabledAtMillis = now;
						LOGGER.warn("[MBM] AUD-52 correction disabled for this track (3 seek attempts within 20s)");
					}
					return;
				}
			}
			// AUD-52: settle window and minimum interval rejection
			// (suppressions are not attempts - they never touch the window)
			if (now < WorldPlaybackChannel.seekSettleUntilMillis
					|| now - WorldPlaybackChannel.lastSeekAtMillis < SEEK_MIN_INTERVAL_MILLIS) {
				LOGGER.debug("[MBM] AUD-52 seek suppressed (settle/rate)");
				return;
			}
			long targetMillis = Math.round(serverPositionSeconds * 1000.0D);
			ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
			String trackId = handler.getCurrentlyPlayingUrl();
			// AUD-49 #4: an explicit failure here just means the drift is
			// re-evaluated on the next tick; no state to roll back
			boolean queued = WorldPlaybackChannel.gatedTransition(handler.getPlayer().seekEnv(), 120L, "clock-seek", () -> {
				// AUD-51: the action only accounts state; the audio seek is
				// offloaded to the audio-I/O executor (no blocking work here)
				// K7-A: the atomic token is captured after the gate queued
				// (the gate already invalidated the anchor); it carries the
				// source version, the track identity and the session
				// generation - restore only succeeds while all three match
				MarkerClock.AnchorToken token = MarkerClock.captureAndInvalidateForSeek(trackId,
						WorldPlaybackChannel.playbackSessionGeneration);
				handler.seekMusicAsync(targetMillis, completedAt -> {
					// AUD-52 v1.2: the anchor moment is the measured watermark
					// fill time of the new line, never the dispatch time.
					// K7-A: on failure restore the OLD anchor - atomically,
					// only when the token still matches the current source.
					// No local re-anchor: the epoch is never touched and the
					// current local position is never used to fabricate a new
					// anchor.
					if (completedAt <= 0L) {
						LOGGER.warn("[MBM] AUD-52 seek to {}ms failed (watermark not reached); restoring old anchor", targetMillis);
						if (MarkerClock.restoreAfterFailedSeek(token)) {
							LOGGER.debug("[MBM] old authoritative anchor restored (token valid)");
						} else {
							LOGGER.debug("[MBM] anchor restore skipped (source changed)");
						}
						// the cost was already paid - the rate limit must count it
						WorldPlaybackChannel.lastSeekAtMillis = System.currentTimeMillis();
						return;
					}
					if (trackId != null) {
						// K7-B: a LOCAL self-anchor at the measured watermark
						// position - the offset is never applied here
						MarkerClock.realignLocalSelfAnchor(trackId, targetMillis, completedAt);
					}
					// K4 P0: rate-limit timing moves to queue time (attempt);
					// onComplete no longer writes the attempt window
					LOGGER.debug("[MBM] AUD-24 seek to {}ms re-anchored at watermark", targetMillis);
				});
				MarkerClock.clearInjectedDrift();
				WorldPlaybackChannel.lastMarkerPosition = -1L;
				WorldPlaybackChannel.firedThisTrack = 0L;
			}, 120L);
			if (queued) {
				// K4 P0: the attempt is recorded at queue time, on the caller
				// (tick) thread - the line rebuild and Thread.start() cost is
				// fully paid at this moment; the settle window and rate limit
				// also start here
				WorldPlaybackChannel.RECENT_SEEK_ATTEMPTS[WorldPlaybackChannel.recentSeekIndex] = now;
				WorldPlaybackChannel.recentSeekIndex = (WorldPlaybackChannel.recentSeekIndex + 1) % 3;
				WorldPlaybackChannel.recentSeekCount = Math.min(3, WorldPlaybackChannel.recentSeekCount + 1);
				WorldPlaybackChannel.giveUpWarned = false;
				WorldPlaybackChannel.lastSeekAtMillis = now;
				WorldPlaybackChannel.seekSettleUntilMillis = now + SEEK_SETTLE_MILLIS;
				WorldPlaybackChannel.seekQueuedAtMillis = now;

			}
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