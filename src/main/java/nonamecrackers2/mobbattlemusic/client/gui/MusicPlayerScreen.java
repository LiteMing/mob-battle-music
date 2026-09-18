package nonamecrackers2.mobbattlemusic.client.gui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import nonamecrackers2.mobbattlemusic.client.audio.WorldPlaybackChannel;
import nonamecrackers2.mobbattlemusic.client.music.ExternalMusicHandler;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;

/**
 * Compact transport screen opened from the vanilla ESC menu. It follows the
 * Concerto layout idea: current track at the top, a dense queue in the middle,
 * and transport controls in one stable bottom row.
 */
public final class MusicPlayerScreen extends Screen
{
	private final Screen parent;
	private final List<TrackChoice> tracks = new ArrayList<>();
	private int selected;
	private int scroll;
	private Button playButton;
	private String lastUrl;

	public MusicPlayerScreen(Screen parent)
	{
		super(Component.translatable("gui.mobbattlemusic.player.title"));
		this.parent = parent;
	}

	public static void open()
	{
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(new MusicPlayerScreen(mc.screen));
	}

	@Override
	protected void init()
	{
		refreshTracks();
		int center = this.width / 2;
		this.addRenderableWidget(Button.builder(Component.translatable("gui.mobbattlemusic.player.edit"),
				button -> MusicPlaylistScreen.openLocalEditor())
				.bounds(center - 190, 18, 112, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.mobbattlemusic.player.filters"),
				button -> this.minecraft.setScreen(new AudioFilterScreen(this, MusicPlaylistScreen.EditMode.LOCAL)))
				.bounds(center - 72, 18, 112, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.mobbattlemusic.player.refresh"),
				button -> refreshTracks())
				.bounds(center + 46, 18, 112, 20).build());

		int controlY = this.height - 34;
		this.addRenderableWidget(Button.builder(Component.literal("<<"), button -> playRelative(-1))
				.bounds(center - 116, controlY, 34, 20).build());
		this.playButton = this.addRenderableWidget(Button.builder(playLabel(), button -> playOrPause())
				.bounds(center - 76, controlY, 52, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("[]"), button -> stop())
				.bounds(center - 16, controlY, 34, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal(">>"), button -> playRelative(1))
				.bounds(center + 24, controlY, 34, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.mobbattlemusic.playlist.button.done"),
				button -> onClose()).bounds(center + 64, controlY, 52, 20).build());
		updatePlayButton();
	}

	private void refreshTracks()
	{
		String current = ExternalMusicHandler.getInstance().getCurrentlyPlayingUrl();
		this.tracks.clear();
		for (MusicTracksManager.DynamicExternalTrack playlist :
				MusicTracksManager.getInstance().getDynamicExternalTracksSnapshot()) {
			for (MusicTracksManager.ExternalPlaylistEntry entry : playlist.entries()) {
				if (entry.url() == null || entry.url().isBlank() || entry.url().startsWith("sound:"))
					continue;
				this.tracks.add(new TrackChoice(playlist, entry));
			}
		}
		if (this.tracks.isEmpty()) {
			this.selected = -1;
			this.scroll = 0;
			updatePlayButton();
			return;
		}
		if (current != null) {
			for (int i = 0; i < this.tracks.size(); i++) {
				if (current.equals(this.tracks.get(i).entry().url())) {
					this.selected = i;
					break;
				}
			}
		}
		this.selected = clamp(this.selected, 0, this.tracks.size() - 1);
		this.scroll = clamp(this.scroll, 0, Math.max(0, this.tracks.size() - visibleRows()));
		updatePlayButton();
	}

	private void playOrPause()
	{
		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		if (handler.isPlaying()) {
			WorldPlaybackChannel.setManIntent(WorldPlaybackChannel.ManIntent.PAUSED);
			handler.pauseMusic();
		} else if (handler.getCurrentlyPlayingUrl() != null && !handler.isStopRequested()) {
			WorldPlaybackChannel.setManIntent(WorldPlaybackChannel.ManIntent.PLAYING);
			handler.resumeMusic();
		} else {
			playSelected();
		}
		updatePlayButton();
	}

	private void playSelected()
	{
		if (this.selected < 0 || this.selected >= this.tracks.size())
			return;
		TrackChoice choice = this.tracks.get(this.selected);
		MusicTracksManager.DynamicExternalTrack playlist = choice.playlist();
		MusicTracksManager.ExternalPlaylistEntry entry = choice.entry();
		WorldPlaybackChannel.adoptManualSelection(entry.url(), playlist.configLocation().toString(), entry.id(),
				MusicTracksManager.playlistRevision(playlist.configLocation()));
		ExternalMusicHandler.getInstance().playMusic(entry.url(), Math.max(1, playlist.fadeTime() * 2));
		updatePlayButton();
	}

	private void playRelative(int delta)
	{
		if (this.tracks.isEmpty())
			return;
		this.selected = Math.floorMod(this.selected + delta, this.tracks.size());
		ensureSelectedVisible();
		playSelected();
	}

	private void stop()
	{
		WorldPlaybackChannel.setManIntent(WorldPlaybackChannel.ManIntent.STOPPED);
		ExternalMusicHandler.getInstance().stopMusic();
		updatePlayButton();
	}

	private void updatePlayButton()
	{
		if (this.playButton != null)
			this.playButton.setMessage(playLabel());
	}

