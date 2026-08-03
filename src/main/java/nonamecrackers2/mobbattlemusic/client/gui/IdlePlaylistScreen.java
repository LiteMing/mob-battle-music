package nonamecrackers2.mobbattlemusic.client.gui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.audio.MbmSessionState;
import nonamecrackers2.mobbattlemusic.client.audio.PreviewChannel;
import nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler;
import nonamecrackers2.mobbattlemusic.client.music.IdleConditionStateClient;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadata;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadataCache;
import nonamecrackers2.mobbattlemusic.client.music.TimelineMarkerStore;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;

final class IdlePlaylistScreen extends Screen
{
	private static final Map<MusicPlaylistScreen.EditMode, State> STATES =
			new EnumMap<>(MusicPlaylistScreen.EditMode.class);

	static {
		STATES.put(MusicPlaylistScreen.EditMode.LOCAL, new State());
		STATES.put(MusicPlaylistScreen.EditMode.SERVER, new State());
	}

	private final Screen parent;
	private final MusicPlaylistScreen.EditMode editMode;
	private final List<Rule> rules = new ArrayList<>();
	private PlaylistScreenLayout layout;
	private PlaylistSelectionList<Rule> ruleList;
	private PlaylistSelectionList<Integer> trackList;
	private InspectorPage inspectorPage = InspectorPage.DETAILS;
	private boolean draftDirty;
	private final PlaylistDropdown orderDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 4);
	private final PlaylistDropdown conditionTypeDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 8);
	private final PlaylistDropdown argumentDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FILTERED, 18, 8);
	private final PlaylistDropdown modeDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 2);
	private Button modeButton;
	private Button orderButton;
	private Button previewButton;
	private Button stopButton;
	private Button refreshButton;
	private Button conditionTypeButton;
	private Button conditionInvertButton;
	private Button conditionAddButton;
	private Button conditionDeleteButton;
	private Button addTrackButton;
	private Button inspectorDetailsButton;
	private Button inspectorBindingButton;
	private Button inspectorConditionsButton;
	private Button saveButton;
	private Button cancelButton;
	private EditBox priorityBox;
	private EditBox intervalBox;
	private EditBox conditionArgumentBox;
	private EditBox ruleBox;
	private int selectedRule;
	private int selectedTrack;
	private int selectedCondition;
	private int syncRefreshCooldown;
	private String conditionType;
	private boolean conditionInverted;
	private String lastPreviewUrl = "";

	private enum InspectorPage
	{
		DETAILS,
		BINDING,
		CONDITIONS
	}

	IdlePlaylistScreen(Screen parent, MusicPlaylistScreen.EditMode editMode)
	{
		super(text("idle.title"));
		this.parent = parent;
		this.editMode = editMode;
	}

	static void refreshIfOpen()
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof IdlePlaylistScreen screen)
			screen.refreshRules();
	}

	@Override
	protected void init()
	{
		super.init();
		this.layout = PlaylistScreenLayout.calculate(this.width, this.height);
		State state = state();
		this.selectedRule = state.selectedRule;
		this.selectedTrack = state.selectedTrack;
		this.selectedCondition = state.selectedCondition;
		this.conditionType = state.conditionType;
		this.conditionInverted = state.conditionInverted;
		this.rebuildRules();

		for (Button tab : PlaylistTabs.create(this.layout.tabBar(), PlaylistTabs.Tab.IDLE, this::navigateTo))
			this.addRenderableWidget(tab);
		this.modeButton = this.addRenderableWidget(Button.builder(modeLabel(), button -> toggleModeDropdown())
				.bounds(this.layout.titleBar().right() - 106, this.layout.titleBar().y(), 100, 20).build());
		this.modeButton.active = this.editMode == MusicPlaylistScreen.EditMode.SERVER || canEditServer();
		this.modeButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
				this.modeButton.active ? text("mode.tooltip") : text("mode.no_permission")));

		PlaylistScreenLayout.Rect sidebar = this.layout.sidebar();
		PlaylistScreenLayout.Rect main = this.layout.main();
		PlaylistScreenLayout.Rect inspector = this.layout.inspector();
		int ix = inspector.innerX();
		int iy = inspector.innerY();
		int iw = inspector.innerWidth();
		int half = Math.max(24, (iw - PlaylistScreenLayout.GAP) / 2);
		int inspectorTabWidth = Math.max(24, (iw - PlaylistScreenLayout.GAP * 2) / 3);
		this.inspectorDetailsButton = this.addRenderableWidget(Button.builder(text("inspector.details"), b -> selectInspectorPage(InspectorPage.DETAILS))
				.bounds(ix, iy, inspectorTabWidth, 18).build());
		this.inspectorBindingButton = this.addRenderableWidget(Button.builder(text("inspector.binding"), b -> selectInspectorPage(InspectorPage.BINDING))
				.bounds(ix + inspectorTabWidth + PlaylistScreenLayout.GAP, iy, inspectorTabWidth, 18).build());
		this.inspectorConditionsButton = this.addRenderableWidget(Button.builder(text("inspector.conditions"), b -> selectInspectorPage(InspectorPage.CONDITIONS))
				.bounds(ix + (inspectorTabWidth + PlaylistScreenLayout.GAP) * 2, iy,
						Math.max(24, iw - (inspectorTabWidth + PlaylistScreenLayout.GAP) * 2), 18).build());
		this.inspectorDetailsButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("inspector.details")));
		this.inspectorBindingButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("inspector.binding")));
		this.inspectorConditionsButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("inspector.conditions")));
		this.orderButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> toggleOrderDropdown())
				.bounds(ix, detailActionY(), half, 20).build());
		this.priorityBox = this.addRenderableWidget(new EditBox(this.font, ix, detailFieldsY(), half, 18,
				text("field.priority")));
		this.priorityBox.setMaxLength(5);
		this.priorityBox.setFilter(value -> value.isEmpty() || value.equals("-") || value.matches("-?[0-9]{0,4}"));
		this.priorityBox.setHint(text("field.priority"));
		this.intervalBox = this.addRenderableWidget(new EditBox(this.font, ix + half + 4, detailFieldsY(), half, 18,
				text("field.interval")));
		this.intervalBox.setMaxLength(5);
		this.intervalBox.setFilter(value -> value.matches("[0-9]{0,5}"));
		this.intervalBox.setHint(text("field.interval"));
		PlaylistScreenLayout.ActionMetrics actions = this.layout.actionMetrics();
		this.previewButton = this.addRenderableWidget(Button.builder(text("button.preview"), button -> previewSelected())
				.bounds(actions.previewX(), actions.y(), actions.previewWidth(), 20).build());
		this.stopButton = this.addRenderableWidget(Button.builder(text("button.stop"), button -> stopPreview())
				.bounds(actions.stopX(), actions.y(), actions.stopWidth(), 20).build());
		this.refreshButton = this.addRenderableWidget(Button.builder(text("button.refresh"), button -> refreshRules())
				.bounds(actions.refreshX(), actions.y(), actions.refreshWidth(), 20).build());
		int editorY = conditionEditorY();
		this.conditionTypeButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> toggleConditionTypeDropdown())
				.bounds(ix, editorY, Math.max(36, (iw - 4) / 2), 20).build());
		int conditionArgumentWidth = Math.max(36, iw - this.conditionTypeButton.getWidth() - 4);
		this.conditionArgumentBox = this.addRenderableWidget(new EditBox(this.font,
				ix + this.conditionTypeButton.getWidth() + 4, editorY + 1, conditionArgumentWidth, 18,
				text("field.condition_argument")));
		this.conditionArgumentBox.setMaxLength(512);
		this.conditionArgumentBox.setHint(text("field.condition_argument"));
		this.conditionArgumentBox.setValue(state.conditionArgument);
		this.conditionArgumentBox.setResponder(value -> {
			state().conditionArgument = value;
			this.argumentDropdown.setFilter(value);
			this.updateButtonState();
		});
		int conditionActionY = editorY + 25;
		int actionWidth = Math.max(28, (iw - 12) / 4);
		this.conditionInvertButton = this.addRenderableWidget(Button.builder(invertLabel(), button -> toggleInverted())
				.bounds(ix, conditionActionY, actionWidth, 20).build());
		this.conditionAddButton = this.addRenderableWidget(Button.builder(text("button.condition_add"), button -> addCondition())
				.bounds(ix + actionWidth + 4, conditionActionY, actionWidth, 20).build());
		this.conditionDeleteButton = this.addRenderableWidget(Button.builder(text("button.condition_delete"), button -> deleteCondition())
				.bounds(ix + (actionWidth + 4) * 2, conditionActionY,
						Math.max(20, iw - (actionWidth + 4) * 2), 20).build());
		int ruleY = sidebar.innerY();
		this.ruleBox = this.addRenderableWidget(new EditBox(this.font, sidebar.innerX(), ruleY + 18, sidebar.innerWidth(), 18,
				text("idle.field.rule")));
		this.ruleBox.setMaxLength(128);
		this.ruleBox.setHint(text("idle.field.rule"));
		this.ruleBox.setValue(state.ruleId);
		this.ruleBox.setResponder(value -> {
			state().ruleId = value;
			this.addTrackButton.active = ResourceLocation.tryParse(value.trim()) != null;
		});
		this.addTrackButton = this.addRenderableWidget(Button.builder(text("idle.button.choose_track"), button -> addTrack())
				.bounds(sidebar.innerX(), ruleY + 41, sidebar.innerWidth(), 20).build());
		this.cancelButton = this.addRenderableWidget(Button.builder(text("button.cancel"), button -> requestClose())
				.bounds(actions.cancelX(), actions.y(), actions.cancelWidth(), 20).build());
		this.saveButton = this.addRenderableWidget(Button.builder(text("button.save"), button -> saveInspector())
				.bounds(actions.saveX(), actions.y(), actions.saveWidth(), 20).build());

		this.orderDropdown.setItems(List.of(
				orderItem(MusicTracksManager.ExternalSelectionMode.RANDOM),
				orderItem(MusicTracksManager.ExternalSelectionMode.SEQUENTIAL),
				orderItem(MusicTracksManager.ExternalSelectionMode.FIRST)));
		this.orderDropdown.setBounds(ix, detailActionY() + 20, iw, inspector.bottom());
		this.modeDropdown.setItems(List.of(
				new PlaylistDropdown.Item("local", text("mode.local")),
				new PlaylistDropdown.Item("server", text("mode.server"))));
		this.modeDropdown.setBounds(this.modeButton.getX(), this.modeButton.getY() + 20,
				this.modeButton.getWidth(), this.layout.frame().bottom());
		this.conditionTypeDropdown.setItems(IdleConditionStateClient.descriptors().stream()
				.map(descriptor -> new PlaylistDropdown.Item(descriptor.id().toString(),
						Component.literal(descriptor.displayName()), Component.literal(descriptor.id().toString()), false))
				.toList());
		this.conditionTypeDropdown.setBounds(ix, editorY + 20, iw, inspector.bottom());
		this.rebuildArgumentDropdown();
		this.argumentDropdown.setBounds(ix, editorY + 20, iw, inspector.bottom());
		this.argumentDropdown.setFilter(this.conditionArgumentBox.getValue());
		this.ruleList = this.addRenderableWidget(new PlaylistSelectionList<>(this.minecraft, this.font,
				sidebar.width(), this.height, sidebar.y() + 59, sidebar.bottom() - 6,
				this::selectRule, value -> {}, value -> {}, move -> {}));
		this.trackList = this.addRenderableWidget(new PlaylistSelectionList<>(this.minecraft, this.font,
				main.width(), this.height, main.y() + 22, main.bottom() - 6,
				this::selectTrack, this::toggleTrack, this::deleteTrackRow, this::moveTrack));
		this.ruleList.setBounds(new PlaylistScreenLayout.Rect(sidebar.x(), sidebar.y() + 82,
				sidebar.width(), Math.max(1, sidebar.height() - 88)));
		this.trackList.setBounds(new PlaylistScreenLayout.Rect(main.x(), main.y() + 22,
				main.width(), Math.max(1, main.height() - 56)));
		this.priorityBox.setResponder(value -> this.draftDirty = true);
		this.intervalBox.setResponder(value -> this.draftDirty = true);
		this.loadSelectedRule(true);
		this.refreshSelectionLists();
		this.updateButtonState();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
	{
		this.renderBackground(graphics);
		this.layout.render(graphics);
		int headerX = this.layout.titleBar().innerX();
		int headerRight = this.modeButton == null ? this.layout.titleBar().right() : this.modeButton.getX() - 6;
		String headerTitle = trimPixels(this.title.getString(), Math.max(60, Math.min(150, headerRight - headerX - 24)));
		graphics.drawString(this.font, headerTitle, headerX, this.layout.titleBar().y() + 6, 0xFFF0F1F2, false);
		Component summary = text("summary", this.rules.size(), this.rules.stream()
				.mapToInt(rule -> rule.playlist().entries().size()).sum());
		int summaryX = headerX + this.font.width(headerTitle) + 8;
		if (summaryX < headerRight - 20)
			graphics.drawString(this.font, trimPixels(summary.getString(), headerRight - summaryX), summaryX,
					this.layout.titleBar().y() + 6, 0xFF9AA2AD, false);
		graphics.drawString(this.font, text("idle.field.rule"), this.layout.sidebar().innerX(),
				this.layout.sidebar().y() + 8, 0xFF9AA2AD, false);
		graphics.drawString(this.font, text("section.rules"), this.layout.sidebar().innerX(),
				this.layout.sidebar().y() + 70, 0xFFB8C0CA, false);
		Rule rule = selectedRule();
		graphics.drawString(this.font, text("section.tracks"), this.layout.main().innerX(),
				this.layout.main().y() + 7, 0xFFB8C0CA, false);
		if (rule != null)
			graphics.drawString(this.font, trimPixels(rule.binding().target(), this.layout.main().innerWidth()),
					this.layout.main().innerX() + 54, this.layout.main().y() + 7, 0xFF9AA2AD, false);
		else
			graphics.drawString(this.font, text("idle.select_rule"), this.layout.main().innerX(),
					this.layout.main().innerY() + 24, 0xFF9AA2AD, false);
		Component legend = text("status.legend");
		int legendWidth = this.font.width(legend);
		if (this.layout.main().innerWidth() > legendWidth + 70)
			graphics.drawString(this.font, legend, this.layout.main().right() - legendWidth - 6,
					this.layout.main().y() + 7, 0xFF7F8791, false);
		this.renderInspector(graphics, mouseX, mouseY);
		this.renderPreviewProgress(graphics);
		super.render(graphics, mouseX, mouseY, partialTick);
		if (this.priorityBox != null && this.priorityBox.visible && parseInteger(this.priorityBox.getValue(), -1000, 1000) == null)
			graphics.renderOutline(this.priorityBox.getX() - 1, this.priorityBox.getY() - 1,
					this.priorityBox.getWidth() + 2, this.priorityBox.getHeight() + 2, 0xFFE06C75);
		if (this.intervalBox != null && this.intervalBox.visible && parseInteger(this.intervalBox.getValue(), 0, 86400) == null)
			graphics.renderOutline(this.intervalBox.getX() - 1, this.intervalBox.getY() - 1,
					this.intervalBox.getWidth() + 2, this.intervalBox.getHeight() + 2, 0xFFE06C75);
		this.orderDropdown.render(graphics, this.font, mouseX, mouseY);
		this.conditionTypeDropdown.render(graphics, this.font, mouseX, mouseY);
		this.argumentDropdown.render(graphics, this.font, mouseX, mouseY);
		this.modeDropdown.render(graphics, this.font, mouseX, mouseY);
		PlaylistSelectionList<?> active = mouseX < this.layout.main().x() ? this.ruleList : this.trackList;
		Component tooltip = active == null ? null : active.tooltipAt(mouseX, mouseY);
		if (tooltip != null)
			this.setTooltipForNextRenderPass(tooltip);
	}

	private void renderInspector(GuiGraphics graphics, int mouseX, int mouseY)
	{
		PlaylistScreenLayout.Rect inspector = this.layout.inspector();
		int x = inspector.innerX();
		int y = inspector.innerY();
		int width = inspector.innerWidth();
		Rule rule = selectedRule();
		if (rule == null) {
			graphics.drawString(this.font, text("idle.select_rule"), x, y + 28, 0xFF9AA2AD, false);
			return;
		}
		if (this.inspectorPage == InspectorPage.DETAILS) {
			graphics.drawString(this.font, trimPixels(rule.binding().target(), width), x, y + 24, 0xFFF0F1F2, false);
			graphics.drawString(this.font, text("idle.rule_summary", rule.playlist().entries().size(),
					isActive(rule) ? text("idle.active") : text("idle.inactive")), x, y + 38, 0xFFB8C0CA, false);
			Component source = text("detail.source_value", sourceMatchesMode(rule.source)
					? text("source." + rule.source.name().toLowerCase(Locale.ROOT)) : text("source.resource_pack"));
			graphics.drawString(this.font, trimPixels(source.getString(), width), x, y + 52, 0xFF9AA2AD, false);
			graphics.drawString(this.font, text("field.priority"), x, detailFieldsY() - 12, 0xFF9AA2AD, false);
			graphics.drawString(this.font, text("field.interval"), x + width / 2 + 2,
					detailFieldsY() - 12, 0xFF9AA2AD, false);
		} else if (this.inspectorPage == InspectorPage.BINDING) {
			graphics.drawString(this.font, text("binding.rule_key"), x, y + 24, 0xFF9AA2AD, false);
			graphics.drawString(this.font, trimPixels(rule.binding().serializedKey(), width), x, y + 40, 0xFFD6D9DE, false);
			graphics.drawString(this.font, text("binding.read_only"), x, y + 64, 0xFF9AA2AD, false);
			graphics.drawString(this.font, text("binding.help"), x, y + 86, 0xFF7F8791, false);
		} else {
			List<IdleCondition> conditions = selectedTrackConditions();
			int editorY = conditionEditorY();
			graphics.drawString(this.font, text("detail.conditions"), x, y + 22, 0xFFB8C0CA, false);
			int rowY = y + 34;
			int visible = Math.min(conditions.size(), Math.max(0, (editorY - rowY - 3) / 18));
			for (int i = 0; i < visible; i++) {
				IdleCondition condition = conditions.get(i);
				boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY + i * 18 && mouseY < rowY + i * 18 + 18;
				if (i == this.selectedCondition || hovered)
					graphics.fill(x, rowY + i * 18, x + width, rowY + i * 18 + 17,
						i == this.selectedCondition ? 0xFF293B4A : 0xFF252A30);
				String value = (condition.inverted() ? "NOT " : "") + condition.type();
				if (!condition.argument().isBlank())
					value += "  " + condition.argument();
				graphics.drawString(this.font, trimPixels(value, width - 14), x + 3, rowY + i * 18 + 4,
						0xFFD6D9DE, false);
				graphics.drawString(this.font, "x", x + width - 9, rowY + i * 18 + 4, 0xFFE06C75, false);
			}
			graphics.drawString(this.font, text("field.condition_type"), x, editorY - 12, 0xFF9AA2AD, false);
			graphics.drawString(this.font, text("field.condition_argument"), x + this.conditionTypeButton.getWidth() + 4,
					editorY - 12, 0xFF9AA2AD, false);
		}
	}

	private int detailFieldsY()
	{
		return this.layout.inspector().innerY() + 90;
	}

	private int detailActionY()
	{
		return this.layout.inspector().innerY() + 112;
	}

	private int conditionEditorY()
	{
		return Math.max(this.layout.inspector().innerY() + 70, this.layout.inspector().bottom() - 48);
	}

	private String trimPixels(String value, int width)
	{
		if (this.font.width(value) <= width)
			return value;
		String suffix = "...";
		return this.font.plainSubstrByWidth(value, Math.max(0, width - this.font.width(suffix))) + suffix;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		if (handleDropdownClick(this.modeDropdown, mouseX, mouseY, button, item -> selectMode(item.id())))
			return true;
		if (handleDropdownClick(this.orderDropdown, mouseX, mouseY, button, item -> selectOrder(item.id())) ||
				handleDropdownClick(this.conditionTypeDropdown, mouseX, mouseY, button,
						item -> selectConditionType(item.id())) ||
				handleDropdownClick(this.argumentDropdown, mouseX, mouseY, button,
						item -> selectArgument(item.id())))
			return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.conditionArgumentBox.isMouseOver(mouseX, mouseY))
			openArgumentDropdown();
		if (seekPreview(mouseX, mouseY))
			return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && clickConditionRow(mouseX, mouseY))
			return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private boolean clickConditionRow(double mouseX, double mouseY)
	{
		if (this.inspectorPage != InspectorPage.CONDITIONS || selectedRule() == null)
			return false;
		int x = this.layout.inspector().innerX();
		int y = this.layout.inspector().innerY() + 34;
		int width = this.layout.inspector().innerWidth();
		List<IdleCondition> conditions = selectedTrackConditions();
		int visible = Math.min(conditions.size(), Math.max(0, (conditionEditorY() - y - 3) / 18));
		for (int i = 0; i < visible; i++) {
			if (mouseX < x || mouseX >= x + width || mouseY < y + i * 18 || mouseY >= y + i * 18 + 18)
				continue;
			this.selectedCondition = i;
			if (mouseX >= x + width - 16)
				deleteCondition();
			else
				this.updateButtonState();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta)
	{
		if (this.modeDropdown.mouseScrolled(delta) || this.orderDropdown.mouseScrolled(delta) || this.conditionTypeDropdown.mouseScrolled(delta) ||
				this.argumentDropdown.mouseScrolled(delta))
			return true;
		return super.mouseScrolled(mouseX, mouseY, delta);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers)
	{
		if (handleDropdownKey(this.modeDropdown, keyCode, item -> selectMode(item.id())) ||
				handleDropdownKey(this.orderDropdown, keyCode, item -> selectOrder(item.id())) ||
				handleDropdownKey(this.conditionTypeDropdown, keyCode, item -> selectConditionType(item.id())) ||
				handleDropdownKey(this.argumentDropdown, keyCode, item -> selectArgument(item.id())))
			return true;
		if (keyCode == GLFW.GLFW_KEY_TAB && this.conditionArgumentBox.isFocused()) {
			openArgumentDropdown();
			this.argumentDropdown.move(0);
			PlaylistDropdown.Item item = this.argumentDropdown.highlightedItem();
			if (item != null)
				selectArgument(item.id());
			return true;
		}
		if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 && keyCode == GLFW.GLFW_KEY_UP)
			return this.trackList != null && this.trackList.moveSelected(-1);
		if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 && keyCode == GLFW.GLFW_KEY_DOWN)
			return this.trackList != null && this.trackList.moveSelected(1);
		if (keyCode == GLFW.GLFW_KEY_ESCAPE && this.draftDirty) {
			requestClose();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void tick()
	{
		super.tick();
		String previewUrl = java.util.Objects.toString(ExternalMusicHandler.getInstance().getPreviewUrl(), "");
		if (!previewUrl.equals(this.lastPreviewUrl)) {
			this.lastPreviewUrl = previewUrl;
			this.refreshSelectionLists();
		}
		if (this.syncRefreshCooldown > 0 && --this.syncRefreshCooldown == 0)
			MobBattleMusicNetwork.requestServerExternalPlaylistSync();
	}

	@Override
	public void onClose()
	{
		requestClose();
	}

	@Override
	public void removed()
	{
		saveState();
		super.removed();
	}

	private void rebuildRules()
	{
		String selectedId = selectedRule() == null ? "" : selectedRule().binding().serializedKey();
		this.rules.clear();
		MusicTracksManager manager = MusicTracksManager.getInstance();
		for (MusicTracksManager.ExternalPlaylist playlist : manager.getSelectablePlaylists()) {
			MusicTracksManager.DynamicBinding binding = manager.editableBinding(playlist.configLocation());
			MusicTracksManager.DynamicSource source = manager.dynamicSource(playlist.configLocation());
			if (binding == null || binding.kind() != MusicTracksManager.DynamicBinding.Kind.IDLE_RULE ||
					source == null || !sourceMatchesMode(source))
				continue;
			MusicTracksManager.DynamicPlaylistSettings settings = manager.dynamicSettings(playlist.configLocation());
			if (settings != null)
				this.rules.add(new Rule(playlist, binding, source, settings));
		}
		if (!selectedId.isBlank()) {
			for (int i = 0; i < this.rules.size(); i++) {
				if (selectedId.equals(this.rules.get(i).binding().serializedKey())) {
					this.selectedRule = i;
					break;
				}
			}
		}
		this.selectedRule = clamp(this.selectedRule, 0, Math.max(0, this.rules.size() - 1));
		if (this.rules.isEmpty())
			this.selectedRule = -1;
		if (this.ruleList != null)
			this.refreshSelectionLists();
	}

	private void refreshSelectionLists()
	{
		if (this.ruleList == null)
			return;
		List<PlaylistSelectionList.Model<Rule>> ruleModels = new ArrayList<>();
		for (Rule rule : this.rules) {
			ruleModels.add(new PlaylistSelectionList.Model<>(rule.binding().serializedKey(), rule,
					Component.literal(rule.binding().target()),
					text("idle.rule_summary", rule.playlist().entries().size(),
							isActive(rule) ? text("idle.active") : text("idle.inactive")),
					isActive(rule) ? PlaylistSelectionList.Status.MATCHED : PlaylistSelectionList.Status.UNMATCHED,
					false, false));
		}
		Rule current = selectedRule();
		this.ruleList.setItems(ruleModels, current == null ? null : current.binding().serializedKey());
		List<PlaylistSelectionList.Model<Integer>> trackModels = new ArrayList<>();
		if (current != null) {
			MusicTracksManager manager = MusicTracksManager.getInstance();
			for (int i = 0; i < current.playlist().entries().size(); i++) {
				MusicTracksManager.ExternalPlaylistEntry entry = current.playlist().entries().get(i);
				MusicMetadata metadata = MusicMetadataCache.getInstance().get(entry.url()).orElse(null);
				String title = metadata == null ? entry.name() : metadata.displayTitle(entry.name());
				PlaylistSelectionList.Status status = !manager.isMusicEntryEnabled(current.playlist().configLocation(), entry)
						? PlaylistSelectionList.Status.DISABLED
						: isPlaying(entry) ? PlaylistSelectionList.Status.PLAYING
						: !entry.conditions().isEmpty() && !IdleConditionStateClient.isEntryActive(current.playlist().configLocation(), i)
								? PlaylistSelectionList.Status.UNMATCHED : PlaylistSelectionList.Status.MATCHED;
				trackModels.add(new PlaylistSelectionList.Model<>(current.binding().serializedKey() + "#" + i, i,
						Component.literal(title), Component.literal("#" + (i + 1) + "  " + current.binding().scene()), status,
						selectedRuleEditable(), selectedRuleEditable()));
			}
		}
		this.trackList.setItems(trackModels, current == null ? null
				: current.binding().serializedKey() + "#" + this.selectedTrack);
	}

	private boolean isPlaying(MusicTracksManager.ExternalPlaylistEntry entry)
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		return entry.url().equals(handler.getPreviewUrl()) || entry.url().equals(handler.getCurrentlyPlayingUrl())
				|| PreviewChannel.isSoundTrackActive() && entry.equals(selectedTrackEntry());
	}

	private boolean selectedRuleEditable()
	{
		Rule rule = selectedRule();
		return rule != null && (this.editMode == MusicPlaylistScreen.EditMode.SERVER
				? rule.source() == MusicTracksManager.DynamicSource.SERVER
				: rule.source() == MusicTracksManager.DynamicSource.LOCAL);
	}

	private void selectRule(Rule rule)
	{
		this.selectedRule = this.rules.indexOf(rule);
		this.selectedTrack = 0;
		this.selectedCondition = 0;
		this.loadSelectedRule(true);
		this.refreshSelectionLists();
	}

	private void selectTrack(Integer index)
	{
		this.selectedTrack = index == null ? 0 : index;
		this.selectedCondition = 0;
		this.updateButtonState();
	}

	private void toggleTrack(Integer index)
	{
		this.selectedTrack = index == null ? 0 : index;
		toggleSelectedEntry();
		this.refreshSelectionLists();
	}

	private void deleteTrackRow(Integer index)
	{
		this.selectedTrack = index == null ? 0 : index;
		deleteTrack();
		this.refreshSelectionLists();
	}

	private void moveTrack(PlaylistSelectionList.Move<Integer> move)
	{
		if (!selectedRuleEditable() || move.from() == move.to())
			return;
		Rule rule = selectedRule();
		if (rule == null)
			return;
		this.selectedTrack = move.to();
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			runServerCommand("mobbattlemusic idle rule " + rule.binding().target() + " move " +
					(move.from() + 1) + " " + (move.to() + 1));
		else {
			message(MusicTracksManager.getInstance().moveLocalEntry(rule.binding(), move.from(), move.to()).message());
			refreshRules();
		}
	}

	private void selectInspectorPage(InspectorPage page)
	{
		this.inspectorPage = page;
		this.updateButtonState();
	}

	private void saveInspector()
	{
		if (selectedRule() == null || !selectedRuleEditable())
			return;
		if (parseInteger(this.priorityBox.getValue(), -1000, 1000) == null) {
			message(text("message.invalid_priority"));
			return;
		}
		if (parseInteger(this.intervalBox.getValue(), 0, 86400) == null) {
			message(text("message.invalid_interval"));
			return;
		}
		applyPriority();
		applyInterval();
		this.draftDirty = false;
		this.saveState();
		message(text("message.saved"));
	}

	private boolean canEditServer()
	{
		// AUD-29 #1: singleplayer eligibility via MbmSessionState only
		return MbmSessionState.isLocalSingleplayer()
				|| this.minecraft.player != null && this.minecraft.player.getPermissionLevel() >= 2;
	}

	private void refreshRules()
	{
		String preferredRule = this.ruleBox == null ? "" : this.ruleBox.getValue().trim();
		rebuildRules();
		if (!preferredRule.isBlank()) {
			for (int i = 0; i < this.rules.size(); i++) {
				if (preferredRule.equals(this.rules.get(i).binding().target())) {
					this.selectedRule = i;
					break;
				}
			}
		}
		loadSelectedRule(true);
	}

	private void loadSelectedRule(boolean updateRuleField)
	{
		Rule rule = selectedRule();
		if (rule == null) {
			this.priorityBox.setValue("");
			this.intervalBox.setValue("");
			this.orderButton.setMessage(orderLabel(null));
		} else {
			this.selectedTrack = clamp(this.selectedTrack, 0, Math.max(0, rule.playlist().entries().size() - 1));
			this.selectedCondition = clamp(this.selectedCondition, 0,
					Math.max(0, selectedTrackConditions().size() - 1));
			this.priorityBox.setValue(String.valueOf(rule.settings().priority()));
			this.intervalBox.setValue(String.valueOf(rule.settings().idleIntervalSeconds()));
			this.orderButton.setMessage(orderLabel(rule.settings().selectionMode()));
			if (updateRuleField)
				this.ruleBox.setValue(rule.binding().target());
		}
		this.conditionTypeButton.setMessage(conditionTypeLabel());
		this.conditionInvertButton.setMessage(invertLabel());
		this.draftDirty = false;
		this.updateButtonState();
	}

	private void updateButtonState()
	{
		Rule rule = selectedRule();
		boolean selected = rule != null;
		boolean editable = selectedRuleEditable();
		boolean details = this.inspectorPage == InspectorPage.DETAILS;
		boolean conditions = this.inspectorPage == InspectorPage.CONDITIONS;
		this.orderButton.visible = details && editable;
		this.orderButton.active = editable;
		this.priorityBox.visible = details && editable;
		this.priorityBox.active = editable;
		this.intervalBox.visible = details && editable;
		this.intervalBox.active = editable;
		this.previewButton.active = selected && selectedTrackEntry() != null;
		this.conditionTypeButton.visible = conditions && editable;
		this.conditionTypeButton.active = this.conditionTypeButton.visible;
		this.conditionArgumentBox.visible = conditions && editable;
		this.conditionArgumentBox.active = this.conditionArgumentBox.visible;
		this.conditionInvertButton.visible = conditions && editable;
		this.conditionInvertButton.active = this.conditionInvertButton.visible;
		this.conditionAddButton.visible = conditions && editable;
		this.conditionAddButton.active = this.conditionAddButton.visible && ResourceLocation.tryParse(this.conditionType) != null;
		this.conditionDeleteButton.visible = conditions && editable;
		this.conditionDeleteButton.active = this.conditionDeleteButton.visible && selected && !selectedTrackConditions().isEmpty();
		int editorY = conditionEditorY();
		this.conditionTypeButton.setY(editorY);
		this.conditionArgumentBox.setY(editorY + 1);
		this.conditionInvertButton.setY(editorY + 25);
		this.conditionAddButton.setY(editorY + 25);
		this.conditionDeleteButton.setY(editorY + 25);
		this.conditionTypeDropdown.setBounds(this.layout.inspector().innerX(), editorY + 20,
				this.layout.inspector().innerWidth(), this.layout.inspector().bottom());
		this.argumentDropdown.setBounds(this.layout.inspector().innerX(), editorY + 20,
				this.layout.inspector().innerWidth(), this.layout.inspector().bottom());
		this.addTrackButton.active = ResourceLocation.tryParse(this.ruleBox.getValue().trim()) != null;
		this.saveButton.active = editable
				&& parseInteger(this.priorityBox.getValue(), -1000, 1000) != null
				&& parseInteger(this.intervalBox.getValue(), 0, 86400) != null;
		this.inspectorDetailsButton.active = this.inspectorPage != InspectorPage.DETAILS;
		this.inspectorBindingButton.active = this.inspectorPage != InspectorPage.BINDING;
		this.inspectorConditionsButton.active = this.inspectorPage != InspectorPage.CONDITIONS;
		this.modeButton.active = this.editMode == MusicPlaylistScreen.EditMode.SERVER || canEditServer();
	}

	private void addTrack()
	{
		String ruleId = this.ruleBox.getValue().trim();
		if (ResourceLocation.tryParse(ruleId) == null) {
			message(text("idle.message.rule_required"));
			return;
		}
		saveState();
		stopPreview();
		MusicPlaylistScreen.openForIdleRule(this, this.editMode, ruleId);
	}

	private void deleteTrack()
	{
		Rule rule = selectedRule();
		if (rule == null || selectedTrackEntry() == null)
			return;
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			runServerCommand("mobbattlemusic idle rule " + rule.binding().target() + " delete " +
					(this.selectedTrack + 1));
		else {
			message(MusicTracksManager.getInstance().deleteLocalIdleRuleUrl(rule.binding().target(), this.selectedTrack).message());
			refreshRules();
		}
	}

	private void toggleSelectedEntry()
	{
		Rule rule = selectedRule();
		MusicTracksManager.ExternalPlaylistEntry entry = selectedTrackEntry();
		if (rule == null || entry == null)
			return;
		MusicTracksManager manager = MusicTracksManager.getInstance();
		boolean enabled = !manager.isMusicEntryEnabled(rule.playlist().configLocation(), entry);
		message(manager.setMusicEntryEnabled(rule.playlist().configLocation(), entry, enabled).message());
		if (!enabled)
			stopPreview();
		updateButtonState();
	}

	private Component entryEnabledLabel()
	{
		Rule rule = selectedRule();
		MusicTracksManager.ExternalPlaylistEntry entry = selectedTrackEntry();
		boolean enabled = rule != null && entry != null && MusicTracksManager.getInstance()
				.isMusicEntryEnabled(rule.playlist().configLocation(), entry);
		return text(enabled ? "button.disable_entry" : "button.enable_entry");
	}

	private void previewSelected()
	{
		MusicTracksManager.ExternalPlaylistEntry entry = selectedTrackEntry();
		if (entry == null)
			return;
		stopPreview();
		ResourceLocation sound = soundLocation(entry.url());
		if (sound != null)
			PreviewChannel.playSound(sound, 20, entry.url());
		else
			PreviewChannel.playUrl(entry.url(), 20, 0L);
	}

	private void stopPreview()
	{
		PreviewChannel.stop();
	}

	private void renderPreviewProgress(GuiGraphics graphics)
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		if (!handler.isPreviewing())
			return;
		int left = this.layout.main().innerX();
		int right = this.layout.main().right() - PlaylistScreenLayout.PADDING;
		int y = this.layout.main().bottom() - 12;
		long position = handler.getPreviewPositionMillis();
		long duration = handler.getPreviewDurationMillis();
		// K4 P1: isPreviewing() is NOT a valid guard - previewUrl is set before
		// the decode thread opens the line, so a -1 position is possible while
		// previewing; clamp it for display arithmetic
		if (position < 0L)
			position = 0L;
		String time = formatDuration(position) + " / " + (duration > 0L ? formatDuration(duration) : "--:--");
		graphics.drawString(this.font, text("preview.progress", time), left, y - 11, 0xFFD8D8D8, false);
		graphics.fill(left, y, right, y + 7, 0xFF202328);
		int filled = duration <= 0L ? 0 : (int)Math.round((right - left) * Math.min(1.0D,
				position / (double)duration));
		graphics.fill(left, y, left + filled, y + 7, 0xFF5AA7C4);
		Rule rule = selectedRule();
		MusicTracksManager.ExternalPlaylistEntry entry = selectedTrackEntry();
		if (rule != null && entry != null && duration > 0L) {
			for (TimelineMarker marker : TimelineMarkerStore.markers(rule.playlist().configLocation(), entry.url(), this.selectedTrack)) {
				int markerX = left + (int)Math.round((right - left) * Math.min(1.0D,
						marker.timeMillis() / (double)duration));
				graphics.fill(markerX, y - 3, markerX + 1, y + 9, 0xFFFFC857);
			}
		}
		graphics.fill(left + Math.max(0, filled - 1), y - 1, left + filled + 1, y + 8, 0xFFE8F4F8);
	}

	private boolean seekPreview(double mouseX, double mouseY)
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		long duration = handler.getPreviewDurationMillis();
		int left = this.layout.main().innerX();
		int right = this.layout.main().right() - PlaylistScreenLayout.PADDING;
		int y = this.layout.main().bottom() - 12;
		if (!handler.isPreviewing() || duration <= 0L || mouseX < left || mouseX > right ||
				mouseY < y - 3 || mouseY > y + 10)
			return false;
		double progress = (mouseX - left) / Math.max(1.0D, right - left);
		return handler.seekPreviewMusic(Math.round(duration * Math.max(0.0D, Math.min(1.0D, progress))));
	}

	private static String formatDuration(long durationMillis)
	{
		long totalSeconds = Math.max(0L, durationMillis / 1000L);
		return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
	}

	private void navigateTo(PlaylistTabs.Tab tab)
	{
		saveState();
		stopPreview();
		MusicPlaylistScreen.openTab(this.parent, this.editMode, tab);
	}

	private void applyPriority()
	{
		Rule rule = selectedRule();
		Integer priority = parseInteger(this.priorityBox.getValue(), -1000, 1000);
		if (rule == null || priority == null) {
			message(text("message.invalid_priority"));
			return;
		}
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			runServerCommand("mobbattlemusic idle rule " + rule.binding().target() + " priority " + priority);
		else {
			message(MusicTracksManager.getInstance().setLocalPriority(rule.binding(), priority).message());
			refreshRules();
		}
	}

	private void applyInterval()
	{
		Rule rule = selectedRule();
		Integer interval = parseInteger(this.intervalBox.getValue(), 0, 86400);
		if (rule == null || interval == null) {
			message(text("message.invalid_interval"));
			return;
		}
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			runServerCommand("mobbattlemusic idle rule " + rule.binding().target() + " interval " + interval);
		else {
			message(MusicTracksManager.getInstance().setLocalIdleInterval(rule.binding(), interval).message());
			refreshRules();
		}
	}

	private void toggleOrderDropdown()
	{
		Rule rule = selectedRule();
		if (rule == null)
			return;
		closeDropdowns(this.orderDropdown);
		if (this.orderDropdown.isOpen())
			this.orderDropdown.close();
		else
			this.orderDropdown.open(rule.settings().selectionMode().getSerializedName());
	}

	private void selectOrder(String id)
	{
		Rule rule = selectedRule();
		if (rule == null)
			return;
		MusicTracksManager.ExternalSelectionMode mode;
		try {
			mode = MusicTracksManager.ExternalSelectionMode.fromSerializedName(id);
		} catch (Exception e) {
			return;
		}
		this.orderDropdown.close();
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			runServerCommand("mobbattlemusic idle rule " + rule.binding().target() + " order " + mode.getSerializedName());
		else {
			message(MusicTracksManager.getInstance().setLocalSelectionMode(rule.binding(), mode).message());
			refreshRules();
		}
	}

	private void toggleConditionTypeDropdown()
	{
		closeDropdowns(this.conditionTypeDropdown);
		if (this.conditionTypeDropdown.isOpen())
			this.conditionTypeDropdown.close();
		else
			this.conditionTypeDropdown.open(this.conditionType);
	}

	private void selectConditionType(String id)
	{
		this.conditionTypeDropdown.close();
		this.conditionType = id;
		state().conditionType = id;
		this.conditionTypeButton.setMessage(conditionTypeLabel());
		this.rebuildArgumentDropdown();
		this.updateButtonState();
	}

	private void openArgumentDropdown()
	{
		if (argumentSuggestions().isEmpty())
			return;
		closeDropdowns(this.argumentDropdown);
		this.argumentDropdown.setFilter(this.conditionArgumentBox.getValue());
		this.argumentDropdown.open(this.conditionArgumentBox.getValue());
	}

	private void selectArgument(String value)
	{
		this.argumentDropdown.close();
		this.conditionArgumentBox.setValue(value);
		this.conditionArgumentBox.setCursorPosition(value.length());
	}

	private void toggleInverted()
	{
		this.conditionInverted = !this.conditionInverted;
		state().conditionInverted = this.conditionInverted;
		this.conditionInvertButton.setMessage(invertLabel());
	}

	private void addCondition()
	{
		Rule rule = selectedRule();
		MusicTracksManager.ExternalPlaylistEntry entry = selectedTrackEntry();
		if (rule == null || entry == null || ResourceLocation.tryParse(this.conditionType) == null)
			return;
		String argument = this.conditionArgumentBox.getValue().trim();
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER) {
			String command = "mobbattlemusic entry_condition " + rule.playlist().configLocation() + " " +
					(this.selectedTrack + 1) + " " +
					(this.conditionInverted ? "add_not " : "add ") + this.conditionType;
			if (!argument.isBlank())
				command += " " + argument;
			runServerCommand(command);
		} else {
			message(MusicTracksManager.getInstance().addLocalEntryCondition(rule.binding(), this.selectedTrack,
					new IdleCondition(this.conditionType, argument, this.conditionInverted)).message());
			refreshRules();
		}
	}

	private void deleteCondition()
	{
		Rule rule = selectedRule();
		if (rule == null || selectedTrackConditions().isEmpty())
			return;
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			runServerCommand("mobbattlemusic entry_condition " + rule.playlist().configLocation() + " " +
					(this.selectedTrack + 1) + " delete " + (this.selectedCondition + 1));
		else {
			message(MusicTracksManager.getInstance().deleteLocalEntryCondition(rule.binding(), this.selectedTrack,
					this.selectedCondition).message());
			refreshRules();
		}
	}

	private void rebuildArgumentDropdown()
	{
		this.argumentDropdown.setItems(argumentSuggestions().stream()
				.map(value -> new PlaylistDropdown.Item(value, Component.literal(value))).toList());
	}

	private List<String> argumentSuggestions()
	{
		if (this.minecraft == null || this.minecraft.level == null)
			return List.of();
		if ("mobbattlemusic:dimension".equals(this.conditionType) && this.minecraft.getConnection() != null)
			return this.minecraft.getConnection().levels().stream().map(key -> key.location().toString()).sorted().toList();
		if ("mobbattlemusic:biome".equals(this.conditionType))
			return this.minecraft.level.registryAccess().registry(Registries.BIOME).stream()
					.flatMap(registry -> registry.keySet().stream()).map(ResourceLocation::toString).sorted().toList();
		if ("mobbattlemusic:structure".equals(this.conditionType))
			return this.minecraft.level.registryAccess().registry(Registries.STRUCTURE).stream()
					.flatMap(registry -> registry.keySet().stream()).map(ResourceLocation::toString).sorted().toList();
		return List.of();
	}

	private boolean isActive(Rule rule)
	{
		for (int i = 0; i < rule.playlist().entries().size(); i++) {
			List<IdleCondition> conditions = rule.playlist().entries().get(i).conditions();
			if (conditions.isEmpty())
				return true;
			if (this.editMode == MusicPlaylistScreen.EditMode.SERVER) {
				if (IdleConditionStateClient.isEntryActive(rule.playlist().configLocation(), i))
					return true;
			} else if (this.minecraft.player != null && IdleConditionRegistry.test(this.minecraft.player, conditions)) {
				return true;
			}
		}
		return false;
	}

	private boolean sourceMatchesMode(MusicTracksManager.DynamicSource source)
	{
		return this.editMode == MusicPlaylistScreen.EditMode.SERVER
				? source == MusicTracksManager.DynamicSource.SERVER : source == MusicTracksManager.DynamicSource.LOCAL;
	}

	private Rule selectedRule()
	{
		return this.selectedRule < 0 || this.selectedRule >= this.rules.size() ? null : this.rules.get(this.selectedRule);
	}

	private List<IdleCondition> selectedTrackConditions()
	{
		MusicTracksManager.ExternalPlaylistEntry entry = selectedTrackEntry();
		return entry == null ? List.of() : entry.conditions();
	}

	private MusicTracksManager.ExternalPlaylistEntry selectedTrackEntry()
	{
		Rule rule = selectedRule();
		return rule == null || this.selectedTrack < 0 || this.selectedTrack >= rule.playlist().entries().size()
				? null : rule.playlist().entries().get(this.selectedTrack);
	}

	private void runServerCommand(String command)
	{
		if (this.minecraft.player == null || this.minecraft.player.connection == null)
			return;
		this.minecraft.player.connection.sendCommand(command);
		this.syncRefreshCooldown = 10;
		message(text("message.sent_command", command));
	}

	private void switchMode()
	{
		if (this.editMode == MusicPlaylistScreen.EditMode.LOCAL && !canEditServer()) {
			message(text("mode.no_permission"));
			return;
		}
		saveState();
		stopPreview();
		MusicPlaylistScreen.EditMode next = this.editMode == MusicPlaylistScreen.EditMode.LOCAL
				? MusicPlaylistScreen.EditMode.SERVER : MusicPlaylistScreen.EditMode.LOCAL;
		this.minecraft.setScreen(new IdlePlaylistScreen(this.parent, next));
	}

	private void toggleModeDropdown()
	{
		if (this.editMode == MusicPlaylistScreen.EditMode.LOCAL && !canEditServer())
			return;
		closeDropdowns(this.modeDropdown);
		if (this.modeDropdown.isOpen())
			this.modeDropdown.close();
		else
			this.modeDropdown.open(this.editMode.name().toLowerCase(Locale.ROOT));
	}

	private void selectMode(String id)
	{
		MusicPlaylistScreen.EditMode selected = "server".equals(id)
				? MusicPlaylistScreen.EditMode.SERVER : MusicPlaylistScreen.EditMode.LOCAL;
		this.modeDropdown.close();
		if (selected != this.editMode)
			switchMode();
	}

	private void closeToParent()
	{
		saveState();
		this.minecraft.setScreen(this.parent);
	}

	private void requestClose()
	{
		if (!this.draftDirty) {
			stopPreview();
			closeToParent();
			return;
		}
		String priorityDraft = this.priorityBox == null ? "" : this.priorityBox.getValue();
		String intervalDraft = this.intervalBox == null ? "" : this.intervalBox.getValue();
		this.minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(confirmed -> {
			if (confirmed) {
				this.draftDirty = false;
				stopPreview();
				closeToParent();
			} else {
				this.minecraft.setScreen(this);
				this.priorityBox.setValue(priorityDraft);
				this.intervalBox.setValue(intervalDraft);
				this.draftDirty = true;
				this.updateButtonState();
			}
		}, text("confirm.title"), text("confirm.message")));
	}

	private void saveState()
	{
		State state = state();
		state.selectedRule = this.selectedRule;
		state.selectedTrack = this.selectedTrack;
		state.selectedCondition = this.selectedCondition;
		state.conditionType = this.conditionType;
		state.conditionInverted = this.conditionInverted;
		if (this.ruleBox != null)
			state.ruleId = this.ruleBox.getValue();
		if (this.conditionArgumentBox != null)
			state.conditionArgument = this.conditionArgumentBox.getValue();
	}

	private State state()
	{
		return STATES.get(this.editMode);
	}

	private Component modeLabel()
	{
		return text("mode_button", text("mode." + this.editMode.name().toLowerCase(Locale.ROOT))).copy().append(" v");
	}

	private Component orderLabel(MusicTracksManager.ExternalSelectionMode mode)
	{
		return mode == null ? text("button.order", "-").copy().append(" v")
				: text("button.order", text("order." + mode.getSerializedName())).copy().append(" v");
	}

	private PlaylistDropdown.Item orderItem(MusicTracksManager.ExternalSelectionMode mode)
	{
		return new PlaylistDropdown.Item(mode.getSerializedName(), text("order." + mode.getSerializedName()));
	}

	private Component conditionTypeLabel()
	{
		for (IdleConditionRegistry.Descriptor descriptor : IdleConditionStateClient.descriptors()) {
			if (descriptor.id().toString().equals(this.conditionType))
				return Component.literal(descriptor.displayName()).append(" v");
		}
		return Component.literal(this.conditionType).append(" v");
	}

	private Component invertLabel()
	{
		return text(this.conditionInverted ? "button.not" : "button.match");
	}

	private void closeDropdowns(PlaylistDropdown except)
	{
		if (this.modeDropdown != except)
			this.modeDropdown.close();
		if (this.orderDropdown != except)
			this.orderDropdown.close();
		if (this.conditionTypeDropdown != except)
			this.conditionTypeDropdown.close();
		if (this.argumentDropdown != except)
			this.argumentDropdown.close();
	}

	private boolean handleDropdownClick(PlaylistDropdown dropdown, double mouseX, double mouseY, int button,
			java.util.function.Consumer<PlaylistDropdown.Item> selected)
	{
		if (!dropdown.isOpen())
			return false;
		PlaylistDropdown.Item item = dropdown.itemAt(mouseX, mouseY);
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && item != null)
			selected.accept(item);
		else
			dropdown.close();
		return true;
	}

	private boolean handleDropdownKey(PlaylistDropdown dropdown, int keyCode,
			java.util.function.Consumer<PlaylistDropdown.Item> selected)
	{
		if (!dropdown.isOpen())
			return false;
		if (keyCode == GLFW.GLFW_KEY_ESCAPE)
			dropdown.close();
		else if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)
			dropdown.move(keyCode == GLFW.GLFW_KEY_UP ? -1 : 1);
		else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			PlaylistDropdown.Item item = dropdown.highlightedItem();
			if (item != null)
				selected.accept(item);
		}
		return true;
	}

	private void message(String message)
	{
		message(Component.literal(message));
	}

	private void message(Component message)
	{
		if (this.minecraft.player != null)
			this.minecraft.player.displayClientMessage(Component.literal("[Mob Battle Music] ").append(message), true);
	}

	private static Integer parseInteger(String raw, int min, int max)
	{
		try {
			int value = Integer.parseInt(raw);
			return value >= min && value <= max ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}

	private static Component text(String path, Object... args)
	{
		return Component.translatable("gui.mobbattlemusic.playlist." + path, args);
	}

	private static ResourceLocation soundLocation(String raw)
	{
		if (raw == null || raw.isBlank())
			return null;
		String id = raw.startsWith("sound:") ? raw.substring("sound:".length()) : raw;
		ResourceLocation location = ResourceLocation.tryParse(id);
		return location == null || Minecraft.getInstance().getSoundManager().getSoundEvent(location) == null
				? null : location;
	}

	private record Rule(MusicTracksManager.ExternalPlaylist playlist, MusicTracksManager.DynamicBinding binding,
			MusicTracksManager.DynamicSource source, MusicTracksManager.DynamicPlaylistSettings settings) {}

	private static final class State
	{
		private int selectedRule = -1;
		private int selectedTrack;
		private int selectedCondition;
		private String ruleId = "mobbattlemusic:default";
		private String conditionType = "mobbattlemusic:dimension";
		private String conditionArgument = "";
		private boolean conditionInverted;
	}
}