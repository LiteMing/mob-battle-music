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
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.TieredItem;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.client.sound.MobBattleTrack;
import nonamecrackers2.mobbattlemusic.client.sound.track.TrackType;
import nonamecrackers2.mobbattlemusic.client.util.MobBattleMusicCompat;
import nonamecrackers2.mobbattlemusic.client.util.MobSelection;
import nonamecrackers2.mobbattlemusic.mixin.MixinAbstractSoundInstance;
import nonamecrackers2.mobbattlemusic.mixin.MixinMusicManagerAccessor;
import nonamecrackers2.mobbattlemusic.mixin.MixinSoundEngineAccessor;
import nonamecrackers2.mobbattlemusic.mixin.MixinSoundManagerAccessor;

public class BattleMusicManager
{
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/BattleMusicManager");
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
	private @Nullable TrackType priorityTrack;
	private int maxThreatRefreshTime;
	private int threatRefreshTimer;
	private int threatRemovalTimer;
	private @Nullable LivingEntity panickingFrom;

	public BattleMusicManager(Minecraft mc, ClientLevel level)
	{
		this.minecraft = mc;
		this.level = level;
	}

	private static boolean blacklist(LivingEntity e)
	{
		return MobBattleMusicConfig.CLIENT.ignoredMobs.get().stream().anyMatch(s -> s.equals(e.getEncodeId()));
	}

	private boolean isMobAggressive(Mob mob)
	{
		// 检查 mob 是否有目标且目标不是玩家
		if (mob.getTarget() != null && mob.getTarget() != minecraft.player)
			return true;

		// 检查 mob 是否处于激怒状态
		if (mob.isAggressive())
			return true;

		// 检查是否是当前的恐慌目标
		return panickingFrom != null && panickingFrom.equals(mob);
	}

	// 此方法用于确定是否应该触发战斗音乐
	private boolean shouldTriggerCombatMusic(MobSelection selection)
	{
		// 检查是否有任何生物处于战斗状态
		return selection.group(MobSelection.GroupType.ENEMIES)
				.forSelector(MobSelection.Selector.LINE_OF_SIGHT)
				.stream()
				.anyMatch(mob -> isMobAggressive(mob));
	}

	public void tick()
	{
		// Handle threat timers
		boolean flag = true;
		if (this.panickingFrom != null)
		{
			if (this.panickingFrom.isAlive() && this.panicConditions.test(this.minecraft.player, this.panickingFrom)
					&& this.minecraft.player.hasLineOfSight(this.panickingFrom))
				flag = false;
		}

		if (flag)
		{
			if (this.threatRemovalTimer++ > MobBattleMusicConfig.CLIENT.calmDownTime.get() * 20)
			{
				this.threatRemovalTimer = 0;
				this.panickingFrom = null;
			}
			this.threatRefreshTimer = this.maxThreatRefreshTime;
		}
		else
		{
			this.threatRemovalTimer = 0;
			if (this.threatRefreshTimer > 0)
			{
				this.threatRefreshTimer--;
				if (this.threatRefreshTimer == 0)
					this.panickingFrom = null;
			}
		}

		MobSelection.Builder builder = MobSelection.builder();

		for (Mob mob : this.level.getNearbyEntities(Mob.class, this.targetingConditions, this.minecraft.player,
				this.minecraft.player.getBoundingBox().inflate(MobBattleMusicConfig.CLIENT.maxMobSearchRadius.get())))
		{
			if (COUNTS_TOWARDS_MOB_COUNT.test(mob))
			{
				boolean viewable = this.minecraft.levelRenderer.getFrustum().isVisible(mob.getBoundingBox());
				boolean lineOfSight = this.minecraft.player.hasLineOfSight(mob);

				// 如果是战斗状态，添加到 ATTACKING 组
				if (isMobAggressive(mob))
				{
					builder.addToGroup(MobSelection.GroupType.ATTACKING, MobSelection.Selector.ANY, mob);
					if (lineOfSight)
					{
						builder.addToGroup(MobSelection.GroupType.ATTACKING, MobSelection.Selector.LINE_OF_SIGHT, mob);
						if (viewable)
							builder.addToGroup(MobSelection.GroupType.ATTACKING, MobSelection.Selector.ON_SCREEN, mob);
					}
				}

				// 所有生物都添加到 ENEMIES 组
				builder.addToGroup(MobSelection.GroupType.ENEMIES, MobSelection.Selector.ANY, mob);
				if (lineOfSight)
				{
					builder.addToGroup(MobSelection.GroupType.ENEMIES, MobSelection.Selector.LINE_OF_SIGHT, mob);
					if (viewable)
						builder.addToGroup(MobSelection.GroupType.ENEMIES, MobSelection.Selector.ON_SCREEN, mob);
				}
			}
		}

		MobSelection selection = builder.setPanicTarget(this.panickingFrom).build();

		// Update panic state
		Mob closestAggressor = null;
		double distance = -1.0D;
		for (Mob mob : selection.group(MobSelection.GroupType.ATTACKING).forSelector(MobSelection.defaultSelector()))
		{
			double d = mob.distanceTo(this.minecraft.player);
			if (distance == -1.0D || distance > d)
			{
				closestAggressor = mob;
				distance = d;
			}
		}

		if (closestAggressor != null && this.panickingFrom != closestAggressor &&
				this.panicConditions.test(this.minecraft.player, closestAggressor) &&
				!(this.panickingFrom instanceof Player))
		{
			this.panic(closestAggressor, MobBattleMusicConfig.CLIENT.threatReevaluationCooldown.get() * 20);
		}

		// Handle track cleanup
		var iterator = this.tracks.entrySet().iterator();
		while (iterator.hasNext())
		{
			var entry = iterator.next();
			MobBattleTrack track = entry.getValue();
			if (track.isStopped() || !this.minecraft.getSoundManager().isActive(track))
			{
				LOGGER.debug("Removing track {}, it is no longer playing", track);
				iterator.remove();
			}
		}


		// 更新音轨
		List<TrackType> tracks = MusicTracksManager.getInstance().getTracks();

		TrackType priority = null;
		for (TrackType type : tracks)
		{
			if (type.canPlay(selection))
			{
				priority = type;
				break;
			}
		}
		this.priorityTrack = priority;

		for (TrackType type : tracks)
		{
			float trackDesiredVolume = shouldStopTracksForModCompat(this.minecraft.getSoundManager()) ? 0.0F : type.getVolume(selection);
			boolean canPlay = priority == type;
			this.initiateAndOrUpdateTrack(type, canPlay && trackDesiredVolume > 0.0F, track -> {
				if (canPlay)
				{
					track.setTargetedVolume(trackDesiredVolume);
				}
				else
				{
					track.setTargetedVolume(0.0F);
				}
			});
		}
	}

