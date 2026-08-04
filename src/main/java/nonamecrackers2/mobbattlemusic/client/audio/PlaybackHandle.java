package nonamecrackers2.mobbattlemusic.client.audio;

import javax.annotation.Nullable;

public final class PlaybackHandle
{
	// K12-B: lifecycle state of the handle. PREPARING is created by
	// adoptManualSelection BEFORE the async play request resolves; ACTIVE is
	// set only once the line actually produces audio; FAILED when the request
	// was refused (generation handoff) or the line/decode aborted. A handle
	// must never claim ACTIVE playback it cannot back with a real source.
	public enum State
	{
		PREPARING,
		ACTIVE,
		FAILED
	}

	private final String track;
	private final long startedEpochMillis;
	private volatile @Nullable SourceRef sourceRef;
	private volatile boolean stopped;
	private volatile State state = State.PREPARING;
	
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

	public State state()
	{
		return this.state;
	}

	public void markActive()
	{
		this.state = State.ACTIVE;
	}

	public void markFailed()
	{
		this.state = State.FAILED;
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
