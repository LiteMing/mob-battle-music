package nonamecrackers2.mobbattlemusic.client.audio;

import javax.annotation.Nullable;

public final class PlaybackHandle
{
	private final String track;
	private final long startedEpochMillis;
	private volatile @Nullable SourceRef sourceRef;
	private volatile boolean stopped;
	
	private PlaybackHandle(String track)
	{
		this.track = track;
		this.startedEpochMillis = System.currentTimeMillis();
	}
	
	public static PlaybackHandle create(String track)
	{
		return new PlaybackHandle(track);
	}
	
	public String track()
	{
		return this.track;
	}
	
	// AUD-22: client-side wall-clock start of this playback (sent to the server)
	public long startedEpochMillis()
	{
		return this.startedEpochMillis;
	}
	
	public boolean isStopped()
	{
		return this.stopped;
	}
	
	public void markStopped()
	{
		this.stopped = true;
	}
	
	// AUD-17: source reference with stable entryKey + owning playlist revision
	public void setSourceRef(@Nullable SourceRef sourceRef)
	{
		this.sourceRef = sourceRef;
	}
	
	public @Nullable SourceRef sourceRef()
	{
		return this.sourceRef;
	}
}
