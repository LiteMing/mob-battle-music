package nonamecrackers2.mobbattlemusic.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;

/** Compact two-line list with stable selection, status and hover actions. */
final class PlaylistSelectionList<T> extends ObjectSelectionList<PlaylistSelectionList<T>.Entry>
{
	static final int ROW_HEIGHT = 24;

	enum Status
	{
		PLAYING("\u25b6", 0xFF73D98A, "status.playing"),
		MATCHED("\u25cf", 0xFF73D98A, "status.matched"),
		UNMATCHED("\u25cb", 0xFF8B929C, "status.unmatched"),
		DISABLED("\u2298", 0xFFE06C75, "status.disabled");

		private final String icon;
		private final int color;
		private final String translation;

		Status(String icon, int color, String translation)
		{
			this.icon = icon;
			this.color = color;
			this.translation = translation;
		}
	}

	record Model<T>(String key, T value, Component title, Component subtitle, Status status,
			boolean canToggle, boolean canDelete, boolean checked)
	{
		Model
		{
			Objects.requireNonNull(key);
			Objects.requireNonNull(title);
			Objects.requireNonNull(subtitle);
			Objects.requireNonNull(status);
		}

		Model(String key, T value, Component title, Component subtitle, Status status,
				boolean canToggle, boolean canDelete)
		{
			this(key, value, title, subtitle, status, canToggle, canDelete, false);
		}
	}

	private final Font font;
	private final Consumer<T> onSelected;
	private final Consumer<T> onToggle;
	private final Consumer<T> onDelete;
	private final Consumer<Move<T>> onMove;
	private int rowWidth;
	private int dragFrom = -1;
	private double dragStartY;
	private int hoveredIndex = -1;
	private boolean hoveredTitleTruncated;
	private boolean hoveredSubtitleTruncated;
	private boolean visible = true;

	PlaylistSelectionList(Minecraft minecraft, Font font, int width, int height, int top, int bottom,
			Consumer<T> onSelected, Consumer<T> onToggle, Consumer<T> onDelete, Consumer<Move<T>> onMove)
	{
		super(minecraft, width, height, top, bottom, ROW_HEIGHT);
		this.font = font;
		this.onSelected = onSelected;
		this.onToggle = onToggle;
		this.onDelete = onDelete;
		this.onMove = onMove;
		this.rowWidth = Math.max(1, width - 8);
		this.setRenderBackground(false);
		this.setRenderTopAndBottom(false);
		this.setRenderSelection(true);
		this.centerListVertically = false;
	}

	void setBounds(PlaylistScreenLayout.Rect bounds)
	{
		this.updateSize(bounds.width(), this.minecraft.getWindow().getGuiScaledHeight(), bounds.y(), bounds.bottom());
		this.setLeftPos(bounds.x());
		this.rowWidth = Math.max(1, bounds.width() - 8);
	}

	void setVisible(boolean visible)
	{
		this.visible = visible;
	}

	void setItems(List<Model<T>> models, @Nullable String selectedKey)
	{
		double oldScroll = this.getScrollAmount();
		List<Entry> entries = new ArrayList<>(models.size());
		for (Model<T> model : models)
			entries.add(new Entry(model));
		this.replaceEntries(entries);
		if (selectedKey != null) {
			for (Entry entry : this.children()) {
				if (selectedKey.equals(entry.model.key())) {
					this.setSelected(entry);
					break;
				}
			}
		}
		this.setScrollAmount(oldScroll);
	}

	void selectIndex(int index, boolean notify)
	{
		if (index < 0 || index >= this.children().size()) {
			this.setSelected(null);
			return;
		}
		Entry entry = this.children().get(index);
		this.setSelected(entry);
		this.ensureVisible(entry);
		if (notify)
			this.onSelected.accept(entry.model.value());
	}

	int selectedIndex()
	{
		return this.getSelected() == null ? -1 : this.children().indexOf(this.getSelected());
	}

	@Nullable
	T selectedValue()
	{
		return this.getSelected() == null ? null : this.getSelected().model.value();
	}

	boolean moveSelected(int delta)
	{
		int from = selectedIndex();
		int to = from + delta;
		if (from < 0 || to < 0 || to >= this.children().size())
			return false;
		this.onMove.accept(new Move<>(this.children().get(from).model.value(), from, to));
		return true;
	}

