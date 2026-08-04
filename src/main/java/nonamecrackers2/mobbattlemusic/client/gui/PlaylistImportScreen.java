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
import nonamecrackers2.mobbattlemusic.client.resource.PlaylistImportParser.PlainImportLine;

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
	// K13-A: netease playlist URL fetch - paste a music.163.com/playlist?id=
	// link, fetch its tracks into the preview (async, generation-guarded)
	private EditBox playlistUrlBox;
	private Button fetchButton;
	private boolean playlistFetching;
	// K13-A: apply the target editor (kind/scene/target) to all selected rows
	private Button applyTargetButton;
	private EditBox sceneBox;
	private EditBox targetBox;
	private String addKind = "scene";
	private Button kindButton;
	private String statusMessage = "";
	// K13-A: per-row target selection - rows can be split across condition
	// groups (idle / ambient / aggressive type / idle rule). Clicking a row
	// toggles its selection; the target controls then apply to ALL selected
	// rows, and the row's own binding overrides the screen default.
	private final java.util.Set<Integer> selectedRows = new java.util.LinkedHashSet<>();
	private boolean dragSelecting;

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
		// the screen's selected target. K13-A: mutable - the user can re-target
		// a row (or a batch of rows) to a different condition group.
		@Nullable MusicTracksManager.DynamicBinding binding;
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
		this.listHeight = Math.min(MAX_VISIBLE_ROWS * ROW_HEIGHT, this.height - 260);

		this.kindButton = this.addRenderableWidget(Button.builder(
				Component.literal(kindLabel()), button -> cycleKind())
				.bounds(this.listLeft + 4, this.listTop + this.listHeight + 6, 120, 20).build());
		this.sceneBox = new EditBox(this.font, this.listLeft + 130, this.listTop + this.listHeight + 6, 120, 20,
				Component.literal("scene"));
		this.sceneBox.setMaxLength(64);
		this.sceneBox.setHint(Component.literal("scene (e.g. aggressive)"));
		this.sceneBox.setValue("aggressive");
		this.addRenderableWidget(this.sceneBox);
		this.targetBox = new EditBox(this.font, this.listLeft + 256, this.listTop + this.listHeight + 6, 160, 20,
				Component.literal("target"));
		// K13-B: entity ids / idle rule ids / uuids exceed the 32-char default
		this.targetBox.setMaxLength(128);
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
		// K13-A: netease playlist fetch - one line above the action row
		this.playlistUrlBox = new EditBox(this.font, this.listLeft + 4, this.listTop + this.listHeight + 56,
				this.listWidth - 140, 20, Component.literal("playlist url"));
		// K13-B: netease playlist URLs (with uct2/params) exceed the default
		// 32-char EditBox cap and were silently truncated - raise the limit
		this.playlistUrlBox.setMaxLength(512);
		this.playlistUrlBox.setHint(Component.literal("music.163.com/playlist?id=..."));
		this.addRenderableWidget(this.playlistUrlBox);
		this.fetchButton = this.addRenderableWidget(Button.builder(
				Component.literal("Fetch playlist"), button -> fetchPlaylist())
				.bounds(this.listLeft + this.listWidth - 130, this.listTop + this.listHeight + 56, 126, 20).build());
		// K13-A: apply the current target (kind/scene/target boxes) to all
		// selected rows - split one playlist across condition groups
		this.applyTargetButton = this.addRenderableWidget(Button.builder(
				Component.literal("Apply target to selected"), button -> applyTargetToSelected())
				.bounds(this.listLeft + 4, this.listTop + this.listHeight + 80,
						Math.min(220, this.listWidth - 8), 20).build());
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
			}).whenCompleteAsync((collected, error) -> {
				// K12-C: generation check BEFORE touching the counter - a
				// stale task (screen closed/cleared) must not decrement the
				// new generation's pending count into the negative
				if (generation != this.importGeneration)
					return;
				this.pendingParseCount = Math.max(0, this.pendingParseCount - 1);
				if (error == null) {
					for (Path child : collected) {
						if (child == null) {
							this.filesInfo.add(file.toString() + ": traversal failed");
							continue;
						}
						this.addFile(child);
					}
					this.statusMessage = "Parsed folder " + file.getFileName();
				} else {
					this.filesInfo.add(file.toString() + ": traversal failed: " + error);
				}
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
					.whenCompleteAsync((lines, error) -> {
						// K12-C: generation check BEFORE decrementing - a
						// stale task must not make the counter negative; the
						// decrement also runs on supplyAsync failure so a
						// crashed parse can never wedge Confirm disabled
						if (generation != this.importGeneration)
							return;
						this.pendingParseCount = Math.max(0, this.pendingParseCount - 1);
						if (error == null) {
							this.addLines(lines, name);
							this.statusMessage = "Parsed " + name;
						} else {
							this.filesInfo.add(name + ": parse failed: " + error);
							this.statusMessage = "Parse failed for " + name;
						}
						this.refreshStatuses();
						this.updateConfirmState();
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
		if (lower.endsWith(".json")) {
			// K12-C: malformed JSON must block the whole batch instead of
			// looking like an empty file (an empty map would let a valid M3U
			// in the same drop commit while the broken JSON is silently
			// dropped). The marker row normalizes to null -> BLOCKING_INVALID.
			if (PlaylistImportParser.isMalformedMbmJson(file))
				return java.util.List.of(new PlainImportLine("malformed-json:" + name, "malformed JSON file"));
			return flattenJson(PlaylistImportParser.parseMbmJson(file));
		}
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

	// K13-A: fetch a music.163.com playlist into the preview. Runs on a
	// background thread; results are generation-guarded like file parses.
	private void fetchPlaylist()
	{
		String url = this.playlistUrlBox.getValue().trim();
		if (url.isEmpty()) {
			this.statusMessage = "Paste a music.163.com playlist URL first";
			return;
		}
		if (nonamecrackers2.mobbattlemusic.client.music.NeteasePlaylistFetcher.playlistId(url) == null) {
			this.statusMessage = "Not a netease playlist URL (need id=...)";
			return;
		}
		if (this.playlistFetching) {
			this.statusMessage = "Playlist fetch already in progress";
			return;
		}
		this.playlistFetching = true;
		// K13-B: the fetch joins the same pending counter as file parses so
		// Confirm stays disabled until the network result is in (atomic batch)
		this.pendingParseCount++;
		this.updateConfirmState();
		this.fetchButton.active = false;
		this.fetchButton.setMessage(Component.literal("Fetching..."));
		this.statusMessage = "Fetching playlist tracks...";
		final int generation = this.importGeneration;
		java.util.concurrent.CompletableFuture
				.supplyAsync(() -> nonamecrackers2.mobbattlemusic.client.music.NeteasePlaylistFetcher.fetch(url))
				.thenCompose(future -> future)
				.whenCompleteAsync((songs, error) -> {
					if (generation != this.importGeneration)
						return;
					this.pendingParseCount = Math.max(0, this.pendingParseCount - 1);
					this.playlistFetching = false;
					this.fetchButton.active = true;
					this.fetchButton.setMessage(Component.literal("Fetch playlist"));
					if (error != null) {
						this.statusMessage = "Playlist fetch failed: " + error;
						this.updateConfirmState();
						return;
					}
					if (songs == null || songs.isEmpty()) {
						this.statusMessage = "Playlist has no tracks (or private playlist)";
						this.updateConfirmState();
						return;
					}
					int before = this.rows.size();
					for (nonamecrackers2.mobbattlemusic.client.music.NeteasePlaylistFetcher.Song song : songs) {
						this.rows.add(new Row(song.url(), song.title() +
								(song.artist().isBlank() ? "" : " - " + song.artist()),
								null, SourceKind.URL, RowStatus.NEW, ""));
					}
					this.statusMessage = "Added " + (this.rows.size() - before)
							+ " tracks from netease playlist";
					this.refreshStatuses();
					this.updateConfirmState();
				}, Minecraft.getInstance());
	}

	// K13-A: apply the current target editor to all selected rows - this is
	// how one playlist is split across condition groups (idle / ambient /
	// aggressive type / idle rule).
	private void applyTargetToSelected()
	{
		MusicTracksManager.DynamicBinding target = currentBinding();
		if (target == null) {
			this.statusMessage = "Target not valid for the current kind/scene/target";
			return;
		}
		java.util.Set<Integer> selection = new java.util.LinkedHashSet<>(this.selectedRows);
		if (selection.isEmpty()) {
			this.statusMessage = "Click rows to select them, then apply a target";
			return;
		}
		for (Integer index : selection) {
			if (index < 0 || index >= this.rows.size())
				continue;
			Row row = this.rows.get(index);
			if (row.sourceKind == SourceKind.LOCAL_AUDIO)
				continue;
			row.binding = target;
		}
		this.statusMessage = "Applied target to " + selection.size() + " selected row(s)";
		this.refreshStatuses();
		this.updateConfirmState();
	}

	private void clearRows()
	{
		// K11-D: a clear invalidates every in-flight async parse; K12-C: the
		// pending counter is RESET with the generation bump (stale tasks no
		// longer decrement it thanks to the generation-first check, so the
		// new generation starts from a clean zero instead of inheriting the
		// old count)
		this.importGeneration++;
		this.pendingParseCount = 0;
		this.rows.clear();
		this.filesInfo.clear();
		this.statusMessage = "";
		this.scrollOffset = 0;
		this.selectedRows.clear();
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
			int rowIndex = this.scrollOffset + i;
			Row row = this.rows.get(rowIndex);
			int y = this.listTop + i * ROW_HEIGHT;
			// K13-A: selected rows get a highlight so the "apply target to
			// selected" action has visible feedback
			if (this.selectedRows.contains(rowIndex))
				graphics.fill(this.listLeft, y, this.listLeft + this.listWidth, y + ROW_HEIGHT - 2, 0xFF2A3B4D);
			int color = switch (row.status) {
				case DUPLICATE -> 0xFF8B929C;
				case BLOCKING_INVALID -> 0xFFE06C75;
				case INFORMATIONAL_UNSUPPORTED -> 0xFFE6C07A;
				default -> 0xFF73D98A;
			};
			String marker = this.selectedRows.contains(rowIndex) ? "\u2713 " : row.status.name().charAt(0) + " ";
			graphics.drawString(this.font, marker, this.listLeft + 6, y + 4, color);
			String title = row.title == null || row.title.isEmpty() ? row.raw : row.title;
			graphics.drawString(this.font, title, this.listLeft + 24, y + 4, 0xFFFFFF);
			// K10-D/K13-A: JSON and re-targeted rows show their binding tag;
			// rows without an explicit binding fall back to the screen target
			graphics.drawString(this.font, row.binding == null ? "[screen target]" : "[" + row.binding.storageKey() + "]",
					this.listLeft + this.listWidth - Math.min(150, this.listWidth / 3), y + 4, 0xFFE5C07B);
			graphics.drawString(this.font, row.raw, this.listLeft + 24, y + 14, 0x8B929C);
		}
		int summaryY = this.listTop + this.listHeight + 104;
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

	// K13-A/K13-B: click a row to toggle its selection (for batch
	// re-targeting); ctrl+click extends, plain click selects only that row.
	// Widgets (buttons, edit boxes) get the FIRST chance to consume the click
	// - only a click on truly empty background clears the selection, otherwise
	// clicking kind/scene/target/Apply would wipe it before the action ran.
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button)
	{
		if (button == 0 && mouseX >= this.listLeft && mouseX < this.listLeft + this.listWidth
				&& mouseY >= this.listTop && mouseY < this.listTop + this.listHeight) {
			int rowIndex = (int) ((mouseY - this.listTop) / ROW_HEIGHT) + this.scrollOffset;
			if (rowIndex >= 0 && rowIndex < this.rows.size()) {
				boolean ctrl = hasControlDown();
				if (ctrl) {
					if (!this.selectedRows.remove(rowIndex))
						this.selectedRows.add(rowIndex);
				} else {
					this.selectedRows.clear();
					this.selectedRows.add(rowIndex);
				}
				return true;
			}
		}
		// let widgets consume their clicks first
		if (super.mouseClicked(mouseX, mouseY, button))
			return true;
		// only a click on empty background clears the selection
		if (button == 0)
			this.selectedRows.clear();
		return false;
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
