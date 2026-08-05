package nonamecrackers2.mobbattlemusic.client.manager;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.TieredItem;
import nonamecrackers2.mobbattlemusic.client.audio.PlaybackHandle;
import nonamecrackers2.mobbattlemusic.client.audio.SourceRef;
import nonamecrackers2.mobbattlemusic.client.audio.WorldPlaybackChannel;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadataCache;
import nonamecrackers2.mobbattlemusic.client.music.TimelineMarkerStore;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.client.sound.ExternalUrlMusicTrack;
import nonamecrackers2.mobbattlemusic.client.sound.MobBattleTrack;
import nonamecrackers2.mobbattlemusic.client.sound.MobBattleMusicSounds;
import nonamecrackers2.mobbattlemusic.client.sound.track.TrackType;
import nonamecrackers2.mobbattlemusic.client.util.AggressiveEntityStateClient;
import nonamecrackers2.mobbattlemusic.client.util.PlayerCombatSessionClient;
import nonamecrackers2.mobbattlemusic.client.util.MobBattleMusicCompat;
import nonamecrackers2.mobbattlemusic.client.util.MobSelection;
import nonamecrackers2.mobbattlemusic.mixin.MixinAbstractSoundInstance;
import nonamecrackers2.mobbattlemusic.mixin.MixinMusicManagerAccessor;
import nonamecrackers2.mobbattlemusic.mixin.MixinSoundEngineAccessor;
import nonamecrackers2.mobbattlemusic.mixin.MixinSoundManagerAccessor;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;
import nonamecrackers2.mobbattlemusic.network.TimelineMarkerHitPacket;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;
import nonamecrackers2.mobbattlemusic.timeline.MobBattleMusicTimeline;

public class BattleMusicManager {
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/BattleMusicManager");
	private static final long EXTERNAL_RESUME_WINDOW_MILLIS = 15_000L;
	public static final SoundSource DEFAULT_SOUND_SOURCE = SoundSource.RECORDS;
	private static final Predicate<LivingEntity> COUNTS_TOWARDS_MOB_COUNT = e -> {
		return e instanceof Mob && (MobBattleMusicConfig.CLIENT.whiteListMode.get() ? blacklist(e) : !blacklist(e));
	};

	private final TargetingConditions targetingConditions = TargetingConditions.forCombat()
			.ignoreLineOfSight()
			.range(MobBattleMusicConfig.CLIENT.maxMobSearchRadius.get());
	private final TargetingConditions panicConditions = this.targetingConditions.copy()
			.range(MobBattleMusicConfig.CLIENT.threatRadius.get());

	private final Minecraft minecraft;
	private final ClientLevel level;
	private final Map<TrackType, MobBattleTrack> tracks = Maps.newHashMap();
	private final Map<TrackType, ExternalUrlMusicTrack> externalTracks = Maps.newHashMap(); // For external URL tracks
	private final Map<ResourceLocation, Long> idleNextStartMillis = Maps.newHashMap();
	private final Map<ResourceLocation, ResumeState> externalResumeStates = Maps.newHashMap();
	// K12-A: last owner seen while holding the deck; the per-tick hold log is
	// only emitted on an owner edge (the probe Ring takes over continuous
	// observation), so a long MAN/CUE session does not spam DEBUG lines
	private WorldPlaybackChannel.PlaybackOwner lastHoldOwner;
	private long idleSuppressedUntilMillis;
	private boolean initialIdleCooldownApplied;
	private java.util.UUID reportedPlayerOpponent;
	private int playerSessionReportCooldown;
	private boolean playerSessionReportInitialized;
	private @Nullable ResourceLocation timelineTrack;
	private String timelineUrl = "";
	private long timelineLastPosition = -1L;
	private @Nullable TrackType priorityTrack;
	private int maxThreatRefreshTime;
	private int threatRefreshTimer;
	private int threatRemovalTimer;
	private @Nullable LivingEntity panickingFrom;
	// K8-A: the first-tick adoption reconciliation runs exactly once per level
	private boolean adoptionChecked;

	public BattleMusicManager(Minecraft mc, ClientLevel level) {
		this.minecraft = mc;
		this.level = level;
	}

	private static boolean blacklist(LivingEntity e) {
		return MobBattleMusicConfig.CLIENT.ignoredMobs.get().stream().anyMatch(s -> s.equals(e.getEncodeId()));
	}

	private boolean isMobAggressive(Mob mob) {
		if (mob.distanceTo(this.minecraft.player) > MobBattleMusicConfig.CLIENT.threatRadius.get())
			return false;
		if (AggressiveEntityStateClient.isServerAggressive(mob.getUUID()))
			return true;
		
		// 检查 mob 是否有仇恨目标
		if (mob.getTarget() != null)
			return true;

		// 检查 mob 是否处于激怒状态
		if (mob.isAggressive())
			return true;

		// 检查是否是当前的恐慌目标
		return panickingFrom != null && panickingFrom.equals(mob);
	}

	// 此方法用于确定是否应该触发战斗音乐
	private boolean shouldTriggerCombatMusic(MobSelection selection) {
		// 检查是否有任何生物处于战斗状态
		return selection.group(MobSelection.GroupType.ENEMIES)
				.forSelector(MobSelection.Selector.LINE_OF_SIGHT)
				.stream()
				.anyMatch(mob -> isMobAggressive(mob));
	}

