package com.wincraft.client.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** In-game file chooser used instead of opening a desktop modal over Minecraft. */
public final class FilePickerScreen extends Screen {
    private record Entry(Path path, boolean directory) { }

    private final Screen parent;
    private final boolean executableOnly;
    private final Consumer<Path> onPick;
    private Path directory;
    private Path selected;
    private List<Entry> entries = List.of();
    private String status = "Choose a file.";
    private EditBox pathBox;
    private int left, top, panelWidth, rows, listTop, page;

    public FilePickerScreen(Screen parent, boolean executableOnly, Consumer<Path> onPick) {
        super(Component.literal(executableOnly ? "Choose an executable" : "Choose a file"));
        this.parent = parent;
        this.executableOnly = executableOnly;
        this.onPick = onPick;
        Path downloads = Path.of(System.getProperty("user.home", "."), "Downloads");
        this.directory = Files.isDirectory(downloads) ? downloads : Path.of(System.getProperty("user.home", "."));
    }

    @Override
    protected void init() {
        panelWidth = Math.min(520, width - 24);
        left = (width - panelWidth) / 2;
        top = Math.max(8, Math.min(24, height / 12));
        pathBox = addRenderableWidget(new EditBox(font, left + 8, top + 26, panelWidth - 104, 20,
            Component.literal("Folder path")));
        pathBox.setMaxLength(2048);
        pathBox.setValue(directory.toString());
        button("Go", left + panelWidth - 92, top + 26, 34, this::goToPath);
        button("Up", left + panelWidth - 54, top + 26, 46, this::goUp);

        rows = Math.clamp((height - top - 114) / 23, 3, 9);
        listTop = top + 52;
        loadEntries();
        int start = page * rows;
        for (int i = 0; i < rows && start + i < entries.size(); i++) {
            Entry entry = entries.get(start + i);
            String mark = entry.directory ? "[Folder] " : "";
            String label = (entry.path.equals(selected) ? "> " : "") + mark + trim(entry.path.getFileName().toString(), panelWidth / 8);
            button(label, left + 8, listTop + i * 23, panelWidth - 16, () -> selectEntry(entry));
        }

        int buttonsY = listTop + rows * 23 + 4;
        int pageCount = Math.max(1, (entries.size() + rows - 1) / rows);
        button("<", left + 8, buttonsY, 28, () -> { if (page > 0) { page--; rebuildWidgets(); } });
        button(">", left + 40, buttonsY, 28, () -> { if (page + 1 < pageCount) { page++; rebuildWidgets(); } });
        button("Cancel", left + panelWidth - 126, buttonsY, 56, this::cancel);
        button(executableOnly ? "Launch" : "Select", left + panelWidth - 66, buttonsY, 58, this::accept);
    }

    private void button(String label, int x, int y, int w, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> action.run())
            .bounds(x, y, w, 20).build());
    }

    private void loadEntries() {
        try (var stream = Files.list(directory)) {
            entries = stream.filter(path -> Files.isDirectory(path) || !executableOnly ||
                    path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe"))
                .map(path -> new Entry(path, Files.isDirectory(path)))
                .sorted(Comparator.comparing((Entry e) -> !e.directory)
                    .thenComparing(e -> e.path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                .limit(512)
                .toList();
            int maxPage = Math.max(0, (entries.size() - 1) / Math.max(1, rows));
            page = Math.clamp(page, 0, maxPage);
            status = entries.size() + " entries  ·  " + directory;
        } catch (IOException | SecurityException e) {
            entries = List.of();
            status = "Cannot read folder: " + e.getMessage();
        }
    }

    private void selectEntry(Entry entry) {
        if (entry.directory) {
            directory = entry.path;
            selected = null;
            page = 0;
            pathBox.setValue(directory.toString());
            rebuildWidgets();
            return;
        }
        selected = entry.path;
        status = "Selected: " + entry.path.getFileName();
        rebuildWidgets();
    }

    private void goToPath() {
        try {
            Path requested = Path.of(pathBox.getValue()).toAbsolutePath().normalize();
            if (Files.isDirectory(requested)) {
                directory = requested;
                selected = null;
                page = 0;
                rebuildWidgets();
            } else if (Files.isRegularFile(requested) && allowedFile(requested)) {
                selected = requested;
                status = "Selected: " + requested.getFileName();
            } else {
                status = executableOnly ? "Enter a folder or an existing .exe path." : "Enter a folder or existing file path.";
            }
        } catch (RuntimeException e) {
            status = "Invalid path: " + e.getMessage();
        }
    }

    private boolean allowedFile(Path path) {
        return !executableOnly || path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe");
    }

    private void goUp() {
        Path parentDir = directory.getParent();
        if (parentDir != null) {
            directory = parentDir;
            selected = null;
            page = 0;
            pathBox.setValue(directory.toString());
            rebuildWidgets();
        }
    }

    private void accept() {
        if (selected == null || !Files.isRegularFile(selected) || !allowedFile(selected)) {
            status = executableOnly ? "Select an .exe file first." : "Select a file first.";
            return;
        }
        Path result = selected;
        minecraft.gui.setScreen(parent);
        onPick.accept(result);
    }

    private void cancel() { minecraft.gui.setScreen(parent); }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == GLFW.GLFW_KEY_ENTER && pathBox != null && pathBox.isFocused()) {
            goToPath();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            cancel();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (deltaY != 0 && mouseY >= listTop && mouseY < listTop + rows * 23) {
            int maxPage = Math.max(0, (entries.size() - 1) / Math.max(1, rows));
            page = Math.clamp(page + (deltaY < 0 ? 1 : -1), 0, maxPage);
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int right = left + panelWidth;
        int bottom = listTop + rows * 23 + 32;
        graphics.fill(left - 8, top - 8, right + 8, bottom + 8, WinCraftBrand.INK);
        graphics.fill(left - 8, top - 8, right + 8, top - 6, WinCraftBrand.ACCENT);
        graphics.fill(left, top + 5, left + 18, top + 21, 0xFF101A28);
        graphics.fill(left + 2, top + 8, left + 16, top + 11, 0xFF8BC9DB);
        graphics.fill(left + 2, top + 13, left + 16, top + 16, 0xFF8BC9DB);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(font, executableOnly ? "SELECT APPLICATION" : "SELECT FILE", left + 26, top + 9, 0xFFF2FAFF, true);
        graphics.text(font, trim(status, panelWidth / 5), left + 8, bottom - 3, WinCraftBrand.MUTED, false);
    }

    private static String trim(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 3)) + "...";
    }

    @Override public boolean isPauseScreen() { return false; }
}
