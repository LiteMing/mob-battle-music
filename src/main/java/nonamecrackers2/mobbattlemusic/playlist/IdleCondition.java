package nonamecrackers2.mobbattlemusic.playlist;

import java.util.Locale;

import net.minecraft.resources.ResourceLocation;

/**
 * K9-2: a single entry/idle-rule condition. The join declares how this
 * condition combines with the previous one in the list (AND is the default
 * and the only mode the server-side store supports; OR groups are evaluated
 * client-side as OR-blocks separated by AND).
 */
public record IdleCondition(String type, String argument, boolean inverted, Join join)
{
	public enum Join
	{
		AND,
		OR
	}

	public IdleCondition
	{
		type = new ResourceLocation(type.toLowerCase(Locale.ROOT)).toString();
		argument = argument == null ? "" : argument.trim();
		join = join == null ? Join.AND : join;
	}

	public IdleCondition(String type, String argument, boolean inverted)
	{
		this(type, argument, inverted, Join.AND);
	}

	public IdleCondition withJoin(Join newJoin)
	{
		return new IdleCondition(this.type, this.argument, this.inverted, newJoin);
	}

	public IdleCondition withInverted(boolean newInverted)
	{
		return new IdleCondition(this.type, this.argument, newInverted, this.join);
	}
}
