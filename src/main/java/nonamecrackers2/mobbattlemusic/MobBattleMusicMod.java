package nonamecrackers2.mobbattlemusic;

import org.apache.maven.artifact.versioning.ArtifactVersion;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.MobBattleMusicClientBootstrap;
import nonamecrackers2.mobbattlemusic.command.AggressiveEntityStateServer;
import nonamecrackers2.mobbattlemusic.command.ExternalPlaylistCatalogServer;
import nonamecrackers2.mobbattlemusic.command.IdleConditionStateServer;
import nonamecrackers2.mobbattlemusic.command.MobBattleMusicCommands;
import nonamecrackers2.mobbattlemusic.command.ServerCueSessionManager;
import nonamecrackers2.mobbattlemusic.command.ServerExternalPlaylistStore;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;

@Mod(MobBattleMusicMod.MODID)
public class MobBattleMusicMod
{
	public static final String MODID = "mobbattlemusic";
	private static ArtifactVersion version;
	
	public MobBattleMusicMod()
	{
		version = ModLoadingContext.get().getActiveContainer().getModInfo().getVersion();
		IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
		DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MobBattleMusicClientBootstrap.register(modEventBus));
		MinecraftForge.EVENT_BUS.addListener(MobBattleMusicCommands::register);
		MinecraftForge.EVENT_BUS.addListener(ExternalPlaylistCatalogServer::onPlayerLoggedOut);
		MinecraftForge.EVENT_BUS.addListener(ServerExternalPlaylistStore::onPlayerLoggedIn);
		MinecraftForge.EVENT_BUS.addListener(ServerExternalPlaylistStore::onLivingDeath);
		MinecraftForge.EVENT_BUS.addListener(ServerExternalPlaylistStore::onEntityLeaveLevel);
		MinecraftForge.EVENT_BUS.addListener(AggressiveEntityStateServer::onPlayerTick);
		MinecraftForge.EVENT_BUS.addListener(IdleConditionStateServer::onPlayerTick);
		MinecraftForge.EVENT_BUS.addListener(IdleConditionStateServer::onPlayerLoggedIn);
		MinecraftForge.EVENT_BUS.addListener(IdleConditionStateServer::onPlayerLoggedOut);
		// K14-B: the authoritative server Cue timeline advances on the server
		// tick; a player joining mid-session receives the current snapshot
		MinecraftForge.EVENT_BUS.addListener(ServerCueSessionManager::onServerTick);
		MinecraftForge.EVENT_BUS.addListener(ServerCueSessionManager::onPlayerLoggedOut);
		MinecraftForge.EVENT_BUS.addListener(ServerExternalPlaylistStore::onPlayerLoggedIn);
		MinecraftForge.EVENT_BUS.addListener(ServerCueSessionManager::onPlayerLoggedIn);
		MobBattleMusicNetwork.register();
		ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, MobBattleMusicConfig.CLIENT_SPEC);
	}
	
	public static ResourceLocation id(String path)
	{
		return new ResourceLocation(MODID, path);
	}
	
	public static ArtifactVersion getModVersion()
	{
		return version;
	}
}
