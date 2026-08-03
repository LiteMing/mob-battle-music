package nonamecrackers2.mobbattlemusic.client.gui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import nonamecrackers2.mobbattlemusic.client.resource.MusicTracksManager;
import nonamecrackers2.mobbattlemusic.client.resource.PlaylistImportParser;
import nonamecrackers2.mobbattlemusic.client.resource.PlaylistImportParser.ImportLine;

/**
 * K9-1: transactional playlist import preview. Candidate entries come from
 * dropped files (M3U/M3U8/MBM JSON), the clipboard, or both. The preview
 * marks every row as NEW / DUPLICATE / INVALID before anything is written;
 * the confirm button commits atomically through
 * MusicTracksManager.importLocalUrls (all-or-nothing). Nothing touches the
 * existing playlists before confirmation.
 */
public class PlaylistImportScreen extends Screen
{
	private static final int ROW_HEIGHT = 24;
	private static final int MAX_VISIBLE_ROWS = 18;

	private final Screen parent;
	private final List<Row> rows = new ArrayList<>();
	private final List<String> filesInfo = new ArrayList<>();

	private int scrollOffset;
	private int listLeft;
	private int listTop;
	private int listWidth;
	private int listHeight;
	private Button confirmButton;
	private Button cancelButton;
	private Button clipboardButton;
	private Button clearButton;
	private EditBox sceneBox;
	private EditBox targetBox;
	private String addKind = "scene";
	private Button kindButton;
	private String statusMessage = "";

	private enum RowStatus
	{
		NEW,
		DUPLICATE,
		INVALID
	}

	private static final class Row
	{
		final String raw;
		final @Nullable String title;
		RowStatus status;
		String error;

		Row(String raw, @Nullable String title, RowStatus status, String error)
		{
			this.raw = raw;
			this.title = title;
			this.status = status;
			this.error = error;
		}
	}

	public PlaylistImportScreen(Screen parent)
	{
		this(parent, List.of());
	}

	public PlaylistImportScreen(Screen parent, List<Path> files)
	{
		super(Component.literal("Import Playlist"));
		this.parent = parent;
		for (Path file : files)
			this.addFile(file);
	}

	public static void openWithFiles(Screen parent, List<Path> files)
	{
		Minecraft.getInstance().setScreen(new PlaylistImportScreen(parent, files));
	}

	// K9-1: more files dropped while this screen is open
	public void addDroppedFiles(List<Path> files)
	{
		for (Path file : files)
			this.addFile(file);
		this.refreshStatuses();
		this.updateConfirmState();
		this.statusMessage = "Added " + files.size() + " dropped file(s)";
	}

	@Override
	protected void init()
	{
		int centerX = this.width / 2;
		int panelWidth = Math.min(560, this.width - 40);
		this.listLeft = centerX - panelWidth / 2;
		this.listTop = 44;
		this.listWidth = panelWidth;
		this.listHeight = Math.min(MAX_VISIBLE_ROWS * ROW_HEIGHT, this.height - 190);

		this.kindButton = this.addRenderableWidget(Button.builder(
				Component.literal(kindLabel()), button -> cycleKind())
				.bounds(this.listLeft + 4, this.listTop + this.listHeight + 6, 120, 20).build());
		this.sceneBox = new EditBox(this.font, this.listLeft + 130, this.listTop + this.listHeight + 6, 120, 20,
				Component.literal("scene"));
		this.sceneBox.setHint(Component.literal("scene (e.g. aggressive)"));
		this.sceneBox.setValue("aggressive");
		this.addRenderableWidget(this.sceneBox);
		this.targetBox = new EditBox(this.font, this.listLeft + 256, this.listTop + this.listHeight + 6, 160, 20,
				Component.literal("target"));
		this.targetBox.setHint(Component.literal("target (entity type / rule id)"));
		this.addRenderableWidget(this.targetBox);

		this.clipboardButton = this.addRenderableWidget(Button.builder(
				Component.literal("Clipboard URLs"), button -> importClipboard())
				.bounds(this.listLeft + 4, this.listTop + this.listHeight + 32, 140, 20).build());
		this.clearButton = this.addRenderableWidget(Button.builder(
				Component.literal("Clear"), button -> clearRows())
				.bounds(this.listLeft + 150, this.listTop + this.listHeight + 32, 90, 20).build());
		this.cancelButton = this.addRenderableWidget(Button.builder(
				Component.literal("Cancel"), button -> this.onClose())
				.bounds(this.listLeft + this.listWidth - 220, this.listTop + this.listHeight + 32, 100, 20).build());
		this.confirmButton = this.addRenderableWidget(Button.builder(
				Component.literal("Import (atomic)"), button -> commit())
				.bounds(this.listLeft + this.listWidth - 114, this.listTop + this.listHeight + 32, 110, 20).build());
		this.updateConfirmState();
	}