	public void tick() {
		// K8-A: the adoption reconciliation runs on the first tick of this
		// level, before any priority decision - the session player may already
		// be playing from the previous dimension
		if (!this.adoptionChecked) {
			this.adoptionChecked = true;
			adoptCurrentSessionPlayback();
		}
		// Handle threat timers
		boolean flag = true;
		if (this.panickingFrom != null) {
			if (this.panickingFrom.isAlive() && this.panicConditions.test(this.minecraft.player, this.panickingFrom)
					&& this.minecraft.player.hasLineOfSight(this.panickingFrom))
				flag = false;
		}

		if (flag) {
			if (this.threatRemovalTimer++ > MobBattleMusicConfig.CLIENT.calmDownTime.get() * 20) {
				this.threatRemovalTimer = 0;
				this.panickingFrom = null;
			}
			this.threatRefreshTimer = this.maxThreatRefreshTime;
		} else {
			this.threatRemovalTimer = 0;
			if (this.threatRefreshTimer > 0) {
				this.threatRefreshTimer--;
				if (this.threatRefreshTimer == 0)
					this.panickingFrom = null;
			}
		}

		MobBattleMusicCompat.YoukaiStgCombat youkaiStgCombat =
				MobBattleMusicCompat.getYoukaiHomecomingStgCombat(this.minecraft.player);
		LivingEntity youkaiStgTarget = youkaiStgCombat.sessionTarget();
		reportPlayerCombatSession(youkaiStgCombat);
		if (youkaiStgTarget != null && !(youkaiStgTarget instanceof Player) && youkaiStgTarget.isAlive()
				&& !(this.panickingFrom instanceof Player)) {
			this.panic(youkaiStgTarget, MobBattleMusicConfig.CLIENT.threatReevaluationCooldown.get() * 20);
		}

		MobSelection.Builder builder = MobSelection.builder();

		double maxMobSearchRadius = MobBattleMusicConfig.CLIENT.maxMobSearchRadius.get();
		for (Mob mob : this.level.getEntitiesOfClass(Mob.class,
				this.minecraft.player.getBoundingBox().inflate(maxMobSearchRadius),
				mob -> mob.isAlive() && mob.distanceTo(this.minecraft.player) <= maxMobSearchRadius &&
						COUNTS_TOWARDS_MOB_COUNT.test(mob))) {
				var frustum = this.minecraft.levelRenderer.getFrustum();
				boolean viewable = frustum != null && frustum.isVisible(mob.getBoundingBox());
				boolean lineOfSight = this.minecraft.player.hasLineOfSight(mob);

				// 如果是战斗状态，添加到 ATTACKING 组
				if (isMobAggressive(mob)) {
					builder.addToGroup(MobSelection.GroupType.ATTACKING, MobSelection.Selector.ANY, mob);
					if (lineOfSight) {
						builder.addToGroup(MobSelection.GroupType.ATTACKING, MobSelection.Selector.LINE_OF_SIGHT, mob);
						if (viewable)
							builder.addToGroup(MobSelection.GroupType.ATTACKING, MobSelection.Selector.ON_SCREEN, mob);
					}
				}

				// 所有生物都添加到 ENEMIES 组
				builder.addToGroup(MobSelection.GroupType.ENEMIES, MobSelection.Selector.ANY, mob);
				if (lineOfSight) {
					builder.addToGroup(MobSelection.GroupType.ENEMIES, MobSelection.Selector.LINE_OF_SIGHT, mob);
					if (viewable)
						builder.addToGroup(MobSelection.GroupType.ENEMIES, MobSelection.Selector.ON_SCREEN, mob);
				}
		}

		boolean playerCombatActive = this.panickingFrom instanceof Player || youkaiStgCombat.playerOpponent();
		MobSelection selection = builder.setPanicTarget(this.panickingFrom)
				.setPlayerCombatActive(playerCombatActive)
				.build();

		// Update panic state
		Mob closestAggressor = null;
		double distance = -1.0D;
		for (Mob mob : selection.group(MobSelection.GroupType.ATTACKING).forSelector(MobSelection.Selector.ANY)) {
			double d = mob.distanceTo(this.minecraft.player);
			if (distance == -1.0D || distance > d) {
				closestAggressor = mob;
				distance = d;
			}
		}

		if (closestAggressor != null && this.panickingFrom != closestAggressor &&
				this.panicConditions.test(this.minecraft.player, closestAggressor) &&
				!(this.panickingFrom instanceof Player)) {
			this.panic(closestAggressor, MobBattleMusicConfig.CLIENT.threatReevaluationCooldown.get() * 20);
		}

		// Handle track cleanup
		var iterator = this.tracks.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			MobBattleTrack track = entry.getValue();
			if (track.isStopped() || !this.minecraft.getSoundManager().isActive(track)) {
				LOGGER.debug("Removing track {}, it is no longer playing", track);
				// AUD-35: unregister from the main channel's own collection
				WorldPlaybackChannel.unregisterEngineTrack(track);
				scheduleIdleNextStart(entry.getKey());
				MusicTracksManager.getInstance().clearSoundSessionSelection(entry.getKey().getTrack());
				iterator.remove();
			}
		}

