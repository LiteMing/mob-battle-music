package nonamecrackers2.mobbattlemusic.client.audio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.client.Minecraft;

public final class MbmSessionState
{
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/MbmSessionState");
	
	private static volatile SessionState current = SessionState.NO_WORLD;
	private static volatile boolean focused = true;
	private static volatile boolean paused;
	private static volatile boolean published;
	private static volatile boolean localSingleplayer;
	
	private MbmSessionState() {}
	
	public static void evaluate()
	{
		Minecraft mc = Minecraft.getInstance();
		SessionState previous = MbmSessionState.current;
		SessionState next;
		
		// AUD-7 step 1: mc.level == null -> DISCONNECTED if a level existed last tick, else NO_WORLD
		if (mc.level == null)
		{
			next = previous == SessionState.NO_WORLD || previous == SessionState.DISCONNECTED
					? SessionState.NO_WORLD : SessionState.DISCONNECTED;
		}
		// AUD-7 step 2: not singleplayer -> MULTIPLAYER
		else if (!mc.hasSingleplayerServer())
		{
			next = SessionState.MULTIPLAYER;
		}
		// AUD-7 step 3: published integrated server beats isPaused(), see AUD-8
		// (world logic keeps running once the save is open to LAN, so the music
		// must not freeze with the pause screen; LAN_HOST is MULTIPLAYER-equivalent)
		else if (mc.getSingleplayerServer().isPublished())
		{
			next = SessionState.LAN_HOST;
		}
		// AUD-7 step 4: singleplayer with frozen game logic
		else if (mc.isPaused())
		{
			next = SessionState.SINGLEPLAYER_PAUSED;
		}
		// AUD-7 step 5: everything else
		else
		{
			next = SessionState.SINGLEPLAYER_RUNNING;
		}
		
		MbmSessionState.current = next;
		// AUD-15: unfocused = window inactive or a pause screen is open
		MbmSessionState.focused = mc.isWindowActive()
				&& !(mc.screen != null && mc.screen.isPauseScreen());
		// Cache the raw inputs used above so the debug probe (AUD-30 session= line)
		// can report them without adding isPaused()/isPublished() call sites outside
		// this class (AUD-29 #1)
		MbmSessionState.paused = mc.isPaused();
		MbmSessionState.published = mc.getSingleplayerServer() != null
				&& mc.getSingleplayerServer().isPublished();
		// AUD-29 #1: singleplayer eligibility for server-data editing, consumed
		// by the playlist GUIs through this accessor only
		MbmSessionState.localSingleplayer = mc.hasSingleplayerServer();
		
		if (next != previous)
			LOGGER.debug("[MBM] session {} -> {}", previous, next);
	}
	
	public static SessionState current()
	{
		return MbmSessionState.current;
	}
	
	public static boolean isFocused()
	{
		return MbmSessionState.focused;
	}
	
	public static boolean isPausedNow()
	{
		return MbmSessionState.paused;
	}
	
	public static boolean isPublishedNow()
	{
		return MbmSessionState.published;
	}
	
	/**
	 * True while running against a local integrated server (singleplayer),
	 * including a published LAN host. Replaces direct hasSingleplayerServer()
	 * calls in the GUI layer (AUD-29 #1).
	 */
	public static boolean isLocalSingleplayer()
	{
		return MbmSessionState.localSingleplayer;
	}
}
