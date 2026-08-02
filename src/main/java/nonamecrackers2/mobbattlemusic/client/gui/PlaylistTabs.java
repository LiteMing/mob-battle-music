package nonamecrackers2.mobbattlemusic.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

final class PlaylistTabs
{
	private static final String PREFIX = "gui.mobbattlemusic.playlist.tab.";

	private PlaylistTabs() {}

	static List<Button> create(int screenWidth, Tab selected, Consumer<Tab> onSelected)
	{
		List<Tab> visibleTabs = List.of(Tab.LIBRARY, Tab.IDLE, Tab.FILTERS, Tab.NETEASE);
		int gap = 4;
		int available = Math.max(200, screenWidth - 24);
		int width = Math.max(38, Math.min(104, (available - gap * (visibleTabs.size() - 1)) / visibleTabs.size()));
		int total = width * visibleTabs.size() + gap * (visibleTabs.size() - 1);
		int x = Math.max(12, (screenWidth - total) / 2);
		List<Button> buttons = new ArrayList<>();
		for (Tab tab : visibleTabs) {
			Button button = Button.builder(Component.translatable(PREFIX + tab.key), value -> onSelected.accept(tab))
					.bounds(x, 28, width, 20).build();
			button.active = tab != selected;
			buttons.add(button);
			x += width + gap;
		}
		return buttons;
	}

	enum Tab
	{
		LIBRARY("library"),
		IDLE("idle"),
		TIMELINE("timeline"),
		FILTERS("filters"),
		NETEASE("netease");

		private final String key;

		Tab(String key)
		{
			this.key = key;
		}
	}
}
