package nonamecrackers2.mobbattlemusic.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;

/**
 * K10-A: client-side condition diagnostics. The public registry only uses
 * pure Minecraft API (it must load on dedicated servers); structure checks
 * need a ServerLevel, which singleplayer provides through the integrated
 * server. This helper resolves that level when the player's own level is a
 * ClientLevel.
 */
public final class ClientConditionDiagnostics
{
	private ClientConditionDiagnostics() {}

	/**
	 * Diagnose one condition against the current environment, with structure
	 * checks working in singleplayer (integrated server level) instead of
	 * always failing on the client level.
	 */
	public static IdleConditionRegistry.MatchResult diagnose(Player player, IdleCondition condition)
	{
		ResourceLocation type = new ResourceLocation(condition.type());
		if (!"structure".equals(type.getPath()))
			return IdleConditionRegistry.diagnose(player, condition);
		boolean inside = insideStructureClient(player, condition.argument());
		return new IdleConditionRegistry.MatchResult(inside, inside ? "inside" : "not inside");
	}

	private static boolean insideStructureClient(Player player, String argument)
	{
		ServerLevel serverLevel = serverLevelFor(player);
		if (serverLevel == null)
			return false;
		ResourceLocation id = ResourceLocation.tryParse(argument);
		if (id == null)
			return false;
		Registry<Structure> registry = serverLevel.registryAccess().registryOrThrow(Registries.STRUCTURE);
		Structure structure = registry.get(id);
		if (structure == null)
			return false;
		StructureStart start = serverLevel.structureManager().getStructureWithPieceAt(player.blockPosition(), structure);
		return start != null && start.isValid();
	}

	/**
	 * The player's own level when it is a ServerLevel, otherwise the matching
	 * dimension's integrated-server level in singleplayer.
	 */
	private static ServerLevel serverLevelFor(Player player)
	{
		if (player.level() instanceof ServerLevel serverLevel)
			return serverLevel;
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSingleplayerServer() == null)
			return null;
		for (ServerLevel level : mc.getSingleplayerServer().getAllLevels()) {
			if (level.dimension().equals(player.level().dimension()))
				return level;
		}
		return null;
	}
}
