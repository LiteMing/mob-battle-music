package nonamecrackers2.mobbattlemusic.client.gui;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import net.minecraftforge.registries.ForgeRegistries;
import nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler;
import nonamecrackers2.mobbattlemusic.client.music.IdleConditionStateClient;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadata;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadataCache;
import nonamecrackers2.mobbattlemusic.client.music.NeteaseMusicSearch;
import nonamecrackers2.mobbattlemusic.client.music.TimelineMarkerStore;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.client.sound.MobBattleTrack;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;

public class MusicPlaylistScreen extends Screen
{
	private static final int MAX_SCENE_LENGTH = 64;
	private static final int MAX_TARGET_LENGTH = 512;
	private static final int MAX_MUSIC_LENGTH = 4096;
	
	public static enum EditMode {
		LOCAL,
		SERVER
	}

	private static enum ViewMode {
		LIBRARY,
		NETEASE
	}
	
	private static final Map<EditMode, EditorState> STATES = Map.of(
			EditMode.LOCAL, new EditorState(),
			EditMode.SERVER, new EditorState());
	
	private final Screen parent;
	private final EditMode editMode;
	private final List<Row> rows = new ArrayList<>();
	private final List<NeteaseMusicSearch.Song> searchRows = new ArrayList<>();
	private int scroll;
	private int selected = -1;
	private int searchScroll;
	private int searchSelected = -1;
	private int searchPage;
	private boolean searchHasNext;
	private boolean searchLoading;
	private String searchError = "";
	private long searchGeneration;
	private ViewMode viewMode = ViewMode.LIBRARY;
	private String addKind = "scene";
	private MobBattleTrack previewTrack;
	private Button previewButton;
	private Button stopButton;
	private Button refreshButton;
	private Button kindButton;
	private Button addButton;
	private Button useSelectedButton;
	private Button deleteButton;
	private Button libraryTabButton;
	private Button idleTabButton;
	private Button timelineTabButton;
	private Button filtersTabButton;
	private Button searchTabButton;
	private Button modeButton;
	private Button searchButton;
	private Button previousPageButton;
	private Button nextPageButton;
	private Button orderButton;
	private Button priorityApplyButton;
	private Button intervalApplyButton;
	private Button conditionInvertButton;
	private Button conditionAddButton;
	private Button conditionDeleteButton;
	private Button markerAddButton;
	private Button markerDeleteButton;
	private EditBox sceneBox;
	private EditBox targetBox;
	private EditBox musicBox;
	private EditBox searchBox;
	private EditBox priorityBox;
	private EditBox intervalBox;
	private EditBox conditionTypeBox;
	private EditBox conditionArgumentBox;
	private EditBox conditionIndexBox;
	private EditBox markerTimeBox;
	private EditBox markerEventBox;
	private EditBox markerIndexBox;
	private ResourceLocation coverTexture;
	private String coverKey = "";
	private int syncRefreshCooldown;
	private ResourceLocation settingsPlaylist;
	private boolean conditionInverted;
	private final PlaylistDropdown kindDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 6);
	private final PlaylistDropdown orderDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 4);
	
	public MusicPlaylistScreen(Screen parent)
	{
		this(parent, EditMode.LOCAL);
	}
	
	public MusicPlaylistScreen(Screen parent, EditMode editMode)
	{
		this(parent, editMode, null);
	}

	private MusicPlaylistScreen(Screen parent, EditMode editMode, ViewMode initialView)
	{
		super(text("title"));
		this.parent = parent;
		this.editMode = editMode;
		if (initialView != null)
			STATES.get(editMode).viewMode = initialView;
	}

	static void openTab(Screen parent, EditMode editMode, PlaylistTabs.Tab tab)
	{
		Minecraft mc = Minecraft.getInstance();
		switch (tab) {
			case LIBRARY -> mc.setScreen(new MusicPlaylistScreen(parent, editMode, ViewMode.LIBRARY));
			case IDLE -> mc.setScreen(new IdlePlaylistScreen(parent, editMode));
			case TIMELINE -> mc.setScreen(new TimelineMarkerScreen(parent, editMode));
			case FILTERS -> mc.setScreen(new AudioFilterScreen(parent, editMode));
			case NETEASE -> mc.setScreen(new MusicPlaylistScreen(parent, editMode, ViewMode.NETEASE));
		}
	}

	public static void openServerEditor()
	{
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(new MusicPlaylistScreen(mc.screen, EditMode.SERVER));
	}

	public static void refreshOpenScreen()
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof MusicPlaylistScreen screen) {
			screen.rebuildRows();
			screen.selected = Math.min(screen.selected, screen.rows.size() - 1);
			screen.loadSelectedSettings(true);
			screen.updateButtonState();
		}
		IdlePlaylistScreen.refreshIfOpen();
		TimelineMarkerScreen.refreshIfOpen();
	}
	
	@Override
	protected void init()
	{
		EditorState state = state();
		this.addKind = state.kind;
		if ("idle_rule".equals(this.addKind))
			this.addKind = "scene";
		this.viewMode = state.viewMode;
		this.scroll = state.scroll;
		this.selected = state.selected;
		this.searchScroll = state.searchScroll;
		this.searchSelected = state.searchSelected;
		this.searchPage = state.searchPage;
		this.searchHasNext = state.searchHasNext;
		this.searchRows.clear();
		this.searchRows.addAll(state.searchRows);
		this.rebuildRows();
		List<Button> tabs = PlaylistTabs.create(this.width,
				this.viewMode == ViewMode.LIBRARY ? PlaylistTabs.Tab.LIBRARY : PlaylistTabs.Tab.NETEASE,
				this::navigateTo);
		this.libraryTabButton = this.addRenderableWidget(tabs.get(0));
		this.idleTabButton = this.addRenderableWidget(tabs.get(1));
		this.timelineTabButton = this.addRenderableWidget(tabs.get(2));
		this.filtersTabButton = this.addRenderableWidget(tabs.get(3));
		this.searchTabButton = this.addRenderableWidget(tabs.get(4));
		this.modeButton = this.addRenderableWidget(Button.builder(modeLabel(), button -> switchEditMode())
				.bounds(this.width - 112, 6, 100, 20).build());
		this.searchButton = this.addRenderableWidget(Button.builder(text("button.search"), button -> startSearch(0))
				.bounds(this.width - 76, 53, 64, 20).build());
		this.searchBox = this.addRenderableWidget(new EditBox(this.font, 12, 54,
				Math.max(60, this.width - 92), 18, text("field.search")));
		this.searchBox.setMaxLength(160);
		this.searchBox.setHint(text("field.search"));
		this.searchBox.setValue(state.searchQuery);
		this.searchBox.setResponder(value -> {
			state().searchQuery = value;
			updateButtonState();
		});
		int inputY = this.height - 56;
		int conditionY = this.height - 80;
		int settingsY = this.height - 104;
		boolean compact = this.width < 600;
		int gap = 4;
		int sceneWidth = compact ? 70 : 78;
		int kindWidth = compact ? 68 : 72;
		int addButtonWidth = 72;
		int addButtonX = this.width - 12 - addButtonWidth;
		int sceneX = 12;
		int kindX = sceneX + sceneWidth + gap;
		int targetX = kindX + kindWidth + gap;
		int sceneY = compact ? conditionY : inputY;
		int targetWidth;
		int musicWidth;
		int musicX;
		if (compact) {
			targetWidth = Math.max(70, this.width - targetX - 12);
			musicX = 12;
			musicWidth = Math.max(80, addButtonX - gap - musicX);
		} else {
			int usableInputWidth = Math.max(260, addButtonX - gap - 12);
			int remaining = Math.max(120, usableInputWidth - sceneWidth - kindWidth - gap * 3);
			musicWidth = clamp(remaining / 3, 120, 280);
			targetWidth = Math.max(120, remaining - musicWidth);
			musicX = targetX + targetWidth + gap;
		}
		int y = this.height - 28;
		this.previewButton = this.addRenderableWidget(Button.builder(text("button.preview"), button -> previewSelected())
				.bounds(12, y, 72, 20).build());
		this.stopButton = this.addRenderableWidget(Button.builder(text("button.stop"), button -> stopPreview())
				.bounds(88, y, 56, 20).build());
		this.refreshButton = this.addRenderableWidget(Button.builder(text("button.refresh"), button -> {
			this.rebuildRows();
			this.selected = this.rows.isEmpty() ? -1 : Math.min(Math.max(this.selected, 0), this.rows.size() - 1);
			this.updateButtonState();
		}).bounds(148, y, 64, 20).build());
		this.useSelectedButton = this.addRenderableWidget(Button.builder(text("button.use"), button -> useSelectedSource())
				.bounds(216, y, 48, 20).build());
		this.deleteButton = this.addRenderableWidget(Button.builder(text("button.delete"), button -> deleteSelected())
				.bounds(268, y, 60, 20).build());
		this.previousPageButton = this.addRenderableWidget(Button.builder(text("button.previous"), button -> startSearch(this.searchPage - 1))
				.bounds(200, y, 72, 20).build());
		this.nextPageButton = this.addRenderableWidget(Button.builder(text("button.next"), button -> startSearch(this.searchPage + 1))
				.bounds(276, y, 56, 20).build());
		this.addRenderableWidget(Button.builder(text("button.done"), button -> closeToParent())
				.bounds(this.width - 76, y, 64, 20).build());
		this.sceneBox = this.addRenderableWidget(new EditBox(this.font, sceneX, sceneY, sceneWidth, 18,
				text("field.scene")));
		this.sceneBox.setMaxLength(MAX_SCENE_LENGTH);
		this.sceneBox.setHint(text("field.scene"));
		this.sceneBox.setValue(state.scene);
		this.sceneBox.setResponder(value -> state().scene = value);
		this.kindButton = this.addRenderableWidget(Button.builder(kindLabel(), button -> toggleKindDropdown())
				.bounds(kindX, sceneY - 1, kindWidth, 20).build());
		this.targetBox = this.addRenderableWidget(new EditBox(this.font, targetX, sceneY, targetWidth, 18,
				text("field.target")));
		this.targetBox.setMaxLength(MAX_TARGET_LENGTH);
		this.targetBox.setHint(text("field.target"));
		this.targetBox.setValue(state.target);
		this.targetBox.setResponder(value -> state().target = value);
		this.musicBox = this.addRenderableWidget(new EditBox(this.font, musicX, inputY, musicWidth, 18,
				text("field.music")));
		this.musicBox.setMaxLength(MAX_MUSIC_LENGTH);
		this.musicBox.setHint(text("field.music"));
		this.musicBox.setValue(state.music);
		this.musicBox.setResponder(value -> state().music = value);
		this.addButton = this.addRenderableWidget(Button.builder(text("button.add_bind"), button -> addBinding())
				.bounds(addButtonX, inputY - 1, addButtonWidth, 20).build());
		this.orderButton = this.addRenderableWidget(Button.builder(text("button.order", "random"), button -> toggleOrderDropdown())
				.bounds(12, settingsY, 108, 20).build());
		this.priorityBox = this.addRenderableWidget(new EditBox(this.font, 124, settingsY + 1, 52, 18,
				text("field.priority")));
		this.priorityBox.setMaxLength(5);
		this.priorityBox.setFilter(value -> value.isEmpty() || value.equals("-") || value.matches("-?[0-9]{0,4}"));
		this.priorityBox.setHint(text("field.priority"));
		this.priorityApplyButton = this.addRenderableWidget(Button.builder(text("button.apply"), button -> applyPriority())
				.bounds(180, settingsY, 48, 20).build());
		this.intervalBox = this.addRenderableWidget(new EditBox(this.font, 232, settingsY + 1, 66, 18,
				text("field.interval")));
		this.intervalBox.setMaxLength(5);
		this.intervalBox.setFilter(value -> value.matches("[0-9]{0,5}"));
		this.intervalBox.setHint(text("field.interval"));
		this.intervalApplyButton = this.addRenderableWidget(Button.builder(text("button.apply"), button -> applyInterval())
				.bounds(302, settingsY, 48, 20).build());
		this.conditionTypeBox = this.addRenderableWidget(new EditBox(this.font, 12, conditionY + 1, 92, 18,
				text("field.condition_type")));
		this.conditionTypeBox.setMaxLength(128);
		this.conditionTypeBox.setHint(text("field.condition_type"));
		this.conditionArgumentBox = this.addRenderableWidget(new EditBox(this.font, 108, conditionY + 1, 106, 18,
				text("field.condition_argument")));
		this.conditionArgumentBox.setMaxLength(MAX_TARGET_LENGTH);
		this.conditionArgumentBox.setHint(text("field.condition_argument"));
		this.conditionInvertButton = this.addRenderableWidget(Button.builder(conditionInvertLabel(), button -> toggleConditionInverted())
				.bounds(218, conditionY, 44, 20).build());
		this.conditionAddButton = this.addRenderableWidget(Button.builder(text("button.condition_add"), button -> addCondition())
				.bounds(266, conditionY, 44, 20).build());
		this.conditionIndexBox = this.addRenderableWidget(new EditBox(this.font, 314, conditionY + 1, 34, 18,
				text("field.index")));
		this.conditionIndexBox.setMaxLength(3);
		this.conditionIndexBox.setFilter(value -> value.matches("[0-9]{0,3}"));
		this.conditionIndexBox.setHint(text("field.index"));
		this.conditionDeleteButton = this.addRenderableWidget(Button.builder(text("button.condition_delete"), button -> deleteCondition())
				.bounds(352, conditionY, 48, 20).build());
		int markerX = Math.max(210, this.width / 2 + 8) + 8;
		int markerRight = this.width - 12;
		int markerTimeWidth = 60;
		int markerAddWidth = 44;
		int markerEventWidth = Math.max(40, markerRight - markerX - markerTimeWidth - markerAddWidth - 8);
		this.markerTimeBox = this.addRenderableWidget(new EditBox(this.font, markerX, settingsY + 1, markerTimeWidth, 18,
				text("field.marker_time")));
		this.markerTimeBox.setMaxLength(8);
		this.markerTimeBox.setFilter(value -> value.matches("[0-9]{0,8}"));
		this.markerTimeBox.setHint(text("field.marker_time"));
		this.markerEventBox = this.addRenderableWidget(new EditBox(this.font, markerX + markerTimeWidth + 4,
				settingsY + 1, markerEventWidth, 18, text("field.marker_event")));
		this.markerEventBox.setMaxLength(128);
		this.markerEventBox.setHint(text("field.marker_event"));
		int markerActionX = markerX + markerTimeWidth + markerEventWidth + 8;
		this.markerAddButton = this.addRenderableWidget(Button.builder(text("button.marker_add"), button -> addMarker())
				.bounds(markerActionX, settingsY, 44, 20).build());
		this.markerIndexBox = this.addRenderableWidget(new EditBox(this.font, markerX, conditionY + 1, 34, 18,
				text("field.index")));
		this.markerIndexBox.setMaxLength(3);
		this.markerIndexBox.setFilter(value -> value.matches("[0-9]{0,3}"));
		this.markerIndexBox.setHint(text("field.index"));
		this.markerDeleteButton = this.addRenderableWidget(Button.builder(text("button.marker_delete"), button -> deleteMarker())
				.bounds(markerX + 38, conditionY, 48, 20).build());
		this.conditionTypeBox.setValue(state.conditionType);
		this.conditionTypeBox.setResponder(value -> {
			state().conditionType = value;
			updateSettingsVisibility();
		});
		this.conditionArgumentBox.setValue(state.conditionArgument);
		this.conditionArgumentBox.setResponder(value -> state().conditionArgument = value);
		this.conditionIndexBox.setValue(state.conditionIndex);
		this.conditionIndexBox.setResponder(value -> {
			state().conditionIndex = value;
			updateSettingsVisibility();
		});
		this.conditionInverted = state.conditionInverted;
		this.conditionInvertButton.setMessage(conditionInvertLabel());
		this.markerTimeBox.setValue(state.markerTime);
		this.markerTimeBox.setResponder(value -> state().markerTime = value);
		this.markerEventBox.setValue(state.markerEvent);
		this.markerEventBox.setResponder(value -> {
			state().markerEvent = value;
			updateSettingsVisibility();
		});
		this.markerIndexBox.setValue(state.markerIndex);
		this.markerIndexBox.setResponder(value -> {
			state().markerIndex = value;
			updateSettingsVisibility();
		});
		this.kindDropdown.setItems(List.of(
				new PlaylistDropdown.Item("scene", text("kind.scene")),
				new PlaylistDropdown.Item("type", text("kind.type")),
				new PlaylistDropdown.Item("uuid", text("kind.uuid")),
				new PlaylistDropdown.Item("player", text("kind.player"))));
		this.kindDropdown.setBounds(kindX, sceneY + 20, Math.max(kindWidth, 112), this.height - 8);
		this.orderDropdown.setItems(List.of(
				orderItem(MusicTracksManager.ExternalSelectionMode.RANDOM),
				orderItem(MusicTracksManager.ExternalSelectionMode.SEQUENTIAL),
				orderItem(MusicTracksManager.ExternalSelectionMode.FIRST)));
		this.orderDropdown.setBounds(12, settingsY + 20, 128, this.height - 8);
		loadSelectedSettings(true);
		updateKindState();
		updateViewState();
		updateButtonState();
	}
	
	private void rebuildRows()
	{
		this.rows.clear();
		MusicTracksManager manager = MusicTracksManager.getInstance();
		for (MusicTracksManager.ExternalPlaylist playlist : manager.getSelectablePlaylists()) {
			String context = manager.describeTrackContext(playlist.configLocation());
			MusicTracksManager.DynamicBinding binding = manager.editableBinding(playlist.configLocation());
			if (binding != null && binding.kind() == MusicTracksManager.DynamicBinding.Kind.IDLE_RULE)
				continue;
			MusicTracksManager.DynamicSource source = manager.dynamicSource(playlist.configLocation());
			for (int i = 0; i < playlist.entries().size(); i++) {
				MusicTracksManager.ExternalPlaylistEntry entry = playlist.entries().get(i);
				MusicMetadataCache.getInstance().prepare(entry.url());
				MusicMetadata metadata = MusicMetadataCache.getInstance().get(entry.url()).orElse(null);
				String title = metadata == null ? entry.name() : metadata.displayTitle(entry.name());
				String artist = metadata == null ? "" : metadata.displayArtist();
				this.rows.add(new Row(playlist.configLocation(), context, binding, source, i, entry, title, artist));
			}
		}
		if (this.selected >= this.rows.size())
			this.selected = this.rows.size() - 1;
	}
	
	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
	{
		this.renderBackground(graphics);
		graphics.drawString(this.font, this.title, 12, 10, 0xFFFFFF, false);
		if (this.viewMode == ViewMode.LIBRARY) {
			if (this.width >= 600)
				graphics.drawString(this.font, text("summary", this.rows.stream().map(Row::playlist).distinct().count(),
						this.rows.size(), text("mode." + this.editMode.name().toLowerCase(Locale.ROOT))), 174, 12,
						0xA0A0A0, false);
			renderRows(graphics, mouseX, mouseY);
			renderDetails(graphics);
		} else {
			renderSearchRows(graphics, mouseX, mouseY);
			renderSearchDetails(graphics);
			if (this.width >= 600)
				graphics.drawString(this.font, text("search.page", this.searchPage + 1), 340, this.height - 22,
						0xA0A0A0, false);
		}
		renderPreviewProgress(graphics);
		super.render(graphics, mouseX, mouseY, partialTick);
		renderCompletions(graphics, mouseX, mouseY);
		this.kindDropdown.render(graphics, this.font, mouseX, mouseY);
		this.orderDropdown.render(graphics, this.font, mouseX, mouseY);
	}
	
	private void renderRows(GuiGraphics graphics, int mouseX, int mouseY)
	{
		int left = 12;
		int top = listTop();
		int width = Math.max(160, this.width / 2 - 20);
		int bottom = this.height - 110;
		graphics.fill(left - 2, top - 2, left + width + 2, bottom + 2, 0x90000000);
		int rowHeight = 32;
		int maxVisible = Math.max(1, (bottom - top) / rowHeight);
		this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, this.rows.size() - maxVisible)));
		for (int visible = 0; visible < maxVisible; visible++) {
			int index = this.scroll + visible;
			if (index >= this.rows.size())
				break;
			Row row = this.rows.get(index);
			int y = top + visible * rowHeight;
			boolean hovered = mouseX >= left && mouseX <= left + width && mouseY >= y && mouseY < y + rowHeight;
			int background = index == this.selected ? 0xAA38546E : hovered ? 0x70405058 : 0x40202020;
			graphics.fill(left, y, left + width, y + rowHeight - 2, background);
			graphics.drawString(this.font, trim(row.title(), 42), left + 6, y + 5, 0xFFFFFF, false);
			String line = row.context() + "  #" + (row.index() + 1);
			graphics.drawString(this.font, trim(line, 48), left + 6, y + 18, 0xB8C5D1, false);
		}
		if (this.rows.isEmpty())
			graphics.drawString(this.font, text("empty"), left + 8, top + 8, 0xA0A0A0, false);
	}
	
	private void renderDetails(GuiGraphics graphics)
	{
		int left = Math.max(210, this.width / 2 + 8);
		int top = listTop();
		int right = this.width - 12;
		graphics.fill(left - 2, top - 2, right, this.height - 110, 0x90000000);
		if (this.selected < 0 || this.selected >= this.rows.size()) {
			graphics.drawString(this.font, text("select_track"), left + 8, top + 8, 0xA0A0A0, false);
			return;
		}
		Row row = this.rows.get(this.selected);
		graphics.drawString(this.font, trim(row.title(), 48), left + 8, top + 8, 0xFFFFFF, false);
		if (!row.artist().isBlank())
			graphics.drawString(this.font, trim(row.artist(), 48), left + 8, top + 21, 0xC8D6E5, false);
		if (this.height < 320) {
			graphics.drawString(this.font, text("detail.context", row.context()), left + 8, top + 38,
					0xA0A0A0, false);
			graphics.drawString(this.font, text("detail.editable", editableLabel(row)), left + 8, top + 51,
					0xA0A0A0, false);
			MusicTracksManager.DynamicPlaylistSettings compactSettings =
					MusicTracksManager.getInstance().dynamicSettings(row.playlist());
			if (compactSettings != null)
				graphics.drawString(this.font, text("detail.settings",
						text("order." + compactSettings.selectionMode().getSerializedName()), compactSettings.priority()),
						left + 8, top + 64, 0xA0A0A0, false);
			return;
		}
		graphics.drawString(this.font, text("detail.playlist", row.playlist()), left + 8, top + 42, 0xA0A0A0, false);
		graphics.drawString(this.font, text("detail.context", row.context()), left + 8, top + 55, 0xA0A0A0, false);
		graphics.drawString(this.font, text("detail.editable", editableLabel(row)), left + 8, top + 68, 0xA0A0A0, false);
		MusicTracksManager.DynamicPlaylistSettings settings = MusicTracksManager.getInstance().dynamicSettings(row.playlist());
		if (settings != null) {
			graphics.drawString(this.font, text("detail.settings", text("order." + settings.selectionMode().getSerializedName()),
					settings.priority()), left + 8, top + 81, 0xA0A0A0, false);
			if (row.binding() != null && row.binding().kind() == MusicTracksManager.DynamicBinding.Kind.IDLE_RULE)
				graphics.drawString(this.font, text("detail.idle", settings.idleIntervalSeconds(),
						settings.idleConditions().size()), left + 8, top + 94, 0xA0A0A0, false);
		}
		graphics.drawString(this.font, text("detail.source"), left + 8, top + 111, 0xA0A0A0, false);
		drawWrapped(graphics, row.entry().url(), left + 8, top + 124, right - left - 16, 0xD8D8D8);
		renderCover(graphics, row, left + 8, Math.min(this.height - 198, top + 170));
		if (settings != null && !settings.idleConditions().isEmpty())
			renderIdleConditions(graphics, settings.idleConditions(), left + 96, top + 170, right - left - 104);
	}

	private void renderIdleConditions(GuiGraphics graphics, List<IdleCondition> conditions, int x, int y, int width)
	{
		graphics.drawString(this.font, text("detail.conditions"), x, y, 0xA0A0A0, false);
		int max = Math.min(5, conditions.size());
		for (int i = 0; i < max; i++) {
			IdleCondition condition = conditions.get(i);
			String value = (i + 1) + ". " + (condition.inverted() ? "NOT " : "") + condition.type() +
					(condition.argument().isBlank() ? "" : " " + condition.argument());
			graphics.drawString(this.font, trim(value, Math.max(12, width / 6)), x, y + 13 + i * 12,
					0xD8D8D8, false);
		}
	}

	private void renderSearchRows(GuiGraphics graphics, int mouseX, int mouseY)
	{
		int left = 12;
		int top = listTop();
		int width = Math.max(160, this.width / 2 - 20);
		int bottom = this.height - 34;
		graphics.fill(left - 2, top - 2, left + width + 2, bottom + 2, 0x90000000);
		int rowHeight = 36;
		int maxVisible = Math.max(1, (bottom - top) / rowHeight);
		this.searchScroll = Math.max(0, Math.min(this.searchScroll, Math.max(0, this.searchRows.size() - maxVisible)));
		for (int visible = 0; visible < maxVisible; visible++) {
			int index = this.searchScroll + visible;
			if (index >= this.searchRows.size())
				break;
			NeteaseMusicSearch.Song song = this.searchRows.get(index);
			int y = top + visible * rowHeight;
			boolean hovered = mouseX >= left && mouseX <= left + width && mouseY >= y && mouseY < y + rowHeight;
			int background = index == this.searchSelected ? 0xAA38546E : hovered ? 0x70405058 : 0x40202020;
			graphics.fill(left, y, left + width, y + rowHeight - 2, background);
			graphics.drawString(this.font, trim(song.title(), 42), left + 6, y + 5, 0xFFFFFF, false);
			String subtitle = song.artist() + (song.album().isBlank() ? "" : "  /  " + song.album());
			graphics.drawString(this.font, trim(subtitle, 48), left + 6, y + 19, 0xB8C5D1, false);
		}
		if (!this.searchRows.isEmpty())
			return;
		Component status;
		if (this.searchLoading)
			status = text("search.loading");
		else if (!this.searchError.isBlank())
			status = text("search.failed", this.searchError);
		else if (this.searchBox != null && !this.searchBox.getValue().isBlank())
			status = text("search.empty");
		else
			status = text("search.prompt");
		graphics.drawString(this.font, status, left + 8, top + 8, 0xA0A0A0, false);
	}

	private void renderSearchDetails(GuiGraphics graphics)
	{
		int left = Math.max(210, this.width / 2 + 8);
		int top = listTop();
		int right = this.width - 12;
		graphics.fill(left - 2, top - 2, right, this.height - 34, 0x90000000);
		if (this.searchSelected < 0 || this.searchSelected >= this.searchRows.size()) {
			graphics.drawString(this.font, text("search.select"), left + 8, top + 8, 0xA0A0A0, false);
			return;
		}
		NeteaseMusicSearch.Song song = this.searchRows.get(this.searchSelected);
		graphics.drawString(this.font, trim(song.title(), 48), left + 8, top + 8, 0xFFFFFF, false);
		graphics.drawString(this.font, trim(song.artist(), 48), left + 8, top + 22, 0xC8D6E5, false);
		graphics.drawString(this.font, text("search.detail.album", song.album()), left + 8, top + 44, 0xA0A0A0, false);
		graphics.drawString(this.font, text("search.detail.id", song.id()), left + 8, top + 57, 0xA0A0A0, false);
		graphics.drawString(this.font, text("search.detail.duration", formatDuration(song.durationMillis())),
				left + 8, top + 70, 0xA0A0A0, false);
		graphics.drawString(this.font, text("detail.source"), left + 8, top + 86, 0xA0A0A0, false);
		drawWrapped(graphics, song.url(), left + 8, top + 100, right - left - 16, 0xD8D8D8);
		if (this.height < 320)
			return;
		MusicMetadataCache.getInstance().get(song.url())
				.ifPresent(metadata -> renderCover(graphics, metadata, left + 8, Math.min(this.height - 118, top + 145)));
	}
	
	private void renderCover(GuiGraphics graphics, Row row, int x, int y)
	{
		MusicMetadata metadata = MusicMetadataCache.getInstance().get(row.entry().url()).orElse(null);
		if (metadata == null)
			return;
		renderCover(graphics, metadata, x, y);
	}

	private void renderCover(GuiGraphics graphics, MusicMetadata metadata, int x, int y)
	{
		ResourceLocation texture = coverTexture(metadata);
		if (texture == null)
			return;
		graphics.blit(texture, x, y, 0.0F, 0.0F, 80, 80, 80, 80);
	}
	
	private ResourceLocation coverTexture(MusicMetadata metadata)
	{
		String key = metadata.songId() + ":" + metadata.coverFileName();
		if (key.equals(this.coverKey))
			return this.coverTexture;
		releaseCoverTexture();
		this.coverKey = key;
		Path path = MusicMetadataCache.getInstance().coverPath(metadata);
		if (path == null || !Files.isRegularFile(path))
			return null;
		try (InputStream stream = Files.newInputStream(path)) {
			NativeImage image = NativeImage.read(stream);
			DynamicTexture texture = new DynamicTexture(image);
			this.coverTexture = Minecraft.getInstance().getTextureManager()
					.register("mbm_cover/" + metadata.songId(), texture);
			return this.coverTexture;
		} catch (Exception e) {
			return null;
		}
	}

	private void releaseCoverTexture()
	{
		if (this.coverTexture != null)
			Minecraft.getInstance().getTextureManager().release(this.coverTexture);
		this.coverTexture = null;
		this.coverKey = "";
	}
	
	private void previewSelected()
	{
		if (this.viewMode == ViewMode.NETEASE) {
			if (this.searchSelected < 0 || this.searchSelected >= this.searchRows.size())
				return;
			stopPreview();
			NeteaseMusicSearch.Song song = this.searchRows.get(this.searchSelected);
			ExternalMusicHandler.getInstance().playPreviewMusic(song.url(), 20, song.durationMillis());
			return;
		}
		if (this.selected < 0 || this.selected >= this.rows.size())
			return;
		Row row = this.rows.get(this.selected);
		stopPreview();
		ResourceLocation sound = soundLocation(row.entry().url());
		if (sound != null) {
			this.previewTrack = new MobBattleTrack(sound, 20);
			this.minecraft.getSoundManager().play(this.previewTrack);
		} else {
			ExternalMusicHandler.getInstance().playPreviewMusic(row.entry().url(), 20, 0L);
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

	private void renderPreviewProgress(GuiGraphics graphics)
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		if (!handler.isPreviewing())
			return;
		int left = previewProgressLeft();
		int right = previewProgressRight();
		int y = previewProgressY();
		long position = handler.getPositionMillis();
		long duration = handler.getDurationMillis();
		String time = formatDuration(position) + " / " + (duration > 0L ? formatDuration(duration) : "--:--");
		graphics.drawString(this.font, text("preview.progress", time), left, y - 11, 0xD8D8D8, false);
		graphics.fill(left, y, right, y + 7, 0xC0202020);
		int filled = duration <= 0L ? 0 : (int)Math.round((right - left) * Math.min(1.0D,
				position / (double)duration));
		graphics.fill(left, y, left + filled, y + 7, 0xFF5AA7C4);
		Row row = this.viewMode == ViewMode.LIBRARY ? selectedRow() : null;
		if (row != null && duration > 0L) {
			for (TimelineMarker marker : TimelineMarkerStore.markers(row.playlist(), row.entry().url(), row.index())) {
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
		long duration = handler.getDurationMillis();
		int left = previewProgressLeft();
		int right = previewProgressRight();
		int y = previewProgressY();
		if (!handler.isPreviewing() || duration <= 0L || mouseX < left || mouseX > right || mouseY < y - 3 || mouseY > y + 10)
			return false;
		double progress = (mouseX - left) / Math.max(1.0D, right - left);
		long position = Math.round(duration * Math.max(0.0D, Math.min(1.0D, progress)));
		if (this.markerTimeBox != null)
			this.markerTimeBox.setValue(String.valueOf(position));
		return handler.seekMusic(position);
	}

	private int previewProgressLeft()
	{
		return Math.max(210, this.width / 2 + 8) + 8;
	}

	private int previewProgressRight()
	{
		return this.width - 20;
	}

	private int previewProgressY()
	{
		return this.height - 124;
	}

	private int listTop()
	{
		return this.viewMode == ViewMode.LIBRARY ? 56 : 80;
	}
	
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		if (this.kindDropdown.isOpen()) {
			PlaylistDropdown.Item item = this.kindDropdown.itemAt(mouseX, mouseY);
			if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && item != null)
				selectKind(item.id());
			else
				this.kindDropdown.close();
			return true;
		}
		if (this.orderDropdown.isOpen()) {
			PlaylistDropdown.Item item = this.orderDropdown.itemAt(mouseX, mouseY);
			if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && item != null)
				selectOrder(item.id());
			else
				this.orderDropdown.close();
			return true;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && clickCompletion(mouseX, mouseY))
			return true;
		if (seekPreview(mouseX, mouseY))
			return true;
		int left = 12;
		int top = listTop();
		int width = Math.max(160, this.width / 2 - 20);
		int rowHeight = this.viewMode == ViewMode.NETEASE ? 36 : 32;
		int bottom = this.viewMode == ViewMode.NETEASE ? this.height - 34 : this.height - 110;
		if (mouseX >= left && mouseX <= left + width && mouseY >= top && mouseY < bottom) {
			if (this.viewMode == ViewMode.NETEASE) {
				int index = this.searchScroll + (int)((mouseY - top) / rowHeight);
				if (index >= 0 && index < this.searchRows.size()) {
					this.searchSelected = index;
					state().searchSelected = index;
					MusicMetadataCache.getInstance().prepare(this.searchRows.get(index).url());
					updateButtonState();
					return true;
				}
			} else {
				int index = this.scroll + (int)((mouseY - top) / rowHeight);
				if (index >= 0 && index < this.rows.size()) {
					this.selected = index;
					state().selected = this.selected;
					loadSelectedSettings(true);
					updateButtonState();
					return true;
				}
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta)
	{
		if (this.kindDropdown.mouseScrolled(delta) || this.orderDropdown.mouseScrolled(delta))
			return true;
		if (this.viewMode == ViewMode.NETEASE) {
			this.searchScroll = Math.max(0, this.searchScroll - (int)Math.signum(delta));
			state().searchScroll = this.searchScroll;
		} else {
			this.scroll = Math.max(0, this.scroll - (int)Math.signum(delta));
			state().scroll = this.scroll;
		}
		return true;
	}

	@Override
	public void tick()
	{
		super.tick();
		if (this.syncRefreshCooldown > 0 && --this.syncRefreshCooldown == 0) {
			MobBattleMusicNetwork.requestServerExternalPlaylistSync();
			this.rebuildRows();
			this.selected = this.rows.isEmpty() ? -1 : Math.min(Math.max(this.selected, 0), this.rows.size() - 1);
			this.loadSelectedSettings(true);
			this.updateButtonState();
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers)
	{
		if (handleDropdownKey(this.kindDropdown, keyCode, item -> selectKind(item.id())) ||
				handleDropdownKey(this.orderDropdown, keyCode, item -> selectOrder(item.id())))
			return true;
		if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) &&
				this.searchBox != null && this.searchBox.isFocused()) {
			startSearch(0);
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_TAB) {
			if (completeFocusedBox())
				return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
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
		releaseCoverTexture();
		super.removed();
	}

	private void closeToParent()
	{
		saveState();
		this.minecraft.setScreen(this.parent);
	}

	private void changeView(ViewMode viewMode)
	{
		this.viewMode = viewMode;
		state().viewMode = viewMode;
		updateViewState();
		updateButtonState();
		if (viewMode == ViewMode.NETEASE && this.searchBox != null)
			this.setFocused(this.searchBox);
	}

	private void navigateTo(PlaylistTabs.Tab tab)
	{
		if (tab == PlaylistTabs.Tab.LIBRARY) {
			changeView(ViewMode.LIBRARY);
			return;
		}
		if (tab == PlaylistTabs.Tab.NETEASE) {
			changeView(ViewMode.NETEASE);
			return;
		}
		saveState();
		stopPreview();
		openTab(this.parent, this.editMode, tab);
	}

	private void switchEditMode()
	{
		saveState();
		stopPreview();
		EditMode next = this.editMode == EditMode.LOCAL ? EditMode.SERVER : EditMode.LOCAL;
		this.minecraft.setScreen(new MusicPlaylistScreen(this.parent, next));
	}

	private Component modeLabel()
	{
		return text("mode_button", text("mode." + this.editMode.name().toLowerCase(Locale.ROOT)));
	}

	private void updateViewState()
	{
		boolean library = this.viewMode == ViewMode.LIBRARY;
		if (this.libraryTabButton != null)
			this.libraryTabButton.active = !library;
		if (this.idleTabButton != null)
			this.idleTabButton.active = true;
		if (this.timelineTabButton != null)
			this.timelineTabButton.active = true;
		if (this.filtersTabButton != null)
			this.filtersTabButton.active = true;
		if (this.searchTabButton != null)
			this.searchTabButton.active = library;
		if (this.searchBox != null)
			this.searchBox.visible = !library;
		if (this.searchButton != null)
			this.searchButton.visible = !library;
		if (this.refreshButton != null)
			this.refreshButton.visible = library;
		if (this.useSelectedButton != null)
			this.useSelectedButton.setX(library ? 216 : 148);
		if (this.deleteButton != null)
			this.deleteButton.visible = library;
		if (this.previousPageButton != null)
			this.previousPageButton.visible = !library;
		if (this.nextPageButton != null)
			this.nextPageButton.visible = !library;
		if (this.sceneBox != null)
			this.sceneBox.visible = library;
		if (this.kindButton != null)
			this.kindButton.visible = library;
		if (this.targetBox != null)
			this.targetBox.visible = library;
		if (this.musicBox != null)
			this.musicBox.visible = library;
		if (this.addButton != null)
			this.addButton.visible = library;
		updateSettingsVisibility();
	}

	private void updateSettingsVisibility()
	{
		boolean editable = this.viewMode == ViewMode.LIBRARY && selectedRowEditable();
		boolean idle = false;
		if (this.orderButton != null) {
			this.orderButton.visible = editable;
			this.orderButton.active = editable;
		}
		if (this.priorityBox != null) {
			this.priorityBox.visible = editable;
			this.priorityBox.active = editable;
		}
		if (this.priorityApplyButton != null) {
			this.priorityApplyButton.visible = editable;
			this.priorityApplyButton.active = editable;
		}
		if (this.intervalBox != null) {
			this.intervalBox.visible = idle;
			this.intervalBox.active = idle;
		}
		if (this.intervalApplyButton != null) {
			this.intervalApplyButton.visible = idle;
			this.intervalApplyButton.active = idle;
		}
		if (this.conditionTypeBox != null) {
			this.conditionTypeBox.visible = idle;
			this.conditionTypeBox.active = idle;
		}
		if (this.conditionArgumentBox != null) {
			this.conditionArgumentBox.visible = idle;
			this.conditionArgumentBox.active = idle;
		}
		if (this.conditionInvertButton != null) {
			this.conditionInvertButton.visible = idle;
			this.conditionInvertButton.active = idle;
		}
		if (this.conditionAddButton != null) {
			this.conditionAddButton.visible = idle;
			this.conditionAddButton.active = idle && !this.conditionTypeBox.getValue().isBlank();
		}
		if (this.conditionIndexBox != null) {
			this.conditionIndexBox.visible = idle;
			this.conditionIndexBox.active = idle;
		}
		if (this.conditionDeleteButton != null) {
			this.conditionDeleteButton.visible = idle;
			this.conditionDeleteButton.active = idle && !this.conditionIndexBox.getValue().isBlank();
		}
		boolean markerEditable = false;
		if (this.markerTimeBox != null) {
			this.markerTimeBox.visible = markerEditable;
			this.markerTimeBox.active = markerEditable;
		}
		if (this.markerEventBox != null) {
			this.markerEventBox.visible = markerEditable;
			this.markerEventBox.active = markerEditable;
		}
		if (this.markerAddButton != null) {
			this.markerAddButton.visible = markerEditable;
			this.markerAddButton.active = markerEditable && !this.markerTimeBox.getValue().isBlank() &&
					!this.markerEventBox.getValue().isBlank();
		}
		if (this.markerIndexBox != null) {
			this.markerIndexBox.visible = markerEditable;
			this.markerIndexBox.active = markerEditable;
		}
		if (this.markerDeleteButton != null) {
			this.markerDeleteButton.visible = markerEditable;
			this.markerDeleteButton.active = markerEditable && !this.markerIndexBox.getValue().isBlank();
		}
	}

	private void loadSelectedSettings(boolean force)
	{
		if (this.priorityBox == null)
			return;
		Row row = selectedRow();
		ResourceLocation playlist = row == null ? null : row.playlist();
		if (!force && java.util.Objects.equals(this.settingsPlaylist, playlist))
			return;
		this.settingsPlaylist = playlist;
		MusicTracksManager.DynamicPlaylistSettings settings = playlist == null ? null :
				MusicTracksManager.getInstance().dynamicSettings(playlist);
		if (settings != null) {
			this.priorityBox.setValue(String.valueOf(settings.priority()));
			this.intervalBox.setValue(String.valueOf(settings.idleIntervalSeconds()));
			this.orderButton.setMessage(orderLabel(settings.selectionMode()));
			if (!settings.idleConditions().isEmpty() && this.conditionIndexBox.getValue().isBlank())
				this.conditionIndexBox.setValue("1");
		} else {
			this.priorityBox.setValue("");
			this.intervalBox.setValue("");
			this.orderButton.setMessage(text("button.order", "-"));
		}
		updateSettingsVisibility();
	}

	private Row selectedRow()
	{
		return this.selected < 0 || this.selected >= this.rows.size() ? null : this.rows.get(this.selected);
	}

	private MusicTracksManager.DynamicBinding selectedBinding()
	{
		Row row = selectedRow();
		return row == null ? null : row.binding();
	}

	private Component orderLabel(MusicTracksManager.ExternalSelectionMode mode)
	{
		return text("button.order", text("order." + mode.getSerializedName())).copy().append(" v");
	}

	private PlaylistDropdown.Item orderItem(MusicTracksManager.ExternalSelectionMode mode)
	{
		return new PlaylistDropdown.Item(mode.getSerializedName(), text("order." + mode.getSerializedName()));
	}

	private Component conditionInvertLabel()
	{
		return text(this.conditionInverted ? "button.not" : "button.match");
	}

	private void toggleOrderDropdown()
	{
		Row row = selectedRow();
		if (row == null || row.binding() == null)
			return;
		MusicTracksManager.DynamicPlaylistSettings settings = MusicTracksManager.getInstance().dynamicSettings(row.playlist());
		if (settings == null)
			return;
		this.kindDropdown.close();
		if (this.orderDropdown.isOpen())
			this.orderDropdown.close();
		else
			this.orderDropdown.open(settings.selectionMode().getSerializedName());
	}

	private void selectOrder(String id)
	{
		Row row = selectedRow();
		if (row == null || row.binding() == null)
			return;
		MusicTracksManager.ExternalSelectionMode next;
		try {
			next = MusicTracksManager.ExternalSelectionMode.fromSerializedName(id);
		} catch (Exception e) {
			return;
		}
		this.orderDropdown.close();
		if (this.editMode == EditMode.SERVER) {
			runServerCommand(bindingCommand(row.binding()) + " order " + next.getSerializedName());
		} else {
			message(MusicTracksManager.getInstance().setLocalSelectionMode(row.binding(), next).message());
			refreshAfterEdit();
		}
		this.orderButton.setMessage(orderLabel(next));
	}

	private void applyPriority()
	{
		Row row = selectedRow();
		if (row == null || row.binding() == null)
			return;
		Integer priority = parseInteger(this.priorityBox.getValue(), -1000, 1000);
		if (priority == null) {
			message(text("message.invalid_priority"));
			return;
		}
		if (this.editMode == EditMode.SERVER)
			runServerCommand(bindingCommand(row.binding()) + " priority " + priority);
		else {
			message(MusicTracksManager.getInstance().setLocalPriority(row.binding(), priority).message());
			refreshAfterEdit();
		}
	}

	private void applyInterval()
	{
		Row row = selectedRow();
		if (row == null || row.binding() == null)
			return;
		Integer seconds = parseInteger(this.intervalBox.getValue(), 0, 86400);
		if (seconds == null) {
			message(text("message.invalid_interval"));
			return;
		}
		if (this.editMode == EditMode.SERVER)
			runServerCommand(bindingCommand(row.binding()) + " interval " + seconds);
		else {
			message(MusicTracksManager.getInstance().setLocalIdleInterval(row.binding(), seconds).message());
			refreshAfterEdit();
		}
	}

	private void toggleConditionInverted()
	{
		this.conditionInverted = !this.conditionInverted;
		this.conditionInvertButton.setMessage(conditionInvertLabel());
		saveState();
	}

	private void addCondition()
	{
		Row row = selectedRow();
		if (row == null || row.binding() == null)
			return;
		String type = this.conditionTypeBox.getValue().trim();
		String argument = this.conditionArgumentBox.getValue().trim();
		try {
			new ResourceLocation(type);
		} catch (Exception e) {
			message(text("message.invalid_condition"));
			return;
		}
		if (this.editMode == EditMode.SERVER) {
			String command = bindingCommand(row.binding()) + " condition " +
					(this.conditionInverted ? "add_not " : "add ") + type;
			if (!argument.isBlank())
				command += " " + argument;
			runServerCommand(command);
		} else {
			message(MusicTracksManager.getInstance().addLocalIdleCondition(row.binding(),
					new IdleCondition(type, argument, this.conditionInverted)).message());
			refreshAfterEdit();
		}
		saveState();
	}

	private void deleteCondition()
	{
		Row row = selectedRow();
		if (row == null || row.binding() == null)
			return;
		Integer index = parseInteger(this.conditionIndexBox.getValue(), 1, 999);
		if (index == null) {
			message(text("message.invalid_condition_index"));
			return;
		}
		if (this.editMode == EditMode.SERVER)
			runServerCommand(bindingCommand(row.binding()) + " condition delete " + index);
		else {
			message(MusicTracksManager.getInstance().deleteLocalIdleCondition(row.binding(), index - 1).message());
			refreshAfterEdit();
		}
	}

	private void addMarker()
	{
		Row row = selectedRow();
		if (row == null)
			return;
		Integer time = parseInteger(this.markerTimeBox.getValue(), 0, 86_400_000);
		ResourceLocation event = ResourceLocation.tryParse(this.markerEventBox.getValue().trim());
		if (time == null || event == null) {
			message(text("message.invalid_marker"));
			return;
		}
		if (this.editMode == EditMode.SERVER) {
			runServerCommand("mobbattlemusic marker " + row.playlist() + " " + (row.index() + 1) +
					" add " + time + " " + event);
		} else {
			message(TimelineMarkerStore.addLocal(row.playlist(), row.index(), new TimelineMarker(time, event)));
			updateButtonState();
		}
		saveState();
	}

	private void deleteMarker()
	{
		Row row = selectedRow();
		Integer index = parseInteger(this.markerIndexBox.getValue(), 1, 999);
		if (row == null || index == null) {
			message(text("message.invalid_marker_index"));
			return;
		}
		if (this.editMode == EditMode.SERVER)
			runServerCommand("mobbattlemusic marker " + row.playlist() + " " + (row.index() + 1) +
					" delete " + index);
		else {
			message(TimelineMarkerStore.deleteLocal(row.playlist(), row.index(), index - 1));
			updateButtonState();
		}
	}

	private String bindingCommand(MusicTracksManager.DynamicBinding binding)
	{
		return switch (binding.kind()) {
			case SCENE -> "mobbattlemusic " + binding.scene();
			case ENTITY_TYPE -> "mobbattlemusic " + binding.scene() + " type " + binding.target();
			case ENTITY_UUID -> "mobbattlemusic " + binding.scene() + " uuid " + binding.target();
			case IDLE_RULE -> "mobbattlemusic idle rule " + binding.target();
		};
	}

	private void refreshAfterEdit()
	{
		ResourceLocation selectedPlaylist = selectedRow() == null ? null : selectedRow().playlist();
		this.rebuildRows();
		if (selectedPlaylist != null) {
			for (int i = 0; i < this.rows.size(); i++) {
				if (this.rows.get(i).playlist().equals(selectedPlaylist)) {
					this.selected = i;
					break;
				}
			}
		}
		loadSelectedSettings(true);
		updateButtonState();
	}

	private static Integer parseInteger(String value, int min, int max)
	{
		try {
			int parsed = Integer.parseInt(value);
			return parsed >= min && parsed <= max ? parsed : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private void startSearch(int page)
	{
		if (this.searchBox == null)
			return;
		String query = this.searchBox.getValue().trim();
		if (query.isBlank()) {
			this.searchRows.clear();
			this.searchSelected = -1;
			this.searchError = "";
			updateButtonState();
			return;
		}
		int requestedPage = Math.max(0, page);
		long generation = ++this.searchGeneration;
		this.searchLoading = true;
		this.searchError = "";
		this.searchPage = requestedPage;
		this.searchHasNext = false;
		this.searchRows.clear();
		this.searchSelected = -1;
		this.searchScroll = 0;
		updateButtonState();
		NeteaseMusicSearch.search(query, requestedPage).whenComplete((result, error) ->
				Minecraft.getInstance().execute(() -> {
					if (generation != this.searchGeneration)
						return;
					this.searchLoading = false;
					if (error != null) {
						Throwable cause = error.getCause() == null ? error : error.getCause();
						this.searchError = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
					} else {
						this.searchRows.addAll(result.songs());
						this.searchPage = result.page();
						this.searchHasNext = result.hasNext();
						if (!this.searchRows.isEmpty()) {
							this.searchSelected = 0;
							MusicMetadataCache.getInstance().prepare(this.searchRows.get(0).url());
						}
					}
					EditorState state = state();
					state.searchQuery = query;
					state.searchPage = this.searchPage;
					state.searchHasNext = this.searchHasNext;
					state.searchRows = List.copyOf(this.searchRows);
					state.searchSelected = this.searchSelected;
					state.searchScroll = this.searchScroll;
					updateButtonState();
				}));
	}

	private boolean completeFocusedBox()
	{
		if (this.sceneBox != null && this.sceneBox.isFocused())
			return complete(this.sceneBox, MusicTracksManager.supportedDynamicScenes());
		if (this.targetBox != null && this.targetBox.isFocused())
			return complete(this.targetBox, targetSuggestions());
		if (this.musicBox != null && this.musicBox.isFocused())
			return complete(this.musicBox, musicSuggestions());
		if (this.conditionTypeBox != null && this.conditionTypeBox.isFocused())
			return complete(this.conditionTypeBox, conditionTypeSuggestions());
		if (this.conditionArgumentBox != null && this.conditionArgumentBox.isFocused())
			return complete(this.conditionArgumentBox, conditionArgumentSuggestions());
		return false;
	}

	private void renderCompletions(GuiGraphics graphics, int mouseX, int mouseY)
	{
		CompletionPopup popup = completionPopup();
		if (popup == null)
			return;
		graphics.fill(popup.x() - 1, popup.y() - 1, popup.x() + popup.width() + 1,
				popup.y() + popup.height() + 1, 0xD0000000);
		for (int i = 0; i < popup.matches().size(); i++) {
			int rowY = popup.rowY(i);
			if (mouseX >= popup.x() && mouseX < popup.x() + popup.width() &&
					mouseY >= rowY && mouseY < rowY + CompletionPopup.ROW_HEIGHT)
				graphics.fill(popup.x(), rowY, popup.x() + popup.width(), rowY + CompletionPopup.ROW_HEIGHT,
						0xFF465A64);
			int color = i == 0 ? 0xFFE6C96A : 0xFFE0E0E0;
			graphics.drawString(this.font, trim(popup.matches().get(i), Math.max(8, popup.width() / 6)),
					popup.x() + 3, rowY + 1, color, false);
		}
	}

	private boolean clickCompletion(double mouseX, double mouseY)
	{
		CompletionPopup popup = completionPopup();
		if (popup == null || mouseX < popup.x() || mouseX >= popup.x() + popup.width() ||
				mouseY < popup.rowsY() || mouseY >= popup.rowsY() + popup.matches().size() * CompletionPopup.ROW_HEIGHT)
			return false;
		int index = (int)((mouseY - popup.rowsY()) / CompletionPopup.ROW_HEIGHT);
		applyCompletion(popup.box(), popup.matches().get(index));
		return true;
	}

	private CompletionPopup completionPopup()
	{
		EditBox box = focusedCompletionBox();
		if (box == null)
			return null;
		List<String> matches = completionMatches(box, suggestionsFor(box));
		if (matches.isEmpty())
			return null;
		List<String> visible = matches.subList(0, Math.min(6, matches.size()));
		int width = Math.max(box.getWidth(), 120);
		int height = visible.size() * CompletionPopup.ROW_HEIGHT + 4;
		return new CompletionPopup(box, visible, box.getX(), Math.max(12, box.getY() - height - 2), width, height);
	}

	private EditBox focusedCompletionBox()
	{
		if (this.sceneBox != null && this.sceneBox.visible && this.sceneBox.isFocused())
			return this.sceneBox;
		if (this.targetBox != null && this.targetBox.visible && this.targetBox.isFocused() && this.targetBox.active)
			return this.targetBox;
		if (this.musicBox != null && this.musicBox.visible && this.musicBox.isFocused())
			return this.musicBox;
		if (this.conditionTypeBox != null && this.conditionTypeBox.visible && this.conditionTypeBox.isFocused())
			return this.conditionTypeBox;
		if (this.conditionArgumentBox != null && this.conditionArgumentBox.visible && this.conditionArgumentBox.isFocused())
			return this.conditionArgumentBox;
		return null;
	}

	private Collection<String> suggestionsFor(EditBox box)
	{
		if (box == this.sceneBox)
			return MusicTracksManager.supportedDynamicScenes();
		if (box == this.targetBox)
			return targetSuggestions();
		if (box == this.musicBox)
			return musicSuggestions();
		if (box == this.conditionTypeBox)
			return conditionTypeSuggestions();
		if (box == this.conditionArgumentBox)
			return conditionArgumentSuggestions();
		return List.of();
	}

	private boolean complete(EditBox box, Collection<String> suggestions)
	{
		if (suggestions.isEmpty())
			return false;
		String current = box.getValue();
		List<String> matches = completionMatches(box, suggestions);
		if (matches.isEmpty())
			return false;
		int currentIndex = matches.indexOf(current);
		String next = matches.get(currentIndex >= 0 ? (currentIndex + 1) % matches.size() : 0);
		applyCompletion(box, next);
		return true;
	}

	private void applyCompletion(EditBox box, String value)
	{
		box.setValue(value);
		box.setCursorPosition(value.length());
		saveState();
	}

	private static List<String> completionMatches(EditBox box, Collection<String> suggestions)
	{
		String lower = box.getValue().toLowerCase(Locale.ROOT);
		return suggestions.stream()
				.filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
				.sorted()
				.toList();
	}

	private List<String> targetSuggestions()
	{
		List<String> suggestions = new ArrayList<>();
			switch (this.addKind) {
			case "type" -> ForgeRegistries.ENTITY_TYPES.getKeys().stream()
					.map(ResourceLocation::toString)
					.forEach(suggestions::add);
			case "player" -> {
				suggestions.add("@p");
				suggestions.add("@a");
				suggestions.add("@s");
				if (this.minecraft.getConnection() != null) {
					for (var player : this.minecraft.getConnection().getOnlinePlayers())
						suggestions.add(player.getProfile().getName());
				}
			}
			case "uuid" -> {
				for (Row row : this.rows) {
					if (row.binding() != null && row.binding().kind() == MusicTracksManager.DynamicBinding.Kind.ENTITY_UUID)
						suggestions.add(row.binding().target());
				}
			}
			case "idle_rule" -> {
				for (Row row : this.rows) {
					if (row.binding() != null && row.binding().kind() == MusicTracksManager.DynamicBinding.Kind.IDLE_RULE)
						suggestions.add(row.binding().target());
				}
			}
			default -> {
			}
		}
		return suggestions.stream().distinct().toList();
	}

	private List<String> conditionTypeSuggestions()
	{
		return IdleConditionStateClient.descriptors().stream()
				.map(descriptor -> descriptor.id().toString())
				.distinct()
				.toList();
	}

	private List<String> conditionArgumentSuggestions()
	{
		if (this.minecraft.level == null || this.conditionTypeBox == null)
			return List.of();
		String type = this.conditionTypeBox.getValue();
		if ("mobbattlemusic:dimension".equals(type) && this.minecraft.getConnection() != null)
			return this.minecraft.getConnection().levels().stream().map(key -> key.location().toString()).sorted().toList();
		if ("mobbattlemusic:biome".equals(type))
			return this.minecraft.level.registryAccess().registry(Registries.BIOME).stream()
					.flatMap(registry -> registry.keySet().stream()).map(ResourceLocation::toString).sorted().toList();
		if ("mobbattlemusic:structure".equals(type))
			return this.minecraft.level.registryAccess().registry(Registries.STRUCTURE).stream()
					.flatMap(registry -> registry.keySet().stream()).map(ResourceLocation::toString).sorted().toList();
		return List.of();
	}

	private List<String> musicSuggestions()
	{
		List<String> suggestions = new ArrayList<>();
		for (Row row : this.rows)
			suggestions.add(row.entry().url());
		for (NeteaseMusicSearch.Song song : this.searchRows)
			suggestions.add(song.url());
		return suggestions.stream().distinct().toList();
	}
	
	private void updateButtonState()
	{
		boolean hasSelection = this.viewMode == ViewMode.NETEASE
				? this.searchSelected >= 0 && this.searchSelected < this.searchRows.size()
				: this.selected >= 0 && this.selected < this.rows.size();
		if (this.previewButton != null)
			this.previewButton.active = hasSelection;
		if (this.useSelectedButton != null)
			this.useSelectedButton.active = hasSelection;
		if (this.deleteButton != null)
			this.deleteButton.active = this.viewMode == ViewMode.LIBRARY && selectedRowEditable();
		if (this.stopButton != null)
			this.stopButton.active = true;
		if (this.searchButton != null)
			this.searchButton.active = this.searchBox != null && !this.searchBox.getValue().isBlank();
		if (this.previousPageButton != null)
			this.previousPageButton.active = !this.searchLoading && this.searchPage > 0;
		if (this.nextPageButton != null)
			this.nextPageButton.active = !this.searchLoading && this.searchHasNext;
		updateSettingsVisibility();
	}

	private void toggleKindDropdown()
	{
		this.orderDropdown.close();
		if (this.kindDropdown.isOpen())
			this.kindDropdown.close();
		else
			this.kindDropdown.open(this.addKind);
	}

	private void selectKind(String kind)
	{
		if (!List.of("scene", "type", "uuid", "player").contains(kind))
			return;
		this.kindDropdown.close();
		this.addKind = kind;
		state().kind = this.addKind;
		updateKindState();
	}

	private void updateKindState()
	{
		if (this.kindButton != null)
			this.kindButton.setMessage(kindLabel());
		if (this.targetBox != null)
			this.targetBox.active = !"scene".equals(this.addKind);
		if (this.sceneBox != null)
			this.sceneBox.active = !"idle_rule".equals(this.addKind);
	}

	private Component kindLabel()
	{
		return text("kind." + this.addKind).copy().append(" v");
	}

	private boolean handleDropdownKey(PlaylistDropdown dropdown, int keyCode,
			Consumer<PlaylistDropdown.Item> onSelected)
	{
		if (!dropdown.isOpen())
			return false;
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			dropdown.close();
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
			dropdown.move(keyCode == GLFW.GLFW_KEY_UP ? -1 : 1);
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			PlaylistDropdown.Item item = dropdown.highlightedItem();
			if (item != null)
				onSelected.accept(item);
			return true;
		}
		return true;
	}

	private void useSelectedSource()
	{
		if (this.viewMode == ViewMode.NETEASE) {
			if (this.searchSelected < 0 || this.searchSelected >= this.searchRows.size())
				return;
			this.musicBox.setValue(this.searchRows.get(this.searchSelected).url());
			saveState();
			changeView(ViewMode.LIBRARY);
			message(text("message.search_selected"));
			return;
		}
		if (this.selected < 0 || this.selected >= this.rows.size())
			return;
		Row row = this.rows.get(this.selected);
		this.musicBox.setValue(row.entry().url());
		if (row.binding() != null) {
			this.sceneBox.setValue(row.binding().scene());
			this.addKind = switch (row.binding().kind()) {
				case SCENE -> "scene";
				case ENTITY_TYPE -> "type";
				case ENTITY_UUID -> "uuid";
				case IDLE_RULE -> "idle_rule";
			};
			this.targetBox.setValue(row.binding().kind() == MusicTracksManager.DynamicBinding.Kind.SCENE
					? "" : row.binding().target());
			updateKindState();
		}
		saveState();
	}

	private void addBinding()
	{
		String scene = this.sceneBox.getValue().trim();
		String target = this.targetBox.getValue().trim();
		String music = this.musicBox.getValue().trim();
		if ((scene.isBlank() && !"idle_rule".equals(this.addKind)) || music.isBlank()) {
			message(text("message.scene_music_required"));
			return;
		}
		if (!"scene".equals(this.addKind) && target.isBlank()) {
			message(text("message.target_required", kindLabel()));
			return;
		}
		if (this.editMode == EditMode.SERVER) {
			runServerCommand(addCommand(scene, target, music));
		} else {
			MusicTracksManager.PlaylistControlResult result = switch (this.addKind) {
				case "idle_rule" -> MusicTracksManager.getInstance().addLocalIdleRuleUrl(target, music);
				case "type" -> MusicTracksManager.getInstance().addLocalEntityTypeUrl(scene, target, music);
				case "uuid" -> MusicTracksManager.getInstance().addLocalEntityUuidUrl(scene, target, music);
				case "player" -> addLocalPlayer(scene, target, music);
				default -> MusicTracksManager.getInstance().addLocalSceneUrl(scene, music);
			};
			message(result.message());
			this.rebuildRows();
			this.selected = this.rows.isEmpty() ? -1 : Math.min(Math.max(this.selected, 0), this.rows.size() - 1);
			this.loadSelectedSettings(true);
			this.updateButtonState();
		}
		saveState();
	}

	private MusicTracksManager.PlaylistControlResult addLocalPlayer(String scene, String target, String music)
	{
		String uuid = resolvePlayerUuid(target);
		if (uuid == null)
			return MusicTracksManager.PlaylistControlResult.failure(text("message.unknown_player", target).getString());
		return MusicTracksManager.getInstance().addLocalEntityUuidUrl(scene, uuid, music);
	}

	private String addCommand(String scene, String target, String music)
	{
		return switch (this.addKind) {
			case "idle_rule" -> "mobbattlemusic idle rule " + target + " add " + music;
			case "type" -> "mobbattlemusic " + scene + " type " + target + " add " + music;
			case "uuid" -> "mobbattlemusic " + scene + " uuid " + target + " add " + music;
			case "player" -> "mobbattlemusic " + scene + " " + target + " add " + music;
			default -> "mobbattlemusic " + scene + " add " + music;
		};
	}

	private void deleteSelected()
	{
		if (!selectedRowEditable())
			return;
		Row row = this.rows.get(this.selected);
		if (this.editMode == EditMode.SERVER) {
			runServerCommand(deleteCommand(row));
		} else {
			MusicTracksManager.PlaylistControlResult result = switch (row.binding().kind()) {
				case SCENE -> MusicTracksManager.getInstance().deleteLocalSceneUrl(row.binding().scene(), row.index());
				case ENTITY_TYPE -> MusicTracksManager.getInstance().deleteLocalEntityTypeUrl(row.binding().scene(),
						row.binding().target(), row.index());
				case ENTITY_UUID -> MusicTracksManager.getInstance().deleteLocalEntityUuidUrl(row.binding().scene(),
						row.binding().target(), row.index());
				case IDLE_RULE -> MusicTracksManager.getInstance().deleteLocalIdleRuleUrl(row.binding().target(), row.index());
			};
			message(result.message());
			this.rebuildRows();
			this.selected = Math.min(this.selected, this.rows.size() - 1);
			this.loadSelectedSettings(true);
			updateButtonState();
		}
		saveState();
	}

	private String deleteCommand(Row row)
	{
		int index = row.index() + 1;
		return switch (row.binding().kind()) {
			case SCENE -> "mobbattlemusic " + row.binding().scene() + " delete " + index;
			case ENTITY_TYPE -> "mobbattlemusic " + row.binding().scene() + " delete type " + row.binding().target() + " " + index;
			case ENTITY_UUID -> "mobbattlemusic " + row.binding().scene() + " delete uuid " + row.binding().target() + " " + index;
			case IDLE_RULE -> "mobbattlemusic idle rule " + row.binding().target() + " delete " + index;
		};
	}

	private boolean selectedRowEditable()
	{
		if (this.selected < 0 || this.selected >= this.rows.size())
			return false;
		Row row = this.rows.get(this.selected);
		if (row.binding() == null || row.source() == null)
			return false;
		return this.editMode == EditMode.SERVER
				? row.source() == MusicTracksManager.DynamicSource.SERVER
				: row.source() == MusicTracksManager.DynamicSource.LOCAL;
	}

	private String editableLabel(Row row)
	{
		if (row.binding() == null || row.source() == null)
			return text("source.resource_pack").getString();
		return text("source." + row.source().name().toLowerCase(Locale.ROOT)).getString();
	}

	private void runServerCommand(String command)
	{
		if (this.minecraft.player == null || this.minecraft.player.connection == null)
			return;
		this.minecraft.player.connection.sendCommand(command);
		this.syncRefreshCooldown = 10;
		message(text("message.sent_command", command));
	}

	private String resolvePlayerUuid(String target)
	{
		try {
			return UUID.fromString(target).toString();
		} catch (IllegalArgumentException ignored) {
		}
		if (this.minecraft.getConnection() == null)
			return null;
		for (var player : this.minecraft.getConnection().getOnlinePlayers()) {
			if (player.getProfile().getName().equalsIgnoreCase(target))
				return player.getProfile().getId().toString();
		}
		return null;
	}

	private EditorState state()
	{
		return STATES.get(this.editMode);
	}

	private void saveState()
	{
		EditorState state = state();
		state.kind = this.addKind;
		state.viewMode = this.viewMode;
		state.scene = this.sceneBox == null ? state.scene : this.sceneBox.getValue();
		state.target = this.targetBox == null ? state.target : this.targetBox.getValue();
		state.music = this.musicBox == null ? state.music : this.musicBox.getValue();
		state.searchQuery = this.searchBox == null ? state.searchQuery : this.searchBox.getValue();
		state.scroll = this.scroll;
		state.selected = this.selected;
		state.searchScroll = this.searchScroll;
		state.searchSelected = this.searchSelected;
		state.searchPage = this.searchPage;
		state.searchHasNext = this.searchHasNext;
		state.searchRows = List.copyOf(this.searchRows);
		state.conditionType = this.conditionTypeBox == null ? state.conditionType : this.conditionTypeBox.getValue();
		state.conditionArgument = this.conditionArgumentBox == null ? state.conditionArgument : this.conditionArgumentBox.getValue();
		state.conditionIndex = this.conditionIndexBox == null ? state.conditionIndex : this.conditionIndexBox.getValue();
		state.conditionInverted = this.conditionInverted;
		state.markerTime = this.markerTimeBox == null ? state.markerTime : this.markerTimeBox.getValue();
		state.markerEvent = this.markerEventBox == null ? state.markerEvent : this.markerEventBox.getValue();
		state.markerIndex = this.markerIndexBox == null ? state.markerIndex : this.markerIndexBox.getValue();
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
	
	private static ResourceLocation soundLocation(String raw)
	{
		if (raw == null)
			return null;
		String id = raw.startsWith("sound:") ? raw.substring("sound:".length()) : raw;
		try {
			ResourceLocation location = new ResourceLocation(id);
			return Minecraft.getInstance().getSoundManager().getSoundEvent(location) == null ? null : location;
		} catch (Exception e) {
			return null;
		}
	}
	
	private void drawWrapped(GuiGraphics graphics, String text, int x, int y, int width, int color)
	{
		for (var line : this.font.split(Component.literal(text), width)) {
			graphics.drawString(this.font, line, x, y, color, false);
			y += 11;
		}
	}
	
	private static String trim(String text, int length)
	{
		return text.length() <= length ? text : text.substring(0, Math.max(0, length - 3)) + "...";
	}

	private static String formatDuration(long durationMillis)
	{
		long totalSeconds = Math.max(0L, durationMillis / 1000L);
		return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
	}

	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}

	private static Component text(String path, Object... args)
	{
		return Component.translatable("gui.mobbattlemusic.playlist." + path, args);
	}

	private static class EditorState
	{
		private String kind = "scene";
		private String scene = "";
		private String target = "";
		private String music = "";
		private String searchQuery = "";
		private String conditionType = "mobbattlemusic:dimension";
		private String conditionArgument = "";
		private String conditionIndex = "1";
		private boolean conditionInverted;
		private String markerTime = "0";
		private String markerEvent = "mobbattlemusic:marker";
		private String markerIndex = "1";
		private ViewMode viewMode = ViewMode.LIBRARY;
		private int scroll;
		private int selected = -1;
		private int searchScroll;
		private int searchSelected = -1;
		private int searchPage;
		private boolean searchHasNext;
		private List<NeteaseMusicSearch.Song> searchRows = List.of();
	}

	private record CompletionPopup(EditBox box, List<String> matches, int x, int y, int width, int height)
	{
		private static final int ROW_HEIGHT = 12;

		int rowsY()
		{
			return this.y + 2;
		}

		int rowY(int index)
		{
			return rowsY() + index * ROW_HEIGHT;
		}
	}
	
	private static record Row(ResourceLocation playlist, String context, MusicTracksManager.DynamicBinding binding,
			MusicTracksManager.DynamicSource source, int index,
			MusicTracksManager.ExternalPlaylistEntry entry, String title, String artist) {}
}
