package nonamecrackers2.mobbattlemusic.client.sound.track;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import com.google.common.collect.Maps;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.util.AggressiveEntityStateClient;
import nonamecrackers2.mobbattlemusic.client.util.MobSelection;

public class MobSpecificTrack extends MobTrack {
	private static final Map<String, Optional<Method>> METHOD_CACHE = Maps.newHashMap();

	private final EntityType<?> type;
	@Nullable
	private final String matchMethod;
	@Nullable
	private final String matchValue;
	private final boolean matchPanicTarget;

	public MobSpecificTrack(EntityType<?> type, ResourceLocation track, int fadeTime, MobSelection.GroupType group,
			MobSelection.Selector selector) {
		this(type, null, null, track, fadeTime, group, selector);
	}

	public MobSpecificTrack(EntityType<?> type, @Nullable String matchMethod, @Nullable String matchValue,
			ResourceLocation track, int fadeTime, MobSelection.GroupType group, MobSelection.Selector selector) {
		this(type, matchMethod, matchValue, track, fadeTime, group, selector, true);
	}

	public MobSpecificTrack(EntityType<?> type, @Nullable String matchMethod, @Nullable String matchValue,
			ResourceLocation track, int fadeTime, MobSelection.GroupType group, MobSelection.Selector selector,
			boolean matchPanicTarget) {
		super(track, fadeTime, group, selector);
		this.type = type;
		this.matchMethod = matchMethod;
		this.matchValue = matchValue;
		this.matchPanicTarget = matchPanicTarget;
	}

	private boolean matchesEntity(LivingEntity entity) {
		if (entity.getType() != this.type)
			return false;
		if (this.matchMethod == null || this.matchValue == null)
			return true;
		return this.matchDataValue(entity);
	}

	private boolean matchDataValue(LivingEntity entity) {
		try {
			String cacheKey = entity.getClass().getName() + "#" + this.matchMethod;
			Optional<Method> cached = METHOD_CACHE.computeIfAbsent(cacheKey, k -> {
				try {
					Method m = entity.getClass().getMethod(this.matchMethod);
					m.setAccessible(true);
					return Optional.of(m);
				} catch (Exception e) {
					return Optional.empty();
				}
			});
			if (cached.isPresent()) {
				Object result = cached.get().invoke(entity);
				return this.matchValue.equals(String.valueOf(result));
			}
		} catch (Exception ignored) {
		}
		return false;
	}

	@Override
	public boolean canPlay(MobSelection selection) {
		return this.matchPanicTarget && selection.panicTarget() != null && this.matchesEntity(selection.panicTarget())
				|| this.getMobs(selection).stream().anyMatch(this::matchesEntity)
				|| this.matchesLoadedEntity();
	}
	
	private boolean matchesLoadedEntity() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null)
			return false;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || !this.matchesEntity(living))
				continue;
			if (!living.isAlive() || living.distanceTo(mc.player) > MobBattleMusicConfig.CLIENT.maxMobSearchRadius.get())
				continue;
			if (!selectorMatches(mc, living))
				continue;
			return switch (this.group) {
				case ATTACKING -> this.matchPanicTarget && living instanceof Mob mob &&
						mob.distanceTo(mc.player) <= MobBattleMusicConfig.CLIENT.threatRadius.get() &&
						(AggressiveEntityStateClient.isServerAggressive(mob.getUUID()) ||
								mob.isAggressive() || mob.getTarget() != null);
				case ENEMIES -> true;
			};
		}
		return false;
	}
	
	private boolean selectorMatches(Minecraft mc, LivingEntity entity) {
		return switch (this.selector) {
			case ANY -> true;
			case LINE_OF_SIGHT -> mc.player.hasLineOfSight(entity);
			case ON_SCREEN -> {
				var frustum = mc.levelRenderer.getFrustum();
				yield frustum != null && frustum.isVisible(entity.getBoundingBox()) && mc.player.hasLineOfSight(entity);
			}
		};
	}
}
