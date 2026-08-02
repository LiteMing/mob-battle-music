package nonamecrackers2.mobbattlemusic.playlist;

import net.minecraft.world.entity.player.Player;

public final class MobBattleMusicConditions
{
	private MobBattleMusicConditions() {}

	public static void register(String id, String displayName, ConditionPredicate predicate)
	{
		IdleConditionRegistry.register(id, displayName, predicate::test);
	}

	@FunctionalInterface
	public interface ConditionPredicate
	{
		boolean test(Player player, String argument);
	}
}
