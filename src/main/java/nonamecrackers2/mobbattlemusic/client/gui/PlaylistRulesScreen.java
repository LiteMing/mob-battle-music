package nonamecrackers2.mobbattlemusic.client.gui;

import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.music.IdleConditionStateClient;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;

/**
 * K16-B: 歌单规则编辑器 - the playlist-level rule. Conditions set here are
 * inherited by every entry of the playlist that carries no conditions of its
 * own; an entry with its own conditions overrides the rule (conflict ->
 * entry wins, highlighted in the track view).
 */
public class PlaylistRulesScreen extends Screen
{
	private static final int ROW_HEIGHT = 18;

	private final Screen parent;
	private final MusicPlaylistScreen.EditMode editMode;
	private final ResourceLocation playlist;
	private final MusicTracksManager.DynamicBinding binding;

	private Button conditionTypeButton;
	private EditBox conditionArgumentBox;
	private Button conditionInvertButton;
	private Button conditionAddButton;
	private Button conditionDeleteButton;

	private String conditionType = "mobbattlemusic:dimension";
	private boolean conditionInverted;
	private int selectedCondition = -1;

	private final PlaylistDropdown conditionTypeDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 8);
	private final PlaylistDropdown argumentDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FILTERED, 18, 8);

	PlaylistRulesScreen(Screen parent, MusicPlaylistScreen.EditMode editMode, ResourceLocation playlist,
			MusicTracksManager.DynamicBinding binding)
	{
		super(Component.literal("Playlist Rules"));
		this.parent = parent;
		this.editMode = editMode;
		this.playlist = playlist;
		this.binding = binding;
	}

	@Override
	protected void init()
	{
		super.init();
		int x = 20;
		int iw = Math.max(240, this.width - 40);
		int editorY = this.height - 82;
		int half = Math.max(36, (iw - 8) / 2);

		this.addRenderableWidget(Button.builder(Component.literal("\u2713 Done"), button -> closeToParent())
				.bounds(this.width - 112, 6, 100, 20).build());

		this.conditionTypeButton = this.addRenderableWidget(Button.builder(conditionTypeLabel(),
				button -> toggleConditionTypeDropdown()).bounds(x, editorY, half, 20).build());
		this.conditionArgumentBox = this.addRenderableWidget(new EditBox(this.font, x + half + 8, editorY + 1,
				Math.max(24, iw - half - 8), 18, Component.literal("condition argument")));
		this.conditionArgumentBox.setMaxLength(512);
		this.conditionArgumentBox.setHint(Component.literal("condition argument"));
		this.conditionArgumentBox.setResponder(value -> {
			this.argumentDropdown.setFilter(value);
			updateButtonState();
		});

		int actionY = editorY + 25;
		int actionWidth = Math.max(28, (iw - 12) / 4);
		this.conditionInvertButton = this.addRenderableWidget(Button.builder(invertLabel(),
				button -> toggleInverted()).bounds(x, actionY, actionWidth, 20).build());
		this.conditionAddButton = this.addRenderableWidget(Button.builder(Component.literal("+ \u6dfb\u52a0"),
				button -> addCondition()).bounds(x + actionWidth + 4, actionY, actionWidth, 20).build());
		this.conditionDeleteButton = this.addRenderableWidget(Button.builder(Component.literal("\u2715 \u5220\u9664"),
				button -> deleteCondition()).bounds(x + (actionWidth + 4) * 2, actionY,
						Math.max(20, iw - (actionWidth + 4) * 2), 20).build());

		this.conditionTypeDropdown.setItems(IdleConditionStateClient.descriptors().stream()
				.map(descriptor -> new PlaylistDropdown.Item(descriptor.id().toString(),
						Component.literal(conditionTypeName(descriptor.id().toString())),
						Component.literal(descriptor.id().toString()), false))
				.toList());
		this.conditionTypeDropdown.setBounds(x, editorY + 20, iw, this.height - 4);
		this.rebuildArgumentDropdown();
		this.argumentDropdown.setBounds(x, editorY + 20, iw, this.height - 4);

		updateButtonState();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
	{
		this.renderBackground(graphics);
		String title = "\u6b4c\u5355\u89c4\u5219 \u00b7 " + describeBinding();
		graphics.drawString(this.font, trimPixels(title, this.width - 240), 20, 14, 0xFFF0F1F2, false);
		graphics.drawString(this.font, Component.literal("playlist: " + this.playlist), 20, 28, 0xFF9AA2AD, false);
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER) {
			graphics.drawString(this.font,
					Component.literal("\u26a0 SERVER \u6a21\u5f0f\u6b64\u7248\u672c\u4e0d\u652f\u6301\u7f16\u8f91\u6b4c\u5355\u89c4\u5219"),
					20, 44, 0xFFE6C07A, false);
		} else if (conditions().isEmpty()) {
			graphics.drawString(this.font,
					Component.literal("\u8be5\u6b4c\u5355\u6ca1\u6709\u89c4\u5219 - \u5185\u5bb9\u65e0\u6761\u4ef6\u64ad\u653e\uff0c\u4e0b\u9762\u6dfb\u52a0\u5219\u7ee7\u627f\u5230\u5168\u90e8\u6b4c\u66f2"),
					20, 44, 0xFF8B929C, false);
		} else {
			graphics.drawString(this.font,
					Component.literal("\u89c4\u5219\u7ee7\u627f\uff1a\u5217\u8868\u91cc\u7684\u6761\u4ef6\u9ed8\u8ba4\u7528\u4e8e\u8be5\u6b4c\u5355\u5185\u6240\u6709\u6b4c\u66f2\uff1b\u67d0\u9996\u6b4c\u81ea\u5e26\u6761\u4ef6\u65f6\u4ee5\u6b4c\u66f2\u6761\u4ef6\u4e3a\u51c6"),
					20, 44, 0xFF9AA2AD, false);
		}
		renderConditionList(graphics, mouseX, mouseY);
		graphics.drawString(this.font, Component.literal("\u6761\u4ef6\u7c7b\u578b"), 20, this.height - 94, 0xFF9AA2AD, false);
		graphics.drawString(this.font, Component.literal("\u53c2\u6570"),
				20 + this.conditionTypeButton.getWidth() + 8, this.height - 94, 0xFF9AA2AD, false);
		this.conditionTypeDropdown.render(graphics, this.font, mouseX, mouseY);
		this.argumentDropdown.render(graphics, this.font, mouseX, mouseY);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	private void renderConditionList(GuiGraphics graphics, int mouseX, int mouseY)
	{
		List<IdleCondition> conditions = conditions();
		int x = 20;
		int width = this.width - 40;
		int rowY = 66;
		int visible = Math.min(conditions.size(), Math.max(0, (this.height - 100 - rowY) / ROW_HEIGHT));
		for (int i = 0; i < visible; i++) {
			IdleCondition condition = conditions.get(i);
			boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY + i * ROW_HEIGHT
					&& mouseY < rowY + i * ROW_HEIGHT + ROW_HEIGHT;
			if (i == this.selectedCondition || hovered)
				graphics.fill(x, rowY + i * ROW_HEIGHT, x + width, rowY + i * ROW_HEIGHT + ROW_HEIGHT - 1,
						i == this.selectedCondition ? 0xFF293B4A : 0xFF252A30);
			String value = (i + 1) + ". " + describeCondition(condition);
			graphics.drawString(this.font, trimPixels(value, width - 20), x + 3, rowY + i * ROW_HEIGHT + 4,
					0xFFD6D9DE, false);
			graphics.drawString(this.font, "x", x + width - 9, rowY + i * ROW_HEIGHT + 4, 0xFFE06C75, false);
		}
	}

	/**
	 * K16-B: human-readable condition, e.g. "\u7ef4\u5ea6: minecraft:the_end" or
	 * "NOT \u5efa\u7b51: minecraft:fortress".
	 */
	private String describeCondition(IdleCondition condition)
	{
		String value = (condition.inverted() ? "NOT " : "") + conditionTypeName(condition.type());
		if (!condition.argument().isBlank())
			value += ": " + condition.argument();
		return value;
	}

	private String conditionTypeName(String type)
	{
		ResourceLocation id = IdleConditionRegistry.normalizeId(type);
		if (id != null) {
			for (IdleConditionRegistry.Descriptor descriptor : IdleConditionStateClient.descriptors()) {
				if (descriptor.id().equals(id))
					return descriptor.displayName();
			}
			return switch (id.getPath()) {
				case "dimension" -> "\u7ef4\u5ea6";
				case "biome" -> "\u7fa4\u7cfb";
				case "structure" -> "\u5efa\u7b51";
				case "underwater" -> "\u6c34\u4e0b";
				case "entity" -> "\u9644\u8fd1\u5b9e\u4f53";
				case "scene" -> "\u573a\u666f";
				default -> id.toString();
			};
		}
		return type;
	}

	private String describeBinding()
	{
		if (this.binding == null)
			return this.playlist.toString();
		MusicTracksManager manager = MusicTracksManager.getInstance();
		String described = manager.describeTrackContext(this.playlist);
		return described == null || described.isBlank() ? this.binding.serializedKey() : described;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		if (handleDropdownClick(this.conditionTypeDropdown, mouseX, mouseY, button, item -> selectConditionType(item.id())))
			return true;
		if (handleDropdownClick(this.argumentDropdown, mouseX, mouseY, button, item -> {
			this.conditionArgumentBox.setValue(item.id());
			this.conditionArgumentBox.setCursorPosition(item.id().length());
		}))
			return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.conditionArgumentBox.isMouseOver(mouseX, mouseY))
			openArgumentDropdown();
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && clickConditionRow(mouseX, mouseY))
			return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers)
	{
		if (handleDropdownKey(this.conditionTypeDropdown, keyCode, item -> selectConditionType(item.id())))
			return true;
		if (handleDropdownKey(this.argumentDropdown, keyCode, item -> {
			this.conditionArgumentBox.setValue(item.id());
			this.conditionArgumentBox.setCursorPosition(item.id().length());
		}))
			return true;
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta)
	{
		if (this.conditionTypeDropdown.mouseScrolled(delta) || this.argumentDropdown.mouseScrolled(delta))
			return true;
		return super.mouseScrolled(mouseX, mouseY, delta);
	}

	private boolean clickConditionRow(double mouseX, double mouseY)
	{
		int x = 20;
		int width = this.width - 40;
		List<IdleCondition> conditions = conditions();
		int visible = Math.min(conditions.size(), Math.max(0, (this.height - 100 - 66) / ROW_HEIGHT));
		for (int i = 0; i < visible; i++) {
			if (mouseX < x || mouseX >= x + width || mouseY < 66 + i * ROW_HEIGHT
					|| mouseY >= 66 + i * ROW_HEIGHT + ROW_HEIGHT)
				continue;
			this.selectedCondition = i;
			if (mouseX >= x + width - 16)
				deleteCondition();
			else
				updateButtonState();
			return true;
		}
		return false;
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
		this.conditionTypeButton.setMessage(conditionTypeLabel());
		this.rebuildArgumentDropdown();
		updateButtonState();
	}

	private void openArgumentDropdown()
	{
		if (argumentSuggestions().isEmpty())
			return;
		closeDropdowns(this.argumentDropdown);
		this.argumentDropdown.setFilter(this.conditionArgumentBox.getValue());
		this.argumentDropdown.open(this.conditionArgumentBox.getValue());
	}

	private void toggleInverted()
	{
		this.conditionInverted = !this.conditionInverted;
		this.conditionInvertButton.setMessage(invertLabel());
	}

	private void addCondition()
	{
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			return;
		if (IdleConditionRegistry.normalizeId(this.conditionType) == null)
			return;
		String argument = this.conditionArgumentBox.getValue().trim();
		MusicTracksManager manager = MusicTracksManager.getInstance();
		message(manager.addLocalIdleCondition(this.binding,
				new IdleCondition(this.conditionType, argument, this.conditionInverted)).message());
		this.selectedCondition = Math.max(0, conditions().size() - 1);
		this.conditionArgumentBox.setValue("");
		refreshConditions();
	}

	private void deleteCondition()
	{
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			return;
		List<IdleCondition> conditions = conditions();
		if (this.selectedCondition < 0 || this.selectedCondition >= conditions.size())
			return;
		MusicTracksManager manager = MusicTracksManager.getInstance();
		message(manager.deleteLocalIdleCondition(this.binding, this.selectedCondition).message());
		this.selectedCondition = -1;
		refreshConditions();
	}

	private void refreshConditions()
	{
		updateButtonState();
		if (this.parent instanceof MusicPlaylistScreen playlistScreen)
			playlistScreen.refreshAfterImport();
	}

	private List<IdleCondition> conditions()
	{
		MusicTracksManager manager = MusicTracksManager.getInstance();
		MusicTracksManager.DynamicPlaylistSettings settings = manager.dynamicSettings(this.playlist);
		return settings == null ? List.of() : settings.idleConditions();
	}

	private void rebuildArgumentDropdown()
	{
		this.argumentDropdown.setItems(argumentSuggestions().stream()
				.map(value -> new PlaylistDropdown.Item(value, Component.literal(value))).toList());
	}

	/**
	 * K16-B: full registry enumeration - every registered
	 * dimension/biome/structure/entity appears in the suggestion list.
	 */
	private List<String> argumentSuggestions()
	{
		if (this.minecraft == null || this.minecraft.level == null)
			return List.of();
		ResourceLocation normalized = IdleConditionRegistry.normalizeId(this.conditionType);
		String type = normalized == null ? "" : normalized.getPath();
		return switch (type) {
			case "dimension" -> this.minecraft.getConnection() == null ? List.of()
					: this.minecraft.getConnection().levels().stream().map(key -> key.location().toString())
							.sorted().toList();
			case "biome" -> this.minecraft.level.registryAccess().registry(Registries.BIOME).stream()
					.flatMap(registry -> registry.keySet().stream()).map(ResourceLocation::toString)
					.sorted().toList();
			case "structure" -> this.minecraft.level.registryAccess().registry(Registries.STRUCTURE).stream()
					.flatMap(registry -> registry.keySet().stream()).map(ResourceLocation::toString)
					.sorted().toList();
			case "entity" -> BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(ResourceLocation::toString)
					.sorted().toList();
			default -> List.of();
		};
	}

	private void updateButtonState()
	{
		boolean server = this.editMode == MusicPlaylistScreen.EditMode.SERVER;
		this.conditionTypeButton.active = !server;
		this.conditionArgumentBox.active = !server;
		this.conditionInvertButton.active = !server;
		this.conditionAddButton.active = !server && IdleConditionRegistry.normalizeId(this.conditionType) != null;
		this.conditionDeleteButton.active = !server && this.selectedCondition >= 0
				&& this.selectedCondition < conditions().size();
	}

	private Component conditionTypeLabel()
	{
		return Component.literal(conditionTypeName(this.conditionType));
	}

	private Component invertLabel()
	{
		return Component.literal(this.conditionInverted ? "\u53cd\u8f6c(NOT)" : "\u6b63\u5e38");
	}

	private void closeDropdowns(PlaylistDropdown except)
	{
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

	private void closeToParent()
	{
		this.minecraft.setScreen(this.parent);
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

	private String trimPixels(String value, int width)
	{
		if (this.font.width(value) <= width)
			return value;
		String suffix = "...";
		return this.font.plainSubstrByWidth(value, Math.max(0, width - this.font.width(suffix))) + suffix;
	}
}
