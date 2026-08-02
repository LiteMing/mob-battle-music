package nonamecrackers2.mobbattlemusic.client.sound;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.manager.BattleMusicManager;

public class MobBattleTrack extends AbstractSoundInstance implements TickableSoundInstance
{
	public static final int MAX_EMPTY_TIME = 300;
	private static volatile boolean mainPlaybackMuted;
	private final int fadeTime;
	private final boolean preview;
	private final long startPositionMillis;
	private boolean startPositionPending;
	private boolean startPositionAttempting;
	private float targetedVolume = 1.0F;
	private int emptyTime;
	private boolean stopped;
	
	public MobBattleTrack(ResourceLocation sound, int fadeTime)
	{
		this(sound, fadeTime, true);
	}

	public MobBattleTrack(ResourceLocation sound, int fadeTime, boolean looping)
	{
		this(sound, fadeTime, looping, false);
	}

	public MobBattleTrack(ResourceLocation sound, int fadeTime, boolean looping, boolean preview)
	{
		this(sound, fadeTime, looping, preview, 0L);
	}

	public MobBattleTrack(ResourceLocation sound, int fadeTime, boolean looping, boolean preview,
			long startPositionMillis)
	{
		super(sound, BattleMusicManager.DEFAULT_SOUND_SOURCE, SoundInstance.createUnseededRandom());
		this.fadeTime = fadeTime;
		this.preview = preview;
		this.startPositionMillis = Math.max(0L, startPositionMillis);
		this.startPositionPending = this.startPositionMillis > 0L;
		this.looping = looping;
		this.delay = 0;
		this.volume = 0.0F;
		this.relative = true;
	}

	@Override
	public void tick()
	{
		float delta = (this.targetedVolume - this.volume) / this.fadeTime;
		this.volume += delta;
		
		if (this.emptyTime++ > MobBattleMusicConfig.CLIENT.musicTrackEmptyTime.get() * 20)
			this.stop();
		
		if (this.volume > 0.01F)
			this.emptyTime = 0;
	}
	
	public void setTargetedVolume(float volume)
	{
		this.targetedVolume = volume;
	}

	public static MobBattleTrack preview(ResourceLocation sound, int fadeTime)
	{
		return new MobBattleTrack(sound, fadeTime, true, true);
	}

	public boolean isPreview()
	{
		return this.preview;
	}

	public static boolean isMainPlaybackMuted()
	{
		return mainPlaybackMuted;
	}

	public static void setMainPlaybackMuted(boolean muted)
	{
		mainPlaybackMuted = muted;
	}

	public ResourceLocation getTrackLocation()
	{
		return this.location;
	}

	public synchronized long beginStartPositionAttempt()
	{
		if (!this.startPositionPending || this.startPositionAttempting)
			return 0L;
		this.startPositionAttempting = true;
		return this.startPositionMillis;
	}

	public synchronized void completeStartPositionAttempt(boolean succeeded)
	{
		if (succeeded)
			this.startPositionPending = false;
		this.startPositionAttempting = false;
	}
	
	@Override
	public boolean canStartSilent()
	{
		return true;
	}
	
	@Override
	public boolean isStopped()
	{
		return this.stopped;
	}
	
	public void stop()
	{
		this.stopped = true;
		this.looping = false;
	}
}
