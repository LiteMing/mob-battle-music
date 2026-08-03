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
		// K10-D: JSON-imported rows keep their source binding; null rows use
		// the screen's selected target
		final @Nullable MusicTracksManager.DynamicBinding binding;
		RowStatus status;
		String error;

		Row(String raw, @Nullable String title, @Nullable MusicTracksManager.DynamicBinding binding,
				RowStatus status, String error)
		{
			this.raw = raw;
			this.title = title;
			this.binding = binding;
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
		// K10-D: the first open must already show validated rows - the
		// constructor's addFile only collected raw entries
		this.refreshStatuses();
		this.updateConfirmState();
	}

	private void addFile(Path file)
	{
		// K10-D: directories are imported recursively; file parsing runs on a
		// background thread so a 1000-entry import never blocks the main
		// thread (the result is applied back on the main thread)
		if (java.nio.file.Files.isDirectory(file)) {
			java.util.List<Path> collected = new java.util.ArrayList<>();
			try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(file)) {
				walk.filter(java.nio.file.Files::isRegularFile)
						.filter(PlaylistImportScreen::isImportableFile)
						.forEach(collected::add);
			} catch (Exception e) {
				this.filesInfo.add(file.toString() + ": " + e.toString());
			}
			for (Path child : collected)
				this.addFile(child);
			return;
		}
		String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".m3u") || lower.endsWith(".m3u8") || lower.endsWith(".json")) {
			java.util.concurrent.CompletableFuture.supplyAsync(() -> parseFile(file, name))
					.thenAcceptAsync(lines -> {
						this.addLines(lines, name);
						this.refreshStatuses();
						this.updateConfirmState();
						this.statusMessage = "Parsed " + name;
					}, Minecraft.getInstance());
		} else if (isAudioFile(lower)) {
			// K10-D: local audio files are visible as rows but cannot be
			// streamed - they are marked INVALID with a clear reason
			this.rows.add(new Row(file.toString(), name, null, RowStatus.INVALID,
					"local audio files cannot be streamed; use http(s) URLs"));
			this.refreshStatuses();
			this.updateConfirmState();
		} else {
			this.filesInfo.add(name + ": unsupported file type");
		}
	}

	private java.util.List<ImportLine> parseFile(Path file, String name)
	{
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".m3u") || lower.endsWith(".m3u8"))
			return PlaylistImportParser.parseM3U(file);
		if (lower.endsWith(".json"))
			return flattenJson(PlaylistImportParser.parseMbmJson(file));
		try {
			return PlaylistImportParser.parseText(java.nio.file.Files.readString(file));
		} catch (Exception e) {
			return java.util.List.of();
		}
	}

	// K10-D: JSON rows keep their source binding and commit back into it
	// instead of being flattened into the screen's single target
	private static java.util.List<ImportLine> flattenJson(java.util.Map<String, List<ImportLine>> bindings)
	{
		java.util.List<ImportLine> lines = new java.util.ArrayList<>();
		for (var entry : bindings.entrySet()) {
			MusicTracksManager.DynamicBinding binding = MusicTracksManager.DynamicBinding.parse(entry.getKey());
			if (binding == null)
				continue;
			for (ImportLine line : entry.getValue())
				lines.add(new BoundImportLine(line.raw(), line.title(), binding));
		}
		return lines;
	}

	private record BoundImportLine(String raw, String title,
			MusicTracksManager.DynamicBinding binding) implements ImportLine
	{
		@Override
		public String raw() { return this.raw; }

		@Override
		public String title() { return this.title; }
	}

	private static boolean isImportableFile(Path file)
	{
		String lower = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
		return lower.endsWith(".m3u") || lower.endsWith(".m3u8") || lower.endsWith(".json")
				|| isAudioFile(lower);
	}

	private static boolean isAudioFile(String lower)
	{
		return lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".wav")
				|| lower.endsWith(".flac") || lower.endsWith(".m4a");
	}

	private int addLines(List<ImportLine> lines, String source)
	{
		for (ImportLine line : lines) {
			if (line instanceof BoundImportLine bound)
				this.rows.add(new Row(bound.raw(), bound.title(), bound.binding(), RowStatus.NEW, ""));
			else
				this.rows.add(new Row(line.raw(), line.title(), null, RowStatus.NEW, ""));
		}
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
		MusicTracksManager.DynamicBinding uiBinding = currentBinding();
		// K10-D: deduplication checks run against the row's own target
		// binding (JSON rows keep theirs; unbound rows use the UI target) AND
		// against the rows already committed in this batch
		java.util.Map<MusicTracksManager.DynamicBinding, List<String>> snapshots = new java.util.LinkedHashMap<>();
		java.util.Set<String> batchSeen = new java.util.HashSet<>();
		for (Row row : this.rows) {
			MusicTracksManager.DynamicBinding target = row.binding != null ? row.binding : uiBinding;
			List<String> existing = snapshots.get(target);
			if (existing == null) {
				existing = target == null ? List.of() : manager.getLocalUrlsSnapshot(target);
				snapshots.put(target, existing);
			}
			String reference = manager.normalizeReferenceForValidation(row.raw);
			if (reference == null) {
				row.status = RowStatus.INVALID;
				row.error = "invalid music reference";
			} else if (existing.contains(reference) || batchSeen.contains(reference)) {
				row.status = RowStatus.DUPLICATE;
				row.error = "already in playlist (or duplicate in this batch)";
			} else {
				row.status = RowStatus.NEW;
				row.error = "";
				batchSeen.add(reference);
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
		MusicTracksManager.DynamicBinding uiBinding = currentBinding();
		// K10-D: rows are committed grouped by their own binding - JSON rows
		// go back into their source binding, unbound rows into the UI target
		java.util.Map<MusicTracksManager.DynamicBinding, List<String>> byBinding = new java.util.LinkedHashMap<>();
		int skipped = 0;
		for (Row row : this.rows) {
			if (row.status != RowStatus.NEW)
				continue;
			MusicTracksManager.DynamicBinding target = row.binding != null ? row.binding : uiBinding;
			if (target == null) {
				skipped++;
				continue;
			}
			byBinding.computeIfAbsent(target, key -> new java.util.ArrayList<>()).add(row.raw);
		}
		MusicTracksManager manager = MusicTracksManager.getInstance();
		int importedTotal = 0;
		String lastMessage = "";
		boolean anyFailure = false;
		for (var entry : byBinding.entrySet()) {
			MusicTracksManager.PlaylistControlResult result =
					manager.importLocalUrls(entry.getKey(), entry.getValue());
			lastMessage = result.message();
			// the result message embeds the committed count; import is
			// all-or-nothing per binding, so a failure means that binding
			// was left untouched
			if (result.success())
				importedTotal += entry.getValue().size();
			else
				anyFailure = true;
		}
		if (byBinding.isEmpty())
			this.statusMessage = "Nothing to import" + (skipped > 0 ? " (" + skipped + " rows had no valid target)" : "");
		else if (anyFailure)
			this.statusMessage = "Import failed for at least one binding: " + lastMessage;
		else
			this.statusMessage = "Imported " + importedTotal + " entries" + (skipped > 0 ? " (" + skipped + " skipped)" : "");
		this.refreshStatuses();
		this.updateConfirmState();
		if (this.parent instanceof MusicPlaylistScreen screen)
			screen.refreshAfterImport();
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
			// K10-D: JSON rows show their source binding tag
			if (row.binding != null)
				graphics.drawString(this.font, "[" + row.binding.storageKey() + "]", this.listLeft + 24, y + 4,
						0xFFE5C07B);
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
