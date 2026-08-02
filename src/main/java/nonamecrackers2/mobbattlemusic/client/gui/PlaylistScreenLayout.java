package nonamecrackers2.mobbattlemusic.client.gui;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.layouts.GridLayout;

/** Shared responsive frame used by every playlist editor tab. */
final class PlaylistScreenLayout
{
	static final int PADDING = 6;
	static final int GAP = 4;
	static final int ROW_H = 20;
	static final int SIDEBAR_W = 140;
	static final int INSPECTOR_W = 180;
	static final int TITLE_H = 20;
	static final int TAB_H = 20;
	static final int ACTION_H = 26;

	private static final int MIN_SIDEBAR_W = 84;
	private static final int MIN_MAIN_W = 92;
	private static final int MIN_INSPECTOR_W = 112;
	private static final int FRAME_COLOR = 0xF0101114;
	private static final int PANEL_COLOR = 0xFF17191D;
	private static final int BORDER_COLOR = 0xFF515861;
	private static final int DIVIDER_COLOR = 0xFF30353B;

	private final Rect frame;
	private final Rect titleBar;
	private final Rect tabBar;
	private final Rect sidebar;
	private final Rect main;
	private final Rect inspector;
	private final Rect actionBar;

	private PlaylistScreenLayout(Rect frame, Rect titleBar, Rect tabBar, Rect sidebar, Rect main,
			Rect inspector, Rect actionBar)
	{
		this.frame = frame;
		this.titleBar = titleBar;
		this.tabBar = tabBar;
		this.sidebar = sidebar;
		this.main = main;
		this.inspector = inspector;
		this.actionBar = actionBar;
	}

	static PlaylistScreenLayout calculate(int screenWidth, int screenHeight)
	{
		int frameX = PADDING;
		int frameY = PADDING;
		int frameWidth = Math.max(1, screenWidth - PADDING * 2);
		int frameHeight = Math.max(1, screenHeight - PADDING * 2);
		int titleY = frameY;
		int tabsY = titleY + TITLE_H + GAP;
		int contentY = tabsY + TAB_H + GAP;
		int desiredActionY = frameY + frameHeight - ACTION_H;
		int actionY = Math.max(contentY + 1, desiredActionY);
		int contentHeight = Math.max(1, actionY - GAP - contentY);

		int available = Math.max(1, frameWidth - GAP * 2);
		int sidebarWidth = SIDEBAR_W;
		int inspectorWidth = INSPECTOR_W;
		if (available - sidebarWidth - inspectorWidth < MIN_MAIN_W) {
			int flexible = Math.max(0, available - MIN_MAIN_W);
			sidebarWidth = Math.max(MIN_SIDEBAR_W, Math.min(SIDEBAR_W, flexible * 4 / 10));
			inspectorWidth = Math.max(MIN_INSPECTOR_W,
					Math.min(INSPECTOR_W, flexible - sidebarWidth));
			if (sidebarWidth + inspectorWidth > flexible) {
				int overflow = sidebarWidth + inspectorWidth - flexible;
				int inspectorReduction = Math.min(overflow, inspectorWidth - 72);
				inspectorWidth -= inspectorReduction;
				overflow -= inspectorReduction;
				sidebarWidth = Math.max(64, sidebarWidth - overflow);
			}
		}
		int mainWidth = Math.max(1, available - sidebarWidth - inspectorWidth);
		int sidebarX = frameX;
		int mainX = sidebarX + sidebarWidth + GAP;
		int inspectorX = mainX + mainWidth + GAP;

		Rect frame = new Rect(frameX, frameY, frameWidth, frameHeight);
		return new PlaylistScreenLayout(frame,
				new Rect(frameX, titleY, frameWidth, TITLE_H),
				new Rect(frameX, tabsY, frameWidth, TAB_H),
				new Rect(sidebarX, contentY, sidebarWidth, contentHeight),
				new Rect(mainX, contentY, mainWidth, contentHeight),
				new Rect(inspectorX, contentY, inspectorWidth, contentHeight),
				new Rect(frameX, actionY, frameWidth, ACTION_H));
	}

	ActionMetrics actionMetrics()
	{
		int available = this.actionBar.innerWidth();
		int previewWidth;
		int stopWidth;
		int refreshWidth;
		int cancelWidth;
		int saveWidth;
		if (available >= 308) {
			previewWidth = 62;
			stopWidth = 52;
			refreshWidth = 62;
			cancelWidth = 58;
			saveWidth = 58;
		} else if (available >= 266) {
			previewWidth = 50;
			stopWidth = 42;
			refreshWidth = 50;
			cancelWidth = 50;
			saveWidth = 50;
		} else {
			previewWidth = 40;
			stopWidth = 32;
			refreshWidth = 40;
			cancelWidth = 40;
			saveWidth = 40;
		}
		int y = this.actionBar.y() + 3;
		int previewX = this.actionBar.innerX();
		int stopX = previewX + previewWidth + GAP;
		int refreshX = stopX + stopWidth + GAP;
		int saveX = this.actionBar.right() - PADDING - saveWidth;
		int cancelX = saveX - GAP - cancelWidth;
		return new ActionMetrics(y, previewX, previewWidth, stopX, stopWidth, refreshX, refreshWidth,
				cancelX, cancelWidth, saveX, saveWidth);
	}

	void render(GuiGraphics graphics)
	{
		fillBordered(graphics, this.frame, FRAME_COLOR, BORDER_COLOR);
		fillBordered(graphics, this.sidebar, PANEL_COLOR, BORDER_COLOR);
		fillBordered(graphics, this.main, PANEL_COLOR, BORDER_COLOR);
		fillBordered(graphics, this.inspector, PANEL_COLOR, BORDER_COLOR);
		graphics.fill(this.actionBar.x(), this.actionBar.y(), this.actionBar.right(), this.actionBar.y() + 1,
				DIVIDER_COLOR);
	}

	/** Uses the vanilla layout engine so button spacing remains stable after localization. */
	static void arrangeRow(List<? extends AbstractWidget> widgets, int x, int y, int gap)
	{
		GridLayout grid = new GridLayout(x, y).columnSpacing(gap);
		GridLayout.RowHelper row = grid.createRowHelper(Math.max(1, widgets.size()));
		for (AbstractWidget widget : widgets)
			row.addChild(widget);
		grid.arrangeElements();
	}

	private static void fillBordered(GuiGraphics graphics, Rect rect, int fill, int border)
	{
		graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), fill);
		graphics.renderOutline(rect.x(), rect.y(), rect.width(), rect.height(), border);
	}

	Rect frame() { return this.frame; }
	Rect titleBar() { return this.titleBar; }
	Rect tabBar() { return this.tabBar; }
	Rect sidebar() { return this.sidebar; }
	Rect main() { return this.main; }
	Rect inspector() { return this.inspector; }
	Rect actionBar() { return this.actionBar; }

	record Rect(int x, int y, int width, int height)
	{
		int right() { return this.x + this.width; }
		int bottom() { return this.y + this.height; }
		int innerX() { return this.x + PADDING; }
		int innerY() { return this.y + PADDING; }
		int innerWidth() { return Math.max(1, this.width - PADDING * 2); }
		int innerHeight() { return Math.max(1, this.height - PADDING * 2); }
	}

	record ActionMetrics(int y, int previewX, int previewWidth, int stopX, int stopWidth,
			int refreshX, int refreshWidth, int cancelX, int cancelWidth, int saveX, int saveWidth) {}
}