		// K11-B: any non-AUTO owner holds the deck - AUTO must never write
		// the player while the user (MAN) or the server (CUE) drives it.
		// Threat/combat-session bookkeeping and timeline markers still run
		// (they observe, they do not select). Returning to AUTO requires an
		// explicit action (MAN: the dock badge; CUE: server release only).
		boolean autoHold = WorldPlaybackChannel.playbackOwner() != WorldPlaybackChannel.PlaybackOwner.AUTO;
		if (autoHold) {
			// K11-B: keep the external wrapper collection in sync with the
			// session player so marker ticking follows manual selections
			syncExternalTrackWrappersToSession();
			// K12-A: log only on an owner edge (probe Ring does continuous
			// observation); long MAN/CUE sessions must not spam DEBUG lines
			if (WorldPlaybackChannel.playbackOwner() != this.lastHoldOwner) {
				LOGGER.debug("[MBM] {} hold active - AUTO selection suspended",
						WorldPlaybackChannel.playbackOwner());
				this.lastHoldOwner = WorldPlaybackChannel.playbackOwner();
			}
			tickTimelineMarkers();
			return;
		}
		if (this.lastHoldOwner != null) {
			LOGGER.debug("[MBM] deck released - AUTO selection resumed");
			this.lastHoldOwner = null;
		}

		// 更新音轨
		List<TrackType> tracks = MusicTracksManager.getInstance().getTracks();

		long now = System.currentTimeMillis();
		initializeIdleCooldowns(tracks, now);
		TrackType priority = null;
		for (TrackType type : tracks) {
			// K16-B: the playlist-level rule gate joins the bucket canPlay
			// check - a playlist whose 歌单规则 does not match (e.g. wrong
			// dimension/biome for a combat playlist) can never win
			if (type.canPlay(selection) && type.playlistConditionsMatch() && isStartAllowed(type, now)) {
				priority = type;
				break;
			}
		}
		if (priority != null && !priority.isIdlePlayback())
			this.idleSuppressedUntilMillis = now + MobBattleMusicConfig.CLIENT.idleResumeDelay.get() * 1000L;
		this.priorityTrack = priority;

