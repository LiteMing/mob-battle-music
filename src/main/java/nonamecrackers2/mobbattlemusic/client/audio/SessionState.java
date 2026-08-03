package nonamecrackers2.mobbattlemusic.client.audio;

public enum SessionState
{
	NO_WORLD,
	SINGLEPLAYER_RUNNING,
	SINGLEPLAYER_PAUSED,
	LAN_HOST,
	MULTIPLAYER,
	// K8-A: cross-dimension transition - the connection still exists but the
	// client level is momentarily null or its identity changed; the main
	// channel must preserve the current playback source instead of treating
	// this as a real NO_WORLD/DISCONNECTED stop
	LEVEL_TRANSITION,
	DISCONNECTED
}
