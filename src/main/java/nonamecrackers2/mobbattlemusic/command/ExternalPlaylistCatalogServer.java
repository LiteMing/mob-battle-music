package nonamecrackers2.mobbattlemusic.command;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import nonamecrackers2.mobbattlemusic.network.ExternalPlaylistCatalogPacket;

public class ExternalPlaylistCatalogServer
{
	private static final Map<UUID, List<ExternalPlaylistCatalogPacket.Playlist>> CATALOGS = new ConcurrentHashMap<>();
	
	public static void update(ServerPlayer player, List<ExternalPlaylistCatalogPacket.Playlist> playlists)
	{
		CATALOGS.put(player.getUUID(), List.copyOf(playlists));
	}
	
	public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event)
	{
		CATALOGS.remove(event.getEntity().getUUID());
	}
	
	public static CompletableFuture<Suggestions> suggestPlaylists(CommandContext<CommandSourceStack> context,
			SuggestionsBuilder builder)
	{
		LinkedHashSet<ResourceLocation> ids = new LinkedHashSet<>();
		for (ExternalPlaylistCatalogPacket.Playlist playlist : visiblePlaylists(context)) {
			ids.add(playlist.configLocation());
			ids.add(playlist.trackLocation());
		}
		return SharedSuggestionProvider.suggestResource(ids, builder);
	}
	
	public static CompletableFuture<Suggestions> suggestEntries(CommandContext<CommandSourceStack> context,
			SuggestionsBuilder builder)
	{
		ResourceLocation playlistId;
		try {
			playlistId = ResourceLocationArgument.getId(context, "playlist");
		} catch (IllegalArgumentException e) {
			return builder.buildFuture();
		}
		
		Map<String, Component> suggestions = new LinkedHashMap<>();
		for (ExternalPlaylistCatalogPacket.Playlist playlist : visiblePlaylists(context)) {
			if (!playlist.matches(playlistId))
				continue;
			for (int i = 0; i < playlist.entries().size(); i++) {
				ExternalPlaylistCatalogPacket.Entry entry = playlist.entries().get(i);
				suggestions.putIfAbsent(entry.id(), Component.literal((i + 1) + ": " + entry.name()));
				suggestions.putIfAbsent(String.valueOf(i + 1), Component.literal(entry.id() + ": " + entry.name()));
			}
		}
		suggestions.forEach(builder::suggest);
		return builder.buildFuture();
	}
	
	public static String chooseRandomEntry(MinecraftServer server, Collection<ServerPlayer> players, ResourceLocation playlistId)
	{
		String serverEntry = ServerExternalPlaylistStore.chooseRandomEntry(server, playlistId);
		if (serverEntry != null)
			return serverEntry;
		for (ServerPlayer player : players) {
			for (ExternalPlaylistCatalogPacket.Playlist playlist : CATALOGS.getOrDefault(player.getUUID(), List.of())) {
				if (!playlist.matches(playlistId) || playlist.entries().isEmpty())
					continue;
				int selected = ThreadLocalRandom.current().nextInt(playlist.entries().size());
				return playlist.entries().get(selected).id();
			}
		}
		return null;
	}

	/**
	 * K14-B: resolve a playlist entry selection (id or 1-based index) to its
	 * URL from the authoritative server-side playlist store. The client
	 * catalog carries only ids (URLs stay client-local), so a Cue session can
	 * only be authoritative for server-store playlists. Returns null when
	 * unresolvable.
	 */
	@javax.annotation.Nullable
	public static String resolveEntryUrl(MinecraftServer server, ResourceLocation playlistId, String selection)
	{
		return ServerExternalPlaylistStore.playlistEntryUrl(server, playlistId, resolveIndex(selection));
	}

	private static int resolveIndex(String selection)
	{
		Integer index = parseIndex(selection);
		return index == null ? -1 : index;
	}

	@javax.annotation.Nullable
	private static Integer parseIndex(String selection)
	{
		if (selection == null || selection.isBlank())
			return null;
		try {
			int index = Integer.parseInt(selection) - 1;
			return index >= 0 ? index : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
	
	public static int list(CommandSourceStack source, Collection<ServerPlayer> players)
	{
		int count = 0;
		for (ServerPlayer player : players) {
			List<ExternalPlaylistCatalogPacket.Playlist> playlists = CATALOGS.getOrDefault(player.getUUID(), List.of());
			source.sendSystemMessage(Component.literal("[Mob Battle Music] " + player.getGameProfile().getName() +
					" external playlists: " + playlists.size()));
			for (ExternalPlaylistCatalogPacket.Playlist playlist : playlists) {
				count++;
				source.sendSystemMessage(Component.literal("  " + playlist.configLocation() +
						" (" + playlist.entries().size() + " music id(s))"));
				for (int i = 0; i < playlist.entries().size(); i++) {
					ExternalPlaylistCatalogPacket.Entry entry = playlist.entries().get(i);
					source.sendSystemMessage(Component.literal("    " + (i + 1) + ": " + entry.id() +
							" (" + entry.name() + ")"));
				}
			}
		}
		return count;
	}
	
	private static List<ExternalPlaylistCatalogPacket.Playlist> visiblePlaylists(CommandContext<CommandSourceStack> context)
	{
		Collection<ServerPlayer> players = targetPlayers(context);
		List<ExternalPlaylistCatalogPacket.Playlist> serverPlaylists =
				ServerExternalPlaylistStore.catalogPlaylists(context.getSource().getServer());
		if (players.isEmpty())
			return java.util.stream.Stream.concat(
					serverPlaylists.stream(),
					CATALOGS.values().stream().flatMap(Collection::stream)).toList();
		return java.util.stream.Stream.concat(
				serverPlaylists.stream(),
				players.stream()
				.flatMap(player -> CATALOGS.getOrDefault(player.getUUID(), List.of()).stream())
		).toList();
	}
	
	private static Collection<ServerPlayer> targetPlayers(CommandContext<CommandSourceStack> context)
	{
		try {
			return EntityArgument.getPlayers(context, "targets");
		} catch (CommandSyntaxException | IllegalArgumentException e) {
			return List.of();
		}
	}
}