	private void initiateAndOrUpdateTrack(TrackType type, boolean allowNewTracks, Consumer<MobBattleTrack> consumer)
	{
		MobBattleTrack track = null;
		if (allowNewTracks && this.minecraft.options.getSoundSourceVolume(BattleMusicManager.DEFAULT_SOUND_SOURCE) > 0.0F
				&& this.minecraft.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F)
		{
			track = this.tracks.computeIfAbsent(type, t -> {
				MobBattleTrack newTrack = new MobBattleTrack(type.getTrack(), type.getFadeTime());
				this.minecraft.getSoundManager().play(newTrack);
				LOGGER.debug("Beginning track {}", type);
				return newTrack;
			});
		}
		else
		{
			track = this.tracks.get(type);
		}

		if (track != null)
		{
			consumer.accept(track);
		}
	}

	public void wasAttacked(DamageSource source)
	{
		Entity entity = source.getEntity();
		if (entity instanceof Mob mob && !(this.panickingFrom instanceof Player))
			this.panic(mob, MobBattleMusicConfig.CLIENT.threatReevaluationCooldown.get() * 20);
		else if (entity instanceof Player player && player != this.minecraft.player &&
				(MobBattleMusicConfig.CLIENT.punchingCountsAsViolence.get() ||
						(player.getMainHandItem().getItem() instanceof TieredItem || source.getDirectEntity() instanceof Projectile)))
			this.panic(player, MobBattleMusicConfig.CLIENT.playerReevaluationCooldown.get() * 20);
	}

	public void onAttack(Entity entity)
	{
		if (entity instanceof Mob mob && !(this.panickingFrom instanceof Player))
			this.panic(mob, MobBattleMusicConfig.CLIENT.threatReevaluationCooldown.get() * 20);
		else if (entity instanceof Player player && !player.isCreative() && player != this.minecraft.player &&
				(MobBattleMusicConfig.CLIENT.punchingCountsAsViolence.get() ||
						this.minecraft.player.getMainHandItem().getItem() instanceof TieredItem))
			this.panic(player, MobBattleMusicConfig.CLIENT.playerReevaluationCooldown.get() * 20);
	}

	private void panic(LivingEntity mob, int time)
	{
		this.panickingFrom = mob;
		this.threatRefreshTimer = time;
		this.maxThreatRefreshTime = time;
	}

	public void reload()
	{
		var iterator = this.tracks.values().iterator();
		while (iterator.hasNext())
		{
			var track = iterator.next();
			track.stop();
			iterator.remove();
			LOGGER.debug("Reloading mob battle music");
		}
	}

	public boolean isPlaying()
	{
		return !this.tracks.isEmpty();
	}

	public @Nullable TrackType getPriorityTrack()
	{
		return this.priorityTrack;
	}

	public List<TrackType> getPlayingTracks()
	{
		return ImmutableList.copyOf(this.tracks.keySet());
	}

	public @Nullable LivingEntity getPanicTarget()
	{
		return this.panickingFrom;
	}  private static void fadeAndStopMinecraftMusic(MusicManager manager)
{
	SoundInstance currentMusic = ((MixinMusicManagerAccessor)manager).mobbattlemusic$getCurrentMusic();
	if (currentMusic instanceof AbstractSoundInstance)
	{
		MixinAbstractSoundInstance mixinCurrentMusic = (MixinAbstractSoundInstance)currentMusic;
		if (currentMusic.getVolume() > 0.0F)
		{
			mixinCurrentMusic.mobbattlemusic$setVolume(mixinCurrentMusic.mobbattlemusic$getVolume() - 0.01F);
			if (currentMusic.getVolume() <= 0.0F)
				manager.stopPlaying();
		}
	}
}

	private static boolean shouldStopTracksForModCompat(SoundManager manager)
	{
		SoundEngine engine = ((MixinSoundManagerAccessor)manager).mobbattlemusic$getSoundEngine();
		MixinSoundEngineAccessor accessor = (MixinSoundEngineAccessor)engine;
		for (SoundInstance sound : accessor.mobbattlemusic$getInstanceToChannel().keySet())
		{
			var clazz = MobBattleMusicCompat.getWitherStormModBossThemeLoopClass();
			if (clazz != null && clazz.isAssignableFrom(sound.getClass()))
				return true;
		}
		return false;
	}
}
