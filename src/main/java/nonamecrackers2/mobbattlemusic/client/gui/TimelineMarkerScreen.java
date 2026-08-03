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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import nonamecrackers2.mobbattlemusic.client.audio.PreviewChannel;
import nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadata;
import nonamecrackers2.mobbattlemusic.client.music.MusicMetadataCache;
import nonamecrackers2.mobbattlemusic.client.music.TimelineMarkerStore;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork;
import nonamecrackers2.mobbattlemusic.playlist.TimelineMarker;

final class TimelineMarkerScreen extends Screen
{
	private static final Map<MusicPlaylistScreen.EditMode, State> STATES =
			new EnumMap<>(MusicPlaylistScreen.EditMode.class);

	static {
		STATES.put(MusicPlaylistScreen.EditMode.LOCAL, new State());
		STATES.put(MusicPlaylistScreen.EditMode.SERVER, new State());
	}

	private final Screen parent;
	private final MusicPlaylistScreen.EditMode editMode;
	private final ResourceLocation preferredPlaylist;
	private final int preferredEntryIndex;
	private final List<Track> tracks = new ArrayList<>();
	private Button modeButton;
	private Button previewButton;
	private Button stopButton;
	private Button addMarkerButton;
	private Button deleteMarkerButton;
	private EditBox markerTimeBox;
	private EditBox markerEventBox;
	private int selectedTrack;
	private int selectedMarker;
	private int trackScroll;
	private int markerScroll;
	private int syncRefreshCooldown;
	private boolean scrubbedTime;

	TimelineMarkerScreen(Screen parent, MusicPlaylistScreen.EditMode editMode)
	{
		super(text("timeline.title"));
		this.parent = parent;
		this.editMode = editMode;
		this.preferredPlaylist = null;
		this.preferredEntryIndex = -1;
	}

	TimelineMarkerScreen(Screen parent, MusicPlaylistScreen.EditMode editMode, ResourceLocation playlist,
			int entryIndex)
	{
		super(text("timeline.title"));
		this.parent = parent;
		this.editMode = editMode;
		this.preferredPlaylist = playlist;
		this.preferredEntryIndex = entryIndex;
	}