	@Nullable
	Component tooltipAt(double mouseX, double mouseY)
	{
		if (this.hoveredIndex < 0 || this.hoveredIndex >= this.children().size())
			return null;
		Entry entry = this.children().get(this.hoveredIndex);
		int right = this.getRowRight();
		if (mouseX >= right - 13 && entry.model.canDelete())
			return text("action.delete");
		if (mouseX >= right - 27 && entry.model.canToggle())
			return text(entry.model.status() == Status.DISABLED ? "action.enable" : "action.disable");
		if (this.hoveredTitleTruncated)
			return entry.model.title();
		if (this.hoveredSubtitleTruncated)
			return entry.model.subtitle();
		return text(entry.model.status().translation);
	}

	@Override
	public int getRowWidth()
	{
		return this.rowWidth;
	}

	@Override
	protected int getScrollbarPosition()
	{
		return this.getRight() - 6;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
	{
		if (!this.visible)
			return;
		this.hoveredIndex = -1;
		this.hoveredTitleTruncated = false;
		this.hoveredSubtitleTruncated = false;
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY)
	{
		return this.visible && super.isMouseOver(mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		return this.visible && super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta)
	{
		return this.visible && super.mouseScrolled(mouseX, mouseY, delta);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY)
	{
		if (button == 0 && this.dragFrom >= 0) {
			if (Math.abs(mouseY - this.dragStartY) < 4.0D)
				return true;
			Entry target = this.getEntryAtPosition(mouseX, mouseY);
			if (target != null) {
				int to = this.children().indexOf(target);
				if (to != this.dragFrom) {
					Entry source = this.children().get(this.dragFrom);
					this.onMove.accept(new Move<>(source.model.value(), this.dragFrom, to));
					this.dragFrom = -1;
				}
				return true;
			}
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button)
	{
		this.dragFrom = -1;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	final class Entry extends ObjectSelectionList.Entry<Entry>
	{
		private final Model<T> model;

		private Entry(Model<T> model)
		{
			this.model = model;
		}

		@Override
		public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
				int mouseX, int mouseY, boolean hovered, float partialTick)
		{
		if (hovered)
			graphics.fill(left, top - 1, left + width, top + height + 1, 0xFF252A30);
		int textLeft = left + 15;
		int actionSpace = hovered && (this.model.canToggle() || this.model.canDelete()) ? 31 : 5;
		int textWidth = Math.max(1, width - 15 - actionSpace);
		String title = ellipsize(this.model.title().getString(), textWidth);
		String subtitle = ellipsize(this.model.subtitle().getString(), textWidth);
		// K13-A: batch-mode check mark replaces the status icon for checked rows
		String icon = this.model.checked ? "\u2713" : this.model.status().icon;
		int iconColor = this.model.checked ? 0xFFE0C36E : this.model.status().color;
		graphics.drawString(font, icon, left + 3, top + 5, iconColor, false);
			graphics.drawString(font, title, textLeft, top + 2,
					this.model.status() == Status.DISABLED ? 0xFF8B929C : 0xFFF0F1F2, false);
			graphics.drawString(font, subtitle, textLeft, top + 13, 0xFF929AA5, false);
			if (hovered) {
				hoveredIndex = index;
				hoveredTitleTruncated = !title.equals(this.model.title().getString());
				hoveredSubtitleTruncated = !subtitle.equals(this.model.subtitle().getString());
				if (this.model.canToggle())
					graphics.drawString(font, this.model.status() == Status.DISABLED ? "+" : "-",
							left + width - 25, top + 7, 0xFFE0C36E, false);
				if (this.model.canDelete())
					graphics.drawString(font, "x", left + width - 11, top + 7, 0xFFE06C75, false);
			}
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button)
		{
			if (button != 0)
				return false;
			int right = PlaylistSelectionList.this.getRowRight();
			if (mouseX >= right - 13 && this.model.canDelete()) {
				onDelete.accept(this.model.value());
				return true;
			}
			if (mouseX >= right - 27 && this.model.canToggle()) {
				onToggle.accept(this.model.value());
				return true;
			}
			PlaylistSelectionList.this.setSelected(this);
			dragFrom = PlaylistSelectionList.this.children().indexOf(this);
			dragStartY = mouseY;
			onSelected.accept(this.model.value());
			return true;
		}

		@Override
		public Component getNarration()
		{
			return Component.translatable("gui.mobbattlemusic.playlist.list.narration",
					this.model.title(), this.model.subtitle(), text(this.model.status().translation));
		}
	}

	private String ellipsize(String value, int width)
	{
		if (this.font.width(value) <= width)
			return value;
		String ellipsis = "...";
		return this.font.plainSubstrByWidth(value, Math.max(0, width - this.font.width(ellipsis))) + ellipsis;
	}

	private static Component text(String path)
	{
		return Component.translatable("gui.mobbattlemusic.playlist." + path);
	}

	record Move<T>(T value, int from, int to) {}
}
