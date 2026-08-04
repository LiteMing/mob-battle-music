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
	// K11-D: import generation - async parse callbacks verify it before
	// touching the rows (a closed/reopened/cleared screen must not receive
	// stale results)
	private int importGeneration;
	// K11-D/K12-B: in-flight async parse tasks (directory traversal and file
	// parsing); Confirm stays disabled while any parse is still running so a
	// dropped batch commits atomically as a whole
	private int pendingParseCount;
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
		// K12-B: an input error (malformed URL / malformed JSON entry) that
		// must abort the WHOLE transaction - the manager's importLocalPlan
		// sees it and refuses to commit anything
		BLOCKING_INVALID,
		// K12-B: an informational row (local audio files cannot be streamed)
		// that is excluded from the transaction instead of blocking it
		INFORMATIONAL_UNSUPPORTED
	}

	// K11-D: rows know their origin so local-audio errors are never
	// overwritten by the generic URL validator
	private enum SourceKind
	{
		URL,
		LOCAL_AUDIO
	}

	private static final class Row
	{
		final String raw;
		final @Nullable String title;
		// K10-D: JSON-imported rows keep their source binding; null rows use
		// the screen's selected target
		final @Nullable MusicTracksManager.DynamicBinding binding;
		final SourceKind sourceKind;
		RowStatus status;
		String error;

		Row(String raw, @Nullable String title, @Nullable MusicTracksManager.DynamicBinding binding,
				SourceKind sourceKind, RowStatus status, String error)
		{
			this.raw = raw;
			this.title = title;
			this.binding = binding;
			this.sourceKind = sourceKind;
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
		// K10-D/K11-D: directory traversal AND file parsing both run on a
		// background thread; the results are applied on the main thread
		// guarded by the import generation (a cleared or closed screen never
		// receives stale results). K12-B: every async task increments
		// pendingParseCount so Confirm stays disabled until the whole dropped
		// batch is parsed - the batch commits atomically.
		if (java.nio.file.Files.isDirectory(file)) {
			final int generation = this.importGeneration;
			this.pendingParseCount++;
			java.util.concurrent.CompletableFuture.supplyAsync(() -> {
				java.util.List<Path> collected = new java.util.ArrayList<>();
				try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(file)) {
					walk.filter(java.nio.file.Files::isRegularFile)
							.filter(PlaylistImportParser::isImportableFile)
							.forEach(collected::add);
				} catch (Exception e) {
					collected.add(null);
				}
				return collected;
			}).thenAcceptAsync(collected -> {
				this.pendingParseCount--;
				if (generation != this.importGeneration)
					return;
				for (Path child : collected) {
					if (child == null) {
						this.filesInfo.add(file.toString() + ": traversal failed");
						continue;
					}
					this.addFile(child);
				}
				this.statusMessage = "Parsed folder " + file.getFileName();
				this.refreshStatuses();
				this.updateConfirmState();
			}, Minecraft.getInstance());
			return;
		}
		String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".m3u") || lower.endsWith(".m3u8") || lower.endsWith(".json")
				|| lower.endsWith(".txt")) {
			// K12-B: .txt is parsed through the shared text parser (the
			// fallback in parseFile) so isImportableFile's accepted set and
			// the actual parse routing agree
			final int generation = this.importGeneration;
			this.pendingParseCount++;
			java.util.concurrent.CompletableFuture.supplyAsync(() -> parseFile(file, name))
					.thenAcceptAsync(lines -> {
						this.pendingParseCount--;
						if (generation != this.importGeneration)
							return;
						this.addLines(lines, name);
						this.refreshStatuses();
						this.updateConfirmState();
						this.statusMessage = "Parsed " + name;
					}, Minecraft.getInstance());
		} else if (PlaylistImportParser.isAudioFile(lower)) {
			// K10-D/K11-D/K12-B: local audio files are visible as rows but
			// cannot be streamed - marked INFORMATIONAL_UNSUPPORTED with a
			// dedicated reason (SourceKind.LOCAL_AUDIO), so they are excluded
			// from the transaction instead of aborting it
			this.rows.add(new Row(file.toString(), name, null, SourceKind.LOCAL_AUDIO,
					RowStatus.INFORMATIONAL_UNSUPPORTED,
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
		// K11-D: single source of truth lives in PlaylistImportParser
		return PlaylistImportParser.isImportableFile(file);
	}

	private static boolean isAudioFile(String lower)
	{
		return PlaylistImportParser.isAudioFile(lower);
	}

	private int addLines(List<ImportLine> lines, String source)
	{
		for (ImportLine line : lines) {
			if (line instanceof BoundImportLine bound)
				this.rows.add(new Row(bound.raw(), bound.title(), bound.binding(), SourceKind.URL,
						RowStatus.NEW, ""));
			else
				this.rows.add(new Row(line.raw(), line.title(), null, SourceKind.URL, RowStatus.NEW, ""));
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
		// K11-D: a clear invalidates every in-flight async parse
		this.importGeneration++;
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
		// K11-D: deduplication is scoped per (binding, reference) - different
		// bindings may legally contain the same URL; the batch seen-set is
		// keyed by binding too
		java.util.Map<MusicTracksManager.DynamicBinding, List<String>> snapshots = new java.util.LinkedHashMap<>();
		java.util.Map<MusicTracksManager.DynamicBinding, java.util.Set<String>> batchSeen =
				new java.util.LinkedHashMap<>();
		for (Row row : this.rows) {
			// K11-D: local-audio rows keep their dedicated error and are
			// never re-validated as URLs (they are informational, not
			// blocking, in K12-B)
			if (row.sourceKind == SourceKind.LOCAL_AUDIO) {
				row.status = RowStatus.INFORMATIONAL_UNSUPPORTED;
				row.error = "local audio files cannot be streamed; use http(s) URLs";
				continue;
			}
			MusicTracksManager.DynamicBinding target = row.binding != null ? row.binding : uiBinding;
			List<String> existing = snapshots.get(target);
			if (existing == null) {
				existing = target == null ? List.of() : manager.getLocalUrlsSnapshot(target);
				snapshots.put(target, existing);
			}
			java.util.Set<String> batch = batchSeen.computeIfAbsent(target, key -> new java.util.HashSet<>());
			String reference = manager.normalizeReferenceForValidation(row.raw);
			if (reference == null) {
				// K12-B: input errors block the whole transaction
				row.status = RowStatus.BLOCKING_INVALID;
				row.error = "invalid music reference";
			} else if (existing.contains(reference) || batch.contains(reference)) {
				row.status = RowStatus.DUPLICATE;
				row.error = "already in playlist (or duplicate in this batch)";
			} else {
				row.status = RowStatus.NEW;
				row.error = "";
				batch.add(reference);
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
		int blockingInvalid = 0;
		for (Row row : this.rows) {
			if (row.status == RowStatus.NEW)
				newCount++;
			else if (row.status == RowStatus.BLOCKING_INVALID)
				blockingInvalid++;
		}
		// K12-B: Confirm needs at least one new row, NO blocking input errors,
		// and no parse still in flight - a batch never commits half-parsed
		this.confirmButton.active = !this.rows.isEmpty() && newCount > 0
				&& blockingInvalid == 0 && this.pendingParseCount == 0;
	}

	private void commit()
	{
		MusicTracksManager.DynamicBinding uiBinding = currentBinding();
		// K11-D: one transaction across all bindings - the manager validates
		// the whole plan first and commits everything (or nothing).
		// K12-B: BLOCKING_INVALID rows are handed to the plan ON PURPOSE so
		// the manager's atomic abort fires (any invalid reference -> nothing
		// written across any binding). Only DUPLICATE and
		// INFORMATIONAL_UNSUPPORTED rows are excluded from the plan.
		java.util.Map<MusicTracksManager.DynamicBinding, List<String>> byBinding = new java.util.LinkedHashMap<>();
		int skipped = 0;
		int blockingInvalid = 0;
		for (Row row : this.rows) {
			if (row.status == RowStatus.DUPLICATE || row.status == RowStatus.INFORMATIONAL_UNSUPPORTED) {
				skipped++;
				continue;
			}
			if (row.status == RowStatus.BLOCKING_INVALID)
				blockingInvalid++;
			MusicTracksManager.DynamicBinding target = row.binding != null ? row.binding : uiBinding;
			if (target == null) {
				skipped++;
				continue;
			}
			byBinding.computeIfAbsent(target, key -> new java.util.ArrayList<>()).add(row.raw);
		}
		if (blockingInvalid > 0) {
			this.statusMessage = "Import aborted: " + blockingInvalid
					+ " invalid reference(s); nothing was changed";
			this.refreshStatuses();
			this.updateConfirmState();
			return;
		}
		if (byBinding.isEmpty()) {
			this.statusMessage = "Nothing to import" + (skipped > 0 ? " (" + skipped + " rows had no valid target)" : "");
			return;
		}
		MusicTracksManager.PlaylistControlResult result =
				MusicTracksManager.getInstance().importLocalPlan(byBinding);
		this.statusMessage = result.message() + (skipped > 0 ? " (" + skipped + " rows skipped)" : "");
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
				case BLOCKING_INVALID -> 0xFFE06C75;
				case INFORMATIONAL_UNSUPPORTED -> 0xFFE6C07A;
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
		int newCount = 0, dupCount = 0, invalidCount = 0, infoCount = 0;
		for (Row row : this.rows) {
			if (row.status == RowStatus.NEW) newCount++;
			else if (row.status == RowStatus.DUPLICATE) dupCount++;
			else if (row.status == RowStatus.BLOCKING_INVALID) invalidCount++;
			else infoCount++;
		}
		graphics.drawString(this.font, String.format(Locale.ROOT,
				"%d new / %d duplicate / %d blocking / %d informational",
				newCount, dupCount, invalidCount, infoCount), this.listLeft + 4, summaryY, 0xFFFFFF);
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
		// K11-D/K12-B: closing invalidates every in-flight async parse so a
		// stale future can never write into this (now detached) screen or
		// hold its rows/widgets after close
		this.importGeneration++;
		this.pendingParseCount = 0;
		Minecraft.getInstance().setScreen(this.parent);
	}
}