	static void refreshIfOpen()
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof TimelineMarkerScreen screen)
			screen.refreshTracks();
	}

	@Override
	protected void init()
	{
		State state = state();
		this.selectedTrack = state.selectedTrack;
		this.selectedMarker = state.selectedMarker;
		this.trackScroll = state.trackScroll;
		this.markerScroll = state.markerScroll;
		this.rebuildTracks();
		selectPreferredTrack();
		if (!compactEditor())
			this.modeButton = this.addRenderableWidget(Button.builder(modeLabel(), button -> switchMode())
					.bounds(this.width - 112, 6, 100, 20).build());

		int x = detailX();
		boolean compact = compactEditor() || detailRight() - x < 420;
		this.previewButton = this.addRenderableWidget(Button.builder(text("button.preview"), button -> previewSelected())
				.bounds(x, contentTop() + 2, 64, 20).build());
		this.stopButton = this.addRenderableWidget(Button.builder(text("button.stop"), button -> stopPreview())
				.bounds(x + 68, contentTop() + 2, 54, 20).build());
		int bottomY = bottomControlsY();
		int timeWidth = compact ? 60 : 72;
		int addWidth = compact ? 42 : 50;
		int deleteWidth = compact ? 42 : 54;
		int doneWidth = compact ? 46 : 54;
		this.markerTimeBox = this.addRenderableWidget(new EditBox(this.font, x, bottomY + 1, timeWidth, 18,
				text("field.marker_time")));
		this.markerTimeBox.setMaxLength(8);
		this.markerTimeBox.setFilter(value -> value.matches("[0-9]{0,8}"));
		this.markerTimeBox.setHint(text("field.marker_time"));
		this.markerTimeBox.setValue(state.markerTime);
		this.markerTimeBox.setResponder(value -> {
			state().markerTime = value;
			this.scrubbedTime = true;
			updateButtonState();
		});
		int eventWidth = Math.max(48, detailRight() - x - timeWidth - addWidth - deleteWidth - doneWidth - 16);
		this.markerEventBox = this.addRenderableWidget(new EditBox(this.font, x + timeWidth + 4, bottomY + 1, eventWidth, 18,
				text("field.marker_event")));
		this.markerEventBox.setMaxLength(128);
		this.markerEventBox.setHint(text("field.marker_event"));
		this.markerEventBox.setValue(state.markerEvent);
		this.markerEventBox.setResponder(value -> {
			state().markerEvent = value;
			updateButtonState();
		});
		int actionsX = this.markerEventBox.getX() + this.markerEventBox.getWidth() + 4;
		this.addMarkerButton = this.addRenderableWidget(Button.builder(text("button.marker_add"), button -> addMarker())
				.bounds(actionsX, bottomY, addWidth, 20).build());
		this.deleteMarkerButton = this.addRenderableWidget(Button.builder(text("button.marker_delete"), button -> deleteMarker())
				.bounds(actionsX + addWidth + 4, bottomY, deleteWidth, 20).build());
		this.addRenderableWidget(Button.builder(text("button.done"), button -> closeToParent())
				.bounds(detailRight() - doneWidth, bottomY, doneWidth, 20).build());
		this.updateButtonState();
	}

	private void selectPreferredTrack()
	{
		if (this.preferredPlaylist == null)
			return;
		for (int i = 0; i < this.tracks.size(); i++) {
			Track track = this.tracks.get(i);
			if (track.playlist().equals(this.preferredPlaylist) && track.entryIndex() == this.preferredEntryIndex) {
				this.selectedTrack = i;
				break;
			}
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
	{
		this.renderBackground(graphics);
		if (compactEditor())
			graphics.fill(panelLeft(), panelTop(), panelRight(), panelBottom(), 0xEE10151A);
		graphics.drawString(this.font, this.title, compactEditor() ? panelLeft() + 8 : 12,
				compactEditor() ? panelTop() + 8 : 10, 0xFFFFFF, false);
		if (!compactEditor())
			renderTrackList(graphics, mouseX, mouseY);
		renderTimelinePanel(graphics, mouseX, mouseY);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	private void renderTrackList(GuiGraphics graphics, int mouseX, int mouseY)
	{
		int x = 12;
		int y = 56;
		int width = leftWidth();
		int bottom = this.height - 34;
		graphics.fill(x - 2, y - 2, x + width + 2, bottom + 2, 0x90000000);
		int rowHeight = 32;
		int visible = Math.max(1, (bottom - y) / rowHeight);
		this.trackScroll = clamp(this.trackScroll, 0, Math.max(0, this.tracks.size() - visible));
		for (int row = 0; row < visible; row++) {
			int index = this.trackScroll + row;
			if (index >= this.tracks.size())
				break;
			Track track = this.tracks.get(index);
			int rowY = y + row * rowHeight;
			boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + rowHeight;
			graphics.fill(x, rowY, x + width, rowY + rowHeight - 2,
					index == this.selectedTrack ? 0xAA38546E : hovered ? 0x70405058 : 0x40202020);
			graphics.drawString(this.font, trim(track.title(), Math.max(12, width / 6)), x + 6, rowY + 5,
					0xFFFFFF, false);
			graphics.drawString(this.font, trim(track.context() + "  #" + (track.entryIndex() + 1),
					Math.max(12, width / 6)), x + 6, rowY + 18, 0xB8C5D1, false);
		}
		if (this.tracks.isEmpty())
			graphics.drawString(this.font, text("timeline.empty"), x + 8, y + 8, 0xA0A0A0, false);
	}

	private void renderTimelinePanel(GuiGraphics graphics, int mouseX, int mouseY)
	{
		int x = detailX();
		int right = detailRight();
		int top = contentTop();
		int bottom = contentBottom();
		graphics.fill(x - 2, top - 2, right, bottom + 2, 0x90000000);
		Track track = selectedTrack();
		if (track == null) {
			graphics.drawString(this.font, text("timeline.select_track"), x + 8, 86, 0xA0A0A0, false);
			return;
		}
		graphics.drawString(this.font, trim(track.title(), Math.max(16, (right - x - 132) / 6)), x + 132, top + 8,
				0xFFFFFF, false);
		long position = previewPosition(track);
		long duration = previewDuration(track);
		int timelineY = top + 50;
		String time = formatDuration(position) + " / " + (duration > 0L ? formatDuration(duration) : "--:--");
		graphics.drawString(this.font, text("preview.progress", time), x, timelineY - 14, 0xD8D8D8, false);
		graphics.fill(x, timelineY, right, timelineY + 9, 0xD0202020);
		int filled = duration <= 0L ? 0 : (int)Math.round((right - x) * Math.min(1.0D,
				position / (double)duration));
		graphics.fill(x, timelineY, x + filled, timelineY + 9, 0xFF5AA7C4);
		List<TimelineMarker> markers = markers(track);
		if (duration > 0L) {
			for (int i = 0; i < markers.size(); i++) {
				TimelineMarker marker = markers.get(i);
				int markerX = x + (int)Math.round((right - x) * Math.min(1.0D,
						marker.timeMillis() / (double)duration));
				int color = i == this.selectedMarker ? 0xFFFFE08A : 0xFFFFB84D;
				graphics.fill(markerX - 1, timelineY - 4, markerX + 2, timelineY + 13, color);
				if (mouseX >= markerX - 3 && mouseX <= markerX + 3 && mouseY >= timelineY - 5 && mouseY <= timelineY + 14)
					graphics.renderTooltip(this.font, Component.literal(formatDuration(marker.timeMillis()) + "  " +
							marker.eventId()), mouseX, mouseY);
			}
		}
		graphics.fill(x + Math.max(0, filled - 1), timelineY - 1, x + filled + 1, timelineY + 10, 0xFFE8F4F8);

		int listTop = timelineY + 26;
		int listBottom = contentBottom();
		graphics.drawString(this.font, text("timeline.markers", markers.size()), x, listTop - 12, 0xA0A0A0, false);
		int rowHeight = 20;
		int visible = Math.max(1, (listBottom - listTop) / rowHeight);
		this.markerScroll = clamp(this.markerScroll, 0, Math.max(0, markers.size() - visible));
		for (int row = 0; row < visible; row++) {
			int index = this.markerScroll + row;
			if (index >= markers.size())
				break;
			TimelineMarker marker = markers.get(index);
			int rowY = listTop + row * rowHeight;
			boolean hovered = mouseX >= x && mouseX < right && mouseY >= rowY && mouseY < rowY + rowHeight;
			if (index == this.selectedMarker || hovered)
				graphics.fill(x, rowY, right, rowY + rowHeight - 2,
						index == this.selectedMarker ? 0x8038546E : 0x50405058);
			graphics.drawString(this.font, (index + 1) + ". " + formatDuration(marker.timeMillis()) + "  " +
					marker.timeMillis() + " ms  " + marker.eventId(), x + 6, rowY + 6, 0xD8D8D8, false);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && seekTimeline(mouseX, mouseY))
			return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && selectTrackRow(mouseX, mouseY))
			return true;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && selectMarkerRow(mouseX, mouseY))
			return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private boolean seekTimeline(double mouseX, double mouseY)
	{
		Track track = selectedTrack();
		long duration = previewDuration(track);
		int x = detailX();
		int right = detailRight();
		int y = contentTop() + 50;
		if (track == null || duration <= 0L || mouseX < x || mouseX > right || mouseY < y - 5 || mouseY > y + 14)
			return false;
		double progress = (mouseX - x) / Math.max(1.0D, right - x);
		long position = Math.round(duration * Math.max(0.0D, Math.min(1.0D, progress)));
		this.scrubbedTime = true;
		this.markerTimeBox.setValue(String.valueOf(position));
		ExternalMusicHandler.getInstance().seekPreviewMusic(position);
		List<TimelineMarker> markers = markers(track);
		for (int i = 0; i < markers.size(); i++) {
			if (Math.abs(markers.get(i).timeMillis() - position) <= Math.max(250L, duration / 150L)) {
				this.selectedMarker = i;
				break;
			}
		}
		this.updateButtonState();
		return true;
	}

	private boolean selectTrackRow(double mouseX, double mouseY)
	{
		if (compactEditor())
			return false;
		int x = 12;
		int y = 56;
		if (mouseX < x || mouseX >= x + leftWidth() || mouseY < y || mouseY >= this.height - 34)
			return false;
		int index = this.trackScroll + (int)((mouseY - y) / 32);
		if (index < 0 || index >= this.tracks.size())
			return false;
		if (index != this.selectedTrack)
			stopPreview();
		this.selectedTrack = index;
		this.selectedMarker = 0;
		this.markerScroll = 0;
		this.scrubbedTime = false;
		this.markerTimeBox.setValue("0");
		this.updateButtonState();
		return true;
	}

	private boolean selectMarkerRow(double mouseX, double mouseY)
	{
		Track track = selectedTrack();
		if (track == null)
			return false;
		int x = detailX();
		int y = contentTop() + 76;
		if (mouseX < x || mouseX >= detailRight() || mouseY < y || mouseY >= contentBottom())
			return false;
		List<TimelineMarker> markers = markers(track);
		int index = this.markerScroll + (int)((mouseY - y) / 20);
		if (index < 0 || index >= markers.size())
			return false;
		this.selectedMarker = index;
		this.scrubbedTime = true;
		this.markerTimeBox.setValue(String.valueOf(markers.get(index).timeMillis()));
		if (previewDuration(track) > 0L)
			ExternalMusicHandler.getInstance().seekPreviewMusic(markers.get(index).timeMillis());
		this.updateButtonState();
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta)
	{
		if (!compactEditor() && mouseX < detailX())
			this.trackScroll = Math.max(0, this.trackScroll - (int)Math.signum(delta));
		else
			this.markerScroll = Math.max(0, this.markerScroll - (int)Math.signum(delta));
		return true;
	}

	@Override
	public void tick()
	{
		super.tick();
		Track track = selectedTrack();
		if (!this.markerTimeBox.isFocused() && isPreviewing(track)) {
			this.markerTimeBox.setValue(String.valueOf(ExternalMusicHandler.getInstance().getPreviewPositionMillis()));
			this.scrubbedTime = false;
		}
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

	private void rebuildTracks()
	{
		String selectedKey = selectedTrack() == null ? "" : selectedTrack().key();
		this.tracks.clear();
		MusicTracksManager manager = MusicTracksManager.getInstance();
		for (MusicTracksManager.ExternalPlaylist playlist : manager.getExternalPlaylists()) {
			MusicTracksManager.DynamicSource source = manager.dynamicSource(playlist.configLocation());
			if (source == null || !sourceMatchesMode(source))
				continue;
			String context = manager.describeTrackContext(playlist.configLocation());
			for (int i = 0; i < playlist.entries().size(); i++) {
				MusicTracksManager.ExternalPlaylistEntry entry = playlist.entries().get(i);
				MusicMetadataCache.getInstance().prepare(entry.url());
				MusicMetadata metadata = MusicMetadataCache.getInstance().get(entry.url()).orElse(null);
				String title = metadata == null ? entry.name() : metadata.displayTitle(entry.name());
				this.tracks.add(new Track(playlist.configLocation(), context, source, i, entry, title));
			}
		}
		if (!selectedKey.isBlank()) {
			for (int i = 0; i < this.tracks.size(); i++) {
				if (selectedKey.equals(this.tracks.get(i).key())) {
					this.selectedTrack = i;
					break;
				}
			}
		}
		this.selectedTrack = clamp(this.selectedTrack, 0, Math.max(0, this.tracks.size() - 1));
		if (this.tracks.isEmpty())
			this.selectedTrack = -1;
	}

	private void refreshTracks()
	{
		rebuildTracks();
		this.selectedMarker = clamp(this.selectedMarker, 0, Math.max(0, markers(selectedTrack()).size() - 1));
		this.updateButtonState();
	}

	private void previewSelected()
	{
		Track track = selectedTrack();
		if (track == null)
			return;
		stopPreview();
		this.scrubbedTime = false;
		PreviewChannel.playUrl(track.entry().url(), 20, 0L);
	}

	private void stopPreview()
	{
		PreviewChannel.stop();
	}

	private void navigateTo(PlaylistTabs.Tab tab)
	{
		saveState();
		stopPreview();
		MusicPlaylistScreen.openTab(this.parent, this.editMode, tab);
	}

	private void addMarker()
	{
		Track track = selectedTrack();
		Long time = parseLong(this.markerTimeBox.getValue(), 0L, 86_400_000L);
		ResourceLocation event = ResourceLocation.tryParse(this.markerEventBox.getValue().trim());
		if (track == null || time == null || event == null) {
			message(text("message.invalid_marker"));
			return;
		}
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			runServerCommand("mobbattlemusic marker " + track.playlist() + " " + (track.entryIndex() + 1) +
					" add " + time + " " + event);
		else {
			message(TimelineMarkerStore.addLocal(track.playlist(), track.entryIndex(), new TimelineMarker(time, event)));
			this.selectedMarker = Math.max(0, markers(track).size() - 1);
			this.updateButtonState();
		}
	}

	private void deleteMarker()
	{
		Track track = selectedTrack();
		List<TimelineMarker> markers = markers(track);
		if (track == null || markers.isEmpty() || this.selectedMarker < 0 || this.selectedMarker >= markers.size())
			return;
		if (this.editMode == MusicPlaylistScreen.EditMode.SERVER)
			runServerCommand("mobbattlemusic marker " + track.playlist() + " " + (track.entryIndex() + 1) +
					" delete " + (this.selectedMarker + 1));
		else {
			message(TimelineMarkerStore.deleteLocal(track.playlist(), track.entryIndex(), this.selectedMarker));
			this.selectedMarker = clamp(this.selectedMarker, 0, Math.max(0, markers(track).size() - 1));
			this.updateButtonState();
		}
	}

	private void updateButtonState()
	{
		Track track = selectedTrack();
		this.previewButton.active = track != null;
		this.addMarkerButton.active = track != null && parseLong(this.markerTimeBox.getValue(), 0L, 86_400_000L) != null &&
				ResourceLocation.tryParse(this.markerEventBox.getValue().trim()) != null;
		this.deleteMarkerButton.active = track != null && !markers(track).isEmpty();
	}

	private boolean sourceMatchesMode(MusicTracksManager.DynamicSource source)
	{
		return this.editMode == MusicPlaylistScreen.EditMode.SERVER
				? source == MusicTracksManager.DynamicSource.SERVER : source == MusicTracksManager.DynamicSource.LOCAL;
	}

	private List<TimelineMarker> markers(Track track)
	{
		return track == null ? List.of() : TimelineMarkerStore.markers(track.playlist(), track.entry().url(), track.entryIndex());
	}

	private boolean isPreviewing(Track track)
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		return track != null && handler.isPreviewing() && track.entry().url().equals(handler.getPreviewUrl());
	}

	private long previewPosition(Track track)
	{
		return isPreviewing(track) ? ExternalMusicHandler.getInstance().getPreviewPositionMillis() : 0L;
	}

	private long previewDuration(Track track)
	{
		return isPreviewing(track) ? ExternalMusicHandler.getInstance().getPreviewDurationMillis() : 0L;
	}

	private Track selectedTrack()
	{
		return this.selectedTrack < 0 || this.selectedTrack >= this.tracks.size() ? null : this.tracks.get(this.selectedTrack);
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
		this.minecraft.setScreen(new TimelineMarkerScreen(this.parent, next));
	}

	private void closeToParent()
	{
		saveState();
		this.minecraft.setScreen(this.parent);
	}

	private void saveState()
	{
		State state = state();
		state.selectedTrack = this.selectedTrack;
		state.selectedMarker = this.selectedMarker;
		state.trackScroll = this.trackScroll;
		state.markerScroll = this.markerScroll;
		if (this.markerTimeBox != null)
			state.markerTime = this.markerTimeBox.getValue();
		if (this.markerEventBox != null)
			state.markerEvent = this.markerEventBox.getValue();
	}

	private State state()
	{
		return STATES.get(this.editMode);
	}

	private int leftWidth()
	{
		return this.width < 600 ? Math.max(96, Math.min(120, this.width / 4))
				: Math.max(130, Math.min(220, this.width / 4));
	}

	private int detailX()
	{
		return compactEditor() ? panelLeft() + 12 : 20 + leftWidth();
	}

	private boolean compactEditor()
	{
		return this.preferredPlaylist != null;
	}

	private int panelLeft()
	{
		return Math.max(12, (this.width - Math.min(520, this.width - 24)) / 2);
	}

	private int panelRight()
	{
		return this.width - panelLeft();
	}

	private int panelTop()
	{
		return Math.max(18, (this.height - Math.min(260, this.height - 36)) / 2);
	}

	private int panelBottom()
	{
		return this.height - panelTop();
	}

	private int detailRight()
	{
		return compactEditor() ? panelRight() - 12 : this.width - 12;
	}

	private int contentTop()
	{
		return compactEditor() ? panelTop() + 30 : 56;
	}

	private int contentBottom()
	{
		return compactEditor() ? panelBottom() - 34 : this.height - 34;
	}

	private int bottomControlsY()
	{
		return compactEditor() ? panelBottom() - 26 : this.height - 28;
	}

	private Component modeLabel()
	{
		return text("mode_button", text("mode." + this.editMode.name().toLowerCase(Locale.ROOT)));
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

	private static Long parseLong(String raw, long min, long max)
	{
		try {
			long value = Long.parseLong(raw);
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

	private static String formatDuration(long durationMillis)
	{
		long seconds = Math.max(0L, durationMillis / 1000L);
		return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
	}

	private static Component text(String path, Object... args)
	{
		return Component.translatable("gui.mobbattlemusic.playlist." + path, args);
	}

	private record Track(ResourceLocation playlist, String context, MusicTracksManager.DynamicSource source,
			int entryIndex, MusicTracksManager.ExternalPlaylistEntry entry, String title)
	{
		String key()
		{
			return this.playlist + "#" + this.entryIndex;
		}
	}

	private static final class State
	{
		private int selectedTrack = -1;
		private int selectedMarker;
		private int trackScroll;
		private int markerScroll;
		private String markerTime = "0";
		private String markerEvent = "mobbattlemusic:marker";
	}
}