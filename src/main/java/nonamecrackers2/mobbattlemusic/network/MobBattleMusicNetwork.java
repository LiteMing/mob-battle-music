package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import nonamecrackers2.mobbattlemusic.MobBattleMusicMod;
import nonamecrackers2.mobbattlemusic.client.audio.ClockOffsetProbeScheduler;

public class MobBattleMusicNetwork
{
	private static final String PROTOCOL_VERSION = "16";
	private static int nextId;
	private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
			.named(MobBattleMusicMod.id("main"))
			.networkProtocolVersion(() -> PROTOCOL_VERSION)
			// K14-A: optional channel - a peer WITHOUT MBM connects fine
			// (mods.toml displayTest already ignores mod version presence);
			// a peer WITH MBM must match protocol 16 exactly, otherwise the
			// handshake rejects (never fall back to version -> true)
			.clientAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals))
			.serverAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals))
			.simpleChannel();
	
	public static void register()
	{
		CHANNEL.messageBuilder(ExternalPlaylistControlPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(ExternalPlaylistControlPacket::encode)
				.decoder(ExternalPlaylistControlPacket::decode)
				.consumerMainThread(MobBattleMusicNetwork::handleExternalPlaylistControl)
				.add();
		CHANNEL.messageBuilder(ExternalPlaylistCatalogPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
				.encoder(ExternalPlaylistCatalogPacket::encode)
				.decoder(ExternalPlaylistCatalogPacket::decode)
				.consumerMainThread(MobBattleMusicNetwork::handleExternalPlaylistCatalog)
				.add();
		CHANNEL.messageBuilder(ServerExternalPlaylistSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(ServerExternalPlaylistSyncPacket::encode)
				.decoder(ServerExternalPlaylistSyncPacket::decode)
				.consumerMainThread(MobBattleMusicNetwork::handleServerExternalPlaylistSync)
				.add();
		CHANNEL.messageBuilder(ServerExternalPlaylistSyncRequestPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
				.encoder(ServerExternalPlaylistSyncRequestPacket::encode)
				.decoder(ServerExternalPlaylistSyncRequestPacket::decode)
				.consumerMainThread(MobBattleMusicNetwork::handleServerExternalPlaylistSyncRequest)
				.add();
		CHANNEL.messageBuilder(AggressiveEntityStatePacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(AggressiveEntityStatePacket::encode)
				.decoder(AggressiveEntityStatePacket::decode)
				.consumerMainThread(MobBattleMusicNetwork::handleAggressiveEntityState)
				.add();
		CHANNEL.messageBuilder(OpenPlaylistGuiPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(OpenPlaylistGuiPacket::encode)
				.decoder(OpenPlaylistGuiPacket::decode)
				.consumerMainThread(MobBattleMusicNetwork::handleOpenPlaylistGui)
				.add();
		CHANNEL.messageBuilder(IdleConditionStatePacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(IdleConditionStatePacket::encode)
				.decoder(IdleConditionStatePacket::decode)
				.consumerMainThread(MobBattleMusicNetwork::handleIdleConditionState)
				.add();
		CHANNEL.messageBuilder(TimelineMarkerHitPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
				.encoder(TimelineMarkerHitPacket::encode)
				.decoder(TimelineMarkerHitPacket::decode)
				.consumerMainThread(MobBattleMusicNetwork::handleTimelineMarkerHit)
				.add();
		CHANNEL.messageBuilder(PlayerCombatSessionReportPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
				.encoder(PlayerCombatSessionReportPacket::encode)
				.decoder(PlayerCombatSessionReportPacket::decode)
				.consumerMainThread(MobBattleMusicNetwork::handlePlayerCombatSessionReport)
				.add();
		CHANNEL.messageBuilder(PlayerCombatSessionStatePacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(PlayerCombatSessionStatePacket::encode)
				.decoder(PlayerCombatSessionStatePacket::decode)
				.consumerMainThread(MobBattleMusicNetwork::handlePlayerCombatSessionState)
				.add();
		CHANNEL.messageBuilder(PlaybackStartReportPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
				.encoder(PlaybackStartReportPacket::encode)
				.decoder(PlaybackStartReportPacket::decode)
				.consumerMainThread(MobBattleMusicNetwork::handlePlaybackStartReport)
				.add();
		CHANNEL.messageBuilder(PlaybackClockSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(PlaybackClockSyncPacket::encode)
				.decoder(PlaybackClockSyncPacket::decode)
				.consumerMainThread(MobBattleMusicNetwork::handlePlaybackClockSync)
				.add();
		// K7-B: the dedicated clock probe pair - t2 is captured on the server
		// network thread BEFORE the server main-thread queue, t4 on the
		// client network thread BEFORE the client main-thread queue; the
		// estimator consumes the sample directly on the network thread
		CHANNEL.messageBuilder(ClockOffsetProbeRequestPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
				.encoder(ClockOffsetProbeRequestPacket::encode)
				.decoder(ClockOffsetProbeRequestPacket::decode)
				.consumerNetworkThread(MobBattleMusicNetwork::handleClockOffsetProbeRequest)
				.add();
		CHANNEL.messageBuilder(ClockOffsetProbeResponsePacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(ClockOffsetProbeResponsePacket::encode)
				.decoder(ClockOffsetProbeResponsePacket::decode)
				.consumerNetworkThread(MobBattleMusicNetwork::handleClockOffsetProbeResponse)
				.add();
		// K14-B: server-authoritative Cue session sync (start/snapshot/pause/
		// resume/stop) - the server broadcasts the logical timeline, clients
		// follow with their own audio
		CHANNEL.messageBuilder(CueSessionSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(CueSessionSyncPacket::encode)
				.decoder(CueSessionSyncPacket::decode)
				.consumerMainThread(MobBattleMusicNetwork::handleCueSessionSync)
				.add();
		// K16-K: /mbmplaylist debug probe - the server command forwards the
		// requested probe action to the client (all probe state is client-local)
		CHANNEL.messageBuilder(MBMDebugPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(MBMDebugPacket::encode)
				.decoder(MBMDebugPacket::decode)
				.consumerMainThread(MobBattleMusicNetwork::handleMBMDebug)
				.add();
	}

	public static void sendCueSessionSync(ServerPlayer player, CueSessionSyncPacket packet)
	{
		if (!remoteHasChannel(player))
			return;
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}

	public static void sendDebug(ServerPlayer player, MBMDebugPacket packet)
	{
		if (!remoteHasChannel(player))
			return;
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}

	private static void handleCueSessionSync(CueSessionSyncPacket packet, Supplier<NetworkEvent.Context> context)
	{
		packet.handle(context);
	}

	private static void handleMBMDebug(MBMDebugPacket packet, Supplier<NetworkEvent.Context> context)
	{
		packet.handle(context);
	}
	
	// K14-C: expose the channel-presence check so the client-only presence
	// class (ClientNetworkPresence) can use it without the network class
	// referencing Minecraft
	public static boolean channelIsRemotePresent(net.minecraft.network.Connection connection)
	{
		return connection != null && CHANNEL.isRemotePresent(connection);
	}

	// K14-A: does the remote end of this player's connection have the MBM
	// channel? False for vanilla/no-MBM peers - ALL S2C sends must be
	// guarded by this, otherwise an unmodded client logs "Unknown custom
	// packet identifier" for every MBM payload.
	public static boolean remoteHasChannel(ServerPlayer player)
	{
		if (player == null || player.connection == null)
			return false;
		// Forge dev mapping: ServerGamePacketListenerImpl.connection is the
		// net.minecraft.network.Connection (public field, SRG-mapped)
		net.minecraft.network.Connection connection = player.connection.connection;
		return channelIsRemotePresent(connection);
	}

	// K14-A: does the server we are connected to have the MBM channel?
	// False for vanilla/no-MBM servers - ALL C2S sends must be guarded by
	// this (catalog, sync request, clock probes, marker hits, start reports).
	// K14-C: delegated to the client-only presence class (this class loads on
	// dedicated servers and must not reference Minecraft).
	public static boolean localServerHasChannel()
	{
		return nonamecrackers2.mobbattlemusic.client.network.ClientNetworkPresence.localServerHasChannel();
	}

	public static void sendExternalPlaylistControl(ServerPlayer player, ExternalPlaylistControlPacket packet)
	{
		if (!remoteHasChannel(player))
			return;
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}
	
	public static void sendServerExternalPlaylistSync(ServerPlayer player, ServerExternalPlaylistSyncPacket packet)
	{
		if (!remoteHasChannel(player))
			return;
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}
	
	public static void sendExternalPlaylistCatalogToServer(ExternalPlaylistCatalogPacket packet)
	{
		if (!localServerHasChannel())
			return;
		CHANNEL.sendToServer(packet);
	}
	
	public static void requestServerExternalPlaylistSync()
	{
		if (!localServerHasChannel())
			return;
		CHANNEL.sendToServer(new ServerExternalPlaylistSyncRequestPacket());
	}
	
	public static void sendAggressiveEntityState(ServerPlayer player, AggressiveEntityStatePacket packet)
	{
		if (!remoteHasChannel(player))
			return;
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}

	public static void openPlaylistGui(ServerPlayer player)
	{
		if (!remoteHasChannel(player))
			return;
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenPlaylistGuiPacket());
	}

	public static void sendIdleConditionState(ServerPlayer player, IdleConditionStatePacket packet)
	{
		if (!remoteHasChannel(player))
			return;
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}

	public static void sendTimelineMarkerHit(TimelineMarkerHitPacket packet)
	{
		if (!localServerHasChannel())
			return;
		CHANNEL.sendToServer(packet);
	}

	public static void reportPlayerCombatSession(java.util.UUID opponent)
	{
		if (!localServerHasChannel())
			return;
		CHANNEL.sendToServer(new PlayerCombatSessionReportPacket(opponent));
	}

	public static void sendPlayerCombatSessionState(ServerPlayer player, PlayerCombatSessionStatePacket packet)
	{
		if (!remoteHasChannel(player))
			return;
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}

	public static void sendPlaybackStartReport(String trackId, long clientStartEpochMillis, long sessionGeneration)
	{
		if (!localServerHasChannel())
			return;
		CHANNEL.sendToServer(new PlaybackStartReportPacket(trackId, clientStartEpochMillis, sessionGeneration));
	}

	// K7-B: the bounded probe burst - called by ClockOffsetProbeScheduler
	public static void sendClockProbeRequest(ClockOffsetProbeRequestPacket packet)
	{
		if (!localServerHasChannel())
			return;
		CHANNEL.sendToServer(packet);
	}

	public static void sendPlaybackClockSync(ServerPlayer player, PlaybackClockSyncPacket packet)
	{
		if (!remoteHasChannel(player))
			return;
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}

	/**
	 * K7-B: probe request arrives on the server NETWORK thread - t2 is
	 * captured here, before the main-thread queue (none needed: the response
	 * is sent directly, t3 captured right before the send).
	 */
	private static void handleClockOffsetProbeRequest(ClockOffsetProbeRequestPacket packet,
			Supplier<NetworkEvent.Context> context)
	{
		long t2 = System.currentTimeMillis();
		NetworkEvent.Context ctx = context.get();
		ServerPlayer player = ctx.getSender();
		if (player == null)
			return;
		// t3 is captured immediately before the actual send
		long t3 = System.currentTimeMillis();
		MobBattleMusicNetwork.sendClockProbeResponse(player,
				new ClockOffsetProbeResponsePacket(packet.probeNonce(), packet.clientSendEpochMillis(), t2, t3));
	}

	/**
	 * K7-B: probe response arrives on the client NETWORK thread - t4 is
	 * captured here, before the client main-thread queue, and the sample is
	 * fed straight into the thread-safe estimator. NEVER re-anchors: a probe
	 * response must not call any MarkerClock.realign*().
	 */
	private static void handleClockOffsetProbeResponse(ClockOffsetProbeResponsePacket packet,
			Supplier<NetworkEvent.Context> context)
	{
		ClockOffsetProbeScheduler.handleProbeResponse(packet);
	}

	private static void sendClockProbeResponse(ServerPlayer player, ClockOffsetProbeResponsePacket packet)
	{
		if (!remoteHasChannel(player))
			return;
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}
	
	private static void handleExternalPlaylistControl(ExternalPlaylistControlPacket packet, Supplier<NetworkEvent.Context> context)
	{
		packet.handle(context);
	}
	
	private static void handleExternalPlaylistCatalog(ExternalPlaylistCatalogPacket packet, Supplier<NetworkEvent.Context> context)
	{
		packet.handle(context);
	}
	
	private static void handleServerExternalPlaylistSync(ServerExternalPlaylistSyncPacket packet, Supplier<NetworkEvent.Context> context)
	{
		packet.handle(context);
	}
	
	private static void handleServerExternalPlaylistSyncRequest(ServerExternalPlaylistSyncRequestPacket packet, Supplier<NetworkEvent.Context> context)
	{
		packet.handle(context);
	}
	
	private static void handleAggressiveEntityState(AggressiveEntityStatePacket packet, Supplier<NetworkEvent.Context> context)
	{
		packet.handle(context);
	}

	private static void handleOpenPlaylistGui(OpenPlaylistGuiPacket packet, Supplier<NetworkEvent.Context> context)
	{
		packet.handle(context);
	}

	private static void handleIdleConditionState(IdleConditionStatePacket packet, Supplier<NetworkEvent.Context> context)
	{
		packet.handle(context);
	}

	private static void handleTimelineMarkerHit(TimelineMarkerHitPacket packet, Supplier<NetworkEvent.Context> context)
	{
		packet.handle(context);
	}

	private static void handlePlayerCombatSessionReport(PlayerCombatSessionReportPacket packet,
			Supplier<NetworkEvent.Context> context)
	{
		packet.handle(context);
	}

	private static void handlePlayerCombatSessionState(PlayerCombatSessionStatePacket packet,
			Supplier<NetworkEvent.Context> context)
	{
		packet.handle(context);
	}

	private static void handlePlaybackStartReport(PlaybackStartReportPacket packet,
			Supplier<NetworkEvent.Context> context)
	{
		packet.handle(context);
	}

	private static void handlePlaybackClockSync(PlaybackClockSyncPacket packet,
			Supplier<NetworkEvent.Context> context)
	{
		packet.handle(context);
	}
}