	private void addFile(Path file)
	{
		String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
		String lower = name.toLowerCase(Locale.ROOT);
		try {
			if (lower.endsWith(".m3u") || lower.endsWith(".m3u8"))
				this.addLines(PlaylistImportParser.parseM3U(file), name);
			else if (lower.endsWith(".json"))
				this.addJsonFile(file, name);
			else
				this.addLines(PlaylistImportParser.parseText(java.nio.file.Files.readString(file)), name);
		} catch (Exception e) {
			this.filesInfo.add(name + ": " + e.toString());
		}
	}

	private void addJsonFile(Path file, String name)
	{
		var bindings = PlaylistImportParser.parseMbmJson(file);
		int count = 0;
		for (List<ImportLine> lines : bindings.values())
			count += this.addLines(lines, name);
		if (count == 0)
			this.filesInfo.add(name + ": no importable entries");
	}

	private int addLines(List<ImportLine> lines, String source)
	{
		for (ImportLine line : lines)
			this.rows.add(new Row(line.raw(), line.title(), RowStatus.NEW, ""));
		return lines.size();
	}

	private void importClipboard()
	{
		long window = Minecraft.getInstance().getWindow().getWindow();
		String text = GLFW.glfwGetClipboardString(window);
		if (text == null || text.isBlank())
		{
			this.statusMessage = "Clipboard is empty";
			return;
		}
		int before = this.rows.size();
		this.addLines(PlaylistImportParser.parseText(text), "clipboard");
		this.statusMessage = "Added " + (this.rows.size() - before) + " clipboard entries";
		this.refreshStatuses();
		this.updateConfirmState();
	}

	private void clearRows()
	{
		this.rows.clear();
		this.filesInfo.clear();
		this.statusMessage = "";
		this.scrollOffset = 0;
		this.updateConfirmState();
	}

	private void refreshStatuses()
	{
		MusicTracksManager manager = MusicTracksManager.getInstance();
		MusicTracksManager.DynamicBinding binding = currentBinding();
		List<String> existing = binding == null ? List.of()
				: manager.getLocalUrlsSnapshot(binding);
		for (Row row : this.rows) {
			String reference = manager.normalizeReferenceForValidation(row.raw);
			if (reference == null) {
				row.status = RowStatus.INVALID;
				row.error = "invalid music reference";
			} else if (existing.contains(reference)) {
				row.status = RowStatus.DUPLICATE;
				row.error = "already in playlist";
			} else {
				row.status = RowStatus.NEW;
				row.error = "";
			}
		}
	}

	private void cycleKind()
	{
		this.addKind = switch (this.addKind) {
			case "scene" -> "type";
			case "type" -> "uuid";
			case "uuid" -> "idle_rule";
			default -> "scene";
		};
		this.kindButton.setMessage(Component.literal(kindLabel()));
		this.refreshStatuses();
		this.updateConfirmState();
	}

	private String kindLabel()
	{
		return switch (this.addKind) {
			case "type" -> "Kind: entity type";
			case "uuid" -> "Kind: entity uuid";
			case "idle_rule" -> "Kind: idle rule";
			default -> "Kind: scene";
		};
	}

