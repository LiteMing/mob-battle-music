package nonamecrackers2.mobbattlemusic.client.gui;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
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
 * explicit UNKNOWN state, never a misleading false.
 */
public final class ClientConditionDiagnostics
{
	private ClientConditionDiagnostics() {}

	/**
	 * Diagnose one condition asynchronously. Non-structure conditions resolve
	 * immediately; structure conditions are computed on the integrated
	 * server's executor and reported back on the main thread.
	 * K12-A: everything the server thread needs (position, dimension, level
	 * handle, argument) is captured on the client thread as immutable values -
	 * the executor never touches the client Player object.
	 */
	public static CompletableFuture<IdleConditionRegistry.MatchResult> diagnoseAsync(
			Player player, IdleCondition condition)
	{
		ResourceLocation type = new ResourceLocation(condition.type());
		if (!"structure".equals(type.getPath()))
			return CompletableFuture.completedFuture(IdleConditionRegistry.diagnose(player, condition));
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer server = mc.getSingleplayerServer();
		if (server == null)
			return CompletableFuture.completedFuture(unavailable());
		// client-thread capture of every value the server executor will read
		ResourceKey<Level> dimension = player.level().dimension();
		BlockPos position = player.blockPosition().immutable();
		String argument = condition.argument();
		return CompletableFuture.supplyAsync(() -> {
			ServerLevel serverLevel = server.getLevel(dimension);
			if (serverLevel == null)
				return unavailable();
			boolean inside = insideStructureServer(serverLevel, position, argument);
			return new IdleConditionRegistry.MatchResult(inside, inside ? "inside" : "not inside");
		}, server)
				.thenApplyAsync(result -> result, Minecraft.getInstance());
	}

	private static IdleConditionRegistry.MatchResult unavailable()
	{
		return new IdleConditionRegistry.MatchResult(false, "Unavailable: server-side structure data required",
				IdleConditionRegistry.MatchResult.State.UNKNOWN);
	}

	private static boolean insideStructureServer(ServerLevel serverLevel, BlockPos position, String argument)
	{
		ResourceLocation id = ResourceLocation.tryParse(argument);
		if (id == null)
			return false;
		Registry<Structure> registry = serverLevel.registryAccess().registryOrThrow(Registries.STRUCTURE);
		Structure structure = registry.get(id);
		if (structure == null)
			return false;
		StructureStart start = serverLevel.structureManager().getStructureWithPieceAt(position, structure);
		return start != null && start.isValid();
	}
}
