package nonamecrackers2.mobbattlemusic.playlist;

import java.util.Locale;

import net.minecraft.resources.ResourceLocation;

public record IdleCondition(String type, String argument, boolean inverted)
{
	public IdleCondition
	{
		type = new ResourceLocation(type.toLowerCase(Locale.ROOT)).toString();
		argument = argument == null ? "" : argument.trim();
	}
}