		for (TrackType type : tracks) {
			float trackDesiredVolume = shouldStopTracksForModCompat(this.minecraft.getSoundManager()) ? 0.0F
					: type.getVolume(selection);
			boolean canPlay = priority == type;
			this.initiateAndOrUpdateTrack(type, canPlay && trackDesiredVolume > 0.0F, track -> {
				if (canPlay) {
					track.setTargetedVolume(trackDesiredVolume);
				} else {
					track.setTargetedVolume(0.0F);
				}
			});
		}
		tickTimelineMarkers();
	}

	/**
	 * K11-B: while a non-AUTO owner holds the deck, the selection engine does
	 * not rebuild wrappers - this mirrors the session player into the
	 * external wrapper collection so timeline markers keep ticking on manual
	 * selections. Wrappers are adopted (never played).
	 * K12-B: the wrapper is derived from the SESSION handle's SourceRef
	 * (playlistId/entryKey/revision), never reverse-derived from the AUTO
	 * manager's current selection - a manual Prev/Next does not move the AUTO
	 * selected index, so a URL lookup against it could miss and drop the
	 * wrapper (marker chain break).
	 */
	private void syncExternalTrackWrappersToSession()
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		String url = handler.getCurrentlyPlayingUrl();
		if (url == null || handler.isPreparingCurrentMusic())
			return;
		PlaybackHandle handle = WorldPlaybackChannel.handle();
		if (handle == null || handle.sourceRef() == null)
			return;
		SourceRef ref = handle.sourceRef();
		MusicTracksManager manager = MusicTracksManager.getInstance();
		for (TrackType type : manager.getTracks()) {
			ResourceLocation location = type.getTrack();
			if (!manager.isExternalUrl(location))
				continue;
			// K12-B: match by the SourceRef playlist id, not the AUTO URL
			if (location.toString().equals(ref.playlistId())) {
				ExternalUrlMusicTrack existing = this.externalTracks.get(type);
				if (existing == null || !existing.getUrl().equals(url))
					this.externalTracks.put(type, ExternalUrlMusicTrack.adopt(url, type.getFadeTime()));
				return;
			}
		}
		// the session URL matches no selected track type - drop stale
		// wrappers so marker ticking does not run against a dead entry
		this.externalTracks.clear();
	}

	private void tickTimelineMarkers()
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		if (!handler.isPlaying()) {
			this.timelineTrack = null;
			this.timelineUrl = "";
			this.timelineLastPosition = -1L;
			return;
		}
		for (Map.Entry<TrackType, ExternalUrlMusicTrack> entry : this.externalTracks.entrySet()) {
			ExternalUrlMusicTrack externalTrack = entry.getValue();
			if (!externalTrack.isPlaying() || !externalTrack.getUrl().equals(handler.getCurrentlyPlayingUrl()))
				continue;
			ResourceLocation track = entry.getKey().getTrack();
			boolean serverTrack = MusicTracksManager.getInstance().dynamicSource(track) ==
					MusicTracksManager.DynamicSource.SERVER;
			long position = handler.getPositionMillis();
			// K4 P1: explicit early exit on unknown position (-1); the
			// isPlaying() guard above does not imply a usable line at this
			// exact moment
			if (position < 0L)
				return;
			if (!track.equals(this.timelineTrack) || !externalTrack.getUrl().equals(this.timelineUrl)) {
				this.timelineTrack = track;
				this.timelineUrl = externalTrack.getUrl();
				this.timelineLastPosition = externalTrack.getStartPositionMillis() > 0L ? position : -1L;
			}
			if (position < this.timelineLastPosition)
				this.timelineLastPosition = position;
			int selectedIndex = MusicTracksManager.getInstance().getExternalPlaylistSelectedIndex(track);
			for (TimelineMarker marker : TimelineMarkerStore.markers(track, externalTrack.getUrl(), selectedIndex)) {
				if (marker.timeMillis() > this.timelineLastPosition && marker.timeMillis() <= position) {
					// K14-B: marker firing is now SERVER-authoritative (the
					// server Cue timeline fires each marker once per session).
					// The client keeps the local fire for visual/audio feedback
					// and diagnosis only - it is never the authority for Boss
					// logic. No marker C2S packet is sent anymore.
					MobBattleMusicTimeline.fireClient(this.minecraft.player, this.panickingFrom, track,
							externalTrack.getUrl(), marker);
				}
			}
			this.timelineLastPosition = position;
			return;
		}
	}

	private void initiateAndOrUpdateTrack(TrackType type, boolean allowNewTracks, Consumer<MobBattleTrack> consumer) {
		// Check if this is an external URL track
		ResourceLocation trackLocation = type.getTrack();
		MusicTracksManager tracksManager = MusicTracksManager.getInstance();

		if (tracksManager.isExternalUrl(trackLocation)) {
			// AUD-46: while a gated switch (fade-out in progress) is active,
			// hold the whole external leg; the new track starts only after the
			// old one has reached zero gain
			if (WorldPlaybackChannel.isGatedTransitionActive())
				return;
			// Handle external URL track
			long synchronizedPosition = allowNewTracks
					? synchronizeCombatPlayback(type, trackLocation, tracksManager) : 0L;
			String url = tracksManager.getExternalUrl(trackLocation);
			ExternalUrlMusicTrack externalTrack = this.externalTracks.get(type);
			if (url == null) {
				if (externalTrack != null) {
					externalTrack.stop();
					this.externalTracks.remove(type);
					tracksManager.clearExternalSessionSelection(trackLocation);
					scheduleIdleNextStart(type);
				}
				return;
			}
			if (url != null) {
				if (externalTrack != null && externalTrack.isStopped()) {
					this.externalTracks.remove(type);
					tracksManager.clearExternalSessionSelection(trackLocation);
					scheduleIdleNextStart(type);
					externalTrack = null;
				}
				if (externalTrack != null && !externalTrack.getUrl().equals(url)) {
					// AUD-46: gated fade-out before switching (same-type switch
					// durations). AUD-49 #4: on rejection keep the old track;
					// the switch retries on the next tick.
					ExternalUrlMusicTrack oldTrack = externalTrack;
					String newUrl = url;
					long fadeOut = switchFadeOutMillis(type.isIdlePlayback(), type.isIdlePlayback());
					long fadeIn = switchFadeInMillis(type.isIdlePlayback(), type.isIdlePlayback());
					boolean queued = WorldPlaybackChannel.gatedTransition(
							ExternalMusicHandler.getInstance().getPlayer().trackEnv(),
							fadeOut, "track-switch",
							() -> {
								cacheExternalResume(trackLocation, oldTrack);
								oldTrack.stop();
								this.externalTracks.remove(type);
								tracksManager.clearExternalSessionSelection(trackLocation);
								// K13-C: an entry switch (disabled current
								// entry / selection change) must NOT inherit
								// the stopped track's idle cooldown - the next
								// tick must be allowed to pick the replacement
								// immediately, otherwise disabling the playing
								// idle track leaves silence until the cooldown
								// expires. The cooldown still applies to
								// natural stops (url==null path).
								this.idleNextStartMillis.remove(type.getTrack());
								LOGGER.info("Switched external URL track to selected playlist entry: {}", newUrl);
							},
							fadeIn);
					if (queued) {
						externalTrack = null;
						// AUD-49 #6: after queueing a gate, this leg must not
						// create or start any source this tick
						return;
					}
				}

				if (allowNewTracks
						&& this.minecraft.options.getSoundSourceVolume(BattleMusicManager.DEFAULT_SOUND_SOURCE) > 0.0F
						&& this.minecraft.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F) {
					// Only create new track if it doesn't exist or has stopped
					if (externalTrack == null || externalTrack.isStopped()) {
						expireExternalResume(trackLocation, tracksManager);
						// AUD-49 #6: when a gate was queued (or another track
						// still needs stopping), no source may be created this
						// tick; selectExternalUrl is deferred so its side
						// effects (session selection, SEQUENTIAL advance) do
						// not run before the early return
						if (stopOtherExternalTracks(type, tracksManager))
							return;
						url = tracksManager.selectExternalUrl(trackLocation);
						if (url == null)
							return;
						long resumePosition = consumeExternalResume(trackLocation, url);
						if (resumePosition <= 0L)
							resumePosition = wrapSynchronizedPosition(url, synchronizedPosition);
						externalTrack = new ExternalUrlMusicTrack(url, type.getFadeTime(), resumePosition);
						// K8-A: declare the playback intent before starting -
						// every intent change invalidates in-flight gates
						WorldPlaybackChannel.setCurrentIntent(url);
						externalTrack.play();
						this.externalTracks.put(type, externalTrack);
						this.notifyTrackSwitch(tracksManager.describeTrack(trackLocation, url));
						LOGGER.info("Beginning external URL track with fade time {}: {}", type.getFadeTime(), url);
					}
				}

				// Handle volume changes for external tracks
				if (externalTrack != null) {
					// Update target volume based on whether this track should be playing
					if (allowNewTracks) {
						// Track should be playing - set target volume to 1.0
						externalTrack.setTargetVolume(1.0f);
					} else {
						// Track should fade out - set target volume to 0.0
						externalTrack.setTargetVolume(0.0f);

						// If volume has reached 0, stop and remove the track
						if (externalTrack.getCurrentVolume() <= 0.01f) {
							boolean resumable = cacheExternalResume(trackLocation, externalTrack);
							externalTrack.stop();
							this.externalTracks.remove(type);
							if (!resumable)
								tracksManager.clearExternalSessionSelection(trackLocation);
							scheduleIdleNextStart(type);
							LOGGER.debug("Stopped and removed external URL track: {}", url);
						}
					}
				}
			}
		} else {
			// Handle normal Minecraft sound track
			long synchronizedPosition = allowNewTracks
					? synchronizeCombatPlayback(type, trackLocation, tracksManager) : 0L;
			boolean soundPlaylist = tracksManager.isSoundPlaylist(trackLocation);
			ResourceLocation soundTrackLocation = tracksManager.selectSoundTrack(trackLocation);
			if (soundPlaylist && soundTrackLocation == null) {
				MobBattleTrack existingTrack = this.tracks.remove(type);
				if (existingTrack != null) {
					WorldPlaybackChannel.unregisterEngineTrack(existingTrack);
					existingTrack.stop();
				}
				tracksManager.clearSoundSessionSelection(trackLocation);
				return;
			}
			ResourceLocation resolvedTrackLocation = soundTrackLocation != null ? soundTrackLocation : trackLocation;
			MobBattleTrack track = null;
			MobBattleTrack existingTrack = this.tracks.get(type);
			if (existingTrack != null && !existingTrack.getTrackLocation().equals(resolvedTrackLocation)) {
				WorldPlaybackChannel.unregisterEngineTrack(existingTrack);
				existingTrack.stop();
				this.tracks.remove(type);
				tracksManager.clearSoundSessionSelection(trackLocation);
				existingTrack = null;
			}
			if (allowNewTracks
					&& this.minecraft.options.getSoundSourceVolume(BattleMusicManager.DEFAULT_SOUND_SOURCE) > 0.0F
					&& this.minecraft.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F) {
				track = this.tracks.computeIfAbsent(type, t -> {
					MobBattleTrack newTrack = new MobBattleTrack(resolvedTrackLocation, type.getFadeTime(),
							!type.isIdlePlayback(), synchronizedPosition);
					// AUD-35: register into the main channel's own collection
					WorldPlaybackChannel.registerEngineTrack(newTrack);
					this.minecraft.getSoundManager().play(newTrack);
					this.notifyTrackSwitch(tracksManager.describeTrack(trackLocation, resolvedTrackLocation.toString()));
					LOGGER.debug("Beginning track {}", type);
					return newTrack;
				});
			} else {
				track = existingTrack;
			}

			if (track != null) {
				consumer.accept(track);
			}
		}
	}

	private boolean isStartAllowed(TrackType type, long now)
	{
		if (!MusicTracksManager.getInstance().hasPlayableMusicEntries(type.getTrack()))
			return false;
		if (this.reportedPlayerOpponent != null && isPlayerTrack(type.getTrack()) &&
				PlayerCombatSessionClient.startTick(this.reportedPlayerOpponent) < 0L)
			return false;
		if (!type.isIdlePlayback())
			return true;
		// K12-F: an adopted (already playing) idle track must not be
		// rejected by the start cooldown - the cooldown only delays NEW
		// starts. Rejecting it here makes the first post-adoption tick fade
		// out and stop the cross-dimension session playback.
		if (hasActiveExternalWrapper(type))
			return true;
		return now >= this.idleSuppressedUntilMillis &&
				now >= this.idleNextStartMillis.getOrDefault(type.getTrack(), 0L);
	}

	private boolean isPlayerTrack(ResourceLocation trackLocation)
	{
		MusicTracksManager.DynamicBinding binding = MusicTracksManager.getInstance().editableBinding(trackLocation);
		return trackLocation.equals(MobBattleMusicSounds.PLAYER_TRACK) ||
				binding != null && "player".equals(binding.scene());
	}

	private void initializeIdleCooldowns(List<TrackType> tracks, long now)
	{
		if (this.initialIdleCooldownApplied)
			return;
		this.initialIdleCooldownApplied = true;
		for (TrackType type : tracks) {
			if (!type.isIdlePlayback())
				continue;
			// K12-F: an idle track that was already adopted from the previous
			// level (cross-dimension session playback) must NOT get a start
			// cooldown - it is already playing, the cooldown only gates NEW
			// starts. Without this, the first tick after adoption would fade
			// out and stop the session track, then replay it from zero.
			if (hasActiveExternalWrapper(type))
				continue;
			int interval = type.getPlaybackIntervalSeconds();
			if (interval <= 0)
				interval = Math.max(5, MobBattleMusicConfig.CLIENT.idleResumeDelay.get());
			double jitter = 0.9D + this.level.random.nextDouble() * 0.2D;
			this.idleNextStartMillis.put(type.getTrack(), now + Math.round(interval * 1000.0D * jitter));
		}
	}

	// K12-F: does this level manager hold a live (not stopped) external wrapper
	// for the track? Used to exempt an in-flight session track from the idle
	// start cooldown and from the "not allowed to start" fade-out path.
	private boolean hasActiveExternalWrapper(TrackType type)
	{
		ExternalUrlMusicTrack track = this.externalTracks.get(type);
		return track != null && !track.isStopped();
	}

	private long synchronizeCombatPlayback(TrackType type, ResourceLocation trackLocation,
			MusicTracksManager tracksManager)
	{
		if (type.isIdlePlayback())
			return 0L;
		MusicTracksManager.DynamicBinding binding = tracksManager.editableBinding(trackLocation);
		java.util.UUID playerOpponent = this.panickingFrom instanceof Player player
				? player.getUUID() : this.reportedPlayerOpponent;
		boolean playerTrack = isPlayerTrack(trackLocation);
		if (playerTrack && playerOpponent != null) {
			long startTick = PlayerCombatSessionClient.startTick(playerOpponent);
			if (startTick < 0L)
				return 0L;
			long seed = startTick ^ this.minecraft.player.getUUID().getMostSignificantBits() ^
					this.minecraft.player.getUUID().getLeastSignificantBits() ^
					playerOpponent.getMostSignificantBits() ^ playerOpponent.getLeastSignificantBits() ^
					trackLocation.hashCode();
			tracksManager.synchronizeSessionSelection(trackLocation, seed);
			return Math.max(0L, this.level.getGameTime() - startTick) * 50L;
		}
		if (this.panickingFrom == null)
			return 0L;
		if (binding != null && !"aggressive".equals(binding.scene()))
			return 0L;
		long startTick = AggressiveEntityStateClient.startTick(this.panickingFrom.getUUID());
		if (startTick < 0L)
			return 0L;
		long seed = startTick ^ this.panickingFrom.getUUID().getMostSignificantBits() ^
				this.panickingFrom.getUUID().getLeastSignificantBits() ^ trackLocation.hashCode();
		tracksManager.synchronizeSessionSelection(trackLocation, seed);
		return Math.max(0L, this.level.getGameTime() - startTick) * 50L;
	}

	private void reportPlayerCombatSession(MobBattleMusicCompat.YoukaiStgCombat combat)
	{
		java.util.UUID opponent = combat.playerOpponent() && combat.sessionTarget() instanceof Player player
				? player.getUUID() : this.panickingFrom instanceof Player player ? player.getUUID() : null;
		if (this.playerSessionReportCooldown > 0)
			this.playerSessionReportCooldown--;
		if (java.util.Objects.equals(opponent, this.reportedPlayerOpponent) && this.playerSessionReportInitialized) {
			if (opponent == null || this.playerSessionReportCooldown > 0)
				return;
		}
		this.reportedPlayerOpponent = opponent;
		this.playerSessionReportInitialized = true;
		this.playerSessionReportCooldown = 10;
		MobBattleMusicNetwork.reportPlayerCombatSession(opponent);
		if (opponent == null)
			PlayerCombatSessionClient.clear();
	}

	private static long wrapSynchronizedPosition(String url, long position)
	{
		if (position <= 0L)
			return 0L;
		long duration = MusicMetadataCache.getInstance().get(url)
				.map(metadata -> metadata.durationMillis()).orElse(0L);
		return duration > 1L ? position % duration : position;
	}

	private void scheduleIdleNextStart(TrackType type)
	{
		if (!type.isIdlePlayback())
			return;
		long delay = Math.max(0L, type.getPlaybackIntervalSeconds()) * 1000L;
		this.idleNextStartMillis.put(type.getTrack(), System.currentTimeMillis() + delay);
	}

	/**
	 * AUD-46/AUD-49 #6: stop other external tracks through gated transitions.
	 * Returns true when this tick queued a gate OR another external track
	 * still needs stopping (gate slot busy); the caller must return early so
	 * no source is created this tick. The lambda captures the TrackType and
	 * ExternalUrlMusicTrack as final locals, never a Map.Entry.
	 */
	private boolean stopOtherExternalTracks(TrackType keep, MusicTracksManager tracksManager)
	{
		boolean pending = false;
		for (Map.Entry<TrackType, ExternalUrlMusicTrack> entry : List.copyOf(this.externalTracks.entrySet())) {
			if (entry.getKey() == keep)
				continue;
			pending = true;
			if (WorldPlaybackChannel.isGatedTransitionActive())
				break;
			TrackType otherType = entry.getKey();
			ExternalUrlMusicTrack externalTrack = entry.getValue();
			long fadeOut = switchFadeOutMillis(otherType.isIdlePlayback(), keep.isIdlePlayback());
			long fadeIn = switchFadeInMillis(otherType.isIdlePlayback(), keep.isIdlePlayback());
			boolean queued = WorldPlaybackChannel.gatedTransition(
					ExternalMusicHandler.getInstance().getPlayer().trackEnv(),
					fadeOut, "switch-others",
					() -> {
						boolean resumable = cacheExternalResume(otherType.getTrack(), externalTrack);
						externalTrack.stop();
						this.externalTracks.remove(otherType);
						if (!resumable)
							tracksManager.clearExternalSessionSelection(otherType.getTrack());
						scheduleIdleNextStart(otherType);
					},
					fadeIn);
			break;
		}
		return pending;
	}

	// AUD-46: asymmetric switch durations (idle->aggressive 200/150,
	// aggressive->idle 1200/800, aggressive->aggressive 300/300; idle->idle
	// is not defined in the table - symmetric default 200/200)
	private static long switchFadeOutMillis(boolean oldIdle, boolean newIdle)
	{
		if (oldIdle && !newIdle)
			return 200L;
		if (!oldIdle && newIdle)
			return 1200L;
		if (!oldIdle)
			return 300L;
		return 200L;
	}

	private static long switchFadeInMillis(boolean oldIdle, boolean newIdle)
	{
		if (oldIdle && !newIdle)
			return 150L;
		if (!oldIdle && newIdle)
			return 800L;
		if (!oldIdle)
			return 300L;
		return 200L;
	}

	private boolean cacheExternalResume(ResourceLocation track, ExternalUrlMusicTrack externalTrack)
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		if (!externalTrack.getUrl().equals(handler.getCurrentlyPlayingUrl()) || handler.isPreparingCurrentMusic())
			return false;
		long position = handler.getPositionMillis();
		// K4 P1: explicit early exit on unknown position (-1); the > 0 check
		// below would also reject it, but the exit is stated explicitly
		if (position < 0L)
			return false;
		if (position > 0L) {
			this.externalResumeStates.put(track, new ResumeState(externalTrack.getUrl(), position,
					System.currentTimeMillis() + EXTERNAL_RESUME_WINDOW_MILLIS));
			return true;
		}
		return false;
	}

	private void expireExternalResume(ResourceLocation track, MusicTracksManager tracksManager)
	{
		ResumeState state = this.externalResumeStates.get(track);
		if (state != null && state.expiresAtMillis() < System.currentTimeMillis()) {
			this.externalResumeStates.remove(track);
			tracksManager.clearExternalSessionSelection(track);
		}
	}

	private long consumeExternalResume(ResourceLocation track, String url)
	{
		ResumeState state = this.externalResumeStates.remove(track);
		if (state == null || state.expiresAtMillis() < System.currentTimeMillis() || !state.url().equals(url))
			return 0L;
		return state.positionMillis();
	}

	private void notifyTrackSwitch(String description)
	{
		if (!MobBattleMusicConfig.CLIENT.showTrackActionbar.get() || this.minecraft.player == null)
			return;
		this.minecraft.player.displayClientMessage(Component.literal("Now playing: " + description), true);
	}

	public void wasAttacked(DamageSource source) {
		Entity entity = source.getEntity();
		if (entity instanceof Mob mob && !(this.panickingFrom instanceof Player))
			this.panic(mob, MobBattleMusicConfig.CLIENT.threatReevaluationCooldown.get() * 20);
		else if (entity instanceof Player player && player != this.minecraft.player &&
				(MobBattleMusicConfig.CLIENT.punchingCountsAsViolence.get() ||
						(player.getMainHandItem().getItem() instanceof TieredItem
								|| source.getDirectEntity() instanceof Projectile)))
			this.panic(player, MobBattleMusicConfig.CLIENT.playerReevaluationCooldown.get() * 20);
	}

	public void onAttack(Entity entity) {
		if (entity instanceof Mob mob && !(this.panickingFrom instanceof Player))
			this.panic(mob, MobBattleMusicConfig.CLIENT.threatReevaluationCooldown.get() * 20);
		else if (entity instanceof Player player && !player.isCreative() && player != this.minecraft.player &&
				(MobBattleMusicConfig.CLIENT.punchingCountsAsViolence.get() ||
						this.minecraft.player.getMainHandItem().getItem() instanceof TieredItem))
			this.panic(player, MobBattleMusicConfig.CLIENT.playerReevaluationCooldown.get() * 20);
	}

	private void panic(LivingEntity mob, int time) {
		this.panickingFrom = mob;
		this.threatRefreshTimer = time;
		this.maxThreatRefreshTime = time;
	}

	/**
	 * K8-A: the level-scoped manager is going away (level unload). Clear all
	 * non-audio state of THIS level scope. When preservePlayback is true the
	 * session player (ExternalMusicHandler) is left untouched - it keeps
	 * playing across the dimension change and the new level's manager adopts
	 * it; never call wrapper.stop() in that case (it stops the session
	 * player). Built-in Minecraft-sound tracks are always stopped: they are
	 * level-scoped sound instances.
	 */
	public void disposeForLevelTransition(boolean preservePlayback) {
		var iterator = this.tracks.values().iterator();
		while (iterator.hasNext()) {
			var track = iterator.next();
			WorldPlaybackChannel.unregisterEngineTrack(track);
			track.stop();
			iterator.remove();
		}
		var externalIterator = this.externalTracks.values().iterator();
		while (externalIterator.hasNext()) {
			var track = externalIterator.next();
			// preserve: the wrapper dies with this level but the session
			// player must NOT be stopped (stop() reaches the handler)
			if (!preservePlayback)
				track.stop();
			externalIterator.remove();
		}
		this.externalResumeStates.clear();
		this.idleNextStartMillis.clear();
		this.idleSuppressedUntilMillis = 0L;
		this.initialIdleCooldownApplied = false;
		this.timelineTrack = null;
		this.timelineUrl = "";
		this.timelineLastPosition = -1L;
		this.panickingFrom = null;
		this.maxThreatRefreshTime = 0;
		this.threatRefreshTimer = 0;
		this.threatRemovalTimer = 0;
		this.reportedPlayerOpponent = null;
		this.playerSessionReportInitialized = false;
		LOGGER.debug("[MBM] level manager disposed (preservePlayback={})", preservePlayback);
	}

	/**
	 * K8-A: the new level's manager reconciles with the session player on its
	 * first tick. If the session is already playing a URL that this level's
	 * selection engine also wants, adopt it WITHOUT calling play() - playCalls
	 * delta stays zero and the position is never reset. A different target is
	 * not adopted; the normal flow queues exactly one gated switch once the
	 * conditions stabilize.
	 */
	private void adoptCurrentSessionPlayback() {
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		String sessionUrl = handler.getCurrentlyPlayingUrl();
		if (sessionUrl == null || handler.isStopRequested())
			return;
		MusicTracksManager tracksManager = MusicTracksManager.getInstance();
		for (TrackType type : tracksManager.getTracks()) {
			ResourceLocation location = type.getTrack();
			if (!tracksManager.isExternalUrl(location))
				continue;
			if (sessionUrl.equals(tracksManager.getExternalUrl(location))
					&& !this.externalTracks.containsKey(type)) {
				// same URL: adopt - the wrapper reflects the session's
				// playback without re-triggering the player
				ExternalUrlMusicTrack adopted = ExternalUrlMusicTrack.adopt(sessionUrl, type.getFadeTime());
				this.externalTracks.put(type, adopted);
				WorldPlaybackChannel.setCurrentIntent(sessionUrl);
				LOGGER.info("[MBM] adopted existing session playback for {} (no re-play)", sessionUrl);
				return;
			}
		}
		// different target (or nothing selected): no adoption - the normal
		// selection flow will gate-switch exactly once
		LOGGER.debug("[MBM] no adoption - session URL {} not selected by new level", sessionUrl);
	}

	public void reload() {
		// Stop and remove normal tracks
		var iterator = this.tracks.values().iterator();
		while (iterator.hasNext()) {
			var track = iterator.next();
			// AUD-35: unregister from the main channel's own collection
			WorldPlaybackChannel.unregisterEngineTrack(track);
			track.stop();
			iterator.remove();
		}

		// Stop and remove external URL tracks
		var externalIterator = this.externalTracks.values().iterator();
		while (externalIterator.hasNext()) {
			var track = externalIterator.next();
			track.stop();
			externalIterator.remove();
		}
		this.externalResumeStates.clear();
		this.idleNextStartMillis.clear();
		this.idleSuppressedUntilMillis = 0L;
		this.timelineTrack = null;
		this.timelineUrl = "";
		this.timelineLastPosition = -1L;

		LOGGER.debug("Reloading mob battle music");
	}

	private static record ResumeState(String url, long positionMillis, long expiresAtMillis) {}

	public boolean isPlaying() {
		return !this.tracks.isEmpty();
	}

	public @Nullable TrackType getPriorityTrack() {
		return this.priorityTrack;
	}

	public List<TrackType> getPlayingTracks() {
		return ImmutableList.copyOf(this.tracks.keySet());
	}

	public @Nullable LivingEntity getPanicTarget() {
		return this.panickingFrom;
	}

	private static void fadeAndStopMinecraftMusic(MusicManager manager) {
		SoundInstance currentMusic = ((MixinMusicManagerAccessor) manager).mobbattlemusic$getCurrentMusic();
		if (currentMusic instanceof AbstractSoundInstance) {
			MixinAbstractSoundInstance mixinCurrentMusic = (MixinAbstractSoundInstance) currentMusic;
			if (currentMusic.getVolume() > 0.0F) {
				mixinCurrentMusic.mobbattlemusic$setVolume(mixinCurrentMusic.mobbattlemusic$getVolume() - 0.01F);
				if (currentMusic.getVolume() <= 0.0F)
					manager.stopPlaying();
			}
		}
	}

	private static boolean shouldStopTracksForModCompat(SoundManager manager) {
		SoundEngine engine = ((MixinSoundManagerAccessor) manager).mobbattlemusic$getSoundEngine();
		MixinSoundEngineAccessor accessor = (MixinSoundEngineAccessor) engine;
		for (SoundInstance sound : accessor.mobbattlemusic$getInstanceToChannel().keySet()) {
			var clazz = MobBattleMusicCompat.getWitherStormModBossThemeLoopClass();
			if (clazz != null && clazz.isAssignableFrom(sound.getClass()))
				return true;
		}
		return false;
	}
}
