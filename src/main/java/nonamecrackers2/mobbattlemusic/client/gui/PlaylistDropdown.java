package nonamecrackers2.mobbattlemusic.client.gui;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

final class PlaylistDropdown
{
	static final float POPUP_Z = 300.0F;

	enum Mode
	{
		FIXED,
		FILTERED
	}

	record Item(String id, Component label, Component detail, boolean muted)
	{
		Item
		{
			id = Objects.requireNonNullElse(id, "");
			label = Objects.requireNonNullElse(label, Component.empty());
			detail = Objects.requireNonNullElse(detail, Component.empty());
		}

		Item(String id, Component label)
		{
			this(id, label, Component.empty(), false);
		}
	}

	private final Mode mode;
	private final int rowHeight;
	private final int maxVisibleRows;
	private List<Item> items = List.of();
	private List<Item> matches = List.of();
	private String filter = "";
	private String selectedId = "";
	private boolean open;
	private int highlightedIndex;
	private int firstVisible;
	private int x;
	private int anchorY;
	private int width;
	private int maxBottom;

	PlaylistDropdown(Mode mode, int rowHeight, int maxVisibleRows)
	{
		this.mode = Objects.requireNonNull(mode);
		this.rowHeight = Math.max(12, rowHeight);
		this.maxVisibleRows = Math.max(1, maxVisibleRows);
	}

	void setBounds(int x, int anchorY, int width, int maxBottom)
	{
		this.x = x;
		this.anchorY = anchorY;
		this.width = Math.max(24, width);
		this.maxBottom = Math.max(anchorY, maxBottom);
	}

	void setItems(List<Item> items)
	{
		String highlightedId = this.highlightedItem() == null ? "" : this.highlightedItem().id();
		this.items = items == null ? List.of() : List.copyOf(items);
		this.rebuildMatches(highlightedId);
	}

	void setFilter(String filter)
	{
		if (this.mode != Mode.FILTERED)
			return;
		String next = Objects.requireNonNullElse(filter, "").trim();
		if (!this.filter.equals(next)) {
			this.filter = next;
			this.rebuildMatches("");
		}
	}

	void open(String selectedId)
	{
		this.selectedId = Objects.requireNonNullElse(selectedId, "");
		this.highlightedIndex = indexOf(this.matches, this.selectedId);
		if (this.highlightedIndex < 0)
			this.highlightedIndex = 0;
		this.firstVisible = Math.max(0, this.highlightedIndex - this.maxVisibleRows + 1);
		this.open = !this.matches.isEmpty();
	}

	void close()
	{
		this.open = false;
	}

	boolean isOpen()
	{
		return this.open;
	}

	Item highlightedItem()
	{
		return this.highlightedIndex >= 0 && this.highlightedIndex < this.matches.size()
				? this.matches.get(this.highlightedIndex) : null;
	}

	Item itemAt(double mouseX, double mouseY)
	{
		if (!this.open || mouseX < this.x || mouseX >= this.x + this.width ||
				mouseY < this.menuY() || mouseY >= this.menuY() + this.menuHeight())
			return null;
		int index = this.firstVisible + (int)((mouseY - this.menuY()) / this.rowHeight);
		return index >= 0 && index < this.matches.size() ? this.matches.get(index) : null;
	}

	void move(int delta)
	{
		if (!this.open || this.matches.isEmpty() || delta == 0)
			return;
		this.highlightedIndex = Math.floorMod(this.highlightedIndex + delta, this.matches.size());
		this.keepHighlightVisible();
	}

	boolean mouseScrolled(double amount)
	{
		if (!this.open)
			return false;
		int visibleRows = this.visibleRowCount();
		if (this.matches.size() > visibleRows) {
			int maxFirst = Math.max(0, this.matches.size() - visibleRows);
			this.firstVisible = clamp(this.firstVisible + (amount < 0.0D ? 1 : -1), 0, maxFirst);
			this.highlightedIndex = clamp(this.highlightedIndex, this.firstVisible,
					Math.min(this.matches.size() - 1, this.firstVisible + visibleRows - 1));
		}
		return true;
	}

