package nonamecrackers2.mobbattlemusic.client.gui;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;

/**
 * K9-2: the standalone Conditions inspector. Edits one entry's full condition
 * list with AND/OR joins and per-condition live testing against the current
 * environment (every row shows matched/mismatched plus the current value as
 * the reason). LOCAL mode is fully editable; SERVER mode is read-only
 * diagnostics (the server-side store does not support OR joins).
 */
public class ConditionInspectorScreen extends Screen
{
	private static final int ROW_HEIGHT = 22;
	private static final int LIST_TOP = 52;

	private final Screen parent;
	private final MusicTracksManager.DynamicBinding binding;
	private final int entryIndex;
	private final String entryLabel;
	private final boolean serverMode;

	private final List<IdleCondition> conditions = new ArrayList<>();
	// K11-A: diagnostics are resolved asynchronously for structure conditions
	// (integrated server thread); missing entries render as "checking..."
	private final java.util.Map<Integer, IdleConditionRegistry.MatchResult> diagnostics =
			new java.util.HashMap<>();
	private int scrollOffset;
	private int listLeft;
	private int listWidth;
	private int listHeight;

	private Button joinToggleButton;
	private Button invertToggleButton;
	private Button addButton;
	private Button updateButton;
	private Button testButton;
	private Button cancelButton;
	private EditBox typeBox;
	private EditBox argumentBox;
	private IdleCondition.Join pendingJoin = IdleCondition.Join.AND;
	private boolean pendingInverted;
	private int selectedConditionIndex = -1;
	private String statusMessage = "";

	public ConditionInspectorScreen(Screen parent, MusicTracksManager.DynamicBinding binding, int entryIndex,
			String entryLabel, List<IdleCondition> conditions, boolean serverMode)
	{
		super(Component.literal("Conditions Inspector"));
		this.parent = parent;
		this.binding = binding;
		this.entryIndex = entryIndex;
		this.entryLabel = entryLabel;
		this.serverMode = serverMode;
		this.conditions.addAll(conditions);
	}