	private Component playLabel()
	{
		return Component.literal(ExternalMusicHandler.getInstance().isPlaying() ? "||" : ">");
	}

	@Override
	public void tick()
	{
		super.tick();
		String current = ExternalMusicHandler.getInstance().getCurrentlyPlayingUrl();
		if (!java.util.Objects.equals(this.lastUrl, current)) {
			this.lastUrl = current;
			refreshTracks();
		}
		updatePlayButton();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
	{
		this.renderBackground(graphics);
		int left = Math.max(18, this.width / 2 - 210);
		int right = Math.min(this.width - 18, this.width / 2 + 210);
		int top = 52;
		int bottom = this.height - 46;
		graphics.fill(left, top, right, bottom, 0xE8151A20);
		graphics.fill(left, top, right, top + 1, 0xFF6E8797);
		graphics.drawString(this.font, this.title, left + 10, 6, 0xFFF0F1F2, false);

		ExternalMusicHandler handler = ExternalMusicHandler.getInstance();
		String url = handler.getCurrentlyPlayingUrl();
		String title = url == null ? Component.translatable("gui.mobbattlemusic.player.empty").getString()
				: describe(url);
		graphics.drawString(this.font, title, left + 12, top + 12, 0xFFF0F1F2, false);
		long duration = handler.getDurationMillis();
		long position = handler.getPositionMillis();
		int barLeft = left + 12;
		int barRight = right - 12;
		int barY = top + 31;
		graphics.fill(barLeft, barY, barRight, barY + 5, 0xFF35414A);
		if (duration > 0L && position >= 0L)
			graphics.fill(barLeft, barY, barLeft + (int)((barRight - barLeft) * Math.min(1.0D,
					position / (double)duration)), barY + 5, 0xFF73D98A);
		graphics.drawString(this.font, formatMillis(position) + " / " + formatMillis(duration),
				barLeft, barY + 9, 0xFF9AA9B3, false);

		int rowTop = top + 58;
		int rowHeight = 22;
		int visible = visibleRows();
		for (int row = 0; row < visible; row++) {
			int index = this.scroll + row;
			if (index >= this.tracks.size())
				break;
			TrackChoice choice = this.tracks.get(index);
			int y = rowTop + row * rowHeight;
			boolean hovered = mouseX >= left + 10 && mouseX < right - 10 && mouseY >= y && mouseY < y + rowHeight;
			boolean playing = url != null && url.equals(choice.entry().url());
			if (index == this.selected || hovered)
				graphics.fill(left + 10, y, right - 10, y + rowHeight - 2,
						index == this.selected ? 0xFF2B4750 : 0xFF222C32);
			graphics.drawString(this.font, playing ? ">" : " ", left + 16, y + 6,
					playing ? 0xFF73D98A : 0xFF79858D, false);
			graphics.drawString(this.font, trim(choice.entry().name(), right - left - 74), left + 31, y + 3,
					0xFFE0E4E7, false);
			graphics.drawString(this.font, trim(choice.playlist().configLocation().toString(), right - left - 74),
					left + 31, y + 12, 0xFF88949C, false);
		}
		if (this.tracks.isEmpty())
			graphics.drawString(this.font, Component.translatable("gui.mobbattlemusic.player.empty").getString(),
					left + 12, rowTop + 8, 0xFF9AA9B3, false);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		int left = Math.max(18, this.width / 2 - 210);
		int right = Math.min(this.width - 18, this.width / 2 + 210);
		int rowTop = 52 + 58;
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && mouseX >= left + 10 && mouseX < right - 10) {
			int row = (int)((mouseY - rowTop) / 22);
			int index = this.scroll + row;
			if (row >= 0 && row < visibleRows() && index >= 0 && index < this.tracks.size()
					&& mouseY >= rowTop && mouseY < rowTop + visibleRows() * 22) {
				this.selected = index;
				ensureSelectedVisible();
				playSelected();
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta)
	{
		if (mouseY >= 100 && mouseY < this.height - 40) {
			this.scroll = clamp(this.scroll - (int)Math.signum(delta), 0,
					Math.max(0, this.tracks.size() - visibleRows()));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, delta);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers)
	{
		if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER) {
			playOrPause();
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_LEFT) {
			playRelative(-1);
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_RIGHT) {
			playRelative(1);
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void onClose()
	{
		this.minecraft.setScreen(this.parent);
	}

	private int visibleRows()
	{
		return Math.max(1, (this.height - 52 - 58 - 46) / 22);
	}

	private void ensureSelectedVisible()
	{
		if (this.selected < this.scroll)
			this.scroll = this.selected;
		else if (this.selected >= this.scroll + visibleRows())
			this.scroll = this.selected - visibleRows() + 1;
	}

	private String describe(String url)
	{
		String title = MusicTracksManager.getInstance().describeMusicSource(url);
		return title == null || title.isBlank() ? url : title;
	}

	private static String formatMillis(long millis)
	{
		if (millis < 0L)
			return "--:--";
		long seconds = millis / 1000L;
		return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
	}

	private static String trim(String value, int width)
	{
		if (value == null)
			return "";
		int maxChars = Math.max(8, width / 6);
		return value.length() <= maxChars ? value : value.substring(0, Math.max(1, maxChars - 3)) + "...";
	}

	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}

	private record TrackChoice(MusicTracksManager.DynamicExternalTrack playlist,
			MusicTracksManager.ExternalPlaylistEntry entry)
	{
	}
}
