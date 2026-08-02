package nonamecrackers2.mobbattlemusic.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

final class MobBattleMusicCommandFeedback
{
	private MobBattleMusicCommandFeedback() {}

	static void success(CommandSourceStack source, Component message)
	{
		if (source.getEntity() instanceof ServerPlayer player)
			player.displayClientMessage(Component.literal("[Mob Battle Music] ").append(message), true);
		else
			source.sendSuccess(() -> message, true);
	}
}
