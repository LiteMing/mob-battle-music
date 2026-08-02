package nonamecrackers2.mobbattlemusic.client.sound.track;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.util.AggressiveEntityStateClient;
import nonamecrackers2.mobbattlemusic.client.util.MobSelection;

public class EntityUuidTrack extends MobTrack
{
	private final UUID uuid;
	private final boolean matchPanicTarget;
	
	public EntityUuidTrack(UUID uuid, ResourceLocation track, int fadeTime, MobSelection.GroupType group,
			MobSelection.Selector selector, boolean matchPanicTarget)
	{
		super(track, fadeTime, group, selector);
		this.uuid = uuid;
		this.matchPanicTarget = matchPanicTarget;
	}
	
	@Override
	public boolean canPlay(MobSelection selection)
	{
		LivingEntity panicTarget = selection.panicTarget();
		if (this.matchPanicTarget && panicTarget != null && panicTarget.getUUID().equals(this.uuid))
			return true;
		return contains(selection) || matchesLoadedEntity();
	}
	
	private boolean contains(MobSelection selection)
	{
		for (Mob mob : this.getMobs(selection)) {
			if (mob.getUUID().equals(this.uuid))
				return true;
		}
		return false;
	}
	
	private boolean matchesLoadedEntity()
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null)
			return false;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!entity.getUUID().equals(this.uuid) || !(entity instanceof LivingEntity living))
				continue;
			if (!living.isAlive() || living.distanceTo(mc.player) > MobBattleMusicConfig.CLIENT.maxMobSearchRadius.get())
				return false;
			if (!selectorMatches(mc, living))
				return false;
			return switch (this.group) {
				case ATTACKING -> this.matchPanicTarget && living instanceof Mob mob &&
						mob.distanceTo(mc.player) <= MobBattleMusicConfig.CLIENT.threatRadius.get() &&
						(AggressiveEntityStateClient.isServerAggressive(mob.getUUID()) ||
								mob.isAggressive() || mob.getTarget() != null);
				case ENEMIES -> true;
			};
		}
		return false;
	}
	
	private boolean selectorMatches(Minecraft mc, LivingEntity entity)
	{
		return switch (this.selector) {
			case ANY -> true;
			case LINE_OF_SIGHT -> mc.player.hasLineOfSight(entity);
			case ON_SCREEN -> {
				var frustum = mc.levelRenderer.getFrustum();
				yield frustum != null && frustum.isVisible(entity.getBoundingBox()) && mc.player.hasLineOfSight(entity);
			}
		};
	}
}
