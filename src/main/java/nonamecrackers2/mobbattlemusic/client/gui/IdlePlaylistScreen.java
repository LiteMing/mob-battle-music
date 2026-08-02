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
import nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler;
import nonamecrackers2.mobbattlemusic.client.music.IdleConditionStateClient;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadata;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadataCache;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.client.sound.MobBattleTrack;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;

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
	private final PlaylistDropdown orderDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 4);
	private final PlaylistDropdown conditionTypeDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 8);
	private final PlaylistDropdown argumentDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FILTERED, 18, 8);
	private Button modeButton;
	private Button orderButton;
	private Button priorityApplyButton;
	private Button intervalApplyButton;
	private Button previewButton;
	private Button stopButton;
	private Button deleteTrackButton;
	private Button conditionTypeButton;
	private Button conditionInvertButton;
	private Button conditionAddButton;
	private Button conditionDeleteButton;
	private Button addTrackButton;
	private EditBox priorityBox;
	private EditBox intervalBox;
	private EditBox conditionArgumentBox;
	private EditBox ruleBox;
	private EditBox musicBox;
	private int selectedRule;
	private int selectedTrack;
	private int selectedCondition;
	private int ruleScroll;
	private int trackScroll;
	private int conditionScroll;
	private int syncRefreshCooldown;
	private String conditionType;
	private boolean conditionInverted;
	private MobBattleTrack previewTrack;

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
		State state = state();
		this.selectedRule = state.selectedRule;
		this.selectedTrack = state.selectedTrack;
		this.selectedCondition = state.selectedCondition;
		this.ruleScroll = state.ruleScroll;
		this.trackScroll = state.trackScroll;
		this.conditionScroll = state.conditionScroll;
		this.conditionType = state.conditionType;
		this.conditionInverted = state.conditionInverted;
		this.rebuildRules();

		for (Button tab : PlaylistTabs.create(this.width, PlaylistTabs.Tab.IDLE, this::navigateTo))
			this.addRenderableWidget(tab);
		this.modeButton = this.addRenderableWidget(Button.builder(modeLabel(), button -> switchMode())
				.bounds(this.width - 112, 6, 100, 20).build());

		int leftWidth = leftWidth();
		int detailX = 12 + leftWidth + 8;
		int detailWidth = Math.max(180, this.width - detailX - 12);
		boolean compact = detailWidth < 420;
		int settingsY = 58;
		int orderWidth = compact ? 86 : Math.min(126, Math.max(92, detailWidth / 4));
		this.orderButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> toggleOrderDropdown())
				.bounds(detailX, settingsY, orderWidth, 20).build());
		int priorityWidth = compact ? 38 : 44;
		int applyWidth = compact ? 36 : 42;
		this.priorityBox = this.addRenderableWidget(new EditBox(this.font, detailX + orderWidth + 4, settingsY + 1,
				priorityWidth, 18, text("field.priority")));
		this.priorityBox.setMaxLength(5);
		this.priorityBox.setFilter(value -> value.isEmpty() || value.equals("-") || value.matches("-?[0-9]{0,4}"));
		this.priorityBox.setHint(text("field.priority"));
		this.priorityApplyButton = this.addRenderableWidget(Button.builder(text("button.apply"), button -> applyPriority())
				.bounds(this.priorityBox.getX() + priorityWidth + 4, settingsY, applyWidth, 20).build());
		this.intervalBox = this.addRenderableWidget(new EditBox(this.font,
				this.priorityApplyButton.getX() + applyWidth + 4, settingsY + 1, compact ? 44 : 54, 18,
				text("field.interval")));
		this.intervalBox.setMaxLength(5);
		this.intervalBox.setFilter(value -> value.matches("[0-9]{0,5}"));
		this.intervalBox.setHint(text("field.interval"));
		this.intervalApplyButton = this.addRenderableWidget(Button.builder(text("button.apply"), button -> applyInterval())
				.bounds(this.intervalBox.getX() + this.intervalBox.getWidth() + 4, settingsY, applyWidth, 20).build());

		int trackActionY = 84;
		this.previewButton = this.addRenderableWidget(Button.builder(text("button.preview"), button -> previewSelected())
				.bounds(detailX, trackActionY, 62, 20).build());
		this.stopButton = this.addRenderableWidget(Button.builder(text("button.stop"), button -> stopPreview())
				.bounds(detailX + 66, trackActionY, 52, 20).build());
		this.deleteTrackButton = this.addRenderableWidget(Button.builder(text("button.delete"), button -> deleteTrack())
				.bounds(detailX + 122, trackActionY, 56, 20).build());

		int conditionY = this.height - 76;
		int conditionTypeWidth = compact ? 84 : Math.min(150, Math.max(104, detailWidth / 4));
		this.conditionTypeButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> toggleConditionTypeDropdown())
				.bounds(detailX, conditionY, conditionTypeWidth, 20).build());
		int invertWidth = compact ? 32 : 44;
		int conditionAddWidth = compact ? 34 : 44;
		int conditionDeleteWidth = compact ? 40 : 48;
		int conditionArgumentWidth = compact
				? Math.max(42, detailWidth - conditionTypeWidth - invertWidth - conditionAddWidth - conditionDeleteWidth - 20)
				: Math.max(70, detailWidth - conditionTypeWidth - 154);
		this.conditionArgumentBox = this.addRenderableWidget(new EditBox(this.font,
				detailX + conditionTypeWidth + 4, conditionY + 1, conditionArgumentWidth, 18,
				text("field.condition_argument")));
		this.conditionArgumentBox.setMaxLength(512);
		this.conditionArgumentBox.setHint(text("field.condition_argument"));
		this.conditionArgumentBox.setValue(state.conditionArgument);
		this.conditionArgumentBox.setResponder(value -> {
			state().conditionArgument = value;
			this.argumentDropdown.setFilter(value);
			this.updateButtonState();
		});
		int conditionActionsX = this.conditionArgumentBox.getX() + this.conditionArgumentBox.getWidth() + 4;
		this.conditionInvertButton = this.addRenderableWidget(Button.builder(invertLabel(), button -> toggleInverted())
				.bounds(conditionActionsX, conditionY, invertWidth, 20).build());
		this.conditionAddButton = this.addRenderableWidget(Button.builder(text("button.condition_add"), button -> addCondition())
				.bounds(conditionActionsX + invertWidth + 4, conditionY, conditionAddWidth, 20).build());
		this.conditionDeleteButton = this.addRenderableWidget(Button.builder(text("button.condition_delete"), button -> deleteCondition())
				.bounds(conditionActionsX + invertWidth + conditionAddWidth + 8, conditionY,
						conditionDeleteWidth, 20).build());

		int addY = this.height - 52;
		int addWidth = 52;
		int doneWidth = 54;
		int ruleWidth = Math.min(150, Math.max(100, detailWidth / 4));
		this.ruleBox = this.addRenderableWidget(new EditBox(this.font, detailX, addY + 1, ruleWidth, 18,
				text("idle.field.rule")));
		this.ruleBox.setMaxLength(128);
		this.ruleBox.setHint(text("idle.field.rule"));
		this.ruleBox.setValue(state.ruleId);
		this.ruleBox.setResponder(value -> state().ruleId = value);
		this.musicBox = this.addRenderableWidget(new EditBox(this.font, detailX + ruleWidth + 4, addY + 1,
				Math.max(60, detailWidth - ruleWidth - addWidth - doneWidth - 12), 18, text("field.music")));
		this.musicBox.setMaxLength(4096);
		this.musicBox.setHint(text("field.music"));
		this.musicBox.setValue(state.music);
		this.musicBox.setResponder(value -> {
			state().music = value;
			this.updateButtonState();
		});
		this.addTrackButton = this.addRenderableWidget(Button.builder(text("idle.button.add_track"), button -> addTrack())
				.bounds(this.musicBox.getX() + this.musicBox.getWidth() + 4, addY, addWidth, 20).build());
		this.addRenderableWidget(Button.builder(text("button.done"), button -> closeToParent())
				.bounds(this.width - 66, addY, doneWidth, 20).build());

		this.orderDropdown.setItems(List.of(
				orderItem(MusicTracksManager.ExternalSelectionMode.RANDOM),
				orderItem(MusicTracksManager.ExternalSelectionMode.SEQUENTIAL),
				orderItem(MusicTracksManager.ExternalSelectionMode.FIRST)));
		this.orderDropdown.setBounds(detailX, settingsY + 20, Math.max(orderWidth, 126), this.height - 8);
		this.conditionTypeDropdown.setItems(IdleConditionStateClient.descriptors().stream()
				.map(descriptor -> new PlaylistDropdown.Item(descriptor.id().toString(),
						Component.literal(descriptor.displayName()), Component.literal(descriptor.id().toString()), false))
				.toList());
		this.conditionTypeDropdown.setBounds(detailX, conditionY + 20, Math.max(conditionTypeWidth, 180), this.height - 8);
		this.rebuildArgumentDropdown();
		this.argumentDropdown.setBounds(this.conditionArgumentBox.getX(), conditionY + 20,
				Math.max(this.conditionArgumentBox.getWidth(), 180), this.height - 8);
		this.argumentDropdown.setFilter(this.conditionArgumentBox.getValue());
		this.loadSelectedRule(true);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
	{
		this.renderBackground(graphics);
		graphics.drawString(this.font, this.title, 12, 10, 0xFFFFFF, false);
		renderRuleList(graphics, mouseX, mouseY);
		renderRuleDetails(graphics, mouseX, mouseY);
		super.render(graphics, mouseX, mouseY, partialTick);
		this.orderDropdown.render(graphics, this.font, mouseX, mouseY);
		this.conditionTypeDropdown.render(graphics, this.font, mouseX, mouseY);
		this.argumentDropdown.render(graphics, this.font, mouseX, mouseY);
	}

	private void renderRuleList(GuiGraphics graphics, int mouseX, int mouseY)
	{
		int x = 12;
		int y = 56;
		int width = leftWidth();
		int bottom = this.height - 32;
		graphics.fill(x - 2, y - 2, x + width + 2, bottom + 2, 0x90000000);
		int rowHeight = 32;
		int visible = Math.max(1, (bottom - y) / rowHeight);
		this.ruleScroll = clamp(this.ruleScroll, 0, Math.max(0, this.rules.size() - visible));
		for (int row = 0; row < visible; row++) {
			int index = this.ruleScroll + row;
			if (index >= this.rules.size())
				break;
			Rule rule = this.rules.get(index);
			int rowY = y + row * rowHeight;
			boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + rowHeight;
			graphics.fill(x, rowY, x + width, rowY + rowHeight - 2,
					index == this.selectedRule ? 0xAA38546E : hovered ? 0x70405058 : 0x40202020);
			graphics.drawString(this.font, trim(rule.binding().target(), Math.max(12, width / 6)), x + 6, rowY + 5,
					0xFFFFFF, false);
			boolean active = isActive(rule);
			Component detail = text("idle.rule_summary", rule.playlist().entries().size(),
					active ? text("idle.active") : text("idle.inactive"));
			graphics.drawString(this.font, trim(detail.getString(), Math.max(12, width / 6)), x + 6, rowY + 18,
					active ? 0x84D49A : 0xA0A0A0, false);
		}
		if (this.rules.isEmpty())
			graphics.drawString(this.font, text("idle.empty"), x + 8, y + 8, 0xA0A0A0, false);
	}

	private void renderRuleDetails(GuiGraphics graphics, int mouseX, int mouseY)
	{
		int x = detailX();
		int right = this.width - 12;
		int top = 56;
		int bottom = this.height - 80;
		graphics.fill(x - 2, top - 2, right, bottom + 2, 0x90000000);
		Rule rule = selectedRule();
		if (rule == null) {
			graphics.drawString(this.font, text("idle.select_rule"), x + 8, 112, 0xA0A0A0, false);
			return;
		}
		graphics.drawString(this.font, rule.binding().target(), x + 184, 90, 0xD8D8D8, false);
		int trackTop = 108;
		int split = idleSplit(top, bottom, trackTop);
		graphics.drawString(this.font, text("idle.tracks", rule.playlist().entries().size()), x + 2, trackTop - 12,
				0xA0A0A0, false);
		renderTracks(graphics, rule, mouseX, mouseY, x, trackTop, right - x, split - trackTop);
		List<IdleCondition> conditions = rule.settings().idleConditions();
		graphics.drawString(this.font, text("idle.conditions", conditions.size()), x + 2, split + 4,
				0xA0A0A0, false);
		renderConditions(graphics, conditions, mouseX, mouseY, x, split + 17, right - x,
				Math.max(0, bottom - split - 20));
	}

	private void renderTracks(GuiGraphics graphics, Rule rule, int mouseX, int mouseY, int x, int y, int width, int height)
	{
		int rowHeight = 18;
		int visible = Math.max(1, height / rowHeight);
		this.trackScroll = clamp(this.trackScroll, 0, Math.max(0, rule.playlist().entries().size() - visible));
		for (int row = 0; row < visible; row++) {
			int index = this.trackScroll + row;
			if (index >= rule.playlist().entries().size())
				break;
			MusicTracksManager.ExternalPlaylistEntry entry = rule.playlist().entries().get(index);
			int rowY = y + row * rowHeight;
			boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + rowHeight;
			if (index == this.selectedTrack || hovered)
				graphics.fill(x, rowY, x + width, rowY + rowHeight - 1,
						index == this.selectedTrack ? 0x8038546E : 0x50405058);
			MusicMetadata metadata = MusicMetadataCache.getInstance().get(entry.url()).orElse(null);
			String title = metadata == null ? entry.name() : metadata.displayTitle(entry.name());
			graphics.drawString(this.font, (index + 1) + ". " + trim(title, Math.max(12, width / 6)), x + 4,
					rowY + 5, 0xD8D8D8, false);
		}
	}

	private void renderConditions(GuiGraphics graphics, List<IdleCondition> conditions, int mouseX, int mouseY,
			int x, int y, int width, int height)
	{
		int rowHeight = 18;
		int visible = Math.max(1, height / rowHeight);
		this.conditionScroll = clamp(this.conditionScroll, 0, Math.max(0, conditions.size() - visible));
		for (int row = 0; row < visible; row++) {
			int index = this.conditionScroll + row;
			if (index >= conditions.size())
				break;
			IdleCondition condition = conditions.get(index);
			int rowY = y + row * rowHeight;
			boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + rowHeight;
			if (index == this.selectedCondition || hovered)
				graphics.fill(x, rowY, x + width, rowY + rowHeight - 1,
						index == this.selectedCondition ? 0x8038546E : 0x50405058);
			String value = (index + 1) + ". " + (condition.inverted() ? "NOT " : "") + condition.type() +
					(condition.argument().isBlank() ? "" : "  " + condition.argument());
			graphics.drawString(this.font, trim(value, Math.max(12, width / 6)), x + 4, rowY + 5,
					0xD8D8D8, false);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		if (handleDropdownClick(this.orderDropdown, mouseX, mouseY, button, item -> selectOrder(item.id())) ||
				handleDropdownClick(this.conditionTypeDropdown, mouseX, mouseY, button,
						item -> selectConditionType(item.id())) ||
				handleDropdownClick(this.argumentDropdown, mouseX, mouseY, button,
						item -> selectArgument(item.id())))
			return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.conditionArgumentBox.isMouseOver(mouseX, mouseY))
			openArgumentDropdown();
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && selectListRow(mouseX, mouseY))
			return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private boolean selectListRow(double mouseX, double mouseY)
	{
		int left = 12;
		int top = 56;
		int bottom = this.height - 32;
		if (mouseX >= left && mouseX < left + leftWidth() && mouseY >= top && mouseY < bottom) {
			int index = this.ruleScroll + (int)((mouseY - top) / 32);
			if (index >= 0 && index < this.rules.size()) {
				this.selectedRule = index;
				this.selectedTrack = 0;
				this.selectedCondition = 0;
				this.trackScroll = 0;
				this.conditionScroll = 0;
				this.loadSelectedRule(true);
				return true;
			}
		}
		Rule rule = selectedRule();
		if (rule == null)
			return false;
		int x = detailX();
		int detailBottom = this.height - 80;
		int trackTop = 108;
		int split = idleSplit(56, detailBottom, trackTop);
		if (mouseX >= x && mouseX < this.width - 12 && mouseY >= trackTop && mouseY < split) {
			int index = this.trackScroll + (int)((mouseY - trackTop) / 18);
			if (index >= 0 && index < rule.playlist().entries().size()) {
				this.selectedTrack = index;
				this.updateButtonState();
				return true;
			}
		}
		int conditionTop = split + 17;
		if (mouseX >= x && mouseX < this.width - 12 && mouseY >= conditionTop && mouseY < detailBottom) {
			int index = this.conditionScroll + (int)((mouseY - conditionTop) / 18);
			if (index >= 0 && index < rule.settings().idleConditions().size()) {
				this.selectedCondition = index;
				this.updateButtonState();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta)
	{
		if (this.orderDropdown.mouseScrolled(delta) || this.conditionTypeDropdown.mouseScrolled(delta) ||
				this.argumentDropdown.mouseScrolled(delta))
			return true;
		if (mouseX < detailX())
			this.ruleScroll = Math.max(0, this.ruleScroll - (int)Math.signum(delta));
		else if (mouseY < 56 + (this.height - 136) / 2)
			this.trackScroll = Math.max(0, this.trackScroll - (int)Math.signum(delta));
		else
			this.conditionScroll = Math.max(0, this.conditionScroll - (int)Math.signum(delta));
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers)
	{
		if (handleDropdownKey(this.orderDropdown, keyCode, item -> selectOrder(item.id())) ||
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
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void tick()
	{
		super.tick();
		if (this.syncRefreshCooldown > 0 && --this.syncRefreshCooldown == 0)
			MobBattleMusicNetwork.requestServerExternalPlaylistSync();
	}

	@Override
	public void onClose()
	{
		stopPreview();
		closeToParent();
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
					Math.max(0, rule.settings().idleConditions().size() - 1));
			this.priorityBox.setValue(String.valueOf(rule.settings().priority()));
			this.intervalBox.setValue(String.valueOf(rule.settings().idleIntervalSeconds()));
			this.orderButton.setMessage(orderLabel(rule.settings().selectionMode()));
			if (updateRuleField)
				this.ruleBox.setValue(rule.binding().target());
		}
		this.conditionTypeButton.setMessage(conditionTypeLabel());
		this.conditionInvertButton.setMessage(invertLabel());
		this.updateButtonState();
	}

	private void updateButtonState()
	{
		Rule rule = selectedRule();
		boolean selected = rule != null;
		this.orderButton.active = selected;
		this.priorityBox.active = selected;
		this.priorityApplyButton.active = selected;
		this.intervalBox.active = selected;
		this.intervalApplyButton.active = selected;
		this.previewButton.active = selected && selectedTrackEntry() != null;
		this.deleteTrackButton.active = selected && selectedTrackEntry() != null;
		this.conditionTypeButton.active = selected;
		this.conditionArgumentBox.active = selected;
		this.conditionInvertButton.active = selected;
		this.conditionAddButton.active = selected && ResourceLocation.tryParse(this.conditionType) != null;
		this.conditionDeleteButton.active = selected && !rule.settings().idleConditions().isEmpty();
		this.addTrackButton.active = !this.ruleBox.getValue().isBlank() && !this.musicBox.getValue().isBlank();
	}

	private void addTrack()
	{
		String ruleId = this.ruleBox.getValue().trim();
		String music = this.musicBox.getValue().trim();
		if (ResourceLocation.tryParse(ruleId) == null || music.isBlank()) {
			message(text("idle.message.rule_music_required"));
			return;
		}
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			runServerCommand("mobbattlemusic idle rule " + ruleId + " add " + music);
		else {
			message(MusicTracksManager.getInstance().addLocalIdleRuleUrl(ruleId, music).message());
			refreshRules();
		}
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

	private void previewSelected()
	{
		MusicTracksManager.ExternalPlaylistEntry entry = selectedTrackEntry();
		if (entry == null)
			return;
		stopPreview();
		ResourceLocation sound = soundLocation(entry.url());
		if (sound != null) {
			this.previewTrack = new MobBattleTrack(sound, 20);
			this.minecraft.getSoundManager().play(this.previewTrack);
		} else {
			ExternalMusicHandler.getInstance().playPreviewMusic(entry.url(), 20, 0L);
		}
	}

	private void stopPreview()
	{
		ExternalMusicHandler.getInstance().stopPreviewMusic();
		if (this.previewTrack != null) {
			this.previewTrack.stop();
			this.previewTrack = null;
		}
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
		if (rule == null || ResourceLocation.tryParse(this.conditionType) == null)
			return;
		String argument = this.conditionArgumentBox.getValue().trim();
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER) {
			String command = "mobbattlemusic idle rule " + rule.binding().target() + " condition " +
					(this.conditionInverted ? "add_not " : "add ") + this.conditionType;
			if (!argument.isBlank())
				command += " " + argument;
			runServerCommand(command);
		} else {
			message(MusicTracksManager.getInstance().addLocalIdleCondition(rule.binding(),
					new IdleCondition(this.conditionType, argument, this.conditionInverted)).message());
			refreshRules();
		}
	}

	private void deleteCondition()
	{
		Rule rule = selectedRule();
		if (rule == null || rule.settings().idleConditions().isEmpty())
			return;
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			runServerCommand("mobbattlemusic idle rule " + rule.binding().target() + " condition delete " +
					(this.selectedCondition + 1));
		else {
			message(MusicTracksManager.getInstance().deleteLocalIdleCondition(rule.binding(), this.selectedCondition).message());
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
		ResourceLocation id = ResourceLocation.tryParse(rule.binding().target());
		if (id == null)
			return false;
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			return IdleConditionStateClient.isActive(id);
		return this.minecraft.player != null && IdleConditionRegistry.test(this.minecraft.player,
				rule.settings().idleConditions());
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
		saveState();
		stopPreview();
		MusicPlaylistScreen.EditMode next = this.editMode == MusicPlaylistScreen.EditMode.LOCAL
				? MusicPlaylistScreen.EditMode.SERVER : MusicPlaylistScreen.EditMode.LOCAL;
		this.minecraft.setScreen(new IdlePlaylistScreen(this.parent, next));
	}

	private void closeToParent()
	{
		saveState();
		this.minecraft.setScreen(this.parent);
	}

	private void saveState()
	{
		State state = state();
		state.selectedRule = this.selectedRule;
		state.selectedTrack = this.selectedTrack;
		state.selectedCondition = this.selectedCondition;
		state.ruleScroll = this.ruleScroll;
		state.trackScroll = this.trackScroll;
		state.conditionScroll = this.conditionScroll;
		state.conditionType = this.conditionType;
		state.conditionInverted = this.conditionInverted;
		if (this.ruleBox != null)
			state.ruleId = this.ruleBox.getValue();
		if (this.musicBox != null)
			state.music = this.musicBox.getValue();
		if (this.conditionArgumentBox != null)
			state.conditionArgument = this.conditionArgumentBox.getValue();
	}

	private State state()
	{
		return STATES.get(this.editMode);
	}

	private int leftWidth()
	{
		return this.width < 600 ? Math.max(110, Math.min(140, this.width / 4))
				: Math.max(130, Math.min(230, this.width / 3));
	}

	private int detailX()
	{
		return 20 + leftWidth();
	}

	private int idleSplit(int top, int bottom, int trackTop)
	{
		return this.height < 320 ? Math.min(bottom - 32, trackTop + 20)
				: Math.max(trackTop + 42, top + (bottom - top) / 2);
	}

	private Component modeLabel()
	{
		return text("mode_button", text("mode." + this.editMode.name().toLowerCase(Locale.ROOT)));
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
			this.minecraft.player.displayClientMessage(Component.literal("[Mob Battle Music] ").append(message), false);
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

	private static String trim(String value, int length)
	{
		return value.length() <= length ? value : value.substring(0, Math.max(0, length - 3)) + "...";
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
		private int ruleScroll;
		private int trackScroll;
		private int conditionScroll;
		private String ruleId = "mobbattlemusic:default";
		private String music = "";
		private String conditionType = "mobbattlemusic:dimension";
		private String conditionArgument = "";
		private boolean conditionInverted;
	}
}