	private @Nullable MusicTracksManager.DynamicBinding currentBinding()
	{
		String target = this.targetBox.getValue().trim();
		return switch (this.addKind) {
			case "type" -> MusicTracksManager.DynamicBinding.entityType(this.sceneBox.getValue().trim(), target);
			case "uuid" -> MusicTracksManager.DynamicBinding.entityUuid(this.sceneBox.getValue().trim(), target);
			case "idle_rule" -> MusicTracksManager.DynamicBinding.idleRule(target);
			default -> MusicTracksManager.DynamicBinding.scene(this.sceneBox.getValue().trim());
		};
	}

	private void updateConfirmState()
	{
		int newCount = 0;
		for (Row row : this.rows)
			if (row.status == RowStatus.NEW)
				newCount++;
		this.confirmButton.active = !this.rows.isEmpty() && newCount > 0;
	}

	private void commit()
	{
		MusicTracksManager.DynamicBinding binding = currentBinding();
		if (binding == null)
		{
			this.statusMessage = "Invalid import target";
			return;
		}
		List<String> urls = new ArrayList<>();
		for (Row row : this.rows)
			if (row.status == RowStatus.NEW)
				urls.add(row.raw);
		MusicTracksManager.PlaylistControlResult result =
				MusicTracksManager.getInstance().importLocalUrls(binding, urls);
		this.statusMessage = result.message();
		if (result.success())
		{
			this.refreshStatuses();
			this.updateConfirmState();
			if (this.parent instanceof MusicPlaylistScreen screen)
				screen.refreshAfterImport();
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
	{
		this.renderBackground(graphics);
		graphics.drawCenteredString(this.font, "Import Playlist", this.width / 2, 14, 0xFFFFFF);
		int visible = this.listHeight / ROW_HEIGHT;
		if (this.scrollOffset > Math.max(0, this.rows.size() - visible))
			this.scrollOffset = Math.max(0, this.rows.size() - visible);
		for (int i = 0; i < visible && this.scrollOffset + i < this.rows.size(); i++) {
			Row row = this.rows.get(this.scrollOffset + i);
			int y = this.listTop + i * ROW_HEIGHT;
			int color = switch (row.status) {
				case DUPLICATE -> 0xFF8B929C;
				case INVALID -> 0xFFE06C75;
				default -> 0xFF73D98A;
			};
			graphics.drawString(this.font, row.status.name().charAt(0) + " ", this.listLeft + 6, y + 4, color);
			String title = row.title == null || row.title.isEmpty() ? row.raw : row.title;
			graphics.drawString(this.font, title, this.listLeft + 24, y + 4, 0xFFFFFF);
			graphics.drawString(this.font, row.raw, this.listLeft + 24, y + 14, 0x8B929C);
		}
		int summaryY = this.listTop + this.listHeight + 58;
		int newCount = 0, dupCount = 0, invalidCount = 0;
		for (Row row : this.rows) {
			if (row.status == RowStatus.NEW) newCount++;
			else if (row.status == RowStatus.DUPLICATE) dupCount++;
			else invalidCount++;
		}
		graphics.drawString(this.font, String.format(Locale.ROOT, "%d new / %d duplicate / %d invalid",
				newCount, dupCount, invalidCount), this.listLeft + 4, summaryY, 0xFFFFFF);
		if (!this.filesInfo.isEmpty())
			graphics.drawString(this.font, this.filesInfo.get(0), this.listLeft + 4, summaryY + 10, 0x8B929C);
		if (!this.statusMessage.isEmpty())
			graphics.drawString(this.font, this.statusMessage, this.listLeft + 4, summaryY + 20, 0x73D98A);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta)
	{
		int visible = this.listHeight / ROW_HEIGHT;
		this.scrollOffset = Math.max(0, Math.min(this.scrollOffset - (int) Math.signum(delta),
				Math.max(0, this.rows.size() - visible)));
		return true;
	}

	@Override
	public boolean isPauseScreen()
	{
		return false;
	}

	@Override
	public void onClose()
	{
		Minecraft.getInstance().setScreen(this.parent);
	}
}
