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
import nonamecrackers2.mobbattlemusic.client.audio.WorldPlaybackChannel;
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
	// K15-A: the full unfiltered/unmerged row list (music-first navigation
	// resolves uses from here even when the visible list is grouped)
	private final List<Row> allRowsCached = new ArrayList<>();
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
	private Button timelineTabButton;
	private Button filtersTabButton;
	private Button neteaseTabButton;
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
	private Button playlistRulesButton;
	private Button useEditorButton;
	// K16-D: last main-channel URL - silent-playback warnings fire once per
	// track transition (start/track-switch/stop), never on a timer
	private String lastMainUrl;
	private Button inspectorDetailsButton;
	private Button inspectorBindingButton;
	private Button inspectorConditionsButton;
	private Button saveButton;
	private Button cancelButton;
	private Button addBindingButton;
	private Button importButton;
	private Button volumeButton;
	// K13-A: LIBRARY batch condition management - when multi-select is on,
	// clicking rows only toggles their membership in selectedRowSet; the
	// "add selected to target" action (addBindingButton) then adds every
	// checked row's URL to the target group chosen by kind/scene/target.
	private boolean multiSelectActive;
	// K13-A: batch selection keys (playlist + "#" + url) - stable across
	// rebuildRows since rows are rebuilt as new instances
	private final java.util.Set<String> selectedRowSet = new java.util.LinkedHashSet<>();
	private Button multiSelectButton;
	// K13-C: manual song selection - plays the selected row through the
	// preview player while covering the main channel
	private Button coverPlayButton;
	// K15-A: music-first grouping toggle
	private Button groupByTrackButton;
	// K9-3: volume popup state - two sliders + preview-follows-main toggle
	private boolean volumePanelOpen;
	private float mainGainValue = 1.0F;
	private float previewGainValue = 1.0F;
	private boolean previewFollowsMain = true;
	private int volumeDraggingIndex = -1;
	// K9-4: main-channel player dock state
	private static final int DOCK_HEIGHT = 56;
	private boolean dockSeekDragging;
	private boolean dockPausedByUser;
	// K10-B: drag previews the position; the seek is committed once on release
	private long dockPreviewPosition = -1L;
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
		this.timelineTabButton = this.addRenderableWidget(tabs.get(1));
		this.filtersTabButton = this.addRenderableWidget(tabs.get(2));
		this.neteaseTabButton = this.addRenderableWidget(tabs.get(3));
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
		// K15-A: music-first grouping toggle (merge same-track rows)
		this.groupByTrackButton = this.addRenderableWidget(Button.builder(groupByTrackLabel(), b -> toggleGroupByTrack())
				.bounds(sidebar.innerX(), sidebar.innerY() + 34, sidebar.innerWidth(), 18).build());
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
		// K16-B: 歌单规则 - the playlist-level rule editor (conditions every
		// entry of this playlist inherits); by-use view only
		this.playlistRulesButton = this.addRenderableWidget(Button.builder(text("button.playlist_rules"),
				button -> openPlaylistRules()).bounds(ix, detailActionY + 24, iw, 20).build());
		// K16-B: 编辑用途 - the music-first use editor (all uses of the
		// selected track, add/remove/jump); by-track view only. Offset from
		// detailActionY so the batch toggle (editY+106) does not overlap.
		this.useEditorButton = this.addRenderableWidget(Button.builder(text("button.use_editor"),
				button -> openUseEditor()).bounds(ix, detailActionY + 48, iw, 20).build());
		this.addBindingButton = this.addRenderableWidget(Button.builder(text("button.add_bind"), button -> useSelectedSource())
				.bounds(ix, editY + 58, iw, 20).build());
		// K9-1: transactional import preview (export is config-file only)
		this.importButton = this.addRenderableWidget(Button.builder(text("button.import"),
				button -> this.minecraft.setScreen(new PlaylistImportScreen(this)))
				.bounds(ix, editY + 82, iw, 20).build());
		// K13-A: LIBRARY batch condition management - toggle multi-select
		// mode; while active, row clicks check rows instead of editing, and
		// "add selected to target" copies every checked track into the target
		// group chosen by kind/scene/target
		this.multiSelectButton = this.addRenderableWidget(Button.builder(
				Component.literal("Batch: off"), button -> toggleMultiSelect())
				.bounds(ix, editY + 106, iw, 20).build());
		// K13-C: manual song selection - play the selected row through the
		// preview player while covering the main channel; the server-side
		// playback process keeps running untouched and the song continues
		// after this GUI closes
		this.coverPlayButton = this.addRenderableWidget(Button.builder(
				Component.literal("Play selected (cover main)"), button -> coverPlaySelected())
				.bounds(ix, editY + 130, iw, 20).build());
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
		this.playlistList.setBounds(new PlaylistScreenLayout.Rect(sidebar.x(), sidebar.y() + 58,
				sidebar.width(), Math.max(1, sidebar.height() - 64)));
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
		this.allRowsCached.clear();
		MusicTracksManager manager = MusicTracksManager.getInstance();
		// K15-A: group-by-track view merges every use of the same song into
		// one row (music-first); the details inspector then shows the uses
		boolean groupByTrack = state().groupByTrack;
		java.util.Map<String, Row> byTrack = new java.util.LinkedHashMap<>();
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
				Row row = new Row(playlist.configLocation(), context, binding, source, i, entry, title, artist);
				this.allRowsCached.add(row);
				if (groupByTrack) {
					byTrack.putIfAbsent(row.trackId(), row);
				} else {
					this.rows.add(row);
				}
			}
		}
		if (groupByTrack)
			this.rows.addAll(byTrack.values());
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
		// K15-C: grouped (by-track) view is a GLOBAL song library - the
		// playlist sidebar filter must NOT apply, otherwise a track whose
		// representative row happens to live in another binding disappears
		boolean grouped = state().groupByTrack;
		for (Row row : this.rows) {
			if (!grouped && this.selectedPlaylist != null && !this.selectedPlaylist.equals(row.playlist()))
				continue;
			// K13-A/K15-A: batch-mode check marks (last arg); subtitle shows
			// the music-first uses ("where else is this song used?")
			String uses = nonamecrackers2.mobbattlemusic.client.resource.TrackAssetRegistry
					.usesDisplay(row.entry().id());
			String subtitle = row.context() + "  #" + (row.index() + 1) +
					(uses.isBlank() ? "" : "  [" + uses + "]");
			// K16-B: the representative use's own conditions override the
			// playlist rule (conflict -> entry wins) - flag such rows with a
			// readable condition summary so the override is visible at a
			// glance in the grouped track view
			if (grouped) {
				String conditionSummary = describeConditions(row.entry().conditions());
				if (!conditionSummary.isBlank())
					subtitle += "  \u26a1" + conditionSummary;
			}
			// K15-C: the row always shows WHERE it lives (source badge) and
			// WHY it is locked when the current edit mode cannot touch it -
			// singleplayer has both LOCAL and SERVER data, so a SERVER page
			// showing LOCAL entries without explanation was confusing
			String badge = switch (row.source() == null ? null : row.source()) {
				case LOCAL -> "LOCAL";
				case SERVER -> "SERVER";
				default -> "RP";
			};
			Component lockedHint = null;
			if (row.binding() != null && row.source() != null
					&& this.editMode == EditMode.SERVER && row.source() != MusicTracksManager.DynamicSource.SERVER)
				lockedHint = text("source.locked_hint", text("source.local"));
			else if (row.binding() != null && row.source() != null
					&& this.editMode == EditMode.LOCAL && row.source() != MusicTracksManager.DynamicSource.LOCAL)
				lockedHint = text("source.locked_hint", text("source.server"));
			// K15-F: grouped (by-track) rows are ASSET representatives, not
			// concrete uses - use-level enable/delete must be impossible here;
			// the inspector gate alone is not enough, the row model hides the
			// +/- and x keys too
			boolean allowUseActions = !state().groupByTrack && selectedRowEditable(row);
			trackModels.add(new PlaylistSelectionList.Model<>(row.playlist() + "#" + row.index(), row,
					Component.literal(row.title()), Component.literal(subtitle),
					trackStatus(row), allowUseActions, allowUseActions,
					this.selectedRowSet.contains(row.playlist() + "#" + row.entry().url()),
					badge, lockedHint));
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
		// K13-A: while batch mode is on, clicking a row only toggles its
		// membership - no editor reload, no state change
		if (this.multiSelectActive) {
			String key = row.playlist() + "#" + row.entry().url();
			if (!this.selectedRowSet.remove(key))
				this.selectedRowSet.add(key);
			this.refreshSelectionLists();
			this.updateButtonState();
			return;
		}
		this.selected = this.rows.indexOf(row);
		this.selectedPlaylist = row.playlist();
		state().selected = this.selected;
		state().selectedPlaylist = row.playlist().toString();
		this.loadSelectedSettings(true);
		this.updateButtonState();
	}

	private void toggleMultiSelect()
	{
		this.multiSelectActive = !this.multiSelectActive;
		if (!this.multiSelectActive)
			this.selectedRowSet.clear();
		if (this.multiSelectButton != null)
			this.multiSelectButton.setMessage(Component.literal(this.multiSelectActive ? "Batch: on" : "Batch: off"));
		this.refreshSelectionLists();
		this.updateButtonState();
	}

	// K15-A: switch between binding-entry view and music-first (grouped) view
	private void toggleGroupByTrack()
	{
		// K15-E: keep the selection across the switch by use identity
		Row current = selectedRow();
		// K15-F: leaving by-use for grouped persists the exact concrete use
		// (playlist#entryId#occurrenceOrdinal) under its trackId - grouped
		// representative rows never overwrite it, so switching back returns
		// to the SAME use the user was editing, not the first occurrence of
		// the track. K15-F-r1: the map keeps one entry PER track, so a track
		// selected in grouped that differs from the persisted one falls back
		// to the grouped selection instead of jumping back to an old track.
		boolean enteringGrouped = !state().groupByTrack;
		if (enteringGrouped && current != null) {
			String key = concreteUseKey(current);
			if (key != null)
				state().lastConcreteUseByTrackId.put(current.trackId(), key);
		}
		String keepTrackId = current == null ? null : current.trackId();
		ResourceLocation keepPlaylist = current == null ? null : current.playlist();
		state().groupByTrack = !state().groupByTrack;
		if (this.groupByTrackButton != null)
			this.groupByTrackButton.setMessage(groupByTrackLabel());
		// K15-F: grouped view is asset-level - the per-use inspector pages
		// (binding/conditions) have nothing editable to show; DETAILS is the
		// only page that exists in this mode
		this.inspectorPage = InspectorPage.DETAILS;
		this.rebuildRows();
		this.selected = -1;
		// K15-F: when returning to by-use, the persisted concrete use is only
		// restored if the grouped selection is still the SAME track - a track
		// picked inside grouped must not bounce back to an old track's use
		String restoredUseKey = keepTrackId == null ? null
				: state().lastConcreteUseByTrackId.get(keepTrackId);
		if (!state().groupByTrack && restoredUseKey != null) {
			for (int i = 0; i < this.rows.size(); i++) {
				if (restoredUseKey.equals(concreteUseKey(this.rows.get(i)))) {
					this.selected = i;
					break;
				}
			}
		}
		if (this.selected < 0 && keepTrackId != null) {
			if (keepPlaylist != null) {
				for (int i = 0; i < this.rows.size(); i++) {
					if (keepTrackId.equals(this.rows.get(i).trackId()) && keepPlaylist.equals(this.rows.get(i).playlist())) {
						this.selected = i;
						break;
					}
				}
			}
			if (this.selected < 0) {
				for (int i = 0; i < this.rows.size(); i++) {
					if (keepTrackId.equals(this.rows.get(i).trackId())) {
						this.selected = i;
						break;
					}
				}
			}
		}
		if (this.selected < 0 && keepPlaylist != null && !state().groupByTrack) {
			for (int i = 0; i < this.rows.size(); i++) {
				if (keepPlaylist.equals(this.rows.get(i).playlist())) {
					this.selected = i;
					break;
				}
			}
		}
		if (this.selected < 0 && !this.rows.isEmpty())
			this.selected = 0;
		Row restored = this.selected >= 0 && this.selected < this.rows.size() ? this.rows.get(this.selected) : null;
		if (restored != null) {
			this.selectedPlaylist = restored.playlist();
			state().selected = this.selected;
			state().selectedPlaylist = restored.playlist().toString();
		}
		this.loadSelectedSettings(true);
		this.updateButtonState();
	}

	// K15-F: the stable identity of a concrete use row -
	// playlist#entryId#occurrenceOrdinal. The ordinal (not the entry index)
	// survives reorders of unrelated playlist entries; occurrenceOrdinal is
	// found by matching the TrackUse whose playlist position equals the row.
	private String concreteUseKey(Row row)
	{
		if (row == null)
			return null;
		for (var use : nonamecrackers2.mobbattlemusic.client.resource.TrackAssetRegistry.usesFor(row.trackId())) {
			if (use.playlistId().equals(row.playlist()) && use.entryId().equals(row.entry().id())
					&& use.entryIndex() == row.index())
				return use.useId();
		}
		return null;
	}

	private Component groupByTrackLabel()
	{
		return text(state().groupByTrack ? "group.by_track" : "group.by_use");
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
		// K15-F: grouped rows are assets - enabling/disabling a
		// representative use would silently mutate the first use; the model
		// hides the key, this guards every other call path
		if (state().groupByTrack)
			return;
		selectTrackRow(row);
		toggleSelectedEntry();
		this.refreshSelectionLists();
	}

	private void deleteTrackRow(Row row)
	{
		// K15-F: grouped rows are assets - deletion targets the first use of
		// the track; the model hides the key, this guards every other call path
		if (state().groupByTrack)
			return;
		// K13-A: batch mode - delete removes the row from the checked set
		// instead of deleting from disk; K13-B: refresh the button state so
		// unchecking the LAST row deactivates "add checked to target group"
		if (this.multiSelectActive) {
			this.selectedRowSet.remove(row.playlist() + "#" + row.entry().url());
			this.refreshSelectionLists();
			this.updateButtonState();
			return;
		}
		selectTrackRow(row);
		deleteSelected();
		this.refreshSelectionLists();
	}

	private void moveTrackRow(PlaylistSelectionList.Move<Row> move)
	{
		Row row = move.value();
		// K15-F: grouped rows are assets - dragging a representative would
		// reorder the underlying playlist of the first use
		if (state().groupByTrack || !selectedRowEditable(row) || move.from() == move.to())
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
		// K15-F: grouped view is asset-level - only the DETAILS page exists;
		// refusing the switch here is the second guard (buttons are also
		// deactivated in updateSettingsVisibility)
		if (state().groupByTrack)
			return;
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
		renderPlayerDock(graphics, mouseX, mouseY);
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
		// K15-E/K15-F: grouped (by-track) view is asset-level - editing
		// requires a concrete use; DETAILS is the only reachable inspector
		// page in grouped mode (toggleGroupByTrack forces it and the page
		// navigation is guarded), so the hint is visible whenever the user
		// could be confused by the locked controls
		boolean grouped = this.viewMode == ViewMode.LIBRARY && state().groupByTrack;
		if (this.inspectorPage == InspectorPage.DETAILS) {
			graphics.drawString(this.font, trimPixels(row.title(), width), x, y + 24, 0xFFF0F1F2, false);
			// K15-E: asset-level hint - grouped rows cannot be edited directly
			if (grouped) {
				graphics.drawString(this.font, text("group.select_use_hint"), x, y + 37, 0xFFE6C07A, false);
			} else {
				graphics.drawString(this.font, trimPixels(row.artist().isBlank() ? row.context() : row.artist(), width),
						x, y + 37, 0xFFB8C0CA, false);
			}
			graphics.drawString(this.font, trimPixels(text("detail.playlist", row.playlist()).getString(), width),
					x, y + 51, 0xFF9AA2AD, false);
			graphics.drawString(this.font, trimPixels(text("detail.source_value", editableLabel(row)).getString(), width), x, y + 64,
					0xFF9AA2AD, false);
			// K15-A/K15-C: music-first - show every OTHER use of this track;
			// each entry jumps to that binding's row. Capped at 3 rows so a
			// long use list can never overlap the priority fields below.
			java.util.List<Row> uses = rowsUsingSameTrack(row.trackId(), row);
			if (!uses.isEmpty()) {
				graphics.drawString(this.font, text("detail.uses"), x, y + 80, 0xFF9AA2AD, false);
				int useY = y + 92;
				int shown = Math.min(3, uses.size());
				for (int u = 0; u < shown; u++) {
					Row use = uses.get(u);
					boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= useY && mouseY < useY + 14;
					graphics.drawString(this.font, trimPixels(use.context() + "  #" + (use.index() + 1), width - 4),
							x + 3, useY, hovered ? 0xFFE5C07B : 0xFFD6D9DE, false);
					if (hovered)
						graphics.fill(x, useY - 1, x + width, useY + 13, 0x44253847);
					useY += 14;
				}
				if (uses.size() > shown)
					graphics.drawString(this.font, text("detail.uses_more", uses.size() - shown),
							x + 3, useY, 0xFF7F8791, false);
			}
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

	// K13-C: manual song selection - play the selected row through the
	// preview player while covering the main channel. The server-side
	// playback process (URL/session/CUE) is untouched; releasing (stop or a
	// new selection) restores the main channel envelope.
	private void coverPlaySelected()
	{
		if (this.selected < 0 || this.selected >= this.rows.size())
			return;
		Row row = this.rows.get(this.selected);
		int fadeTicks = Math.max(1, row.binding() == null ? 0 : row.binding().kind() == MusicTracksManager.DynamicBinding.Kind.SCENE
				? switch (row.binding().scene()) {
					case "ambient", "idle" -> 0;
					default -> 40;
				}
				: 40);
		PreviewChannel.playUrl(row.entry().url(), fadeTicks, 0L, true);
		message(Component.literal("Playing selected track (covers main) - close this screen to keep it playing"));
		this.updateButtonState();
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
				// K10-C/K11-C: following mirrors ONE factor (user gain); the
				// independent previewGain config value stays untouched
				ExternalMusicHandler.getInstance().getPreviewPlayer().setUserGain(value);
			}
		} else {
			// the preview slider only ever writes the INDEPENDENT preview
			// gain - it is remembered across follows-main toggles
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
		if (this.previewFollowsMain) {
			// K11-C: only the runtime effective gain changes - the stored
			// independent previewGain value is preserved for the next toggle
			handler.getPreviewPlayer().setUserGain(this.mainGainValue);
			handler.getPreviewPlayer().setPreviewGain(1.0f);
		} else {
			handler.getPreviewPlayer().setUserGain(1.0f);
			// restore the remembered independent value
			float independent = MobBattleMusicConfig.CLIENT.previewGain.get().floatValue();
			this.previewGainValue = independent;
			handler.getPreviewPlayer().setPreviewGain(independent);
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
		// K15-A: click a "also used in" entry in the details inspector
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && clickUseNav(mouseX, mouseY))
			return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && clickConditionRow(mouseX, mouseY))
			return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && clickDock(mouseX, mouseY))
			return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}

	// K15-A: the details inspector lists every other use of the selected
	// track; clicking one jumps the selection to that binding's row
	private boolean clickUseNav(double mouseX, double mouseY)
	{
		if (this.viewMode != ViewMode.LIBRARY || this.inspectorPage != InspectorPage.DETAILS)
			return false;
		Row row = selectedRow();
		if (row == null)
			return false;
		int x = this.layout.inspector().innerX();
		int width = this.layout.inspector().innerWidth();
		java.util.List<Row> uses = rowsUsingSameTrack(row.trackId(), row);
		if (uses.isEmpty())
			return false;
		int y = this.layout.inspector().innerY() + 92;
		int shown = Math.min(3, uses.size());
		for (int u = 0; u < shown; u++) {
			Row use = uses.get(u);
			if (mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 14) {
				jumpToUse(use);
				return true;
			}
			y += 14;
		}
		return false;
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
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.dockSeekDragging) {
			this.dockSeekDragging = false;
			// K10-B: exactly one seek per drag - committed on release
			commitDockSeek();
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
		if (this.dockSeekDragging) {
			updateDockSeek(mouseX);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	// ==================== K9-4: main-channel player dock ====================

	private int dockY()
	{
		return this.height - DOCK_HEIGHT;
	}

	private boolean hasMainTrack()
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		return handler.getCurrentlyPlayingUrl() != null && !handler.isStopRequested();
	}

	private void renderPlayerDock(GuiGraphics graphics, int mouseX, int mouseY)
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		graphics.fill(0, dockY(), this.width, this.height, 0xF0141A20);
		graphics.fill(0, dockY(), this.width, dockY() + 1, 0xFF3A4550);
		// K16-F: the dock always reflects what the player ACTUALLY hears -
		// an active preview/cover (audible) wins over the main channel (which
		// may be covered/muted or empty)
		graphics.drawString(this.font, "MAIN", 8, dockY() + 5, 0xFF73D98A);
		int progressLeft = 150;
		int progressRight = this.width - 220;
		int progressY = dockY() + 36;
		boolean previewActive = PreviewChannel.isActive();
		WorldPlaybackChannel.PlaybackOwner owner = WorldPlaybackChannel.playbackOwner();
		if (previewActive) {
			String previewUrl = PreviewChannel.currentTrack();
			String title = previewUrl == null ? "..." : describeTrackTitle(previewUrl);
			graphics.drawString(this.font, title, 52, dockY() + 5, 0xFFD6D9DE);
			graphics.drawString(this.font, PreviewChannel.isCoveringMain() ? "cover main" : "preview",
					52, dockY() + 18, 0xFF8B929C);
			graphics.drawString(this.font, "[AUTO]", 8, dockY() + 24, 0xFF8B929C);
			long duration = PreviewChannel.durationMillis();
			long position = PreviewChannel.positionMillis();
			graphics.fill(progressLeft, progressY, progressRight, progressY + 4, 0xFF3A4550);
			if (duration > 0L && position >= 0L) {
				long filledLong = Math.round((progressRight - progressLeft)
						* Math.min(1.0D, position / (double) duration));
				int filled = (int) Math.min(progressRight - progressLeft, filledLong);
				graphics.fill(progressLeft, progressY, progressLeft + filled, progressY + 4, 0xFF73D98A);
				graphics.drawString(this.font, formatMillis(position) + " / " + formatMillis(duration),
						progressLeft, progressY + 8, 0xFFB8C0CA);
			} else {
				graphics.drawString(this.font, "preparing...", progressLeft, progressY + 8, 0xFF8B929C);
			}
		} else if (hasMainTrack()) {
			String url = handler.getCurrentlyPlayingUrl();
			boolean buffering = handler.isPreparingCurrentMusic();
			boolean audible = handler.getPositionMillis() >= 0L;
			nonamecrackers2.mobbattlemusic.client.audio.PlaybackHandle handle = WorldPlaybackChannel.handle();
			String title = buffering ? "buffering..." : describeTrackTitle(url);
			graphics.drawString(this.font, title, 52, dockY() + 5, 0xFFD6D9DE);
			String source = describeDockSource(handle);
			graphics.drawString(this.font, source, 52, dockY() + 18, 0xFF8B929C);
			graphics.drawString(this.font, "[" + owner + "]", 8, dockY() + 24, ownerColor(owner));
			// K10-B: the owner badge is the explicit way back to AUTO
			if (owner != WorldPlaybackChannel.PlaybackOwner.AUTO)
				graphics.drawString(this.font, "click to return to AUTO", 52, dockY() + 32, 0xFF8B929C);

			long duration = handler.getDurationMillis();
			long position = handler.getPositionMillis();
			graphics.fill(progressLeft, progressY, progressRight, progressY + 4, 0xFF3A4550);
			if (duration > 0L && position >= 0L) {
				// K10-B: while dragging, show the previewed position, not the
				// live one - the seek commits only on release
				long displayed = this.dockSeekDragging && this.dockPreviewPosition >= 0L
						? this.dockPreviewPosition : position;
				long filledLong = Math.round((progressRight - progressLeft)
						* Math.min(1.0D, displayed / (double) duration));
				int filled = (int) Math.min(progressRight - progressLeft, filledLong);
				graphics.fill(progressLeft, progressY, progressLeft + filled, progressY + 4, 0xFF73D98A);
				graphics.drawString(this.font, formatMillis(displayed) + " / " + formatMillis(duration),
						progressLeft, progressY + 8, 0xFFB8C0CA);
			} else {
				graphics.drawString(this.font, buffering ? "preparing..." : "position n/a", progressLeft,
						progressY + 8, 0xFF8B929C);
			}
			if (audible && owner == WorldPlaybackChannel.PlaybackOwner.CUE)
				graphics.drawString(this.font, "locked (CUE)", progressRight - 70, dockY() + 5, 0xFFE06C75);
		} else {
			// K16-F: the dock NEVER hides - the transport buttons stay usable
			// even while nothing is playing
			graphics.drawString(this.font, "no track", 52, dockY() + 6, 0xFF8B929C);
			graphics.drawString(this.font, "[AUTO]", 8, dockY() + 24, 0xFF8B929C);
			graphics.fill(progressLeft, progressY, progressRight, progressY + 4, 0xFF3A4550);
			graphics.drawString(this.font, "position n/a", progressLeft, progressY + 8, 0xFF8B929C);
		}

		// K16-F: transport buttons are ALWAYS rendered (visible + clickable)
		int buttonY = dockY() + 16;
		int x = this.width - 172;
		boolean playing = previewActive ? PreviewChannel.isPlaying() : handler.isPlaying();
		graphics.drawCenteredString(this.font, "\u23ee", x + 16, buttonY, 0xFFD6D9DE);
		graphics.drawCenteredString(this.font, playing ? "\u23f8" : "\u25b6", x + 54, buttonY, 0xFFD6D9DE);
		graphics.drawCenteredString(this.font, "\u23f9", x + 92, buttonY, 0xFFD6D9DE);
		graphics.drawCenteredString(this.font, "\u23ed", x + 130, buttonY, 0xFFD6D9DE);
	}

	private static String formatMillis(long millis)
	{
		long totalSeconds = Math.max(0L, millis / 1000L);
		return String.format(java.util.Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
	}

	private int ownerColor(WorldPlaybackChannel.PlaybackOwner owner)
	{
		return switch (owner) {
			case MAN -> 0xFFE5C07B;
			case CUE -> 0xFFE06C75;
			default -> 0xFF8B929C;
		};
	}

	private String describeTrackTitle(String url)
	{
		String described = MusicTracksManager.getInstance().describeMusicSource(url);
		return described == null || described.isBlank() ? url : described;
	}

	private String describeDockSource(nonamecrackers2.mobbattlemusic.client.audio.PlaybackHandle handle)
	{
		if (handle == null || handle.sourceRef() == null)
			return "unknown source";
		if (handle.sourceRef().isDirect())
			return "direct";
		return handle.sourceRef().playlistId() + " @ " + handle.sourceRef().entryKey();
	}

	private boolean dockCanSeek()
	{
		if (!hasMainTrack())
			return false;
		// CUE tracks and tracks with timeline markers must not be dragged
		if (WorldPlaybackChannel.playbackOwner() == WorldPlaybackChannel.PlaybackOwner.CUE)
			return false;
		String url = ExternalMusicHandler.getInstance().getCurrentlyPlayingUrl();
		nonamecrackers2.mobbattlemusic.client.audio.PlaybackHandle handle = WorldPlaybackChannel.handle();
		if (handle != null && handle.sourceRef() != null && !handle.sourceRef().isDirect()) {
			try {
				ResourceLocation playlist = new ResourceLocation(handle.sourceRef().playlistId());
				// K10-B: markers bind to the concrete entry index - resolve
				// it from the source reference; fall back to the whole-
				// playlist check only when the entry cannot be located
				int entryIndex = resolveEntryIndex(playlist, url);
				List<TimelineMarker> markers = entryIndex >= 0
						? TimelineMarkerStore.markers(playlist, url, entryIndex)
						: TimelineMarkerStore.markers(playlist, url);
				if (!markers.isEmpty())
					return false;
			} catch (Exception ignored) {
			}
		}
		return true;
	}

	// K10-B: the entry index of the currently playing URL inside its playlist
	private int resolveEntryIndex(ResourceLocation playlist, String url)
	{
		MusicTracksManager manager = MusicTracksManager.getInstance();
		for (MusicTracksManager.DynamicExternalTrack track : manager.getDynamicExternalTracksSnapshot()) {
			if (!track.configLocation().toString().equals(playlist.toString()))
				continue;
			List<MusicTracksManager.ExternalPlaylistEntry> entries = track.entries();
			for (int i = 0; i < entries.size(); i++) {
				if (entries.get(i).url().equals(url))
					return i;
			}
		}
		return -1;
	}

	private boolean clickDock(double mouseX, double mouseY)
	{
		if (mouseY < dockY())
			return false;
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		// K11-B: the owner badge returns to AUTO for MAN only - CUE is owned
		// by the server and cannot be downgraded from the GUI
		if (mouseX >= 8 && mouseX <= 48 && mouseY >= dockY() + 20 && mouseY <= dockY() + 32
				&& WorldPlaybackChannel.playbackOwner() == WorldPlaybackChannel.PlaybackOwner.MAN) {
			WorldPlaybackChannel.setPlaybackOwner(WorldPlaybackChannel.PlaybackOwner.AUTO);
			return true;
		}
		// progress seek - a manual seek takes MAN ownership (K11-B)
		int progressLeft = 150;
		int progressRight = this.width - 220;
		int progressY = dockY() + 36;
		if (mouseY >= progressY - 4 && mouseY <= progressY + 8
				&& mouseX >= progressLeft && mouseX <= progressRight) {
			if (dockCanSeek() && handler.getDurationMillis() > 0L) {
				WorldPlaybackChannel.setManIntent(WorldPlaybackChannel.ManIntent.PLAYING);
				this.dockSeekDragging = true;
				updateDockSeek(mouseX);
			}
			return true;
		}
		// buttons
		int buttonY = dockY() + 16;
		if (mouseY >= buttonY - 8 && mouseY <= buttonY + 10) {
			int x = this.width - 172;
			if (mouseX >= x && mouseX < x + 36) {
				playNeighbor(-1);
				return true;
			}
			if (mouseX >= x + 36 && mouseX < x + 76) {
				// K16-F: while a preview/cover is audible, the play button
				// stops it (the audible track lives on the preview channel);
				// otherwise it is the main-channel play/pause with MAN intent
				if (PreviewChannel.isActive()) {
					PreviewChannel.stop();
					return true;
				}
				// K11-B: play/pause from the dock always takes MAN ownership
				// (persistent intent, even when the current track was AUTO)
				if (handler.isPlaying()) {
					WorldPlaybackChannel.setManIntent(WorldPlaybackChannel.ManIntent.PAUSED);
					handler.pauseMusic();
				} else {
					WorldPlaybackChannel.setManIntent(WorldPlaybackChannel.ManIntent.PLAYING);
					handler.resumeMusic();
				}
				return true;
			}
			if (mouseX >= x + 76 && mouseX < x + 110) {
				// K16-F: stop stops whatever is audible - the preview/cover
				// channel first (which also releases the main-channel cover)
				if (PreviewChannel.isActive()) {
					PreviewChannel.stop();
					return true;
				}
				// K11-B: stop takes MAN ownership with an explicit STOPPED
				// intent - the AUTO engine stays suspended until the user
				// returns to AUTO (badge) or plays again
				WorldPlaybackChannel.setManIntent(WorldPlaybackChannel.ManIntent.STOPPED);
				handler.stopMusic();
				return true;
			}
			if (mouseX >= x + 110 && mouseX < x + 148) {
				playNeighbor(1);
				return true;
			}
		}
		return false;
	}

	private void updateDockSeek(double mouseX)
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		long duration = handler.getDurationMillis();
		if (duration <= 0L)
			return;
		int progressLeft = 150;
		int progressRight = this.width - 220;
		double progress = (mouseX - progressLeft) / Math.max(1.0D, progressRight - progressLeft);
		// K10-B: dragging only previews the position; the single seek is
		// committed in mouseReleased
		this.dockPreviewPosition = Math.round(duration * Math.max(0.0D, Math.min(1.0D, progress)));
	}

	private void commitDockSeek()
	{
		if (this.dockPreviewPosition >= 0L) {
			ExternalMusicHandler.getInstance().seekMusicAsync(this.dockPreviewPosition, null);
			this.dockPreviewPosition = -1L;
		}
	}

	// K9-4: manual prev/next within the current playlist (owner becomes MAN);
	// sound-reference entries cannot be played through the stream player and
	// are skipped to the next playable entry
	private void playNeighbor(int direction)
	{
		nonamecrackers2.mobbattlemusic.client.audio.PlaybackHandle handle = WorldPlaybackChannel.handle();
		if (handle == null || handle.sourceRef() == null || handle.sourceRef().isDirect()
				|| ExternalMusicHandler.getInstance().getCurrentlyPlayingUrl() == null) {
			// K16-F: no current track (stopped / direct URL / nothing ever
			// played) - pick a playable entry from an idle playlist instead of
			// failing; the idle library always has candidates unless empty
			if (playIdleFallback())
				return;
			message(text("message.dock_neighbor_unavailable"));
			return;
		}
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		String currentUrl = handler.getCurrentlyPlayingUrl();
		MusicTracksManager manager = MusicTracksManager.getInstance();
		for (MusicTracksManager.DynamicExternalTrack track : manager.getDynamicExternalTracksSnapshot()) {
			if (!track.configLocation().toString().equals(handle.sourceRef().playlistId()))
				continue;
			List<MusicTracksManager.ExternalPlaylistEntry> entries = track.entries();
			if (entries.isEmpty())
				return;
			int index = -1;
			for (int i = 0; i < entries.size(); i++) {
				if (entries.get(i).url().equals(currentUrl)) {
					index = i;
					break;
				}
			}
			if (index < 0)
				return;
			for (int step = 1; step <= entries.size(); step++) {
				int candidate = Math.floorMod(index + direction * step, entries.size());
				MusicTracksManager.ExternalPlaylistEntry entry = entries.get(candidate);
				String url = entry.url();
				if (!url.startsWith("sound:")) {
					// K11-B: the session handle/source follow the manual
					// selection (markers and dock source display stay correct)
					WorldPlaybackChannel.adoptManualSelection(url,
							track.configLocation().toString(), entry.id(),
							MusicTracksManager.playlistRevision(track.configLocation()));
					handler.playMusic(url, track.fadeTime());
					return;
				}
			}
			return;
		}
		message(text("message.dock_neighbor_unavailable"));
	}

	// K16-F: manual prev/next with nothing currently playing - start a
	// playable entry from the first idle playlist (the shared idle library)
	private boolean playIdleFallback()
	{
		MusicTracksManager manager = MusicTracksManager.getInstance();
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		for (MusicTracksManager.DynamicExternalTrack track : manager.getDynamicExternalTracksSnapshot()) {
			MusicTracksManager.DynamicBinding binding = manager.editableBinding(track.configLocation());
			if (binding == null || binding.kind() != MusicTracksManager.DynamicBinding.Kind.IDLE_RULE)
				continue;
			for (MusicTracksManager.ExternalPlaylistEntry entry : track.entries()) {
				if (entry.url().startsWith("sound:") || !manager.isMusicEntryEnabled(track.configLocation(), entry))
					continue;
				WorldPlaybackChannel.adoptManualSelection(entry.url(), track.configLocation().toString(),
						entry.id(), MusicTracksManager.playlistRevision(track.configLocation()));
				handler.playMusic(entry.url(), track.fadeTime());
				return true;
			}
		}
		return false;
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
		// K16-D: silent-playback warning - fired once per main-channel track
		// transition (start/track-switch/stop), not on a timer: when a new
		// song begins or playback ends while the user gain is (near) zero, an
		// actionbar hint explains why nothing is audible
		float gain = MobBattleMusicConfig.CLIENT.mbmUserGain.get().floatValue();
		String mainUrl = ExternalMusicHandler.getInstance().getCurrentlyPlayingUrl();
		if (!java.util.Objects.equals(mainUrl, this.lastMainUrl)) {
			boolean started = mainUrl != null;
			boolean stopped = this.lastMainUrl != null && mainUrl == null;
			this.lastMainUrl = mainUrl;
			if (gain <= 0.05f && (started || stopped)) {
				int percent = Math.max(0, Math.round(gain * 100.0f));
				this.minecraft.player.displayClientMessage(
						Component.literal("[Mob Battle Music] \u00a7c"
								+ (stopped ? "\u64ad\u653e\u5df2\u7ed3\u675f - " : "")
								+ "\u76ee\u524d MBM \u5904\u4e8e\u97f3\u91cf " + percent
								+ "% - \u53f3\u4e0a\u89d2 Vol \u9762\u677f\u53ef\u4ee5\u8c03\u56de\u6765"),
						true);
			}
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
		// K13-C: a cover (manual song selection) survives the GUI - the
		// preview player keeps playing after the screen closes; only plain
		// auditions are stopped
		if (!this.draftDirty) {
			if (!PreviewChannel.isCoveringMain())
				stopPreview();
			closeToParent();
			return;
		}
		String priorityDraft = this.priorityBox == null ? "" : this.priorityBox.getValue();
		String intervalDraft = this.intervalBox == null ? "" : this.intervalBox.getValue();
		this.minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(confirmed -> {
			if (confirmed) {
				this.draftDirty = false;
				if (!PreviewChannel.isCoveringMain())
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
		// K13-C: a cover survives navigation between playlist screens
		if (!PreviewChannel.isCoveringMain())
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
		// K13-C: a cover survives edit-mode switches
		if (!PreviewChannel.isCoveringMain())
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
		if (this.timelineTabButton != null)
			this.timelineTabButton.active = true;
		if (this.filtersTabButton != null)
			this.filtersTabButton.active = true;
		if (this.neteaseTabButton != null)
			this.neteaseTabButton.active = library;
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
		if (this.groupByTrackButton != null) {
			this.groupByTrackButton.visible = library;
			this.groupByTrackButton.active = library;
		}
		if (this.previousPageButton != null)
			this.previousPageButton.visible = !library;
		if (this.nextPageButton != null)
			this.nextPageButton.visible = !library;
		if (this.playlistList != null)
			this.playlistList.setVisible(library && !state().groupByTrack);
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
		// K15-E: in grouped (by-track) view the row is an ASSET, not a use -
		// use-level editing (binding/conditions/priority/delete/timeline) is
		// forbidden here so it can never silently mutate the representative
		// use. Only asset-level actions (preview / cover / use navigation)
		// remain; pick a concrete use in by-use view to edit it.
		boolean grouped = this.viewMode == ViewMode.LIBRARY && state().groupByTrack;
		boolean editable = hasTrack && selectedRowEditable() && !grouped;
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
		// K16-B: 歌单规则 lives in the by-use view only - grouped rows are
		// assets and have no playlist of their own
		this.playlistRulesButton.visible = details && !grouped && hasTrack;
		this.playlistRulesButton.active = this.playlistRulesButton.visible;
		// K16-B: the use editor opens from the grouped (music-first) view -
		// it manages every use of the selected track
		this.useEditorButton.visible = details && grouped && hasTrack;
		this.useEditorButton.active = this.useEditorButton.visible;
		this.sceneBox.visible = binding && this.viewMode == ViewMode.LIBRARY;
		this.sceneBox.active = this.sceneBox.visible;
		this.kindButton.visible = binding && this.viewMode == ViewMode.LIBRARY;
		this.kindButton.active = this.kindButton.visible;
		this.targetBox.visible = binding && this.viewMode == ViewMode.LIBRARY;
		this.targetBox.active = this.targetBox.visible && !"scene".equals(this.addKind);
		this.addBindingButton.visible = binding || this.viewMode == ViewMode.NETEASE;
		this.addBindingButton.active = this.viewMode == ViewMode.NETEASE
				? this.searchSelected >= 0 && this.searchSelected < this.searchRows.size()
				: this.multiSelectActive ? !this.selectedRowSet.isEmpty() : hasTrack;
		// K13-A: batch-mode button label reflects the current mode
		if (this.addBindingButton != null)
			this.addBindingButton.setMessage(Component.literal(this.multiSelectActive
					? "Add checked to target group" : "Add selected to target group"));
		this.multiSelectButton.visible = this.viewMode == ViewMode.LIBRARY && (binding || grouped);
		this.multiSelectButton.active = this.multiSelectButton.visible;
		// K13-C: cover-play is available wherever a LIBRARY row is selected
		if (this.coverPlayButton != null) {
			this.coverPlayButton.visible = this.viewMode == ViewMode.LIBRARY && binding;
			this.coverPlayButton.active = this.coverPlayButton.visible && hasTrack;
		}
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
		// K15-F: grouped view is asset-level - the per-use inspector pages
		// don't exist here, so the page navigation buttons are deactivated
		// (selectInspectorPage also refuses the switch)
		boolean pageNavEnabled = !grouped;
		this.inspectorDetailsButton.active = pageNavEnabled && this.inspectorPage != InspectorPage.DETAILS;
		this.inspectorBindingButton.active = pageNavEnabled && this.inspectorPage != InspectorPage.BINDING;
		this.inspectorConditionsButton.active = pageNavEnabled && this.inspectorPage != InspectorPage.CONDITIONS;
	}

	private void openTimelineEditor()
	{
		Row row = selectedRow();
		if (row == null)
			return;
		saveState();
		this.minecraft.setScreen(new TimelineMarkerScreen(this, this.editMode, row.playlist(), row.index()));
	}

	// K16-B: open the playlist-level rule editor for the selected playlist
	private void openPlaylistRules()
	{
		Row row = selectedRow();
		if (row == null || state().groupByTrack)
			return;
		saveState();
		this.minecraft.setScreen(new PlaylistRulesScreen(this, this.editMode, row.playlist(), row.binding()));
	}

	// K16-B: open the music-first use editor for the selected track; in
	// batch mode every checked track's URL is added to the chosen use
	private void openUseEditor()
	{
		Row row = selectedRow();
		if (row == null || !state().groupByTrack)
			return;
		saveState();
		List<String> urls = new java.util.ArrayList<>();
		if (this.multiSelectActive) {
			for (Row candidate : this.rows) {
				if (this.selectedRowSet.contains(candidate.playlist() + "#" + candidate.entry().url()))
					urls.add(candidate.entry().url());
			}
		} else {
			urls.add(row.entry().url());
		}
		if (urls.isEmpty())
			return;
		this.minecraft.setScreen(new UseEditorScreen(this, this.editMode, row.trackId(), row.title(), urls));
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

	// K15-A/K15-C: rows for every OTHER use of this track (music-first
	// navigation). Reads the O(1) registry reverse index - never rescans
	// playlists, never mixes in songs that merely share a binding.
	// K15-F: the (playlist, entryId, occurrenceOrdinal) resolution ALWAYS
	// runs over allRowsCached (the full, unfiltered row order) - the library
	// filter must never shift ordinals; visibility is checked afterwards.
	private java.util.List<Row> rowsUsingSameTrack(String trackId, Row self)
	{
		if (trackId == null || trackId.isBlank())
			return List.of();
		java.util.List<Row> result = new java.util.ArrayList<>();
		for (var use : nonamecrackers2.mobbattlemusic.client.resource.TrackAssetRegistry.usesFor(trackId)) {
			Row match = null;
			int ordinal = 0;
			for (Row row : this.allRowsCached) {
				if (use.playlistId().equals(row.playlist()) && use.entryId().equals(row.entry().id())) {
					if (ordinal == use.occurrenceOrdinal()) {
						match = row;
						break;
					}
					ordinal++;
				}
			}
			if (match == null || match == self)
				continue;
			// by-use view: the filtered list may hide some uses - only show
			// the ones that are currently visible in this.rows
			if (!state().groupByTrack && !this.rows.contains(match))
				continue;
			result.add(match);
		}
		return result;
	}

	// K15-A: jump the selection to the row of another use of the same track.
	// In grouped view the target row may not be visible - switch to the
	// by-use view first so the binding row exists.
	private void jumpToUse(Row target)
	{
		if (state().groupByTrack) {
			state().groupByTrack = false;
			if (this.groupByTrackButton != null)
				this.groupByTrackButton.setMessage(groupByTrackLabel());
			this.rebuildRows();
		}
		int index = this.rows.indexOf(target);
		if (index < 0)
			return;
		this.selected = index;
		this.selectedPlaylist = target.playlist();
		state().selected = index;
		state().selectedPlaylist = target.playlist().toString();
		this.loadSelectedSettings(true);
		this.refreshSelectionLists();
		this.updateButtonState();
	}

	// K16-B: public jump used by the use-editor screen - leave grouped view
	// and select the concrete use (playlist, entryIndex)
	public void selectUse(ResourceLocation playlist, int entryIndex)
	{
		if (state().groupByTrack) {
			state().groupByTrack = false;
			if (this.groupByTrackButton != null)
				this.groupByTrackButton.setMessage(groupByTrackLabel());
			this.rebuildRows();
		}
		int index = -1;
		for (int i = 0; i < this.rows.size(); i++) {
			Row candidate = this.rows.get(i);
			if (candidate.playlist().equals(playlist) && candidate.index() == entryIndex) {
				index = i;
				break;
			}
		}
		if (index < 0 && !this.rows.isEmpty())
			index = 0;
		this.selected = index;
		this.selectedPlaylist = playlist;
		state().selected = index;
		state().selectedPlaylist = playlist.toString();
		this.loadSelectedSettings(true);
		this.refreshSelectionLists();
		this.updateButtonState();
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
		if (this.coverPlayButton != null) {
			// K13-C: manual song selection is available for LIBRARY rows
			this.coverPlayButton.active = this.viewMode == ViewMode.LIBRARY && hasSelection;
		}
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
		// K13-A/K13-B: batch mode adds every checked row's URL to the target
		// group (kind/scene/target) in ONE atomic plan; normal mode adds the
		// single selected row
		if (this.multiSelectActive) {
			if (this.selectedRowSet.isEmpty()) {
				message(text("message.batch_no_selection"));
				return;
			}
			String scene = this.sceneBox.getValue().trim();
			String target = this.targetBox.getValue().trim();
			if ((scene.isBlank() && !"idle_rule".equals(this.addKind)) ||
					(!"scene".equals(this.addKind) && target.isBlank())) {
				message(text("message.scene_music_required"));
				return;
			}
			if (this.editMode == EditMode.SERVER) {
				// server mode: commands are inherently per-track, best-effort
				int sent = 0;
				for (String key : List.copyOf(this.selectedRowSet)) {
					Row row = this.rows.stream()
							.filter(candidate -> (candidate.playlist() + "#" + candidate.entry().url()).equals(key))
							.findFirst().orElse(null);
					if (row == null || row.binding() == null || row.entry() == null)
						continue;
					runServerCommand(addCommand(scene, target, row.entry().url()));
					sent++;
				}
				this.selectedRowSet.clear();
				this.rebuildRows();
				this.loadSelectedSettings(true);
				this.updateButtonState();
				return;
			}
			MusicTracksManager.DynamicBinding binding = currentTargetBinding();
			if (binding == null) {
				message(text("message.batch_invalid_target"));
				return;
			}
			// K13-B: ONE plan -> ONE validation -> ONE commit -> ONE save
			java.util.Map<MusicTracksManager.DynamicBinding, List<String>> plan =
					new java.util.LinkedHashMap<>();
			for (String key : List.copyOf(this.selectedRowSet)) {
				Row row = this.rows.stream()
						.filter(candidate -> (candidate.playlist() + "#" + candidate.entry().url()).equals(key))
						.findFirst().orElse(null);
				if (row == null || row.entry() == null)
					continue;
				plan.computeIfAbsent(binding, b -> new java.util.ArrayList<>()).add(row.entry().url());
			}
			MusicTracksManager.PlaylistControlResult result =
					MusicTracksManager.getInstance().importLocalPlan(plan);
			message(result.message());
			this.selectedRowSet.clear();
			this.rebuildRows();
			this.loadSelectedSettings(true);
			this.updateButtonState();
			return;
		}
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

	// K13-B: resolve the current kind/scene/target editor into a binding for
	// the atomic batch import plan
	private MusicTracksManager.DynamicBinding currentTargetBinding()
	{
		String scene = this.sceneBox.getValue().trim();
		String target = this.targetBox.getValue().trim();
		return switch (this.addKind) {
			case "type" -> MusicTracksManager.DynamicBinding.entityType(scene, target);
			case "uuid" -> MusicTracksManager.DynamicBinding.entityUuid(scene, target);
			case "idle_rule" -> MusicTracksManager.DynamicBinding.idleRule(target);
			default -> MusicTracksManager.DynamicBinding.scene(scene);
		};
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
		if (!enabled && !PreviewChannel.isCoveringMain())
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
	
	// K16-B: readable condition summary, e.g. "维度=the_end 且 群系=deep_dark" -
	// used to flag entries whose own conditions override the playlist rule
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
		ResourceLocation id = nonamecrackers2.mobbattlemusic.playlist.IdleConditionRegistry.normalizeId(type);
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
		// K16-B: the search text covers the conditions of EVERY use of the
		// track (not just the representative row) plus the Chinese condition
		// words - searching "\u7ef4\u5ea6" or "the_end" finds the song no matter
		// which playlist carries the condition
		for (IdleCondition condition : conditionsOfEveryUse(row)) {
			text.append(condition.type()).append(' ').append(condition.argument()).append(' ')
					.append(conditionTypeName(condition.type())).append(' ');
		}
		return text.toString().toLowerCase(Locale.ROOT);
	}

	private static List<IdleCondition> conditionsOfEveryUse(Row row)
	{
		java.util.List<IdleCondition> all = new java.util.ArrayList<>(row.entry().conditions());
		for (var use : nonamecrackers2.mobbattlemusic.client.resource.TrackAssetRegistry.usesFor(row.trackId()))
			all.addAll(use.entryConditions());
		return all;
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
		// K15-A: music-first grouping - merge same-track rows into one
		private boolean groupByTrack;
		// K15-F: per-track record of the concrete use
		// (playlist#entryId#occurrenceOrdinal) selected when leaving by-use
		// view for grouped view; grouped representative rows never overwrite
		// it, so switching back returns to the exact use instead of the first
		// occurrence. Keyed by trackId (not a single global value) so a track
		// selected inside grouped is never overridden by another track's use.
		private Map<String, String> lastConcreteUseByTrackId = new java.util.LinkedHashMap<>();
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
			MusicTracksManager.ExternalPlaylistEntry entry, String title, String artist)
	{
		// K15-A: the stable song identity (same URL -> same trackId across
		// every binding) - the music-first key
		String trackId()
		{
			return this.entry.id();
		}

		String key()
		{
			return this.playlist + "#" + this.index;
		}
	}
}