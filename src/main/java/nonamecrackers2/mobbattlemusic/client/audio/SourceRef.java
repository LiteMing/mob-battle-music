package nonamecrackers2.mobbattlemusic.client.audio;

import javax.annotation.Nullable;

/**
 * AUD-17: playback handle source reference. entryKey is a stable identifier
 * (URL-derived, never a list index); revision is the owning playlist's
 * monotonic revision (AUD-18).
 */
public record SourceRef(String playlistId, String entryKey, int revision)
{
	public static final String DIRECT_PLAYLIST = "mobbattlemusic:direct";

	public static SourceRef direct(String entryKey)
	{
		return new SourceRef(DIRECT_PLAYLIST, entryKey, 0);
	}

	public boolean isDirect()
	{
		return DIRECT_PLAYLIST.equals(this.playlistId());
	}

	@Override
	public String toString()
	{
		return "playlist=" + this.playlistId() + " entry=" + this.entryKey() + " rev=" + this.revision();
	}
}
