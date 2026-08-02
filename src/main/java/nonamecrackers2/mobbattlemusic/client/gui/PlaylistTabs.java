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
		return create(new PlaylistScreenLayout.Rect(0, 28, screenWidth, 20), selected, onSelected);
	}

	static List<Button> create(PlaylistScreenLayout.Rect bounds, Tab selected, Consumer<Tab> onSelected)
	{
		List<Tab> visibleTabs = List.of(Tab.LIBRARY, Tab.IDLE, Tab.FILTERS, Tab.NETEASE);
		int gap = PlaylistScreenLayout.GAP;
		int available = Math.max(1, bounds.width());
		int width = Math.max(38, Math.min(104, (available - gap * (visibleTabs.size() - 1)) / visibleTabs.size()));
		int total = width * visibleTabs.size() + gap * (visibleTabs.size() - 1);
		int x = bounds.x() + Math.max(0, (bounds.width() - total) / 2);
		List<Button> buttons = new ArrayList<>();
		for (Tab tab : visibleTabs) {
			Button button = Button.builder(Component.translatable(PREFIX + tab.key), value -> onSelected.accept(tab))
					.bounds(x, bounds.y(), width, bounds.height()).build();
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
