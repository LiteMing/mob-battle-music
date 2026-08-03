package nonamecrackers2.mobbattlemusic.network;

import java.util.function.Supplier;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import nonamecrackers2.mobbattlemusic.MobBattleMusicMod;

public class MobBattleMusicNetwork
{
	private static final String PROTOCOL_VERSION = "14";
	private static int nextId;
	private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
			.named(MobBattleMusicMod.id("main"))
			.networkProtocolVersion(() -> PROTOCOL_VERSION)
			.clientAcceptedVersions(PROTOCOL_VERSION::equals)
			.serverAcceptedVersions(PROTOCOL_VERSION::equals)
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
	}
	
	public static void sendExternalPlaylistControl(ServerPlayer player, ExternalPlaylistControlPacket packet)
	{
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}
	
	public static void sendServerExternalPlaylistSync(ServerPlayer player, ServerExternalPlaylistSyncPacket packet)
	{
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}
	
	public static void sendExternalPlaylistCatalogToServer(ExternalPlaylistCatalogPacket packet)
	{
		CHANNEL.sendToServer(packet);
	}
	
	public static void requestServerExternalPlaylistSync()
	{
		CHANNEL.sendToServer(new ServerExternalPlaylistSyncRequestPacket());
	}
	
	public static void sendAggressiveEntityState(ServerPlayer player, AggressiveEntityStatePacket packet)
	{
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}

	public static void openPlaylistGui(ServerPlayer player)
	{
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenPlaylistGuiPacket());
	}

	public static void sendIdleConditionState(ServerPlayer player, IdleConditionStatePacket packet)
	{
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}

	public static void sendTimelineMarkerHit(TimelineMarkerHitPacket packet)
	{
		CHANNEL.sendToServer(packet);
	}

	public static void reportPlayerCombatSession(java.util.UUID opponent)
	{
		CHANNEL.sendToServer(new PlayerCombatSessionReportPacket(opponent));
	}

	public static void sendPlayerCombatSessionState(ServerPlayer player, PlayerCombatSessionStatePacket packet)
	{
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
	}

	public static void sendPlaybackStartReport(String trackId, long clientStartEpochMillis, long clientSendEpochMillis)
	{
		CHANNEL.sendToServer(new PlaybackStartReportPacket(trackId, clientStartEpochMillis, clientSendEpochMillis));
	}

	public static void sendPlaybackClockSync(ServerPlayer player, PlaybackClockSyncPacket packet)
	{
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
