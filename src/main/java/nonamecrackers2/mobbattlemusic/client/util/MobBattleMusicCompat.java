package nonamecrackers2.mobbattlemusic.client.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

public class MobBattleMusicCompat
{
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/MobBattleMusicCompat");
	private static  @Nullable Class<?> WITHER_STORM_MOD_BOSS_THEME_LOOP;
	private static @Nullable Object YOUKAI_GRAZE_HOLDER;
	private static @Nullable Method YOUKAI_GRAZE_HOLDER_GET;
	private static @Nullable Method YOUKAI_GRAZE_IS_IN_DANMAKU_COMBAT;
	private static @Nullable Method YOUKAI_GRAZE_FIND_ANY;
	private static @Nullable Field YOUKAI_GRAZE_PLAYER_OPPONENTS;
	private static boolean warnedYoukaiStgQueryFailure;
	
	public static void checkModCompat()
	{
		if (ModList.get().isLoaded("witherstormmod"))
		{
			try
			{
				WITHER_STORM_MOD_BOSS_THEME_LOOP = Class.forName("nonamecrackers2.witherstormmod.client.audio.bosstheme.BossThemeLoop");
				LOGGER.info("witherstormmod detected, enabling compat");
			}
			catch (ClassNotFoundException e)
			{
				LOGGER.warn("Failed to get class for BossThemeLoop from 'witherstormmod'");
				e.printStackTrace();
			}
		}
		if (ModList.get().isLoaded("youkaishomecoming"))
		{
			try
			{
				Class<?> grazeCapability = Class.forName("dev.xkmc.youkaishomecoming.content.capability.GrazeCapability");
				YOUKAI_GRAZE_HOLDER = grazeCapability.getField("HOLDER").get(null);
				YOUKAI_GRAZE_HOLDER_GET = findPlayerCapabilityGetter(YOUKAI_GRAZE_HOLDER.getClass());
				YOUKAI_GRAZE_IS_IN_DANMAKU_COMBAT = grazeCapability.getMethod("isInDanmakuCombat");
				YOUKAI_GRAZE_FIND_ANY = grazeCapability.getMethod("findAny", Player.class);
				YOUKAI_GRAZE_PLAYER_OPPONENTS = grazeCapability.getDeclaredField("playerOpponents");
				YOUKAI_GRAZE_PLAYER_OPPONENTS.setAccessible(true);
				if (YOUKAI_GRAZE_HOLDER_GET != null)
					LOGGER.info("youkaishomecoming detected, enabling STG combat music compat");
				else
					LOGGER.warn("Failed to find GrazeCapability getter for youkaishomecoming STG compat");
			}
			catch (ReflectiveOperationException | SecurityException e)
			{
				LOGGER.warn("Failed to enable youkaishomecoming STG combat music compat", e);
			}
		}
	}
	
	public static Class<?> getWitherStormModBossThemeLoopClass()
	{
		return WITHER_STORM_MOD_BOSS_THEME_LOOP;
	}

	public static boolean isYoukaiHomecomingStgCombatActive(@Nullable Player player)
	{
		return getYoukaiHomecomingStgCombat(player).active();
	}

	public static YoukaiStgCombat getYoukaiHomecomingStgCombat(@Nullable Player player)
	{
		if (player == null || YOUKAI_GRAZE_HOLDER == null || YOUKAI_GRAZE_HOLDER_GET == null ||
				YOUKAI_GRAZE_IS_IN_DANMAKU_COMBAT == null)
			return YoukaiStgCombat.NONE;
		
		try
		{
			Object capability = YOUKAI_GRAZE_HOLDER_GET.invoke(YOUKAI_GRAZE_HOLDER, player);
			if (capability == null)
				return YoukaiStgCombat.NONE;
			
			boolean active = Boolean.TRUE.equals(YOUKAI_GRAZE_IS_IN_DANMAKU_COMBAT.invoke(capability));
			boolean playerOpponent = hasPlayerOpponent(capability);
			LivingEntity sessionTarget = findSessionTarget(capability, player);
			return new YoukaiStgCombat(active, playerOpponent, sessionTarget);
		}
		catch (ReflectiveOperationException | IllegalArgumentException e)
		{
			if (!warnedYoukaiStgQueryFailure)
			{
				warnedYoukaiStgQueryFailure = true;
				LOGGER.warn("Failed to query youkaishomecoming STG combat state", e);
			}
			return YoukaiStgCombat.NONE;
		}
	}
	
	private static boolean hasPlayerOpponent(Object capability) throws ReflectiveOperationException
	{
		if (YOUKAI_GRAZE_PLAYER_OPPONENTS == null)
			return false;
		Object value = YOUKAI_GRAZE_PLAYER_OPPONENTS.get(capability);
		return value instanceof Set<?> set && !set.isEmpty();
	}
	
	private static @Nullable LivingEntity findSessionTarget(Object capability, Player player) throws ReflectiveOperationException
	{
		if (YOUKAI_GRAZE_FIND_ANY != null) {
			Object value = YOUKAI_GRAZE_FIND_ANY.invoke(capability, player);
			if (value instanceof Optional<?> optional && optional.orElse(null) instanceof LivingEntity target)
				return target;
		}
		if (YOUKAI_GRAZE_PLAYER_OPPONENTS != null && YOUKAI_GRAZE_PLAYER_OPPONENTS.get(capability) instanceof Set<?> set) {
			for (Object value : set) {
				if (value instanceof java.util.UUID uuid) {
					Player target = player.level().getPlayerByUUID(uuid);
					if (target != null)
						return target;
				}
			}
		}
		return null;
	}

	private static @Nullable Method findPlayerCapabilityGetter(Class<?> holderClass)
	{
		for (Method method : holderClass.getMethods())
		{
			if (!method.getName().equals("get") || method.getParameterCount() != 1)
				continue;
			if (method.getParameterTypes()[0].isAssignableFrom(Player.class))
				return method;
		}
		return null;
	}
	
	public static record YoukaiStgCombat(boolean active, boolean playerOpponent, @Nullable LivingEntity sessionTarget)
	{
		public static final YoukaiStgCombat NONE = new YoukaiStgCombat(false, false, null);
	}
}
