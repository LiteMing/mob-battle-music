package nonamecrackers2.mobbattlemusic.client.gui;

import java.util.concurrent.CompletableFuture;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;

/**
 * K10-A/K11-A: client-side condition diagnostics. The public registry only
 * uses pure Minecraft API (it must load on dedicated servers); structure
 * checks need a ServerLevel AND run on the integrated server's executor -
 * structure data belongs to the server thread. This helper schedules the
 * check on the server executor and hands the result back to the client
 * thread. On dedicated servers (no local ServerLevel) the result is an
 * explicit "Unavailable" state, never a misleading false.
 */
public final class ClientConditionDiagnostics
{
	private ClientConditionDiagnostics() {}

	/**
	 * Diagnose one condition asynchronously. Non-structure conditions resolve
	 * immediately; structure conditions are computed on the integrated
	 * server's executor and reported back on the main thread.
	 */
	public static CompletableFuture<IdleConditionRegistry.MatchResult> diagnoseAsync(
			Player player, IdleCondition condition)
	{
		ResourceLocation type = new ResourceLocation(condition.type());
		if (!"structure".equals(type.getPath()))
			return CompletableFuture.completedFuture(IdleConditionRegistry.diagnose(player, condition));
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSingleplayerServer() == null)
			return CompletableFuture.completedFuture(new IdleConditionRegistry.MatchResult(false,
					"Unavailable: server-side structure data required"));
		ServerLevel serverLevel = serverLevelFor(player);
		if (serverLevel == null)
			return CompletableFuture.completedFuture(new IdleConditionRegistry.MatchResult(false,
					"Unavailable: server-side structure data required"));
		// K11-A: structure data belongs to the integrated server thread -
		// never touch serverLevel.structureManager() on the render thread
		return CompletableFuture.supplyAsync(() -> {
			boolean inside = insideStructureServer(serverLevel, player, condition.argument());
			return new IdleConditionRegistry.MatchResult(inside, inside ? "inside" : "not inside");
		}, serverLevel.getServer())
				.thenApplyAsync(result -> result, Minecraft.getInstance());
	}

	private static boolean insideStructureServer(ServerLevel serverLevel, Player player, String argument)
	{
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