	void render(GuiGraphics graphics, Font font, int mouseX, int mouseY)
	{
		if (!this.open || this.matches.isEmpty())
			return;
		this.keepHighlightVisible();
		int menuY = this.menuY();
		int menuHeight = this.menuHeight();
		graphics.pose().pushPose();
		graphics.pose().translate(0.0F, 0.0F, POPUP_Z);
		graphics.fill(this.x, menuY, this.x + this.width, menuY + menuHeight, 0xFF101318);
		drawBorder(graphics, this.x, menuY, this.width, menuHeight, 0xFF78B9D1);
		Item hovered = this.itemAt(mouseX, mouseY);
		for (int row = 0; row < this.visibleRowCount(); row++) {
			int index = this.firstVisible + row;
			if (index >= this.matches.size())
				break;
			Item item = this.matches.get(index);
			int rowY = menuY + row * this.rowHeight;
			if (item == hovered)
				graphics.fill(this.x + 1, rowY + 1, this.x + this.width - 1, rowY + this.rowHeight, 0xFF465A64);
			else if (index == this.highlightedIndex)
				graphics.fill(this.x + 1, rowY + 1, this.x + this.width - 1, rowY + this.rowHeight, 0xFF27343B);
			if (item.id().equals(this.selectedId))
				graphics.fill(this.x + 2, rowY + 3, this.x + 4, rowY + this.rowHeight - 2, 0xFF78B9D1);
			int detailWidth = item.detail().getString().isBlank() ? 0 : font.width(item.detail()) + 6;
			String label = font.plainSubstrByWidth(item.label().getString(), Math.max(8, this.width - 13 - detailWidth));
			graphics.drawString(font, label, this.x + 8, rowY + 4, item.muted() ? 0x8E98A6 : 0xE6E8EB, false);
			if (detailWidth > 0) {
				String detail = font.plainSubstrByWidth(item.detail().getString(), Math.max(8, this.width / 2));
				graphics.drawString(font, detail, this.x + this.width - font.width(detail) - 5, rowY + 4,
						0x8E98A6, false);
			}
		}
		graphics.pose().popPose();
	}

	private void rebuildMatches(String preferredId)
	{
		String normalized = this.filter.toLowerCase(Locale.ROOT);
		this.matches = this.items.stream().filter(item -> normalized.isEmpty() ||
				item.label().getString().toLowerCase(Locale.ROOT).startsWith(normalized) ||
				item.id().toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
		this.highlightedIndex = indexOf(this.matches, preferredId);
		if (this.highlightedIndex < 0)
			this.highlightedIndex = indexOf(this.matches, this.selectedId);
		if (this.highlightedIndex < 0)
			this.highlightedIndex = 0;
		this.firstVisible = 0;
		if (this.matches.isEmpty())
			this.open = false;
	}

	private void keepHighlightVisible()
	{
		int rows = this.visibleRowCount();
		int maxFirst = Math.max(0, this.matches.size() - rows);
		if (this.highlightedIndex < this.firstVisible)
			this.firstVisible = this.highlightedIndex;
		if (this.highlightedIndex >= this.firstVisible + rows)
			this.firstVisible = this.highlightedIndex - rows + 1;
		this.firstVisible = clamp(this.firstVisible, 0, maxFirst);
	}

	private int visibleRowCount()
	{
		return Math.min(this.maxVisibleRows, this.matches.size());
	}

	private int menuHeight()
	{
		return this.visibleRowCount() * this.rowHeight;
	}

	private int menuY()
	{
		int height = this.menuHeight();
		return this.anchorY + height <= this.maxBottom ? this.anchorY : Math.max(8, this.maxBottom - height);
	}

	private static int indexOf(List<Item> items, String id)
	{
		if (id == null || id.isBlank())
			return -1;
		for (int i = 0; i < items.size(); i++) {
			if (id.equals(items.get(i).id()))
				return i;
		}
		return -1;
	}

	private static void drawBorder(GuiGraphics graphics, int x, int y, int width, int height, int color)
	{
		graphics.fill(x, y, x + width, y + 1, color);
		graphics.fill(x, y + height - 1, x + width, y + height, color);
		graphics.fill(x, y, x + 1, y + height, color);
		graphics.fill(x + width - 1, y, x + width, y + height, color);
	}

	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}
}
