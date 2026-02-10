package nonamecrackers2.mobbattlemusic.client.sound.track;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import com.google.common.collect.Maps;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import nonamecrackers2.mobbattlemusic.client.util.MobSelection;

public class MobSpecificTrack extends MobTrack {
	private static final Map<String, Optional<Method>> METHOD_CACHE = Maps.newHashMap();

	private final EntityType<?> type;
	@Nullable
	private final String matchMethod;
	@Nullable
	private final String matchValue;

	public MobSpecificTrack(EntityType<?> type, ResourceLocation track, int fadeTime, MobSelection.GroupType group,
			MobSelection.Selector selector) {
		this(type, null, null, track, fadeTime, group, selector);
	}

	public MobSpecificTrack(EntityType<?> type, @Nullable String matchMethod, @Nullable String matchValue,
			ResourceLocation track, int fadeTime, MobSelection.GroupType group, MobSelection.Selector selector) {
		super(track, fadeTime, group, selector);
		this.type = type;
		this.matchMethod = matchMethod;
		this.matchValue = matchValue;
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
		return selection.panicTarget() != null && this.matchesEntity(selection.panicTarget())
				|| this.getMobs(selection).stream().anyMatch(this::matchesEntity);
	}
}
