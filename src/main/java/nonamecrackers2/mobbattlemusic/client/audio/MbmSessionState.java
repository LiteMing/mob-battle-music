package nonamecrackers2.mobbattlemusic.client.audio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.client.Minecraft;

public final class MbmSessionState
{
	private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/MbmSessionState");
	// K8-A: the bounded grace period for a cross-dimension load; a transition
	// that produces no new level within this window degrades to DISCONNECTED
	private static final long LEVEL_TRANSITION_TIMEOUT_MILLIS = 30_000L;

	private static volatile SessionState current = SessionState.NO_WORLD;
	private static volatile boolean focused = true;
	private static volatile boolean paused;
	private static volatile boolean published;
	private static volatile boolean localSingleplayer;
	// K8-A: the last non-null client level identity and the LoggingOut marker.
	// prevLevel is only advanced by a non-null level, so the transition keeps
	// its baseline while the level is null.
	private static volatile net.minecraft.client.multiplayer.ClientLevel prevLevel;
	private static volatile boolean loggedOut;
	private static volatile long levelTransitionStartedAtMillis;

	private MbmSessionState() {}

	/**
	 * K8-A: called by the LoggingOut event on the main thread - the next
	 * evaluate() leaves immediately (DISCONNECTED/NO_WORLD), never entering
	 * the transition grace period.
	 */
	public static void markLoggedOut()
	{
		MbmSessionState.loggedOut = true;
	}

	public static void evaluate()
	{
		Minecraft mc = Minecraft.getInstance();
		SessionState previous = MbmSessionState.current;
		net.minecraft.client.multiplayer.ClientLevel level = mc.level;
		SessionState next;
		long now = System.currentTimeMillis();

		if (MbmSessionState.loggedOut) {
			// K8-A: a real LoggingOut stops immediately - no transition grace
			MbmSessionState.loggedOut = false;
			MbmSessionState.prevLevel = null;
			MbmSessionState.levelTransitionStartedAtMillis = 0L;
			next = previous == SessionState.NO_WORLD || previous == SessionState.DISCONNECTED
					|| previous == SessionState.LEVEL_TRANSITION
							? SessionState.NO_WORLD : SessionState.DISCONNECTED;
		}
		// K8-A: level is null, but the connection and a previous level exist -
		// this is a cross-dimension load, not a real disconnect
		else if (level == null && mc.getConnection() != null && MbmSessionState.prevLevel != null) {
			if (MbmSessionState.levelTransitionStartedAtMillis <= 0L)
				MbmSessionState.levelTransitionStartedAtMillis = now;
			if (now - MbmSessionState.levelTransitionStartedAtMillis > MbmSessionState.LEVEL_TRANSITION_TIMEOUT_MILLIS) {
				// bounded grace: a transition that never completes degrades
				MbmSessionState.levelTransitionStartedAtMillis = 0L;
				next = SessionState.DISCONNECTED;
			} else {
				next = SessionState.LEVEL_TRANSITION;
			}
		}
		// K8-A: a direct level identity change (A -> B with no null gap)
		else if (level != null && MbmSessionState.prevLevel != null && level != MbmSessionState.prevLevel) {
			MbmSessionState.levelTransitionStartedAtMillis = 0L;
			next = SessionState.LEVEL_TRANSITION;
		}
		else {
			MbmSessionState.levelTransitionStartedAtMillis = 0L;
			// AUD-7 step 1: mc.level == null -> DISCONNECTED if a level existed last tick, else NO_WORLD
			if (level == null)
			{
				next = previous == SessionState.NO_WORLD || previous == SessionState.DISCONNECTED
						|| previous == SessionState.LEVEL_TRANSITION
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
		}
		// K8-A: the transition baseline survives null ticks; only a real level
		// advances it
		if (level != null)
			MbmSessionState.prevLevel = level;
		
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