	@Override
	protected void init()
	{
		int centerX = this.width / 2;
		int panelWidth = Math.min(620, this.width - 40);
		this.listLeft = centerX - panelWidth / 2;
		this.listWidth = panelWidth;
		this.listHeight = Math.min(14 * ROW_HEIGHT, this.height - 200);

		this.typeBox = new EditBox(this.font, this.listLeft + 4, LIST_TOP + this.listHeight + 6, 140, 20,
				Component.literal("type"));
		this.typeBox.setHint(Component.literal("dimension|biome|structure|underwater|entity|scene"));
		this.typeBox.setValue("dimension");
		this.addRenderableWidget(this.typeBox);
		this.argumentBox = new EditBox(this.font, this.listLeft + 150, LIST_TOP + this.listHeight + 6, 150, 20,
				Component.literal("argument"));
		this.argumentBox.setHint(Component.literal("argument (e.g. the_end)"));
		this.addRenderableWidget(this.argumentBox);

		this.joinToggleButton = this.addRenderableWidget(Button.builder(
				Component.literal("Join: AND"), button -> toggleJoin())
				.bounds(this.listLeft + 306, LIST_TOP + this.listHeight + 6, 86, 20)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(
						Component.literal("How this condition joins the previous one (OR not supported on server)")))
				.build());
		this.invertToggleButton = this.addRenderableWidget(Button.builder(
				Component.literal("Invert: no"), button -> toggleInvert())
				.bounds(this.listLeft + 398, LIST_TOP + this.listHeight + 6, 86, 20).build());
		this.addButton = this.addRenderableWidget(Button.builder(
				Component.literal("Add"), button -> addCondition())
				.bounds(this.listLeft + 490, LIST_TOP + this.listHeight + 6, 60, 20).build());
		this.updateButton = this.addRenderableWidget(Button.builder(
				Component.literal("Update selected"), button -> updateCondition())
				.bounds(this.listLeft + 556, LIST_TOP + this.listHeight + 6, 60, 20)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(
						Component.literal("Click a row to load it into the editor; update replaces it")))
				.build());
		this.testButton = this.addRenderableWidget(Button.builder(
				Component.literal("Test current environment"), button -> runDiagnostics())
				.bounds(this.listLeft + 4, LIST_TOP + this.listHeight + 32, 200, 20).build());
		this.cancelButton = this.addRenderableWidget(Button.builder(
				Component.literal("Back"), button -> saveAndClose())
				.bounds(this.listLeft + this.listWidth - 160, LIST_TOP + this.listHeight + 32, 80, 20).build());
		this.updateEditorState();
		this.runDiagnostics();
	}

	private void toggleJoin()
	{
		this.pendingJoin = this.pendingJoin == IdleCondition.Join.AND ? IdleCondition.Join.OR : IdleCondition.Join.AND;
		this.updateEditorState();
	}

	private void toggleInvert()
	{
		this.pendingInverted = !this.pendingInverted;
		this.updateEditorState();
	}

	private void updateEditorState()
	{
		String joinLabel = this.pendingJoin == IdleCondition.Join.OR ? "Join: OR" : "Join: AND";
		this.joinToggleButton.setMessage(Component.literal(joinLabel));
		this.joinToggleButton.active = !this.serverMode;
		String invertLabel = this.pendingInverted ? "Invert: yes" : "Invert: no";
		this.invertToggleButton.setMessage(Component.literal(invertLabel));
		this.invertToggleButton.active = !this.serverMode;
		this.addButton.active = !this.serverMode;
		this.updateButton.active = !this.serverMode && this.selectedConditionIndex >= 0;
	}

	private void addCondition()
	{
		String type = this.typeBox.getValue().trim();
		String argument = this.argumentBox.getValue().trim();
		if (type.isEmpty() || !IdleConditionRegistry.isRegistered(type)) {
			this.statusMessage = "Unknown condition type: " + type;
			return;
		}
		// K11-A: save the canonical id (bare ids normalize to
		// mobbattlemusic:...) so runtime lookup always matches
		ResourceLocation canonical = IdleConditionRegistry.normalizeId(type);
		this.conditions.add(new IdleCondition(canonical == null ? type : canonical.toString(),
				argument, this.pendingInverted, this.pendingJoin));
		this.statusMessage = "Added condition #" + this.conditions.size();
		this.runDiagnostics();
	}

	// K10-A: load a row into the editor (inline edit of join/inverted/type/argument)
	private void selectCondition(int index)
	{
		if (index < 0 || index >= this.conditions.size())
			return;
		IdleCondition condition = this.conditions.get(index);
		this.selectedConditionIndex = index;
		this.typeBox.setValue(condition.type());
		this.argumentBox.setValue(condition.argument());
		this.pendingJoin = condition.join();
		this.pendingInverted = condition.inverted();
		this.updateEditorState();
	}

	private void updateCondition()
	{
		if (this.selectedConditionIndex < 0 || this.selectedConditionIndex >= this.conditions.size()) {
			this.statusMessage = "Select a condition row first";
			return;
		}
		String type = this.typeBox.getValue().trim();
		if (type.isEmpty() || !IdleConditionRegistry.isRegistered(type)) {
			this.statusMessage = "Unknown condition type: " + type;
			return;
		}
		// K11-A: canonicalize on update as well
		ResourceLocation canonical = IdleConditionRegistry.normalizeId(type);
		IdleCondition updated = new IdleCondition(canonical == null ? type : canonical.toString(),
				this.argumentBox.getValue().trim(), this.pendingInverted, this.pendingJoin);
		this.conditions.set(this.selectedConditionIndex, updated);
		this.statusMessage = "Updated condition #" + (this.selectedConditionIndex + 1);
		this.runDiagnostics();
	}

	private void deleteCondition(int index)
	{
		if (index < 0 || index >= this.conditions.size())
			return;
		this.conditions.remove(index);
		if (this.selectedConditionIndex == index)
			this.selectedConditionIndex = -1;
		else if (this.selectedConditionIndex > index)
			this.selectedConditionIndex--;
		this.statusMessage = "Deleted condition #" + (index + 1);
		this.runDiagnostics();
	}

	private void runDiagnostics()
	{
		this.diagnostics.clear();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			for (int i = 0; i < this.conditions.size(); i++)
				this.diagnostics.put(i, new IdleConditionRegistry.MatchResult(false, "no player in world"));
			return;
		}
		for (int i = 0; i < this.conditions.size(); i++) {
			IdleCondition condition = this.conditions.get(i);
			// K11-A: structure checks run on the integrated server thread and
			// come back on the main thread; everything else resolves inline
			final int index = i;
			ClientConditionDiagnostics.diagnoseAsync(mc.player, condition)
					.thenAcceptAsync(result -> this.diagnostics.put(index, result), mc);
		}
	}

	private void saveAndClose()
	{
		if (!this.serverMode) {
			MusicTracksManager.PlaylistControlResult result = MusicTracksManager.getInstance()
					.replaceLocalEntryConditions(this.binding, this.entryIndex, this.conditions);
			this.statusMessage = result.message();
			if (result.success() && this.parent instanceof MusicPlaylistScreen screen)
				screen.refreshAfterImport();
		}
		Minecraft.getInstance().setScreen(this.parent);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
	{
		this.renderBackground(graphics);
		graphics.drawCenteredString(this.font, "Conditions Inspector - " + this.entryLabel, this.width / 2, 12, 0xFFFFFF);
		String mode = this.serverMode ? "SERVER (read-only; server has no OR joins)" : "LOCAL (editable)";
		graphics.drawCenteredString(this.font, mode, this.width / 2, 26, this.serverMode ? 0xFFE06C75 : 0xFF73D98A);
		graphics.drawString(this.font, "binding=" + this.binding.storageKey() + " entry=#" + (this.entryIndex + 1),
				this.listLeft, LIST_TOP - 12, 0xFF8B929C);
		int visible = this.listHeight / ROW_HEIGHT;
		if (this.scrollOffset > Math.max(0, this.conditions.size() - visible))
			this.scrollOffset = Math.max(0, this.conditions.size() - visible);
		for (int i = 0; i < visible && this.scrollOffset + i < this.conditions.size(); i++) {
			int rowIndex = this.scrollOffset + i;
			IdleCondition condition = this.conditions.get(rowIndex);
			int y = LIST_TOP + i * ROW_HEIGHT;
			int rowColor = rowIndex == this.selectedConditionIndex ? 0xFF293B4A : 0xFF1E2329;
			graphics.fill(this.listLeft, y, this.listLeft + this.listWidth, y + ROW_HEIGHT - 2, rowColor);
			String join = rowIndex > 0 ? (condition.join() == IdleCondition.Join.OR ? "OR  " : "AND ") : "";
			String inv = condition.inverted() ? " NOT" : "";
			String text = join + condition.type() + inv +
					(condition.argument().isBlank() ? "" : "  [" + condition.argument() + "]");
			graphics.drawString(this.font, text, this.listLeft + 4, y + 4, 0xFFD6D9DE);
			IdleConditionRegistry.MatchResult diagnostic = this.diagnostics.get(rowIndex);
			if (diagnostic != null) {
				boolean matched = condition.inverted() ? !diagnostic.matched() : diagnostic.matched();
				String state = matched ? "\u2713 " : "\u2717 ";
				graphics.drawString(this.font, state + (diagnostic.reason() == null ? "" : diagnostic.reason()),
						this.listLeft + 220, y + 4, matched ? 0xFF73D98A : 0xFFE06C75);
			} else {
				// K11-A: async structure check still in flight
				graphics.drawString(this.font, "checking...", this.listLeft + 220, y + 4, 0xFF8B929C);
			}
			if (!this.serverMode)
				graphics.drawString(this.font, "x", this.listLeft + this.listWidth - 10, y + 4, 0xFFE06C75);
		}
		boolean fallback = this.conditions.isEmpty();
		graphics.drawString(this.font, fallback
				? "fallback: no conditions - this entry always matches"
				: "entry priority/fallback role: conditions define match; empty = fallback",
				this.listLeft, LIST_TOP + this.listHeight + 56, fallback ? 0xFF73D98A : 0xFF8B929C);
		if (!this.statusMessage.isEmpty())
			graphics.drawString(this.font, this.statusMessage, this.listLeft, LIST_TOP + this.listHeight + 68, 0xFF8B929C);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		if (button == 0 && !this.serverMode
				&& mouseX >= this.listLeft && mouseX < this.listLeft + this.listWidth
				&& mouseY >= LIST_TOP && mouseY < LIST_TOP + this.listHeight) {
			int row = (int) ((mouseY - LIST_TOP) / ROW_HEIGHT) + this.scrollOffset;
			if (row >= 0 && row < this.conditions.size()) {
				if (mouseX >= this.listLeft + this.listWidth - 24)
					this.deleteCondition(row);
				else
					this.selectCondition(row);
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta)
	{
		int visible = this.listHeight / ROW_HEIGHT;
		this.scrollOffset = Math.max(0, Math.min(this.scrollOffset - (int) Math.signum(delta),
				Math.max(0, this.conditions.size() - visible)));
		return true;
	}

	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
}
