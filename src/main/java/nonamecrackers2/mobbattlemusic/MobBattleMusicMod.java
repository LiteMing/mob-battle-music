package nonamecrackers2.mobbattlemusic;

import org.apache.maven.artifact.versioning.ArtifactVersion;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.event.MobBattleMusicClientEvents;
import nonamecrackers2.mobbattlemusic.client.init.MobBattleMusicClientCapabilities;
import nonamecrackers2.mobbattlemusic.client.util.MobBattleMusicCompat;

@Mod(MobBattleMusicMod.MODID)
public class MobBattleMusicMod
{
	public static final String MODID = "mobbattlemusic";
	private static ArtifactVersion version;
	
	public MobBattleMusicMod()
	{
		version = ModLoadingContext.get().getActiveContainer().getModInfo().getVersion();
		IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
		modEventBus.addListener(MobBattleMusicClientEvents::registerReloadListeners);
		modEventBus.addListener(this::clientSetup);
		ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, MobBattleMusicConfig.CLIENT_SPEC);
	}
	
	public void clientSetup(FMLClientSetupEvent event)
	{
		IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
		modEventBus.addListener(MobBattleMusicClientCapabilities::registerCapabilities);
		modEventBus.addListener(MobBattleMusicClientEvents::onSoundEngineLoad);
		modEventBus.addListener(MobBattleMusicClientEvents::registerConfigScreen);
		modEventBus.addListener(MobBattleMusicClientEvents::registerConfigMenuButton);
		IEventBus forgeBus = MinecraftForge.EVENT_BUS;
		forgeBus.addGenericListener(Level.class, MobBattleMusicClientCapabilities::attachLevelCapabilities);
		forgeBus.register(MobBattleMusicClientEvents.class);
		event.enqueueWork(() -> {
			MobBattleMusicCompat.checkModCompat();
			// Initialize external music cache
			initializeExternalMusicCache();
		});
	}
	
	private void initializeExternalMusicCache()
	{
		try {
			// Run audio system diagnostics
			nonamecrackers2.mobbattlemusic.client.music.AudioSystemDiagnostics.runDiagnostics();
			
			nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler handler = 
				nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler.getInstance();
			nonamecrackers2.mobbattlemusic.client.music.MusicCache cache = handler.getCache();
			
			// Validate cache on startup
			cache.validateCache();
			
			// Mark old files (older than 30 days)
			cache.markOldFiles(30);
			
			// Log cache size
			cache.logCacheSize();
		} catch (Exception e) {
			// Log error but don't crash
			org.apache.logging.log4j.LogManager.getLogger("mobbattlemusic").error("Failed to initialize external music cache", e);
		}
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
