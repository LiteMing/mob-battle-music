package nonamecrackers2.mobbattlemusic.client.event;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.sound.SoundEngineLoadEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.config.ModConfig;
import nonamecrackers2.crackerslib.client.event.impl.ConfigMenuButtonEvent;
import nonamecrackers2.crackerslib.client.event.impl.RegisterConfigScreensEvent;
import nonamecrackers2.crackerslib.client.gui.ConfigHomeScreen;
import nonamecrackers2.crackerslib.client.gui.title.ImageTitle;
import nonamecrackers2.mobbattlemusic.MobBattleMusicMod;
import nonamecrackers2.mobbattlemusic.client.audio.MbmSessionState;
import nonamecrackers2.mobbattlemusic.client.audio.SessionState;
import nonamecrackers2.mobbattlemusic.client.audio.WorldPlaybackChannel;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.audio.AudioFilterManager;
import nonamecrackers2.mobbattlemusic.client.audio.GlobalAudioFilterManager;
import nonamecrackers2.mobbattlemusic.client.init.MobBattleMusicClientCapabilities;
import nonamecrackers2.mobbattlemusic.client.manager.BattleMusicManager;
import nonamecrackers2.mobbattlemusic.client.music.IdleConditionStateClient;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.client.util.PlayerCombatSessionClient;
import nonamecrackers2.mobbattlemusic.client.sound.track.TrackType;
import nonamecrackers2.mobbattlemusic.client.util.AggressiveEntityStateClient;

public class MobBattleMusicClientEvents
{
	public static void registerConfigScreen(RegisterConfigScreensEvent event)
	{
		event.builder(ConfigHomeScreen.builder(ImageTitle.ofMod(MobBattleMusicMod.MODID, 512, 256, 0.5F))
				.crackersDefault("https://github.com/nonamecrackers2/mob-battle-music/issues").build()
		).addSpec(ModConfig.Type.CLIENT, MobBattleMusicConfig.CLIENT_SPEC).register();
	}
	
	public static void registerConfigMenuButton(ConfigMenuButtonEvent event)
	{
		event.defaultButtonWithSingleCharacter('M', 0xFFFF4949);
	}
	
	public static void registerReloadListeners(RegisterClientReloadListenersEvent event)
	{
		event.registerReloadListener(MusicTracksManager.getInstance());
	}
	
	public static void onSoundEngineLoad(SoundEngineLoadEvent event)
	{
		GlobalAudioFilterManager.reset();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null)
			mc.level.getCapability(MobBattleMusicClientCapabilities.MUSIC_MANAGER).ifPresent(BattleMusicManager::reload);
	}

	@SubscribeEvent
	public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event)
	{
		AggressiveEntityStateClient.clear();
		PlayerCombatSessionClient.clear();
		IdleConditionStateClient.clear();
		MusicTracksManager.getInstance().syncExternalPlaylistCatalogToServer();
	}
	
	@SubscribeEvent
	public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event)
	{
		AggressiveEntityStateClient.clear();
		IdleConditionStateClient.clear();
		AudioFilterManager.deactivate();
		WorldPlaybackChannel.reset();
	}
	
	@SubscribeEvent
	public static void onEntityLeaveLevel(EntityLeaveLevelEvent event)
	{
		if (event.getLevel().isClientSide()) {
			var reason = event.getEntity().getRemovalReason();
			if (reason != null && reason.shouldDestroy())
				MusicTracksManager.getInstance().removeLocalEntityUuid(event.getEntity().getUUID());
		}
	}
	
	@SubscribeEvent
	public static void onClientTick(TickEvent.ClientTickEvent event)
	{
		Minecraft mc = Minecraft.getInstance();
		if (event.phase == TickEvent.Phase.END)
		{
			// AUD-36: ordering is explicit inside this single MBM tick entry;
			// do not rely on relative order between Forge listeners. The order
			// below is load-bearing: 1) session evaluate, 2) channel update
			// (AUD-19 invalidation may stop playback), 3) selection engine tick
			// which must observe the stopped state to continue with the next
			// track in the same tick.
			MbmSessionState.evaluate();
			// AUD-5/AUD-13: the session state machine drives the main playback channel
			WorldPlaybackChannel.update(MbmSessionState.current(), MbmSessionState.isFocused());
			if (mc.level != null)
			{
				if (mc.player != null)
					AudioFilterManager.tick(mc.player);
				// Only tick the music manager while world logic is running
				// (SINGLEPLAYER_PAUSED freezes world logic; LAN_HOST and MULTIPLAYER do not)
				if (MbmSessionState.current() != SessionState.SINGLEPLAYER_PAUSED)
					mc.level.getCapability(MobBattleMusicClientCapabilities.MUSIC_MANAGER).ifPresent(BattleMusicManager::tick);
			}
		}
	}
	
	@SubscribeEvent
	public static void onPlayerAttack(AttackEntityEvent event)
	{
		Player player = event.getEntity();
		if (player.level().isClientSide())
		{
			player.level().getCapability(MobBattleMusicClientCapabilities.MUSIC_MANAGER).ifPresent(manager -> {
				manager.onAttack(event.getTarget());
			});
		}
	}
	
	@SubscribeEvent
	public static void onRenderDebugOverlay(CustomizeGuiOverlayEvent.DebugText event)
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.renderDebug)
		{
			List<String> text = event.getRight();
			text.add("");
			text.add(MobBattleMusicMod.MODID + ": " + MobBattleMusicMod.getModVersion());
			if (mc.level != null)
			{
				mc.level.getCapability(MobBattleMusicClientCapabilities.MUSIC_MANAGER).ifPresent(manager -> 
				{
					TrackType track = manager.getPriorityTrack();
					text.add("Priority track: " + (track == null ? ChatFormatting.RED + "none" : ChatFormatting.GREEN + track.toString()));
					text.add("Tracks playing:");
					for (TrackType playing : manager.getPlayingTracks())
						text.add(playing.toString());
					text.add("Panic target: " + (manager.getPanicTarget() == null ? "none" : manager.getPanicTarget().getDisplayName().getString()));
				});
			}
		}
	}
}
