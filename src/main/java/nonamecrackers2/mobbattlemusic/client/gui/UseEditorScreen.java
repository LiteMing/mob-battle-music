package nonamecrackers2.mobbattlemusic.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.client.resource.TrackAssetRegistry;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry;

/**
 * K16-B: 用途编辑器 - the music-first view of ONE track: every use (playlist
 * entry) of it, with a readable condition summary, click-to-jump, remove,
 * and add-to-a-new-use. Local mode only for mutations; server playlists are
 * shown read-only.
 */
public class UseEditorScreen extends Screen
{
	private static final int ROW_HEIGHT = 18;

	private final Screen parent;
	private final MusicPlaylistScreen.EditMode editMode;
	private final String trackId;
	private final String title;
	// K16-B: batch mode - every checked track's URL gets the new use; the
	// first URL drives the track identity shown in the header
	private final List<String> urls;

	private Button kindButton;
	private Button sceneButton;
	private Button targetButton;
	private Button addButton;

	private String kind = "scene";
	private String scene = "aggressive";
	private String target = "";

	private final PlaylistDropdown kindDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 8);
	private final PlaylistDropdown sceneDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 6);
	private final PlaylistDropdown targetDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FILTERED, 18, 8);

	UseEditorScreen(Screen parent, MusicPlaylistScreen.EditMode editMode, String trackId, String title,
			List<String> urls)
	{
		super(Component.literal("Use Editor"));
		this.parent = parent;
		this.editMode = editMode;
		this.trackId = trackId;
		this.title = title;
		this.urls = urls == null || urls.isEmpty() ? List.of() : List.copyOf(urls);
	}

	@Override
	protected void init()
	{
		super.init();
		int x = 20;
		int iw = Math.max(240, this.width - 40);
		int editorY = this.height - 60;
		int third = Math.max(24, (iw - 12) / 3);

		this.addRenderableWidget(Button.builder(Component.literal("\u2713 Done"), button -> closeToParent())
				.bounds(this.width - 112, 6, 100, 20).build());

		this.kindButton = this.addRenderableWidget(Button.builder(Component.literal(kindLabel()), button -> toggleKindDropdown())
				.bounds(x, editorY, third, 20).build());
		this.sceneButton = this.addRenderableWidget(Button.builder(Component.literal(sceneLabel()),
				button -> toggleSceneDropdown()).bounds(x + third + 4, editorY, third, 20).build());
		this.targetButton = this.addRenderableWidget(Button.builder(Component.literal("\u76ee\u6807..."),
				button -> toggleTargetDropdown()).bounds(x + (third + 4) * 2, editorY,
						Math.max(24, iw - (third + 4) * 2), 20).build());
		this.addButton = this.addRenderableWidget(Button.builder(Component.literal("+ \u6dfb\u52a0\u7528\u9014"),
				button -> addUse()).bounds(x, editorY + 24, iw, 20).build());

		this.kindDropdown.setItems(List.of(
				new PlaylistDropdown.Item("scene", Component.literal("\u573a\u666f")),
				new PlaylistDropdown.Item("entity_type", Component.literal("\u5b9e\u4f53\u7c7b\u578b")),
				new PlaylistDropdown.Item("entity_uuid", Component.literal("\u73a9\u5bb6")),
				new PlaylistDropdown.Item("idle_rule", Component.literal("idle \u89c4\u5219"))));
		this.kindDropdown.setBounds(x, editorY + 20, iw, this.height - 4);
		this.sceneDropdown.setItems(List.of(
				new PlaylistDropdown.Item("aggressive", Component.literal("\u6218\u6597\u66f2")),
				new PlaylistDropdown.Item("ambient", Component.literal("\u73af\u5883\u66f2")),
				new PlaylistDropdown.Item("player", Component.literal("\u73a9\u5bb6\u66f2")),
				new PlaylistDropdown.Item("idle", Component.literal("\u95f2\u7f6e\u66f2"))));
		this.sceneDropdown.setBounds(x, editorY + 20, iw, this.height - 4);
		this.rebuildTargets();
		this.targetDropdown.setBounds(x, editorY + 20, iw, this.height - 4);

		updateButtonState();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
	{
		this.renderBackground(graphics);
		graphics.drawString(this.font, trimPixels("\u7528\u9014 \u00b7 " + this.title, this.width - 240), 20, 14,
				0xFFF0F1F2, false);
		List<TrackAssetRegistry.TrackUse> uses = uses();
		if (uses.isEmpty()) {
			graphics.drawString(this.font, Component.literal("\u8fd9\u9996\u6b4c\u8fd8\u6ca1\u6709\u7528\u9014 - \u5728\u4e0b\u9762\u6dfb\u52a0"),
					20, 44, 0xFF8B929C, false);
		} else {
			graphics.drawString(this.font,
					Component.literal("\u70b9\u51fb\u884c\u8df3\u8f6c\u5230\u8be5\u7528\u9014\uff1b\u53f3\u4fa7 x \u79fb\u9664\uff08\u4ec5\u672c\u5730\uff09"),
					20, 44, 0xFF9AA2AD, false);
		}
		renderUseList(graphics, mouseX, mouseY);
		graphics.drawString(this.font, Component.literal("\u65b0\u589e\u7528\u9014\uff1a\u7c7b\u578b / \u573a\u666f / \u76ee\u6807"), 20,
				this.height - 74, 0xFF9AA2AD, false);
		this.kindDropdown.render(graphics, this.font, mouseX, mouseY);
		this.sceneDropdown.render(graphics, this.font, mouseX, mouseY);
		this.targetDropdown.render(graphics, this.font, mouseX, mouseY);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	private void renderUseList(GuiGraphics graphics, int mouseX, int mouseY)
	{
		List<TrackAssetRegistry.TrackUse> uses = uses();
		int x = 20;
		int width = this.width - 40;
		int rowY = 62;
		int visible = Math.min(uses.size(), Math.max(0, (this.height - 84 - rowY) / ROW_HEIGHT));
		MusicTracksManager manager = MusicTracksManager.getInstance();
		for (int i = 0; i < visible; i++) {
			TrackAssetRegistry.TrackUse use = uses.get(i);
			MusicTracksManager.DynamicSource source = manager.dynamicSource(use.playlistId());
			boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY + i * ROW_HEIGHT
					&& mouseY < rowY + i * ROW_HEIGHT + ROW_HEIGHT;
			if (hovered)
				graphics.fill(x, rowY + i * ROW_HEIGHT, x + width, rowY + i * ROW_HEIGHT + ROW_HEIGHT - 1, 0xFF252A30);
			String context = manager.describeTrackContext(use.playlistId());
			String sourceText = source == null ? "RP" : source.name().charAt(0)
					+ source.name().substring(1).toLowerCase(java.util.Locale.ROOT);
			StringBuilder line = new StringBuilder();
			line.append(context == null || context.isBlank() ? use.playlistId() : context);
			line.append("  #").append(use.entryIndex() + 1);
			String conditions = describeConditions(use.entryConditions());
			if (!conditions.isBlank())
				line.append("  \u26a1").append(conditions);
			line.append("  [").append(sourceText).append("]");
			graphics.drawString(this.font, trimPixels(line.toString(), width - 20), x + 3,
					rowY + i * ROW_HEIGHT + 4, 0xFFD6D9DE, false);
			graphics.drawString(this.font, "x", x + width - 9, rowY + i * ROW_HEIGHT + 4,
					source == MusicTracksManager.DynamicSource.LOCAL ? 0xFFE06C75 : 0xFF3A4550, false);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		if (handleDropdownClick(this.kindDropdown, mouseX, mouseY, button, item -> selectKind(item.id())))
			return true;
		if (handleDropdownClick(this.sceneDropdown, mouseX, mouseY, button, item -> selectScene(item.id())))
			return true;
		if (handleDropdownClick(this.targetDropdown, mouseX, mouseY, button, item -> {
			this.target = item.id();
			this.targetButton.setMessage(Component.literal(trimPixels(item.label().getString(), 120)));
		}))
			return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && clickUseRow(mouseX, mouseY))
			return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private boolean clickUseRow(double mouseX, double mouseY)
	{
		List<TrackAssetRegistry.TrackUse> uses = uses();
		int x = 20;
		int width = this.width - 40;
		int rowY = 62;
		int visible = Math.min(uses.size(), Math.max(0, (this.height - 84 - rowY) / ROW_HEIGHT));
		for (int i = 0; i < visible; i++) {
			if (mouseX < x || mouseX >= x + width || mouseY < rowY + i * ROW_HEIGHT
					|| mouseY >= rowY + i * ROW_HEIGHT + ROW_HEIGHT)
				continue;
			TrackAssetRegistry.TrackUse use = uses.get(i);
			if (mouseX >= x + width - 16) {
				removeUse(use);
			} else if (this.parent instanceof MusicPlaylistScreen playlistScreen) {
				playlistScreen.selectUse(use.playlistId(), use.entryIndex());
				closeToParent();
			}
			return true;
		}
		return false;
	}

	private void removeUse(TrackAssetRegistry.TrackUse use)
	{
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			return;
		MusicTracksManager manager = MusicTracksManager.getInstance();
		if (use.binding() == null
				|| manager.dynamicSource(use.playlistId()) != MusicTracksManager.DynamicSource.LOCAL)
			return;
		MusicTracksManager.PlaylistControlResult result = switch (use.binding().kind()) {
			case SCENE -> manager.deleteLocalSceneUrl(use.binding().scene(), use.entryIndex());
			case ENTITY_TYPE -> manager.deleteLocalEntityTypeUrl(use.binding().scene(), use.binding().target(),
					use.entryIndex());
			case ENTITY_UUID -> manager.deleteLocalEntityUuidUrl(use.binding().scene(), use.binding().target(),
					use.entryIndex());
			case IDLE_RULE -> manager.deleteLocalIdleRuleUrl(use.binding().target(), use.entryIndex());
		};
		message(result.message());
		if (this.parent instanceof MusicPlaylistScreen playlistScreen)
			playlistScreen.refreshAfterImport();
	}

	private void addUse()
	{
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			return;
		if (this.target.isBlank() || this.urls.isEmpty())
			return;
		MusicTracksManager manager = MusicTracksManager.getInstance();
		int added = 0;
		for (String url : this.urls) {
			MusicTracksManager.PlaylistControlResult result = switch (this.kind) {
				case "entity_type" -> manager.addLocalEntityTypeUrl(this.scene, this.target, url);
				case "entity_uuid" -> manager.addLocalEntityUuidUrl(this.scene, this.target, url);
				case "idle_rule" -> manager.addLocalIdleRuleUrl(this.target, url);
				default -> manager.addLocalSceneUrl(this.target, url);
			};
			if (result.success())
				added++;
		}
		message("\u5df2\u5c06 " + added + "/" + this.urls.size() + " \u9996\u6b4c\u52a0\u5165\u7528\u9014");
		this.target = "";
		this.targetButton.setMessage(Component.literal("\u76ee\u6807..."));
		if (this.parent instanceof MusicPlaylistScreen playlistScreen)
			playlistScreen.refreshAfterImport();
	}

	/** The URL of the first use - the track's source URL (same for all uses). */
	private String representativeUrl()
	{
		List<TrackAssetRegistry.TrackUse> uses = uses();
		return uses.isEmpty() ? null : uses.get(0).url();
	}

	private List<TrackAssetRegistry.TrackUse> uses()
	{
		return TrackAssetRegistry.usesFor(this.trackId);
	}

	private void selectKind(String id)
	{
		this.kindDropdown.close();
		this.kind = id;
		this.kindButton.setMessage(Component.literal(kindLabel()));
		this.rebuildTargets();
		updateButtonState();
	}

	private void selectScene(String id)
	{
		this.sceneDropdown.close();
		this.scene = id;
		this.sceneButton.setMessage(Component.literal(sceneLabel()));
	}

	private void toggleKindDropdown()
	{
		closeDropdowns(this.kindDropdown);
		if (this.kindDropdown.isOpen())
			this.kindDropdown.close();
		else
			this.kindDropdown.open(this.kind);
	}

	private void toggleSceneDropdown()
	{
		closeDropdowns(this.sceneDropdown);
		if (this.sceneDropdown.isOpen())
			this.sceneDropdown.close();
		else
			this.sceneDropdown.open(this.scene);
	}

	private void toggleTargetDropdown()
	{
		closeDropdowns(this.targetDropdown);
		this.targetDropdown.setFilter("");
		if (this.targetDropdown.isOpen())
			this.targetDropdown.close();
		else
			this.targetDropdown.open(this.target);
	}

	private void rebuildTargets()
	{
		List<PlaylistDropdown.Item> items = new ArrayList<>();
		switch (this.kind) {
			case "entity_type" -> {
				BuiltInRegistries.ENTITY_TYPE.keySet().stream().sorted().forEach(id ->
						items.add(new PlaylistDropdown.Item(id.toString(), Component.literal(id.toString()))));
			}
			case "entity_uuid" -> {
				if (this.minecraft != null && this.minecraft.getConnection() != null) {
					this.minecraft.getConnection().getOnlinePlayers().forEach(player ->
							items.add(new PlaylistDropdown.Item(player.getProfile().getId().toString(),
									Component.literal(player.getProfile().getName()))));
				}
			}
			case "idle_rule" -> {
				MusicTracksManager manager = MusicTracksManager.getInstance();
				for (MusicTracksManager.ExternalPlaylist playlist : manager.getSelectablePlaylists()) {
					MusicTracksManager.DynamicBinding binding = manager.editableBinding(playlist.configLocation());
					if (binding != null && binding.kind() == MusicTracksManager.DynamicBinding.Kind.IDLE_RULE)
						items.add(new PlaylistDropdown.Item(binding.target(),
								Component.literal(binding.target())));
				}
			}
			default -> List.of("aggressive", "ambient", "idle", "player").forEach(scene ->
					items.add(new PlaylistDropdown.Item(scene, Component.literal(scene))));
		}
		this.targetDropdown.setItems(items);
	}

	private void updateButtonState()
	{
		boolean server = this.editMode == MusicPlaylistScreen.EditMode.SERVER;
		this.kindButton.active = !server;
		this.sceneButton.active = !server && !"scene".equals(this.kind) && !"idle_rule".equals(this.kind);
		this.targetButton.active = !server;
		this.addButton.active = !server && !this.target.isBlank();
	}

	private String kindLabel()
	{
		return switch (this.kind) {
			case "entity_type" -> "\u5b9e\u4f53\u7c7b\u578b";
			case "entity_uuid" -> "\u73a9\u5bb6";
			case "idle_rule" -> "idle \u89c4\u5219";
			default -> "\u573a\u666f";
		};
	}

	private String sceneLabel()
	{
		return switch (this.scene) {
			case "ambient" -> "\u73af\u5883\u66f2";
			case "player" -> "\u73a9\u5bb6\u66f2";
			case "idle" -> "\u95f2\u7f6e\u66f2";
			default -> "\u6218\u6597\u66f2";
		};
	}

	private static String describeConditions(List<IdleCondition> conditions)
	{
		if (conditions == null || conditions.isEmpty())
			return "";
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < conditions.size(); i++) {
			IdleCondition condition = conditions.get(i);
			if (i > 0)
				builder.append(condition.join() == IdleCondition.Join.OR ? " \u6216 " : " \u4e14 ");
			builder.append(conditionTypeName(condition.type()));
			if (!condition.argument().isBlank())
				builder.append('=').append(condition.argument());
		}
		return builder.toString();
	}

	private static String conditionTypeName(String type)
	{
		ResourceLocation id = IdleConditionRegistry.normalizeId(type);
		if (id == null)
			return type;
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

	private void closeDropdowns(PlaylistDropdown except)
	{
		if (this.kindDropdown != except)
			this.kindDropdown.close();
		if (this.sceneDropdown != except)
			this.sceneDropdown.close();
		if (this.targetDropdown != except)
			this.targetDropdown.close();
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

	private void closeToParent()
	{
		this.minecraft.setScreen(this.parent);
	}

	private void message(String message)
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
