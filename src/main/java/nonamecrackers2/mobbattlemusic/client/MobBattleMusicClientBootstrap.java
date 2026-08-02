package nonamecrackers2.mobbattlemusic.client;

import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import nonamecrackers2.mobbattlemusic.client.event.MobBattleMusicClientEvents;
import nonamecrackers2.mobbattlemusic.client.audio.AudioFilterManager;
import nonamecrackers2.mobbattlemusic.client.init.MobBattleMusicClientCapabilities;
import nonamecrackers2.mobbattlemusic.client.util.MobBattleMusicCompat;

public class MobBattleMusicClientBootstrap
{
	public static void register(IEventBus modEventBus)
	{
		modEventBus.addListener(MobBattleMusicClientEvents::registerReloadListeners);
		modEventBus.addListener(MobBattleMusicClientCapabilities::registerCapabilities);
		modEventBus.addListener(MobBattleMusicClientEvents::onSoundEngineLoad);
		modEventBus.addListener(MobBattleMusicClientEvents::registerConfigScreen);
		modEventBus.addListener(MobBattleMusicClientEvents::registerConfigMenuButton);
		modEventBus.addListener(MobBattleMusicClientBootstrap::clientSetup);
		
		IEventBus forgeBus = MinecraftForge.EVENT_BUS;
		forgeBus.addGenericListener(Level.class, MobBattleMusicClientCapabilities::attachLevelCapabilities);
		forgeBus.register(MobBattleMusicClientEvents.class);
	}
	
	private static void clientSetup(FMLClientSetupEvent event)
	{
		event.enqueueWork(() -> {
			MobBattleMusicCompat.checkModCompat();
			AudioFilterManager.loadConfig();
			initializeExternalMusicCache();
		});
	}
	
	private static void initializeExternalMusicCache()
	{
		try {
			nonamecrackers2.mobbattlemusic.client.music.AudioSystemDiagnostics.runDiagnostics();
			
			nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler handler =
					nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler.getInstance();
			nonamecrackers2.mobbattlemusic.client.music.MusicCache cache = handler.getCache();
			
			cache.validateCache();
			cache.markOldFiles(30);
			cache.logCacheSize();
		} catch (Exception e) {
			org.apache.logging.log4j.LogManager.getLogger("mobbattlemusic").error("Failed to initialize external music cache", e);
		}
	}
}
