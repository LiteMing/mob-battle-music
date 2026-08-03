package nonamecrackers2.mobbattlemusic.client.gui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import nonamecrackers2.mobbattlemusic.client.audio.MbmSessionState;
import nonamecrackers2.mobbattlemusic.client.audio.PreviewChannel;
import nonamecrackers2.mobbattlemusic.client.config.MobBattleMusicConfig;
import nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler;
import nonamecrackers2.mobbattlemusic.client.music.IdleConditionStateClient;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadata;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadataCache;
import nonamecrackers2.mobbattlemusic.client.music.NeteaseMusicSearch;
import nonamecrackers2.mobbattlemusic.client.music.TimelineMarkerStore;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;
import nonamecrackers2.mobbattlemusic.playlist.IdleCondition;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;

public class MusicPlaylistScreen extends Screen
{
	private static final int MAX_SCENE_LENGTH = 64;
	private static final int MAX_TARGET_LENGTH = 512;
	
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
	private PlaylistScreenLayout layout;
	private PlaylistSelectionList<ResourceLocation> playlistList;
	private PlaylistSelectionList<Row> trackList;
	private PlaylistSelectionList<NeteaseMusicSearch.Song> searchResultList;
	private ResourceLocation selectedPlaylist;
	private InspectorPage inspectorPage = InspectorPage.DETAILS;
	private boolean draftDirty;
	private int selected = -1;
	private int searchSelected = -1;
	private int searchPage;
	private boolean searchHasNext;
	private boolean searchLoading;
	private String searchError = "";
	private long searchGeneration;
	private ViewMode viewMode = ViewMode.LIBRARY;
	private String addKind = "scene";
	private String lastPreviewUrl = "";
	private Button previewButton;
	private Button stopButton;
	private Button refreshButton;
	private Button kindButton;
	private Button copyUrlButton;
	private Button libraryTabButton;
	private Button idleTabButton;
	private Button filtersTabButton;
	private Button searchTabButton;
	private Button modeButton;
	private Button searchButton;
	private Button previousPageButton;
	private Button nextPageButton;
	private Button orderButton;
	private Button conditionInvertButton;
	private Button conditionAddButton;
	private Button conditionDeleteButton;
	private Button conditionsInspectorButton;
	private Button timelineEditorButton;
	private Button inspectorDetailsButton;
	private Button inspectorBindingButton;
	private Button inspectorConditionsButton;
	private Button saveButton;
	private Button cancelButton;
	private Button addBindingButton;
	private Button importButton;
	private Button exportButton;
	private Button volumeButton;
	// K9-3: volume popup state - two sliders + preview-follows-main toggle
	private boolean volumePanelOpen;
	private float mainGainValue = 1.0F;
	private float previewGainValue = 1.0F;
	private boolean previewFollowsMain = true;
	private int volumeDraggingIndex = -1;
	private EditBox sceneBox;
	private EditBox targetBox;
	private EditBox searchBox;
	private EditBox libraryFilterBox;
	private EditBox priorityBox;
	private EditBox intervalBox;
	private EditBox conditionTypeBox;
	private EditBox conditionArgumentBox;
	private ResourceLocation coverTexture;
	private String coverKey = "";
	private int syncRefreshCooldown;
	private ResourceLocation settingsPlaylist;
	private ResourceLocation pendingSelectionPlaylist;
	private int pendingSelectionIndex = -1;
	private boolean conditionInverted;
	private final PlaylistDropdown kindDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 6);
	private final PlaylistDropdown orderDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 4);
	private final PlaylistDropdown modeDropdown = new PlaylistDropdown(PlaylistDropdown.Mode.FIXED, 18, 2);

	private enum InspectorPage
	{
		DETAILS,
		BINDING,
		CONDITIONS
	}
	
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

	static void openForIdleRule(Screen parent, EditMode editMode, String ruleId)
	{
		EditorState state = STATES.get(editMode);
		state.kind = "idle_rule";
		state.scene = "idle";
		state.target = ruleId;
		state.viewMode = ViewMode.LIBRARY;
		Minecraft.getInstance().setScreen(new MusicPlaylistScreen(parent, editMode, ViewMode.LIBRARY));
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
		super.init();
		this.layout = PlaylistScreenLayout.calculate(this.width, this.height);
		EditorState state = state();
		this.addKind = state.kind;
		this.viewMode = state.viewMode;
		this.selected = state.selected;
		this.searchSelected = state.searchSelected;
		this.searchPage = state.searchPage;
		this.searchHasNext = state.searchHasNext;
		this.searchRows.clear();
		this.searchRows.addAll(state.searchRows);
		this.selectedPlaylist = state.selectedPlaylist == null || state.selectedPlaylist.isBlank()
				? null : ResourceLocation.tryParse(state.selectedPlaylist);
		this.rebuildRows();
		List<Button> tabs = PlaylistTabs.create(this.layout.tabBar(),
				this.viewMode == ViewMode.LIBRARY ? PlaylistTabs.Tab.LIBRARY : PlaylistTabs.Tab.NETEASE,
				this::navigateTo);
		this.libraryTabButton = this.addRenderableWidget(tabs.get(0));
		this.idleTabButton = this.addRenderableWidget(tabs.get(1));
		this.filtersTabButton = this.addRenderableWidget(tabs.get(2));
		this.searchTabButton = this.addRenderableWidget(tabs.get(3));
		this.modeButton = this.addRenderableWidget(Button.builder(modeLabel(), button -> toggleModeDropdown())
				.bounds(this.layout.titleBar().right() - 106, this.layout.titleBar().y(), 100, 20).build());
		this.modeButton.active = this.editMode == EditMode.SERVER || canEditServer();
		this.modeButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
				this.modeButton.active ? text("mode.tooltip") : text("mode.no_permission")));
		PlaylistScreenLayout.Rect sidebar = this.layout.sidebar();
		PlaylistScreenLayout.Rect main = this.layout.main();
		PlaylistScreenLayout.Rect inspector = this.layout.inspector();
		this.searchButton = this.addRenderableWidget(Button.builder(text("button.search"), button -> startSearch(0))
				.bounds(sidebar.innerX(), sidebar.innerY() + 34, sidebar.innerWidth(), 20).build());
		this.searchBox = this.addRenderableWidget(new EditBox(this.font, sidebar.innerX(), sidebar.innerY() + 12,
				sidebar.innerWidth(), 18, text("field.search")));
		this.searchBox.setMaxLength(160);
		this.searchBox.setHint(text("field.search"));
		this.searchBox.setValue(state.searchQuery);
		this.searchBox.setResponder(value -> {
			state().searchQuery = value;
			updateButtonState();
		});
		this.libraryFilterBox = this.addRenderableWidget(new EditBox(this.font, sidebar.innerX(), sidebar.innerY() + 12,
				sidebar.innerWidth(), 18, text("field.library_filter")));
		this.libraryFilterBox.setMaxLength(160);
		this.libraryFilterBox.setHint(text("field.library_filter"));
		this.libraryFilterBox.setValue(state.libraryFilter);
		this.libraryFilterBox.setResponder(value -> {
			state().libraryFilter = value;
			rebuildRows();
			this.selected = this.rows.isEmpty() ? -1 : clamp(this.selected, 0, this.rows.size() - 1);
			loadSelectedSettings(true);
			updateButtonState();
		});
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
		int editY = iy + 34;
		this.sceneBox = this.addRenderableWidget(new EditBox(this.font, ix, editY, half, 18, text("field.scene")));
		this.sceneBox.setMaxLength(MAX_SCENE_LENGTH);
		this.sceneBox.setHint(text("field.scene"));
		this.sceneBox.setValue(state.scene);
		this.sceneBox.setResponder(value -> state().scene = value);
		this.kindButton = this.addRenderableWidget(Button.builder(kindLabel(), button -> toggleKindDropdown())
				.bounds(ix + half + 4, editY - 1, half, 20).build());
		this.targetBox = this.addRenderableWidget(new EditBox(this.font, ix, editY + 34, iw, 18,
				text("field.target")));
		this.targetBox.setMaxLength(MAX_TARGET_LENGTH);
		this.targetBox.setHint(text("field.target"));
		this.targetBox.setValue(state.target);
		this.targetBox.setResponder(value -> state().target = value);
		int detailActionY = detailActionY();
		this.orderButton = this.addRenderableWidget(Button.builder(text("button.order", "random"), button -> toggleOrderDropdown())
				.bounds(ix, detailActionY, half, 20).build());
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
		this.conditionTypeBox = this.addRenderableWidget(new EditBox(this.font, ix, conditionEditorY(), half, 18,
				text("field.condition_type")));
		this.conditionTypeBox.setMaxLength(128);
		this.conditionTypeBox.setHint(text("field.condition_type"));
		this.conditionArgumentBox = this.addRenderableWidget(new EditBox(this.font,
				ix + half + PlaylistScreenLayout.GAP, conditionEditorY(),
				Math.max(24, iw - half - PlaylistScreenLayout.GAP), 18,
				text("field.condition_argument")));
		this.conditionArgumentBox.setMaxLength(MAX_TARGET_LENGTH);
		this.conditionArgumentBox.setHint(text("field.condition_argument"));
		int conditionActionY = conditionEditorY() + 22;
		int conditionActionWidth = Math.max(28, (iw - 8) / 3);
		this.conditionInvertButton = this.addRenderableWidget(Button.builder(conditionInvertLabel(), button -> toggleConditionInverted())
				.bounds(ix, conditionActionY, conditionActionWidth, 20).build());
		this.conditionAddButton = this.addRenderableWidget(Button.builder(text("button.condition_add"), button -> addCondition())
				.bounds(ix + conditionActionWidth + 4, conditionActionY, conditionActionWidth, 20).build());
		this.conditionDeleteButton = this.addRenderableWidget(Button.builder(text("button.condition_delete"), button -> deleteCondition())
				.bounds(ix + (conditionActionWidth + 4) * 2, conditionActionY,
						Math.max(20, iw - (conditionActionWidth + 4) * 2), 20).build());
		// K9-2: open the standalone Conditions inspector (AND/OR groups, live
		// per-condition diagnostics)
		this.conditionsInspectorButton = this.addRenderableWidget(Button.builder(
				text("button.conditions_inspector"), button -> openConditionsInspector())
				.bounds(ix, conditionActionY + 24, iw, 20).build());
		this.timelineEditorButton = this.addRenderableWidget(Button.builder(text("button.timeline_editor"),
				button -> openTimelineEditor()).bounds(ix + half + PlaylistScreenLayout.GAP, detailActionY,
						Math.max(24, iw - half - PlaylistScreenLayout.GAP), 20).build());
		this.addBindingButton = this.addRenderableWidget(Button.builder(text("button.add_bind"), button -> useSelectedSource())
				.bounds(ix, editY + 58, iw, 20).build());
		// K9-1: transactional import preview + export (MBM JSON round-trip)
		this.importButton = this.addRenderableWidget(Button.builder(text("button.import"),
				button -> this.minecraft.setScreen(new PlaylistImportScreen(this)))
				.bounds(ix, editY + 82, iw, 20).build());
		this.exportButton = this.addRenderableWidget(Button.builder(text("button.export"),
				button -> exportSelectedBinding())
				.bounds(ix, editY + 106, iw, 20).build());
		this.copyUrlButton = this.addRenderableWidget(Button.builder(text("button.copy_url"), button -> copySelectedUrl())
				.bounds(ix, detailActionY, iw, 20).build());
		this.timelineEditorButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("button.timeline_editor")));
		PlaylistScreenLayout.ActionMetrics actions = this.layout.actionMetrics();
		this.previewButton = this.addRenderableWidget(Button.builder(text("button.preview"), button -> previewSelected())
				.bounds(actions.previewX(), actions.y(), actions.previewWidth(), 20).build());
		this.stopButton = this.addRenderableWidget(Button.builder(text("button.stop"), button -> stopPreview())
				.bounds(actions.stopX(), actions.y(), actions.stopWidth(), 20).build());
		this.refreshButton = this.addRenderableWidget(Button.builder(text("button.refresh"), button -> {
			this.rebuildRows();
			this.updateButtonState();
		}).bounds(actions.refreshX(), actions.y(), actions.refreshWidth(), 20).build());
		this.cancelButton = this.addRenderableWidget(Button.builder(text("button.cancel"), button -> requestClose())
				.bounds(actions.cancelX(), actions.y(), actions.cancelWidth(), 20).build());
		this.saveButton = this.addRenderableWidget(Button.builder(text("button.save"), button -> saveInspector())
				.bounds(actions.saveX(), actions.y(), actions.saveWidth(), 20).build());
		// K9-3: persistent volume popup (main MBM gain + preview gain)
		this.mainGainValue = MobBattleMusicConfig.CLIENT.mbmUserGain.get().floatValue();
		this.previewGainValue = MobBattleMusicConfig.CLIENT.previewGain.get().floatValue();
		this.previewFollowsMain = MobBattleMusicConfig.CLIENT.previewFollowsMain.get();
		this.volumeButton = this.addRenderableWidget(Button.builder(Component.literal("Vol"),
				button -> this.volumePanelOpen = !this.volumePanelOpen)
				.bounds(this.width - 46, 6, 40, 18).build());
		this.previousPageButton = this.addRenderableWidget(Button.builder(text("button.previous"), button -> startSearch(this.searchPage - 1))
				.bounds(sidebar.innerX(), sidebar.innerY() + 59, Math.max(1, (sidebar.innerWidth() - 4) / 2), 20).build());
		this.nextPageButton = this.addRenderableWidget(Button.builder(text("button.next"), button -> startSearch(this.searchPage + 1))
				.bounds(sidebar.innerX() + Math.max(1, (sidebar.innerWidth() - 4) / 2) + 4,
						sidebar.innerY() + 59, Math.max(1, (sidebar.innerWidth() - 4) / 2), 20).build());
		this.sceneBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("field.scene.tooltip")));
		this.targetBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("field.target.tooltip")));
		this.conditionTypeBox.setValue(state.conditionType);
		this.conditionTypeBox.setResponder(value -> {
			state().conditionType = value;
			updateSettingsVisibility();
		});
		this.conditionArgumentBox.setValue(state.conditionArgument);
		this.conditionArgumentBox.setResponder(value -> state().conditionArgument = value);
		this.sceneBox.setResponder(value -> { state().scene = value; this.draftDirty = true; });
		this.targetBox.setResponder(value -> { state().target = value; this.draftDirty = true; });
		this.priorityBox.setResponder(value -> { this.draftDirty = true; });
		this.intervalBox.setResponder(value -> { this.draftDirty = true; });
		this.conditionInverted = state.conditionInverted;
		this.conditionInvertButton.setMessage(conditionInvertLabel());
		this.kindDropdown.setItems(List.of(
				new PlaylistDropdown.Item("scene", text("kind.scene")),
				new PlaylistDropdown.Item("idle_rule", text("kind.idle_rule")),
				new PlaylistDropdown.Item("type", text("kind.type")),
				new PlaylistDropdown.Item("uuid", text("kind.uuid")),
				new PlaylistDropdown.Item("player", text("kind.player"))));
		this.kindDropdown.setBounds(ix + half + 4, editY + 20,
				Math.max(24, iw - half - PlaylistScreenLayout.GAP), inspector.bottom());
		this.orderDropdown.setItems(List.of(
				orderItem(MusicTracksManager.ExternalSelectionMode.RANDOM),
				orderItem(MusicTracksManager.ExternalSelectionMode.SEQUENTIAL),
				orderItem(MusicTracksManager.ExternalSelectionMode.FIRST)));
		this.orderDropdown.setBounds(ix, detailActionY + 20, iw, inspector.bottom());
		this.modeDropdown.setItems(List.of(
				new PlaylistDropdown.Item("local", text("mode.local")),
				new PlaylistDropdown.Item("server", text("mode.server"))));
		this.modeDropdown.setBounds(this.modeButton.getX(), this.modeButton.getY() + 20,
				this.modeButton.getWidth(), this.layout.frame().bottom());
		this.playlistList = this.addRenderableWidget(new PlaylistSelectionList<>(this.minecraft, this.font,
				sidebar.width(), this.height, sidebar.y() + 28, sidebar.bottom() - 6,
				this::selectPlaylist, value -> {}, value -> {}, move -> {}));
		this.trackList = this.addRenderableWidget(new PlaylistSelectionList<>(this.minecraft, this.font,
				main.width(), this.height, main.y() + 22, main.bottom() - 6,
				this::selectTrackRow, this::toggleTrackRow, this::deleteTrackRow, this::moveTrackRow));
		this.searchResultList = this.addRenderableWidget(new PlaylistSelectionList<>(this.minecraft, this.font,
				main.width(), this.height, main.y() + 22, main.bottom() - 6,
				this::selectSearchSong, value -> {}, value -> {}, move -> {}));
		this.playlistList.setBounds(new PlaylistScreenLayout.Rect(sidebar.x(), sidebar.y() + 52,
				sidebar.width(), Math.max(1, sidebar.height() - 58)));
		this.trackList.setBounds(new PlaylistScreenLayout.Rect(main.x(), main.y() + 22,
				main.width(), Math.max(1, main.height() - 56)));
		this.searchResultList.setBounds(new PlaylistScreenLayout.Rect(main.x(), main.y() + 22,
				main.width(), Math.max(1, main.height() - 56)));
		loadSelectedSettings(true);
		this.refreshSelectionLists();
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
		String filter = state().libraryFilter.trim().toLowerCase(Locale.ROOT);
		if (!filter.isBlank()) {
			List<String> terms = List.of(filter.split("\\s+"));
			this.rows.removeIf(row -> {
				String haystack = rowFilterText(row);
				return terms.stream().anyMatch(term -> !haystack.contains(term));
			});
		}
		if (this.selected >= this.rows.size())
			this.selected = this.rows.size() - 1;
		if (this.selectedPlaylist == null && this.selected >= 0 && this.selected < this.rows.size())
			this.selectedPlaylist = this.rows.get(this.selected).playlist();
		if (this.selectedPlaylist == null && !this.rows.isEmpty())
			this.selectedPlaylist = this.rows.get(0).playlist();
		if (this.selectedPlaylist != null && this.rows.stream().noneMatch(row -> row.playlist().equals(this.selectedPlaylist)))
			this.selectedPlaylist = this.rows.isEmpty() ? null : this.rows.get(0).playlist();
		if (this.pendingSelectionPlaylist != null) {
			for (int i = 0; i < this.rows.size(); i++) {
				Row row = this.rows.get(i);
				if (row.playlist().equals(this.pendingSelectionPlaylist) && row.index() == this.pendingSelectionIndex) {
					this.selected = i;
					this.selectedPlaylist = row.playlist();
					break;
				}
			}
			this.pendingSelectionPlaylist = null;
			this.pendingSelectionIndex = -1;
		}
		if (this.trackList != null)
			this.refreshSelectionLists();
	}

	private void refreshSelectionLists()
	{
		if (this.playlistList == null)
			return;
		List<ResourceLocation> playlists = this.rows.stream().map(Row::playlist).distinct().toList();
		List<PlaylistSelectionList.Model<ResourceLocation>> playlistModels = new ArrayList<>();
		MusicTracksManager manager = MusicTracksManager.getInstance();
		for (ResourceLocation playlist : playlists) {
			List<Row> group = this.rows.stream().filter(row -> row.playlist().equals(playlist)).toList();
			Row first = group.get(0);
			boolean enabled = group.stream().anyMatch(row -> manager.isMusicEntryEnabled(row.playlist(), row.entry()));
			PlaylistSelectionList.Status status = !enabled ? PlaylistSelectionList.Status.DISABLED
					: group.stream().anyMatch(this::isPlaying) ? PlaylistSelectionList.Status.PLAYING
					: PlaylistSelectionList.Status.MATCHED;
			playlistModels.add(new PlaylistSelectionList.Model<>(playlist.toString(), playlist, Component.literal(first.context()),
					Component.translatable("gui.mobbattlemusic.playlist.group.tracks", group.size()), status, false, false));
		}
		this.playlistList.setItems(playlistModels, this.selectedPlaylist == null ? null : this.selectedPlaylist.toString());
		List<PlaylistSelectionList.Model<Row>> trackModels = new ArrayList<>();
		for (Row row : this.rows) {
			if (this.selectedPlaylist != null && !this.selectedPlaylist.equals(row.playlist()))
				continue;
			trackModels.add(new PlaylistSelectionList.Model<>(row.playlist() + "#" + row.index(), row,
					Component.literal(row.title()), Component.literal(row.context() + "  #" + (row.index() + 1)),
					trackStatus(row), selectedRowEditable(row), selectedRowEditable(row)));
		}
		String selectedKey = selectedRow() == null ? null : selectedRow().playlist() + "#" + selectedRow().index();
		this.trackList.setItems(trackModels, selectedKey);
		List<PlaylistSelectionList.Model<NeteaseMusicSearch.Song>> searchModels = new ArrayList<>();
		for (NeteaseMusicSearch.Song song : this.searchRows)
			searchModels.add(new PlaylistSelectionList.Model<>(song.id(), song, Component.literal(song.title()),
					Component.literal(song.artist() + (song.album().isBlank() ? "" : "  /  " + song.album())),
					PlaylistSelectionList.Status.MATCHED, false, false));
		this.searchResultList.setItems(searchModels,
				this.searchSelected >= 0 && this.searchSelected < this.searchRows.size()
						? this.searchRows.get(this.searchSelected).id() : null);
		this.trackList.setVisible(this.viewMode == ViewMode.LIBRARY);
		this.searchResultList.setVisible(this.viewMode == ViewMode.NETEASE);
		this.playlistList.setVisible(this.viewMode == ViewMode.LIBRARY);
	}

	private PlaylistSelectionList.Status trackStatus(Row row)
	{
		if (!MusicTracksManager.getInstance().isMusicEntryEnabled(row.playlist(), row.entry()))
			return PlaylistSelectionList.Status.DISABLED;
		if (isPlaying(row))
			return PlaylistSelectionList.Status.PLAYING;
		if (!row.entry().conditions().isEmpty())
			return IdleConditionStateClient.isEntryActive(row.playlist(), row.index())
					? PlaylistSelectionList.Status.MATCHED : PlaylistSelectionList.Status.UNMATCHED;
		return PlaylistSelectionList.Status.MATCHED;
	}

	private boolean isPlaying(Row row)
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		return row.entry().url().equals(handler.getPreviewUrl()) || row.entry().url().equals(handler.getCurrentlyPlayingUrl())
				|| PreviewChannel.isSoundTrackActive() && row.equals(selectedRow());
	}

	private boolean selectedRowEditable(Row row)
	{
		if (row == null || row.binding() == null || row.source() == null)
			return false;
		return this.editMode == EditMode.SERVER ? row.source() == MusicTracksManager.DynamicSource.SERVER
				: row.source() == MusicTracksManager.DynamicSource.LOCAL;
	}

	private void selectPlaylist(ResourceLocation playlist)
	{
		this.selectedPlaylist = playlist;
		for (int i = 0; i < this.rows.size(); i++) {
			if (playlist.equals(this.rows.get(i).playlist())) {
				this.selected = i;
				break;
			}
		}
		state().selectedPlaylist = playlist.toString();
		this.loadSelectedSettings(true);
		this.refreshSelectionLists();
		this.updateButtonState();
	}

	private void selectTrackRow(Row row)
	{
		this.selected = this.rows.indexOf(row);
		this.selectedPlaylist = row.playlist();
		state().selected = this.selected;
		state().selectedPlaylist = row.playlist().toString();
		this.loadSelectedSettings(true);
		this.updateButtonState();
	}

	private void selectSearchSong(NeteaseMusicSearch.Song song)
	{
		this.searchSelected = this.searchRows.indexOf(song);
		state().searchSelected = this.searchSelected;
		MusicMetadataCache.getInstance().prepare(song.url());
		this.updateButtonState();
	}

	private void toggleTrackRow(Row row)
	{
		selectTrackRow(row);
		toggleSelectedEntry();
		this.refreshSelectionLists();
	}

	private void deleteTrackRow(Row row)
	{
		selectTrackRow(row);
		deleteSelected();
		this.refreshSelectionLists();
	}

	private void moveTrackRow(PlaylistSelectionList.Move<Row> move)
	{
		Row row = move.value();
		if (!selectedRowEditable(row) || move.from() == move.to())
			return;
		List<Row> visibleRows = this.rows.stream()
				.filter(candidate -> candidate.playlist().equals(row.playlist())).toList();
		if (move.to() < 0 || move.to() >= visibleRows.size())
			return;
		int from = row.index();
		int to = visibleRows.get(move.to()).index();
		if (from == to)
			return;
		if (this.editMode == EditMode.SERVER) {
			this.pendingSelectionPlaylist = row.playlist();
			this.pendingSelectionIndex = to;
			runServerCommand(bindingCommand(row.binding()) + " move " + (from + 1) + " " + (to + 1));
		} else {
			message(MusicTracksManager.getInstance().moveLocalEntry(row.binding(), from, to).message());
			refreshAfterEdit(row.playlist(), to);
		}
	}

	private void selectInspectorPage(InspectorPage page)
	{
		this.inspectorPage = page;
		this.updateSettingsVisibility();
	}

	private void saveInspector()
	{
		if (this.viewMode == ViewMode.LIBRARY && selectedRowEditable()) {
			if (parseInteger(this.priorityBox.getValue(), -1000, 1000) == null) {
				message(text("message.invalid_priority"));
				return;
			}
			applyPriority();
			Row row = selectedRow();
			if (row != null && row.binding() != null && "idle".equals(row.binding().scene())) {
				if (parseInteger(this.intervalBox.getValue(), 0, 86400) == null) {
					message(text("message.invalid_interval"));
					return;
				}
				applyInterval();
			}
		}
		this.draftDirty = false;
		this.saveState();
		this.message(text("message.saved"));
	}

	private boolean canEditServer()
	{
		// AUD-29 #1: singleplayer eligibility via MbmSessionState only
		return MbmSessionState.isLocalSingleplayer()
				|| this.minecraft.player != null && this.minecraft.player.getPermissionLevel() >= 2;
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
		long playlistCount = this.rows.stream().map(Row::playlist).distinct().count();
		Component summary = text("summary", playlistCount, this.rows.size());
		int summaryX = headerX + this.font.width(headerTitle) + 8;
		if (summaryX < headerRight - 20)
			graphics.drawString(this.font, trimPixels(summary.getString(), headerRight - summaryX), summaryX,
					this.layout.titleBar().y() + 6, 0xFF9AA2AD, false);
		if (this.viewMode == ViewMode.LIBRARY) {
			graphics.drawString(this.font, text("field.library_filter"), this.layout.sidebar().innerX(),
					this.layout.sidebar().innerY() + 1, 0xFF9AA2AD, false);
			graphics.drawString(this.font, text("section.playlists"), this.layout.sidebar().innerX(),
					this.layout.sidebar().y() + 40, 0xFFB8C0CA, false);
			graphics.drawString(this.font, text("section.tracks"), this.layout.main().innerX(),
					this.layout.main().y() + 7, 0xFFB8C0CA, false);
			if (this.rows.isEmpty())
				graphics.drawString(this.font, text("empty"), this.layout.main().innerX(),
						this.layout.main().innerY() + 24, 0xFF9AA2AD, false);
		} else {
			graphics.drawString(this.font, text("field.search"), this.layout.sidebar().innerX(),
					this.layout.sidebar().innerY() + 1, 0xFF9AA2AD, false);
			graphics.drawString(this.font, text("section.search_results"), this.layout.main().innerX(),
					this.layout.main().y() + 7, 0xFFB8C0CA, false);
			graphics.drawString(this.font, text("search.page", this.searchPage + 1),
					this.layout.sidebar().innerX(), this.layout.sidebar().y() + 86, 0xFF9AA2AD, false);
			if (this.searchRows.isEmpty()) {
				Component searchState = this.searchLoading ? text("search.loading") : !this.searchError.isBlank()
						? text("search.failed", this.searchError) : this.searchBox.getValue().isBlank()
								? text("search.prompt") : text("search.empty");
				graphics.drawString(this.font, searchState, this.layout.main().innerX(),
						this.layout.main().innerY() + 24, 0xFF9AA2AD, false);
			}
		}
		Component legend = text("status.legend");
		int legendWidth = this.font.width(legend);
		if (this.layout.main().innerWidth() > legendWidth + 70)
			graphics.drawString(this.font, legend, this.layout.main().right() - legendWidth - 6,
					this.layout.main().y() + 7, 0xFF7F8791, false);
		this.renderInspector(graphics, mouseX, mouseY);
		renderPreviewProgress(graphics);
		if (this.volumePanelOpen)
			renderVolumePanel(graphics, mouseX, mouseY);
		super.render(graphics, mouseX, mouseY, partialTick);
		if (this.priorityBox != null && this.priorityBox.visible && parseInteger(this.priorityBox.getValue(), -1000, 1000) == null)
			graphics.renderOutline(this.priorityBox.getX() - 1, this.priorityBox.getY() - 1,
					this.priorityBox.getWidth() + 2, this.priorityBox.getHeight() + 2, 0xFFE06C75);
		if (this.intervalBox != null && this.intervalBox.visible && parseInteger(this.intervalBox.getValue(), 0, 86400) == null)
			graphics.renderOutline(this.intervalBox.getX() - 1, this.intervalBox.getY() - 1,
					this.intervalBox.getWidth() + 2, this.intervalBox.getHeight() + 2, 0xFFE06C75);
		renderCompletions(graphics, mouseX, mouseY);
		this.kindDropdown.render(graphics, this.font, mouseX, mouseY);
		this.orderDropdown.render(graphics, this.font, mouseX, mouseY);
		this.modeDropdown.render(graphics, this.font, mouseX, mouseY);
		PlaylistSelectionList<?> activeList = this.viewMode == ViewMode.LIBRARY
				? (mouseX < this.layout.main().x() ? this.playlistList : this.trackList) : this.searchResultList;
		Component tooltip = activeList == null ? null : activeList.tooltipAt(mouseX, mouseY);
		if (tooltip != null)
			this.setTooltipForNextRenderPass(tooltip);
	}

	private void renderInspector(GuiGraphics graphics, int mouseX, int mouseY)
	{
		PlaylistScreenLayout.Rect inspector = this.layout.inspector();
		int x = inspector.innerX();
		int y = inspector.innerY();
		int width = inspector.innerWidth();
		if (this.viewMode == ViewMode.NETEASE) {
			if (this.searchSelected < 0 || this.searchSelected >= this.searchRows.size()) {
				graphics.drawString(this.font, text("search.select"), x, y + 42, 0xFF9AA2AD, false);
				return;
			}
			NeteaseMusicSearch.Song song = this.searchRows.get(this.searchSelected);
			graphics.drawString(this.font, trimPixels(song.title(), width), x, y, 0xFFF0F1F2, false);
			graphics.drawString(this.font, trimPixels(song.artist(), width), x, y + 13, 0xFFB8C0CA, false);
			graphics.drawString(this.font, text("search.detail.album", trimPixels(song.album(), width)), x, y + 31,
					0xFF9AA2AD, false);
			graphics.drawString(this.font, text("search.detail.id", song.id()), x, y + 44, 0xFF9AA2AD, false);
			graphics.drawString(this.font, text("search.detail.duration", formatDuration(song.durationMillis())), x, y + 57,
					0xFF9AA2AD, false);
			graphics.drawString(this.font, text("detail.source"), x, y + 73, 0xFF9AA2AD, false);
			drawWrapped(graphics, song.url(), x, y + 86, width, 0xFFD6D9DE);
			return;
		}
		Row row = selectedRow();
		if (row == null) {
			graphics.drawString(this.font, text("select_track"), x, y + 28, 0xFF9AA2AD, false);
			return;
		}
		if (this.inspectorPage == InspectorPage.DETAILS) {
			graphics.drawString(this.font, trimPixels(row.title(), width), x, y + 24, 0xFFF0F1F2, false);
			graphics.drawString(this.font, trimPixels(row.artist().isBlank() ? row.context() : row.artist(), width),
					x, y + 37, 0xFFB8C0CA, false);
			graphics.drawString(this.font, trimPixels(text("detail.playlist", row.playlist()).getString(), width),
					x, y + 51, 0xFF9AA2AD, false);
			graphics.drawString(this.font, trimPixels(text("detail.source_value", editableLabel(row)).getString(), width), x, y + 64,
					0xFF9AA2AD, false);
			graphics.drawString(this.font, text("field.priority"), x, detailFieldsY() - 12, 0xFF9AA2AD, false);
			if (this.intervalBox.visible)
				graphics.drawString(this.font, text("field.interval"), x + (width / 2) + 2,
						detailFieldsY() - 12, 0xFF9AA2AD, false);
			if (this.layout.inspector().height() >= 230)
				renderCover(graphics, row, x, Math.min(this.layout.inspector().bottom() - 86, detailActionY() + 28));
		} else if (this.inspectorPage == InspectorPage.BINDING) {
			graphics.drawString(this.font, text("field.scene"), x, y + 22, 0xFF9AA2AD, false);
			graphics.drawString(this.font, text("field.kind"), x + width / 2 + 2, y + 22, 0xFF9AA2AD, false);
			graphics.drawString(this.font, text("field.target"), x, y + 56, 0xFF9AA2AD, false);
			graphics.drawString(this.font, text("binding.help"), x, y + 119, 0xFF7F8791, false);
		} else {
			List<IdleCondition> conditions = row.entry().conditions();
			int editorY = conditionEditorY();
			graphics.drawString(this.font, text("detail.conditions"), x, y + 22, 0xFFB8C0CA, false);
			int rowY = y + 34;
			int available = Math.max(0, editorY - rowY - 3);
			int visible = Math.min(conditions.size(), Math.max(0, available / 18));
			for (int i = 0; i < visible; i++) {
				IdleCondition condition = conditions.get(i);
				boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY + i * 18 && mouseY < rowY + i * 18 + 18;
				if (i == parseConditionIndex() - 1 || hovered)
					graphics.fill(x, rowY + i * 18, x + width, rowY + i * 18 + 17,
							i == parseConditionIndex() - 1 ? 0xFF293B4A : 0xFF252A30);
				String value = (condition.inverted() ? "NOT " : "") + condition.type();
				if (!condition.argument().isBlank())
					value += "  " + condition.argument();
				graphics.drawString(this.font, trimPixels(value, width - 18), x + 3, rowY + i * 18 + 4,
						0xFFD6D9DE, false);
				graphics.drawString(this.font, "x", x + width - 9, rowY + i * 18 + 4, 0xFFE06C75, false);
			}
			graphics.drawString(this.font, text("field.condition_type"), x, editorY - 12, 0xFF9AA2AD, false);
			graphics.drawString(this.font, text("field.condition_argument"), x + width / 2 + 2,
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
		PlaylistScreenLayout.Rect inspector = this.layout.inspector();
		return Math.max(inspector.innerY() + 70, inspector.bottom() - 48);
	}

	private int parseConditionIndex()
	{
		return Math.max(1, state().conditionIndex);
	}

	private String trimPixels(String value, int width)
	{
		if (this.font.width(value) <= width)
			return value;
		String suffix = "...";
		return this.font.plainSubstrByWidth(value, Math.max(0, width - this.font.width(suffix))) + suffix;
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
			PreviewChannel.playUrl(song.url(), 20, song.durationMillis());
			return;
		}
		if (this.selected < 0 || this.selected >= this.rows.size())
			return;
		Row row = this.rows.get(this.selected);
		stopPreview();
		ResourceLocation sound = soundLocation(row.entry().url());
		if (sound != null)
			PreviewChannel.playSound(sound, 20, row.entry().url());
		else
			PreviewChannel.playUrl(row.entry().url(), 20, 0L);
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
		int left = previewProgressLeft();
		int right = previewProgressRight();
		int y = previewProgressY();
		long position = handler.getPreviewPositionMillis();
		// K4 P1: isPreviewing() is NOT a valid guard - previewUrl is set before
		// the decode thread opens the line; clamp -1 for display arithmetic
		if (position < 0L)
			position = 0L;
		long duration = handler.getPreviewDurationMillis();
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
		long duration = handler.getPreviewDurationMillis();
		int left = previewProgressLeft();
		int right = previewProgressRight();
		int y = previewProgressY();
		if (!handler.isPreviewing() || duration <= 0L || mouseX < left || mouseX > right || mouseY < y - 3 || mouseY > y + 10)
			return false;
		double progress = (mouseX - left) / Math.max(1.0D, right - left);
		long position = Math.round(duration * Math.max(0.0D, Math.min(1.0D, progress)));
		return handler.seekPreviewMusic(position);
	}

	// ==================== K9-3: volume popup ====================

	private int volumePanelX()
	{
		return this.width - 236;
	}

	private int volumePanelY()
	{
		return 28;
	}

	private void renderVolumePanel(GuiGraphics graphics, int mouseX, int mouseY)
	{
		int x = volumePanelX();
		int y = volumePanelY();
		graphics.fill(x, y, x + 230, y + 96, 0xE81A1F26);
		graphics.renderOutline(x, y, 230, 96, 0xFF3A4550);
		graphics.drawString(this.font, "Main MBM", x + 8, y + 6, 0xFFB8C0CA);
		renderVolumeSlider(graphics, 0, x + 8, y + 18, 166);
		graphics.drawString(this.font, Component.literal(String.format(Locale.ROOT, "%.0f%%", this.mainGainValue * 100)),
				x + 180, y + 20, 0xFFD6D9DE);
		graphics.drawString(this.font, "Preview", x + 8, y + 38, 0xFFB8C0CA);
		boolean follows = this.previewFollowsMain;
		renderVolumeSlider(graphics, 1, x + 8, y + 50, follows ? 166 : 166);
		graphics.drawString(this.font, Component.literal(String.format(Locale.ROOT, "%.0f%%",
				(follows ? this.mainGainValue : this.previewGainValue) * 100)),
				x + 180, y + 52, follows ? 0xFF8B929C : 0xFFD6D9DE);
		Component followsLabel = Component.literal(follows ? "[x] preview follows main" : "[ ] preview follows main");
		graphics.drawString(this.font, followsLabel, x + 8, y + 74, follows ? 0xFF73D98A : 0xFF8B929C);
	}

	private void renderVolumeSlider(GuiGraphics graphics, int index, int x, int y, int width)
	{
		float value = index == 0 ? this.mainGainValue : this.previewGainValue;
		if (index == 1 && this.previewFollowsMain)
			value = this.mainGainValue;
		graphics.fill(x, y + 4, x + width, y + 6, 0xFF3A4550);
		int filled = Math.round(width * value);
		graphics.fill(x, y + 4, x + filled, y + 6, 0xFF73D98A);
		graphics.fill(x + filled - 2, y + 1, x + filled + 2, y + 9, 0xFFE8F4F8);
	}

	private int volumeSliderAt(double mouseX, double mouseY)
	{
		int x = volumePanelX();
		int y = volumePanelY();
		int sliderWidth = 166;
		if (mouseY >= y + 16 && mouseY <= y + 28)
			return mouseX >= x + 8 && mouseX <= x + 8 + sliderWidth ? 0 : -1;
		if (mouseY >= y + 48 && mouseY <= y + 60)
			return mouseX >= x + 8 && mouseX <= x + 8 + sliderWidth ? 1 : -1;
		return -1;
	}

	private boolean volumeFollowsButtonAt(double mouseX, double mouseY)
	{
		int x = volumePanelX();
		int y = volumePanelY();
		return mouseX >= x + 8 && mouseX <= x + 200 && mouseY >= y + 70 && mouseY <= y + 90;
	}

	private void updateVolumeSlider(int index, double mouseX)
	{
		int x = volumePanelX();
		float value = (float) Math.max(0.0D, Math.min(1.0D, (mouseX - (x + 8)) / 166.0D));
		if (index == 0) {
			this.mainGainValue = value;
			MobBattleMusicConfig.CLIENT.mbmUserGain.set((double) value);
			ExternalMusicHandler.getInstance().getPlayer().setUserGain(value);
			if (this.previewFollowsMain) {
				ExternalMusicHandler.getInstance().getPreviewPlayer().setUserGain(value);
				MobBattleMusicConfig.CLIENT.previewGain.set((double) value);
			}
		} else {
			this.previewGainValue = value;
			MobBattleMusicConfig.CLIENT.previewGain.set((double) value);
			ExternalMusicHandler.getInstance().getPreviewPlayer().setPreviewGain(value);
		}
	}

	private void togglePreviewFollowsMain()
	{
		this.previewFollowsMain = !this.previewFollowsMain;
		MobBattleMusicConfig.CLIENT.previewFollowsMain.set(this.previewFollowsMain);
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		handler.getPreviewPlayer().setUserGain(this.previewFollowsMain ? this.mainGainValue : 1.0F);
		if (this.previewFollowsMain) {
			MobBattleMusicConfig.CLIENT.previewGain.set((double) this.mainGainValue);
			this.previewGainValue = this.mainGainValue;
			handler.getPreviewPlayer().setPreviewGain(this.mainGainValue);
		}
		saveVolumeConfig();
	}

	private void saveVolumeConfig()
	{
		try {
			MobBattleMusicConfig.CLIENT_SPEC.save();
		} catch (Exception e) {
			org.apache.logging.log4j.LogManager.getLogger("mobbattlemusic")
					.warn("Failed to save volume config", e);
		}
	}

	private int previewProgressLeft()
	{
		return this.layout.main().innerX();
	}

	private int previewProgressRight()
	{
		return this.layout.main().right() - PlaylistScreenLayout.PADDING;
	}

	private int previewProgressY()
	{
		return this.layout.main().bottom() - 12;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		if (this.modeDropdown.isOpen()) {
			PlaylistDropdown.Item item = this.modeDropdown.itemAt(mouseX, mouseY);
			if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && item != null)
				selectEditMode(item.id());
			else
				this.modeDropdown.close();
			return true;
		}
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
		// K9-3: volume panel sliders come before the preview seek handling
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.volumePanelOpen) {
			int sliderIndex = volumeSliderAt(mouseX, mouseY);
			if (sliderIndex >= 0) {
				this.volumeDraggingIndex = sliderIndex;
				updateVolumeSlider(sliderIndex, mouseX);
				return true;
			}
			if (volumeFollowsButtonAt(mouseX, mouseY)) {
				togglePreviewFollowsMain();
				return true;
			}
		}
		if (seekPreview(mouseX, mouseY))
			return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && clickConditionRow(mouseX, mouseY))
			return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button)
	{
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.volumeDraggingIndex >= 0) {
			this.volumeDraggingIndex = -1;
			// K9-3: persist on release - the config save is a disk write and
			// must not run on every mouse move
			saveVolumeConfig();
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY)
	{
		if (this.volumeDraggingIndex >= 0) {
			updateVolumeSlider(this.volumeDraggingIndex, mouseX);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	private boolean clickConditionRow(double mouseX, double mouseY)
	{		if (this.viewMode != ViewMode.LIBRARY || this.inspectorPage != InspectorPage.CONDITIONS)
			return false;
		Row row = selectedRow();
		if (row == null || !selectedRowEditable())
			return false;
		int x = this.layout.inspector().innerX();
		int y = this.layout.inspector().innerY() + 34;
		int width = this.layout.inspector().innerWidth();
		int visible = Math.min(row.entry().conditions().size(),
				Math.max(0, (conditionEditorY() - y - 3) / 18));
		for (int i = 0; i < visible; i++) {
			if (mouseX < x || mouseX >= x + width || mouseY < y + i * 18 || mouseY >= y + i * 18 + 18)
				continue;
			state().conditionIndex = i + 1;
			if (mouseX >= x + width - 16)
				deleteCondition();
			return true;
		}
		return false;
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta)
	{
		if (this.modeDropdown.mouseScrolled(delta) || this.kindDropdown.mouseScrolled(delta) || this.orderDropdown.mouseScrolled(delta))
			return true;
		return super.mouseScrolled(mouseX, mouseY, delta);
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
		if (handleDropdownKey(this.modeDropdown, keyCode, item -> selectEditMode(item.id())) ||
				handleDropdownKey(this.kindDropdown, keyCode, item -> selectKind(item.id())) ||
				handleDropdownKey(this.orderDropdown, keyCode, item -> selectOrder(item.id())))
			return true;
		if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) &&
				this.searchBox != null && this.searchBox.isFocused()) {
			if (!this.searchRows.isEmpty()) {
				this.searchResultList.selectIndex(0, true);
				this.setFocused(this.searchResultList);
			} else {
				startSearch(0);
			}
			return true;
		}
		if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) &&
				this.libraryFilterBox != null && this.libraryFilterBox.isFocused() && this.trackList != null) {
			this.trackList.selectIndex(0, true);
			this.setFocused(this.trackList);
			return true;
		}
		if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 && keyCode == GLFW.GLFW_KEY_UP && this.viewMode == ViewMode.LIBRARY)
			return this.trackList != null && this.trackList.moveSelected(-1);
		if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 && keyCode == GLFW.GLFW_KEY_DOWN && this.viewMode == ViewMode.LIBRARY)
			return this.trackList != null && this.trackList.moveSelected(1);
		if (keyCode == GLFW.GLFW_KEY_ESCAPE && this.draftDirty) {
			requestClose();
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
		requestClose();
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
		if (this.editMode == EditMode.LOCAL && !canEditServer()) {
			message(text("mode.no_permission"));
			return;
		}
		saveState();
		stopPreview();
		EditMode next = this.editMode == EditMode.LOCAL ? EditMode.SERVER : EditMode.LOCAL;
		this.minecraft.setScreen(new MusicPlaylistScreen(this.parent, next));
	}

	private void toggleModeDropdown()
	{
		if (this.editMode == EditMode.LOCAL && !canEditServer())
			return;
		this.kindDropdown.close();
		this.orderDropdown.close();
		if (this.modeDropdown.isOpen())
			this.modeDropdown.close();
		else
			this.modeDropdown.open(this.editMode.name().toLowerCase(Locale.ROOT));
	}

	private void selectEditMode(String id)
	{
		EditMode selected = "server".equals(id) ? EditMode.SERVER : EditMode.LOCAL;
		this.modeDropdown.close();
		if (selected == this.editMode)
			return;
		switchEditMode();
	}

	private Component modeLabel()
	{
		return text("mode_button", text("mode." + this.editMode.name().toLowerCase(Locale.ROOT))).copy().append(" v");
	}

	private void updateViewState()
	{
		boolean library = this.viewMode == ViewMode.LIBRARY;
		if (this.libraryTabButton != null)
			this.libraryTabButton.active = !library;
		if (this.idleTabButton != null)
			this.idleTabButton.active = true;
		if (this.filtersTabButton != null)
			this.filtersTabButton.active = true;
		if (this.searchTabButton != null)
			this.searchTabButton.active = library;
		if (this.searchBox != null)
			this.searchBox.visible = !library;
		if (this.libraryFilterBox != null)
			this.libraryFilterBox.visible = library;
		if (this.searchButton != null)
			this.searchButton.visible = !library;
		if (this.refreshButton != null)
			this.refreshButton.visible = true;
		if (this.copyUrlButton != null)
			this.copyUrlButton.visible = !library && this.inspectorPage == InspectorPage.DETAILS;
		if (this.previousPageButton != null)
			this.previousPageButton.visible = !library;
		if (this.nextPageButton != null)
			this.nextPageButton.visible = !library;
		if (this.playlistList != null)
			this.playlistList.setVisible(library);
		if (this.trackList != null)
			this.trackList.setVisible(library);
		if (this.searchResultList != null)
			this.searchResultList.setVisible(!library);
		updateSettingsVisibility();
	}

	private void updateSettingsVisibility()
	{
		Row selected = selectedRow();
		boolean hasTrack = this.viewMode == ViewMode.LIBRARY && selected != null;
		boolean editable = hasTrack && selectedRowEditable();
		boolean idle = editable && selected.binding() != null && "idle".equals(selected.binding().scene());
		boolean details = this.inspectorPage == InspectorPage.DETAILS;
		boolean binding = this.inspectorPage == InspectorPage.BINDING;
		boolean conditions = this.inspectorPage == InspectorPage.CONDITIONS;
		this.inspectorDetailsButton.visible = this.viewMode == ViewMode.LIBRARY;
		this.inspectorBindingButton.visible = this.viewMode == ViewMode.LIBRARY;
		this.inspectorConditionsButton.visible = this.viewMode == ViewMode.LIBRARY;
		this.orderButton.visible = details && editable;
		this.orderButton.active = editable;
		this.priorityBox.visible = details && editable;
		this.priorityBox.active = editable;
		this.intervalBox.visible = details && idle;
		this.intervalBox.active = idle;
		this.timelineEditorButton.visible = details && editable;
		this.timelineEditorButton.active = editable;
		this.sceneBox.visible = binding && this.viewMode == ViewMode.LIBRARY;
		this.sceneBox.active = this.sceneBox.visible;
		this.kindButton.visible = binding && this.viewMode == ViewMode.LIBRARY;
		this.kindButton.active = this.kindButton.visible;
		this.targetBox.visible = binding && this.viewMode == ViewMode.LIBRARY;
		this.targetBox.active = this.targetBox.visible && !"scene".equals(this.addKind);
		this.addBindingButton.visible = binding || this.viewMode == ViewMode.NETEASE;
		this.addBindingButton.active = this.viewMode == ViewMode.NETEASE
				? this.searchSelected >= 0 && this.searchSelected < this.searchRows.size() : hasTrack;
		this.copyUrlButton.visible = this.viewMode == ViewMode.NETEASE && details;
		this.copyUrlButton.active = this.searchSelected >= 0 && this.searchSelected < this.searchRows.size();
		int editorY = conditionEditorY();
		this.conditionTypeBox.setY(editorY);
		this.conditionArgumentBox.setY(editorY);
		int actionY = editorY + 22;
		this.conditionInvertButton.setY(actionY);
		this.conditionAddButton.setY(actionY);
		this.conditionDeleteButton.setY(actionY);
		this.conditionTypeBox.visible = conditions && editable;
		this.conditionTypeBox.active = this.conditionTypeBox.visible;
		this.conditionArgumentBox.visible = conditions && editable;
		this.conditionArgumentBox.active = this.conditionArgumentBox.visible;
		this.conditionInvertButton.visible = conditions && editable;
		this.conditionInvertButton.active = this.conditionInvertButton.visible;
		this.conditionAddButton.visible = conditions && editable;
		this.conditionAddButton.active = this.conditionAddButton.visible && !this.conditionTypeBox.getValue().isBlank();
		this.conditionDeleteButton.visible = conditions && editable;
		this.conditionDeleteButton.active = this.conditionDeleteButton.visible && selected != null &&
				!selected.entry().conditions().isEmpty();
		boolean priorityValid = parseInteger(this.priorityBox.getValue(), -1000, 1000) != null;
		boolean intervalValid = !idle || parseInteger(this.intervalBox.getValue(), 0, 86400) != null;
		this.saveButton.active = editable && priorityValid && intervalValid;
		this.inspectorDetailsButton.active = this.inspectorPage != InspectorPage.DETAILS;
		this.inspectorBindingButton.active = this.inspectorPage != InspectorPage.BINDING;
		this.inspectorConditionsButton.active = this.inspectorPage != InspectorPage.CONDITIONS;
	}

	private void openTimelineEditor()
	{
		Row row = selectedRow();
		if (row == null)
			return;
		saveState();
		this.minecraft.setScreen(new TimelineMarkerScreen(this, this.editMode, row.playlist(), row.index()));
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
			if (row != null && !row.entry().conditions().isEmpty())
				state().conditionIndex = clamp(state().conditionIndex, 1, row.entry().conditions().size());
		} else {
			this.priorityBox.setValue("");
			this.intervalBox.setValue("");
			this.orderButton.setMessage(text("button.order", "-"));
		}
		if (force)
			this.draftDirty = false;
		updateSettingsVisibility();
	}

	private Row selectedRow()
	{
		return this.selected < 0 || this.selected >= this.rows.size() ? null : this.rows.get(this.selected);
	}

	// K9-1: export the selected binding as MBM playlist JSON (importable via
	// PlaylistImportScreen -> round-trip)
	private void exportSelectedBinding()
	{
		MusicTracksManager.DynamicBinding binding = selectedBinding();
		if (binding == null || this.editMode != EditMode.LOCAL) {
			message(text("message.export_requires_local_binding"));
			return;
		}
		String json = MusicTracksManager.getInstance().exportLocalBindingJson(binding);
		Path path = this.minecraft.gameDirectory.toPath()
				.resolve("mobbattlemusic_export_" + binding.storageKey().replace(':', '_') + ".json");
		try {
			Files.writeString(path, json, StandardCharsets.UTF_8);
			message(text("message.exported_to", path.getFileName().toString()));
		} catch (IOException e) {
			message(text("message.export_failed"));
		}
	}

	// K9-1: called by PlaylistImportScreen after an atomic commit
	public void refreshAfterImport()
	{
		this.rebuildRows();
		this.loadSelectedSettings(true);
		this.updateButtonState();
	}

	private MusicTracksManager.DynamicBinding selectedBinding()
	{
		Row row = selectedRow();
		return row == null ? null : row.binding();
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

	// K9-2: open the standalone Conditions inspector for the selected entry
	private void openConditionsInspector()
	{
		Row row = selectedRow();
		if (row == null)
			return;
		MusicTracksManager.DynamicBinding binding = selectedBinding();
		if (binding == null)
			return;
		boolean serverMode = this.editMode == EditMode.SERVER;
		String label = (row.title() == null ? row.entry().url() : row.title()) + " @" + binding.storageKey();
		this.minecraft.setScreen(new ConditionInspectorScreen(this, binding, row.index(), label,
				row.entry().conditions(), serverMode));
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
			String command = "mobbattlemusic entry_condition " + row.playlist() + " " + (row.index() + 1) + " " +
					(this.conditionInverted ? "add_not " : "add ") + type;
			if (!argument.isBlank())
				command += " " + argument;
			runServerCommand(command);
		} else {
			message(MusicTracksManager.getInstance().addLocalEntryCondition(row.binding(), row.index(),
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
		int index = state().conditionIndex;
		if (index < 1 || index > row.entry().conditions().size()) {
			message(text("message.invalid_condition_index"));
			return;
		}
		if (this.editMode == EditMode.SERVER)
			runServerCommand("mobbattlemusic entry_condition " + row.playlist() + " " + (row.index() + 1) +
					" delete " + index);
		else {
			message(MusicTracksManager.getInstance().deleteLocalEntryCondition(row.binding(), row.index(), index - 1).message());
			refreshAfterEdit();
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
		Row selected = selectedRow();
		refreshAfterEdit(selected == null ? null : selected.playlist(), selected == null ? -1 : selected.index());
	}

	private void refreshAfterEdit(ResourceLocation selectedPlaylist, int selectedTrackIndex)
	{
		this.rebuildRows();
		if (selectedPlaylist != null) {
			for (int i = 0; i < this.rows.size(); i++) {
				Row candidate = this.rows.get(i);
				if (candidate.playlist().equals(selectedPlaylist) &&
						(selectedTrackIndex < 0 || candidate.index() == selectedTrackIndex)) {
					this.selected = i;
					this.selectedPlaylist = candidate.playlist();
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
			if (this.searchResultList != null)
				this.refreshSelectionLists();
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
					if (this.searchResultList != null)
						this.refreshSelectionLists();
					updateButtonState();
				}));
	}

	private boolean completeFocusedBox()
	{
		if (this.sceneBox != null && this.sceneBox.isFocused())
			return complete(this.sceneBox, MusicTracksManager.supportedDynamicScenes());
		if (this.targetBox != null && this.targetBox.isFocused())
			return complete(this.targetBox, targetSuggestions());
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
			graphics.drawString(this.font, trimPixels(popup.matches().get(i), Math.max(8, popup.width() - 6)),
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

	private void updateButtonState()
	{
		boolean hasSelection = this.viewMode == ViewMode.NETEASE
				? this.searchSelected >= 0 && this.searchSelected < this.searchRows.size()
				: this.selected >= 0 && this.selected < this.rows.size();
		if (this.previewButton != null)
			this.previewButton.active = hasSelection;
		if (this.addBindingButton != null)
			this.addBindingButton.active = this.viewMode == ViewMode.NETEASE
					? this.searchSelected >= 0 && this.searchSelected < this.searchRows.size()
					: hasSelection;
		if (this.stopButton != null)
			this.stopButton.active = true;
		if (this.searchButton != null)
			this.searchButton.active = this.searchBox != null && !this.searchBox.getValue().isBlank();
		if (this.previousPageButton != null)
			this.previousPageButton.active = !this.searchLoading && this.searchPage > 0;
		if (this.nextPageButton != null)
			this.nextPageButton.active = !this.searchLoading && this.searchHasNext;
		if (this.modeButton != null) {
			this.modeButton.active = this.editMode == EditMode.SERVER || canEditServer();
			this.modeButton.setMessage(modeLabel());
		}
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
		if (!List.of("scene", "idle_rule", "type", "uuid", "player").contains(kind))
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
			addBinding(this.searchRows.get(this.searchSelected).url());
			return;
		}
		if (this.selected < 0 || this.selected >= this.rows.size())
			return;
		addBinding(this.rows.get(this.selected).entry().url());
	}

	private void addBinding(String music)
	{
		String scene = this.sceneBox.getValue().trim();
		String target = this.targetBox.getValue().trim();
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

	private void copySelectedUrl()
	{
		if (this.viewMode != ViewMode.NETEASE || this.searchSelected < 0 ||
				this.searchSelected >= this.searchRows.size())
			return;
		this.minecraft.keyboardHandler.setClipboard(this.searchRows.get(this.searchSelected).url());
		message(text("message.url_copied"));
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

	private void toggleSelectedEntry()
	{
		Row row = selectedRow();
		if (row == null)
			return;
		MusicTracksManager manager = MusicTracksManager.getInstance();
		boolean enabled = !manager.isMusicEntryEnabled(row.playlist(), row.entry());
		message(manager.setMusicEntryEnabled(row.playlist(), row.entry(), enabled).message());
		if (!enabled)
			stopPreview();
		this.updateButtonState();
	}

	private Component entryEnabledLabel()
	{
		Row row = selectedRow();
		boolean enabled = row != null && MusicTracksManager.getInstance().isMusicEntryEnabled(row.playlist(), row.entry());
		return text(enabled ? "button.disable_entry" : "button.enable_entry");
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
		state.searchQuery = this.searchBox == null ? state.searchQuery : this.searchBox.getValue();
		state.libraryFilter = this.libraryFilterBox == null ? state.libraryFilter : this.libraryFilterBox.getValue();
		state.selected = this.selected;
		state.searchSelected = this.searchSelected;
		state.searchPage = this.searchPage;
		state.searchHasNext = this.searchHasNext;
		state.searchRows = List.copyOf(this.searchRows);
		state.conditionType = this.conditionTypeBox == null ? state.conditionType : this.conditionTypeBox.getValue();
		state.conditionArgument = this.conditionArgumentBox == null ? state.conditionArgument : this.conditionArgumentBox.getValue();
		state.conditionInverted = this.conditionInverted;
		state.selectedPlaylist = this.selectedPlaylist == null ? "" : this.selectedPlaylist.toString();
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
	
	private static String rowFilterText(Row row)
	{
		StringBuilder text = new StringBuilder().append(row.title()).append(' ').append(row.artist()).append(' ')
				.append(row.context()).append(' ').append(row.playlist()).append(' ');
		if (row.binding() != null) {
			text.append(row.binding().scene()).append(' ').append(row.binding().target()).append(' ');
			switch (row.binding().scene()) {
				case "aggressive" -> text.append("battle combat 战斗 攻击 ");
				case "ambient" -> text.append("ambient atmosphere 氛围 环境 ");
				case "idle" -> text.append("idle 空闲 ");
				case "player" -> text.append("player pvp 玩家 ");
			}
		}
		for (IdleCondition condition : row.entry().conditions()) {
			text.append(condition.type()).append(' ').append(condition.argument()).append(' ');
			if (condition.type().endsWith(":underwater"))
				text.append("underwater 水下 ");
			else if (condition.type().endsWith(":structure"))
				text.append("structure 结构 ");
			else if (condition.type().endsWith(":biome"))
				text.append("biome 生物群系 ");
			else if (condition.type().endsWith(":dimension"))
				text.append("dimension 维度 ");
		}
		return text.toString().toLowerCase(Locale.ROOT);
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
		private String searchQuery = "";
		private String libraryFilter = "";
		private String conditionType = "mobbattlemusic:dimension";
		private String conditionArgument = "";
		private int conditionIndex = 1;
		private boolean conditionInverted;
		private String selectedPlaylist = "";
		private ViewMode viewMode = ViewMode.LIBRARY;
		private int selected = -1;
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