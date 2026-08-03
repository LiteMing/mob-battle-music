package nonamecrackers2.mobbattlemusic.client.audio;

import javax.annotation.Nullable;

public final class PlaybackHandle
{
	private final boolean preview;
	private final String track;
	private final long startedNanos;
	private final long startedEpochMillis;
	private volatile @Nullable SourceRef sourceRef;
	private volatile boolean stopped;
	
	private PlaybackHandle(boolean preview, String track)
	{
		this.preview = preview;
		this.track = track;
		this.startedNanos = System.nanoTime();
		this.startedEpochMillis = System.currentTimeMillis();
	}
	
	public static PlaybackHandle world(String track)
	{
		return new PlaybackHandle(false, track);
	}
	
	public static PlaybackHandle preview(String track)
	{
		return new PlaybackHandle(true, track);
	}
	
	public boolean isPreview()
	{
		return this.preview;
	}
	
	public String track()
	{
		return this.track;
	}
	
	public long startedNanos()
	{
		return this.startedNanos;
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
