package nonamecrackers2.mobbattlemusic.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.audio.AudioFilterDefinition;
import nonamecrackers2.mobbattlemusic.client.audio.AudioFilterManager;
import nonamecrackers2.mobbattlemusic.client.music.IdleConditionStateClient;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;

final class AudioFilterScreen extends Screen
{
	private static final State STATE = new State();
	private final Screen parent;
	private final MusicPlaylistScreen.EditMode editMode;
	private final List<Entry> entries = new ArrayList<>();
	private final List<IdleCondition> draftConditions = new ArrayList<>();
	private final PlaylistDropdown scopeDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 3);
	private final PlaylistDropdown typeDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 5);
	private final PlaylistDropdown conditionTypeDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 8);
	private Button modeButton;
	private Button enabledButton;
	private Button newButton;
	private Button saveButton;
	private Button deleteButton;
	private Button reloadButton;
	private Button scopeButton;
	private Button typeButton;
	private Button conditionTypeButton;
	private Button conditionInvertButton;
	private Button conditionAddButton;
	private Button conditionDeleteButton;
	private EditBox idBox;
	private EditBox frequencyBox;
	private EditBox qBox;
	private EditBox gainBox;
	private EditBox bitDepthBox;
	private EditBox sampleRateBox;
	private EditBox conditionArgumentBox;
	private int selected;
	private int selectedCondition;
	private int scroll;
	private int conditionScroll;
	private AudioFilterDefinition.Scope scope;
	private AudioFilterDefinition.Type type;
	private String conditionType;
	private boolean conditionInverted;
	private boolean selectedRuntime;

	AudioFilterScreen(Screen parent, MusicPlaylistScreen.EditMode editMode)
	{
		super(text("filters.title"));
		this.parent = parent;
		this.editMode = editMode;
	}

	@Override
	protected void init()
	{
		this.selected = STATE.selected;
		this.selectedCondition = STATE.selectedCondition;
		this.scroll = STATE.scroll;
		this.conditionScroll = STATE.conditionScroll;
		this.scope = STATE.scope;
		this.type = STATE.type;
		this.conditionType = STATE.conditionType;
		this.conditionInverted = STATE.conditionInverted;
		this.draftConditions.clear();
		this.draftConditions.addAll(STATE.conditions);
		this.rebuildEntries();
		for (Button tab : PlaylistTabs.create(this.width, PlaylistTabs.Tab.FILTERS, this::navigateTo))
			this.addRenderableWidget(tab);
		this.modeButton = this.addRenderableWidget(Button.builder(text("filters.client_only"), button -> {})
				.bounds(this.width - 172, 6, 100, 20).build());
		this.modeButton.active = false;
		this.addRenderableWidget(Button.builder(text("button.done"), button -> closeToParent())
				.bounds(this.width - 66, 6, 54, 20).build());

		int x = detailX();
		int right = this.width - 12;
		int detailWidth = right - x;
		boolean compact = detailWidth < 420;
		int topY = 58;
		int enabledWidth = compact ? 70 : 86;
		int newWidth = compact ? 44 : 52;
		int saveWidth = compact ? 44 : 52;
		int deleteWidth = compact ? 48 : 56;
		int reloadWidth = compact ? 48 : 58;
		this.enabledButton = this.addRenderableWidget(Button.builder(enabledLabel(), button -> toggleEnabled())
				.bounds(x, topY, enabledWidth, 20).build());
		this.newButton = this.addRenderableWidget(Button.builder(text("filters.button.new"), button -> newFilter())
				.bounds(x + enabledWidth + 4, topY, newWidth, 20).build());
		this.saveButton = this.addRenderableWidget(Button.builder(text("filters.button.save"), button -> saveFilter())
				.bounds(this.newButton.getX() + newWidth + 4, topY, saveWidth, 20).build());
		this.deleteButton = this.addRenderableWidget(Button.builder(text("button.delete"), button -> deleteFilter())
				.bounds(this.saveButton.getX() + saveWidth + 4, topY, deleteWidth, 20).build());
		this.reloadButton = this.addRenderableWidget(Button.builder(text("button.refresh"), button -> reloadFilters())
				.bounds(this.deleteButton.getX() + deleteWidth + 4, topY, reloadWidth, 20).build());

		int idY = 84;
		int selectorY = compact ? 108 : idY;
		int scopeWidth = compact ? Math.max(70, (detailWidth - 4) / 2) : 92;
		int typeWidth = compact ? detailWidth - scopeWidth - 4 : 104;
		this.idBox = this.addRenderableWidget(new EditBox(this.font, x, idY + 1,
				compact ? detailWidth : Math.max(90, right - x - scopeWidth - typeWidth - 8), 18,
				text("filters.field.id")));
		this.idBox.setMaxLength(128);
		this.idBox.setHint(text("filters.field.id"));
		this.idBox.setValue(STATE.id);
		this.idBox.setResponder(value -> {
			STATE.id = value;
			updateButtonState();
		});
		this.scopeButton = this.addRenderableWidget(Button.builder(scopeLabel(), button -> toggleScopeDropdown())
				.bounds(compact ? x : this.idBox.getX() + this.idBox.getWidth() + 4, selectorY, scopeWidth, 20).build());
		this.typeButton = this.addRenderableWidget(Button.builder(typeLabel(), button -> toggleTypeDropdown())
				.bounds(this.scopeButton.getX() + scopeWidth + 4, selectorY, typeWidth, 20).build());

		int numbersY = compact ? 134 : 110;
		int gap = 4;
		int numberWidth = Math.max(42, (right - x - gap * 4) / 5);
		this.frequencyBox = numberBox(x, numbersY, numberWidth, "filters.field.frequency", STATE.frequency, 12);
		this.qBox = numberBox(x + (numberWidth + gap), numbersY, numberWidth, "filters.field.q", STATE.q, 8);
		this.gainBox = numberBox(x + (numberWidth + gap) * 2, numbersY, numberWidth,
				"filters.field.gain", STATE.gain, 8);
		this.bitDepthBox = numberBox(x + (numberWidth + gap) * 3, numbersY, numberWidth,
				"filters.field.bit_depth", STATE.bitDepth, 2);
		this.sampleRateBox = numberBox(x + (numberWidth + gap) * 4, numbersY,
				right - (x + (numberWidth + gap) * 4), "filters.field.sample_rate", STATE.sampleRate, 5);

		int conditionY = this.height - 28;
		int conditionTypeWidth = compact ? 84 : Math.min(150, Math.max(104, (right - x) / 4));
		this.conditionTypeButton = this.addRenderableWidget(Button.builder(conditionTypeLabel(),
				button -> toggleConditionTypeDropdown()).bounds(x, conditionY, conditionTypeWidth, 20).build());
		int invertWidth = compact ? 32 : 42;
		int conditionAddWidth = compact ? 32 : 42;
		int conditionDeleteWidth = compact ? 40 : 48;
		int argumentWidth = compact
				? Math.max(42, detailWidth - conditionTypeWidth - invertWidth - conditionAddWidth - conditionDeleteWidth - 20)
				: Math.max(70, right - x - conditionTypeWidth - 144);
		this.conditionArgumentBox = this.addRenderableWidget(new EditBox(this.font, x + conditionTypeWidth + 4,
				conditionY + 1, argumentWidth, 18,
				text("field.condition_argument")));
		this.conditionArgumentBox.setMaxLength(512);
		this.conditionArgumentBox.setHint(text("field.condition_argument"));
		this.conditionArgumentBox.setValue(STATE.conditionArgument);
		this.conditionArgumentBox.setResponder(value -> {
			STATE.conditionArgument = value;
			updateButtonState();
		});
		int actionsX = this.conditionArgumentBox.getX() + this.conditionArgumentBox.getWidth() + 4;
		this.conditionInvertButton = this.addRenderableWidget(Button.builder(invertLabel(), button -> toggleConditionInverted())
				.bounds(actionsX, conditionY, invertWidth, 20).build());
		this.conditionAddButton = this.addRenderableWidget(Button.builder(text("button.condition_add"), button -> addCondition())
				.bounds(actionsX + invertWidth + 4, conditionY, conditionAddWidth, 20).build());
		this.conditionDeleteButton = this.addRenderableWidget(Button.builder(text("button.condition_delete"),
				button -> deleteCondition()).bounds(actionsX + invertWidth + conditionAddWidth + 8, conditionY,
						conditionDeleteWidth, 20).build());

		this.scopeDropdown.setItems(List.of(
				new PlaylistDropdown.Item("mbm", text("filters.scope.mbm")),
				new PlaylistDropdown.Item("global", text("filters.scope.global"))));
		this.scopeDropdown.setBounds(this.scopeButton.getX(), selectorY + 20, this.scopeButton.getWidth(), this.height - 8);
		this.typeDropdown.setItems(List.of(
				typeItem(AudioFilterDefinition.Type.LOW_PASS),
				typeItem(AudioFilterDefinition.Type.HIGH_PASS),
				typeItem(AudioFilterDefinition.Type.PEAK_EQ),
				typeItem(AudioFilterDefinition.Type.LOFI)));
		this.typeDropdown.setBounds(this.typeButton.getX(), selectorY + 20, this.typeButton.getWidth(), this.height - 8);
		this.conditionTypeDropdown.setItems(IdleConditionStateClient.descriptors().stream()
				.map(descriptor -> new PlaylistDropdown.Item(descriptor.id().toString(),
						Component.literal(descriptor.displayName()), Component.literal(descriptor.id().toString()), false))
				.toList());
		this.conditionTypeDropdown.setBounds(x, conditionY + 20, Math.max(conditionTypeWidth, 180), this.height - 8);

		if (!STATE.initialized && !this.entries.isEmpty())
			loadSelectedEntry();
		else
			applyDraftToWidgets();
		STATE.initialized = true;
		this.updateButtonState();
	}

	private EditBox numberBox(int x, int y, int width, String key, String value, int maxLength)
	{
		EditBox box = this.addRenderableWidget(new EditBox(this.font, x, y + 1, Math.max(36, width), 18, text(key)));
		box.setMaxLength(maxLength);
		box.setFilter(input -> input.isEmpty() || input.equals("-") || input.equals(".") || input.equals("-.") ||
				input.matches("-?[0-9]*\\.?[0-9]*"));
		box.setHint(text(key));
		box.setValue(value);
		box.setResponder(input -> captureNumberValues());
		return box;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
	{
		this.renderBackground(graphics);
		graphics.drawString(this.font, this.title, 12, 10, 0xFFFFFF, false);
		renderFilterList(graphics, mouseX, mouseY);
		renderFilterDetails(graphics, mouseX, mouseY);
		super.render(graphics, mouseX, mouseY, partialTick);
		this.scopeDropdown.render(graphics, this.font, mouseX, mouseY);
		this.typeDropdown.render(graphics, this.font, mouseX, mouseY);
		this.conditionTypeDropdown.render(graphics, this.font, mouseX, mouseY);
	}

	private void renderFilterList(GuiGraphics graphics, int mouseX, int mouseY)
	{
		int x = 12;
		int y = 56;
		int width = leftWidth();
		int bottom = this.height - 12;
		graphics.fill(x - 2, y - 2, x + width + 2, bottom, 0x90000000);
		int rowHeight = 38;
		int visible = Math.max(1, (bottom - y) / rowHeight);
		this.scroll = clamp(this.scroll, 0, Math.max(0, this.entries.size() - visible));
		for (int row = 0; row < visible; row++) {
			int index = this.scroll + row;
			if (index >= this.entries.size())
				break;
			Entry entry = this.entries.get(index);
			int rowY = y + row * rowHeight;
			boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + rowHeight;
			graphics.fill(x, rowY, x + width, rowY + rowHeight - 2,
					index == this.selected ? 0xAA38546E : hovered ? 0x70405058 : 0x40202020);
			graphics.drawString(this.font, trim(entry.definition().id().toString(), Math.max(12, width / 6)),
					x + 6, rowY + 5, 0xFFFFFF, false);
			Component source = text(entry.runtime() ? "filters.source.runtime" : "filters.source.config");
			Component detail = text("filters.row_detail", typeName(entry.definition().type()),
					scopeName(entry.definition().scope()), source);
			graphics.drawString(this.font, trim(detail.getString(), Math.max(12, width / 6)), x + 6, rowY + 18,
					entry.runtime() ? 0xD8B878 : 0xB8C5D1, false);
			boolean active = AudioFilterManager.activeMbmFilters().contains(entry.definition());
			graphics.fill(x + width - 6, rowY + 5, x + width - 3, rowY + 8,
					active ? 0xFF76D18B : 0xFF666666);
		}
	}

	private void renderFilterDetails(GuiGraphics graphics, int mouseX, int mouseY)
	{
		int x = detailX();
		int right = this.width - 12;
		int top = 56;
		int bottom = this.height - 34;
		graphics.fill(x - 2, top - 2, right, bottom + 2, 0x90000000);
		int listTop = filterListTop();
		graphics.drawString(this.font, text("filters.conditions", this.draftConditions.size()), x, listTop - 13,
				0xA0A0A0, false);
		int rowHeight = 20;
		int visible = Math.max(1, (bottom - listTop) / rowHeight);
		this.conditionScroll = clamp(this.conditionScroll, 0, Math.max(0, this.draftConditions.size() - visible));
		for (int row = 0; row < visible; row++) {
			int index = this.conditionScroll + row;
			if (index >= this.draftConditions.size())
				break;
			IdleCondition condition = this.draftConditions.get(index);
			int rowY = listTop + row * rowHeight;
			boolean hovered = mouseX >= x && mouseX < right && mouseY >= rowY && mouseY < rowY + rowHeight;
			if (index == this.selectedCondition || hovered)
				graphics.fill(x, rowY, right, rowY + rowHeight - 2,
						index == this.selectedCondition ? 0x8038546E : 0x50405058);
			String value = (index + 1) + ". " + (condition.inverted() ? "NOT " : "") + condition.type() +
					(condition.argument().isBlank() ? "" : "  " + condition.argument());
			graphics.drawString(this.font, trim(value, Math.max(12, (right - x) / 6)), x + 6, rowY + 6,
					0xD8D8D8, false);
		}
		if (this.scope == AudioFilterDefinition.Scope.GLOBAL &&
				(this.type == AudioFilterDefinition.Type.PEAK_EQ || this.type == AudioFilterDefinition.Type.LOFI))
			graphics.drawString(this.font, text("filters.global_pcm_warning"), x,
					filterListTop() - 10, 0xE5B95C, false);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		if (handleDropdownClick(this.scopeDropdown, mouseX, mouseY, button, item -> selectScope(item.id())) ||
				handleDropdownClick(this.typeDropdown, mouseX, mouseY, button, item -> selectType(item.id())) ||
				handleDropdownClick(this.conditionTypeDropdown, mouseX, mouseY, button,
						item -> selectConditionType(item.id())))
			return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && selectEntry(mouseX, mouseY))
			return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && selectCondition(mouseX, mouseY))
			return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private boolean selectEntry(double mouseX, double mouseY)
	{
		int x = 12;
		int y = 56;
		if (mouseX < x || mouseX >= x + leftWidth() || mouseY < y || mouseY >= this.height - 12)
			return false;
		int index = this.scroll + (int)((mouseY - y) / 38);
		if (index < 0 || index >= this.entries.size())
			return false;
		this.selected = index;
		this.selectedCondition = 0;
		this.conditionScroll = 0;
		this.loadSelectedEntry();
		return true;
	}

	private boolean selectCondition(double mouseX, double mouseY)
	{
		int x = detailX();
		int y = filterListTop();
		if (mouseX < x || mouseX >= this.width - 12 || mouseY < y || mouseY >= this.height - 34)
			return false;
		int index = this.conditionScroll + (int)((mouseY - y) / 20);
		if (index < 0 || index >= this.draftConditions.size())
			return false;
		this.selectedCondition = index;
		this.updateButtonState();
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta)
	{
		if (this.scopeDropdown.mouseScrolled(delta) || this.typeDropdown.mouseScrolled(delta) ||
				this.conditionTypeDropdown.mouseScrolled(delta))
			return true;
		if (mouseX < detailX())
			this.scroll = Math.max(0, this.scroll - (int)Math.signum(delta));
		else
			this.conditionScroll = Math.max(0, this.conditionScroll - (int)Math.signum(delta));
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers)
	{
		if (handleDropdownKey(this.scopeDropdown, keyCode, item -> selectScope(item.id())) ||
				handleDropdownKey(this.typeDropdown, keyCode, item -> selectType(item.id())) ||
				handleDropdownKey(this.conditionTypeDropdown, keyCode, item -> selectConditionType(item.id())))
			return true;
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void onClose()
	{
		closeToParent();
	}

	@Override
	public void removed()
	{
		captureState();
		super.removed();
	}

	private void rebuildEntries()
	{
		String selectedId = selectedEntry() == null ? "" : selectedEntry().key();
		this.entries.clear();
		AudioFilterManager.configDefinitions().forEach(definition -> this.entries.add(new Entry(definition, false)));
		AudioFilterManager.runtimeDefinitions().forEach(definition -> this.entries.add(new Entry(definition, true)));
		if (!selectedId.isBlank()) {
			for (int i = 0; i < this.entries.size(); i++) {
				if (selectedId.equals(this.entries.get(i).key())) {
					this.selected = i;
					break;
				}
			}
		}
		this.selected = clamp(this.selected, 0, Math.max(0, this.entries.size() - 1));
		if (this.entries.isEmpty())
			this.selected = -1;
	}

	private void loadSelectedEntry()
	{
		Entry entry = selectedEntry();
		if (entry == null) {
			newFilter();
			return;
		}
		AudioFilterDefinition definition = entry.definition();
		this.selectedRuntime = entry.runtime();
		STATE.id = definition.id().toString();
		STATE.frequency = formatNumber(definition.frequencyHz());
		STATE.q = formatNumber(definition.q());
		STATE.gain = formatNumber(definition.gainDb());
		STATE.bitDepth = String.valueOf(definition.bitDepth());
		STATE.sampleRate = String.valueOf(definition.sampleRateHz());
		this.scope = definition.scope();
		this.type = definition.type();
		this.draftConditions.clear();
		this.draftConditions.addAll(definition.conditions());
		this.applyDraftToWidgets();
	}

	private void applyDraftToWidgets()
	{
		this.idBox.setValue(STATE.id);
		this.frequencyBox.setValue(STATE.frequency);
		this.qBox.setValue(STATE.q);
		this.gainBox.setValue(STATE.gain);
		this.bitDepthBox.setValue(STATE.bitDepth);
		this.sampleRateBox.setValue(STATE.sampleRate);
		this.scopeButton.setMessage(scopeLabel());
		this.typeButton.setMessage(typeLabel());
		this.conditionTypeButton.setMessage(conditionTypeLabel());
		this.conditionInvertButton.setMessage(invertLabel());
		this.updateButtonState();
	}

	private void newFilter()
	{
		this.selected = -1;
		this.selectedRuntime = false;
		this.selectedCondition = 0;
		this.scope = AudioFilterDefinition.Scope.MBM;
		this.type = AudioFilterDefinition.Type.LOW_PASS;
		STATE.id = "mobbattlemusic:new_filter";
		STATE.frequency = "1000";
		STATE.q = "0.707";
		STATE.gain = "0";
		STATE.bitDepth = "12";
		STATE.sampleRate = "22050";
		this.draftConditions.clear();
		if (this.idBox != null)
			this.applyDraftToWidgets();
	}

	private void saveFilter()
	{
		ResourceLocation id = ResourceLocation.tryParse(this.idBox.getValue().trim());
		Double frequency = parseDouble(this.frequencyBox.getValue(), 20.0D, 20_000.0D);
		Double q = parseDouble(this.qBox.getValue(), 0.1D, 10.0D);
		Double gain = parseDouble(this.gainBox.getValue(), -12.0D, 12.0D);
		Integer bitDepth = parseInteger(this.bitDepthBox.getValue(), 8, 16);
		Integer sampleRate = parseInteger(this.sampleRateBox.getValue(), 8_000, 48_000);
		if (id == null || frequency == null || q == null || gain == null || bitDepth == null || sampleRate == null) {
			message(text("filters.message.invalid"));
			return;
		}
		if (this.scope == AudioFilterDefinition.Scope.GLOBAL &&
				(this.type == AudioFilterDefinition.Type.PEAK_EQ || this.type == AudioFilterDefinition.Type.LOFI)) {
			message(text("filters.message.global_unsupported"));
			return;
		}
		AudioFilterManager.putConfigDefinition(new AudioFilterDefinition(id, this.scope, this.type, frequency, q, gain,
				bitDepth, sampleRate, this.draftConditions));
		activateNow();
		message(text("filters.message.saved", id));
		this.rebuildEntries();
		for (int i = 0; i < this.entries.size(); i++) {
			if (!this.entries.get(i).runtime() && this.entries.get(i).definition().id().equals(id)) {
				this.selected = i;
				break;
			}
		}
		this.loadSelectedEntry();
	}

	private void deleteFilter()
	{
		Entry entry = selectedEntry();
		if (entry == null || entry.runtime())
			return;
		if (AudioFilterManager.removeConfigDefinition(entry.definition().id())) {
			activateNow();
			message(text("filters.message.deleted", entry.definition().id()));
		}
		this.rebuildEntries();
		if (this.entries.isEmpty())
			newFilter();
		else
			loadSelectedEntry();
	}

	private void reloadFilters()
	{
		AudioFilterManager.loadConfig();
		activateNow();
		this.rebuildEntries();
		if (!this.entries.isEmpty())
			this.loadSelectedEntry();
		message(text("filters.message.reloaded"));
	}

	private void toggleEnabled()
	{
		AudioFilterManager.setConfigEnabled(!AudioFilterManager.configEnabled());
		activateNow();
		this.enabledButton.setMessage(enabledLabel());
	}

	private void activateNow()
	{
		if (this.minecraft.player != null)
			AudioFilterManager.tick(this.minecraft.player);
	}

	private void toggleScopeDropdown()
	{
		closeDropdowns(this.scopeDropdown);
		if (this.scopeDropdown.isOpen())
			this.scopeDropdown.close();
		else
			this.scopeDropdown.open(this.scope.getSerializedName());
	}

	private void selectScope(String id)
	{
		this.scopeDropdown.close();
		this.scope = AudioFilterDefinition.Scope.parse(id);
		STATE.scope = this.scope;
		this.scopeButton.setMessage(scopeLabel());
	}

	private void toggleTypeDropdown()
	{
		closeDropdowns(this.typeDropdown);
		if (this.typeDropdown.isOpen())
			this.typeDropdown.close();
		else
			this.typeDropdown.open(this.type.getSerializedName());
	}

	private void selectType(String id)
	{
		this.typeDropdown.close();
		try {
			this.type = AudioFilterDefinition.Type.parse(id);
		} catch (Exception e) {
			return;
		}
		STATE.type = this.type;
		this.typeButton.setMessage(typeLabel());
		this.updateButtonState();
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
		STATE.conditionType = id;
		this.conditionTypeButton.setMessage(conditionTypeLabel());
		this.updateButtonState();
	}

	private void toggleConditionInverted()
	{
		this.conditionInverted = !this.conditionInverted;
		STATE.conditionInverted = this.conditionInverted;
		this.conditionInvertButton.setMessage(invertLabel());
	}

	private void addCondition()
	{
		if (ResourceLocation.tryParse(this.conditionType) == null)
			return;
		this.draftConditions.add(new IdleCondition(this.conditionType,
				this.conditionArgumentBox.getValue().trim(), this.conditionInverted));
		this.selectedCondition = this.draftConditions.size() - 1;
		this.updateButtonState();
	}

	private void deleteCondition()
	{
		if (this.selectedCondition < 0 || this.selectedCondition >= this.draftConditions.size())
			return;
		this.draftConditions.remove(this.selectedCondition);
		this.selectedCondition = clamp(this.selectedCondition, 0, Math.max(0, this.draftConditions.size() - 1));
		this.updateButtonState();
	}

	private void updateButtonState()
	{
		boolean editable = !this.selectedRuntime;
		this.idBox.active = editable;
		this.frequencyBox.active = editable && this.type != AudioFilterDefinition.Type.LOFI;
		this.qBox.active = editable && this.type != AudioFilterDefinition.Type.LOFI;
		this.gainBox.active = editable && this.type == AudioFilterDefinition.Type.PEAK_EQ;
		this.bitDepthBox.active = editable && this.type == AudioFilterDefinition.Type.LOFI;
		this.sampleRateBox.active = editable && this.type == AudioFilterDefinition.Type.LOFI;
		this.scopeButton.active = editable;
		this.typeButton.active = editable;
		this.conditionTypeButton.active = editable;
		this.conditionArgumentBox.active = editable;
		this.conditionInvertButton.active = editable;
		this.conditionAddButton.active = editable && ResourceLocation.tryParse(this.conditionType) != null;
		this.conditionDeleteButton.active = editable && !this.draftConditions.isEmpty();
		this.saveButton.active = editable && ResourceLocation.tryParse(this.idBox.getValue().trim()) != null;
		this.deleteButton.active = selectedEntry() != null && !this.selectedRuntime;
	}

	private void captureNumberValues()
	{
		if (this.frequencyBox == null)
			return;
		STATE.frequency = this.frequencyBox.getValue();
		STATE.q = this.qBox.getValue();
		STATE.gain = this.gainBox.getValue();
		STATE.bitDepth = this.bitDepthBox.getValue();
		STATE.sampleRate = this.sampleRateBox.getValue();
	}

	private void captureState()
	{
		STATE.selected = this.selected;
		STATE.selectedCondition = this.selectedCondition;
		STATE.scroll = this.scroll;
		STATE.conditionScroll = this.conditionScroll;
		STATE.scope = this.scope;
		STATE.type = this.type;
		STATE.conditionType = this.conditionType;
		STATE.conditionInverted = this.conditionInverted;
		STATE.conditions = List.copyOf(this.draftConditions);
		if (this.idBox != null)
			STATE.id = this.idBox.getValue();
		if (this.conditionArgumentBox != null)
			STATE.conditionArgument = this.conditionArgumentBox.getValue();
		captureNumberValues();
	}

	private Entry selectedEntry()
	{
		return this.selected < 0 || this.selected >= this.entries.size() ? null : this.entries.get(this.selected);
	}

	private void switchMode()
	{
		captureState();
		MusicPlaylistScreen.EditMode next = this.editMode == MusicPlaylistScreen.EditMode.LOCAL
				? MusicPlaylistScreen.EditMode.SERVER : MusicPlaylistScreen.EditMode.LOCAL;
		this.minecraft.setScreen(new AudioFilterScreen(this.parent, next));
	}

	private void navigateTo(PlaylistTabs.Tab tab)
	{
		captureState();
		MusicPlaylistScreen.openTab(this.parent, this.editMode, tab);
	}

	private void closeToParent()
	{
		captureState();
		this.minecraft.setScreen(this.parent);
	}

	private Component modeLabel()
	{
		return text("mode_button", text("mode." + this.editMode.name().toLowerCase(Locale.ROOT)));
	}

	private Component enabledLabel()
	{
		return text(AudioFilterManager.configEnabled() ? "filters.enabled" : "filters.disabled");
	}

	private Component scopeLabel()
	{
		return scopeName(this.scope).copy().append(" v");
	}

	private Component typeLabel()
	{
		return typeName(this.type).copy().append(" v");
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

	private PlaylistDropdown.Item typeItem(AudioFilterDefinition.Type type)
	{
		return new PlaylistDropdown.Item(type.getSerializedName(), typeName(type));
	}

	private static Component typeName(AudioFilterDefinition.Type type)
	{
		return text("filters.type." + type.getSerializedName());
	}

	private static Component scopeName(AudioFilterDefinition.Scope scope)
	{
		return text("filters.scope." + scope.getSerializedName());
	}

	private void closeDropdowns(PlaylistDropdown except)
	{
		if (this.scopeDropdown != except)
			this.scopeDropdown.close();
		if (this.typeDropdown != except)
			this.typeDropdown.close();
		if (this.conditionTypeDropdown != except)
			this.conditionTypeDropdown.close();
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

	private int leftWidth()
	{
		return this.width < 600 ? Math.max(110, Math.min(140, this.width / 4))
				: Math.max(160, Math.min(270, this.width / 3));
	}

	private int detailX()
	{
		return 20 + leftWidth();
	}

	private int filterListTop()
	{
		return this.width - detailX() - 12 < 420 ? 170 : 146;
	}

	private void message(Component message)
	{
		if (this.minecraft.player != null)
			this.minecraft.player.displayClientMessage(Component.literal("[Mob Battle Music] ").append(message), false);
	}

	private static Double parseDouble(String raw, double min, double max)
	{
		try {
			double value = Double.parseDouble(raw);
			return Double.isFinite(value) && value >= min && value <= max ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
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

	private static String formatNumber(double value)
	{
		return value == Math.rint(value) ? String.valueOf((long)value) : String.valueOf(value);
	}

	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}

	private static String trim(String value, int length)
	{
		return value.length() <= length ? value : value.substring(0, Math.max(0, length - 3)) + "...";
	}

	private static Component text(String path, Object... args)
	{
		return Component.translatable("gui.mobbattlemusic.playlist." + path, args);
	}

	private record Entry(AudioFilterDefinition definition, boolean runtime)
	{
		String key()
		{
			return (this.runtime ? "runtime:" : "config:") + this.definition.id();
		}
	}

	private static final class State
	{
		private boolean initialized;
		private int selected = -1;
		private int selectedCondition;
		private int scroll;
		private int conditionScroll;
		private String id = "mobbattlemusic:new_filter";
		private String frequency = "1000";
		private String q = "0.707";
		private String gain = "0";
		private String bitDepth = "12";
		private String sampleRate = "22050";
		private AudioFilterDefinition.Scope scope = AudioFilterDefinition.Scope.MBM;
		private AudioFilterDefinition.Type type = AudioFilterDefinition.Type.LOW_PASS;
		private String conditionType = "mobbattlemusic:underwater";
		private String conditionArgument = "";
		private boolean conditionInverted;
		private List<IdleCondition> conditions = List.of();
	}
}
