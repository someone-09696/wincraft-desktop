package com.wincraft.client.gui;

import com.wincraft.client.WinCraftClient;
import com.wincraft.client.bridge.InstalledAppInfo;
import com.wincraft.client.bridge.InputBridge;
import com.wincraft.client.bridge.NativeCaptureBridge;
import com.wincraft.client.bridge.WindowHandleInfo;
import com.wincraft.client.input.WindowInputHandler;
import com.wincraft.client.render.AppWindowEntity;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;

/**
 * Searchable, tabbed Windows launcher with installed app discovery,
 * running window enumeration, spatial panel controls, and file transfer.
 */
public final class AppLauncherScreen extends Screen {
    public enum Tab { WINDOWS, APPS, PANELS }

    private Tab currentTab = Tab.WINDOWS;
    private String selectedCategory = "All";

    private List<WindowHandleInfo> allWindows = List.of();
    private List<InstalledAppInfo> allApps = List.of();

    private List<WindowHandleInfo> filteredWindows = List.of();
    private List<InstalledAppInfo> filteredApps = List.of();

    private int scrollOffset = 0;
    private int selectedIndex = -1;
    private AppWindowEntity selectedPanel = null;

    private static boolean isExcludedWindow(WindowHandleInfo w) {
        if (w == null) return true;
        if (w.width() < 120 || w.height() < 80) return true;
        String t = w.title().trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return true;
        return t.contains("amd dvr") || t.contains("amd:dvr") || t.contains("radeon") ||
               t.contains("overlay") || t.contains("geforce") || t.contains("nvidia share") ||
               t.contains("xbox game bar") || t.contains("program manager") ||
               t.contains("desktopwindowxamlsource") || t.contains("msctfime") ||
               t.contains("windows input experience");
    }

    private String searchFilter = "";
    private String status = "Choose a running window or app to place a screen in Minecraft.";
    private String rememberedUrl = "";
    private boolean dataLoaded;
    private final Map<String, Identifier> iconTextures = new HashMap<>();
    private final Set<String> missingIcons = new HashSet<>();

    private EditBox searchBox;
    private EditBox urlBox;
    private int layoutLeft, layoutWidth, layoutTop, layoutBottom;
    private int listLeft, listTop, listWidth, listBottom, visibleRows;
    private int footerTop;

    private static final int INK = WinCraftBrand.INK;
    private static final int RAIL = WinCraftBrand.RAIL;
    private static final int ACCENT = WinCraftBrand.ACCENT;
    private static final int MUTED = WinCraftBrand.MUTED;

    public AppLauncherScreen() {
        super(Component.literal("WinCraft Desktop"));
    }

    private Button button(String text, int x, int y, int w, Runnable action) {
        return button(text, x, y, w, 20, action);
    }

    private Button button(String text, int x, int y, int w, int h, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(x, y, w, h).build());
    }

    @Override
    protected void init() {
        if (!dataLoaded) loadData();

        applyFilter();

        int w = Math.min(480, width - 20);
        int left = (width - w) / 2;
        int top = Math.max(6, (height - 210) / 2);
        int railW = 32;
        int contentLeft = left + railW + 10;
        int contentW = w - railW - 10;
        layoutLeft = left;
        layoutWidth = w;
        layoutTop = top;

        // Side-rail tab buttons
        button((currentTab == Tab.WINDOWS ? "● " : "") + "Win", left, top + 20, railW, 22, () -> setTab(Tab.WINDOWS));
        button((currentTab == Tab.APPS ? "● " : "") + "App", left, top + 46, railW, 22, () -> setTab(Tab.APPS));
        button((currentTab == Tab.PANELS ? "● " : "") + "Scr", left, top + 72, railW, 22, () -> setTab(Tab.PANELS));

        // Search box
        int searchY = top + 16;
        searchBox = addRenderableWidget(new EditBox(font, contentLeft, searchY, contentW, 20, Component.literal("Search")));
        searchBox.setMaxLength(128);
        searchBox.setHint(Component.literal("Search windows, apps, or paths..."));
        searchBox.setValue(searchFilter);
        searchBox.setResponder(val -> {
            searchFilter = val;
            scrollOffset = 0;
            selectedIndex = -1;
            applyFilter();
            refreshSearchResultsPreservingFocus();
        });

        int listY = searchY + 24;

        // Categories bar for APPS tab
        if (currentTab == Tab.APPS) {
            String[] cats = {"All", "Browsers", "Development", "Media", "Utilities", "Games"};
            int catW = (contentW - 10) / cats.length;
            for (int i = 0; i < cats.length; i++) {
                final String cat = cats[i];
                button(cat.equals(selectedCategory) ? "[" + cat + "]" : cat, contentLeft + i * (catW + 2), listY, catW, 18, () -> {
                    selectedCategory = cat;
                    scrollOffset = 0;
                    selectedIndex = -1;
                    applyFilter();
                    rebuildWidgets();
                });
            }
            listY += 21;
        }

        // List rows - Always show 4 open apps and allow scrolling
        int rows = 4;
        int itemH = 26;
        listLeft = contentLeft;
        listTop = listY;
        listWidth = contentW;
        visibleRows = rows;

        int maxOffset = Math.max(0, itemCount() - visibleRows);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;

        if (currentTab == Tab.WINDOWS) {
            int count = filteredWindows.size();
            int start = scrollOffset;
            for (int r = 0; r < rows && start + r < count; r++) {
                int idx = start + r;
                WindowHandleInfo item = filteredWindows.get(idx);
                String privacy = WinCraftClient.isMinecraftWindow(item.hwnd()) ? " [read-only]" : "";
                String label = "    " + (selectedIndex == idx ? "> " : "") + trim(item.title(), w / 10) + privacy + " (" + item.width() + "x" + item.height() + ")";
                button(label, contentLeft, listY + r * itemH, contentW, 24, () -> {
                    selectedIndex = idx;
                    status = "Selected: " + item.title();
                    rebuildWidgets();
                });
            }
        } else if (currentTab == Tab.APPS) {
            int count = filteredApps.size();
            int start = scrollOffset;
            for (int r = 0; r < rows && start + r < count; r++) {
                int idx = start + r;
                InstalledAppInfo app = filteredApps.get(idx);
                String label = "    " + (selectedIndex == idx ? "> " : "") + trim(app.name(), w / 10) + " [" + app.category() + "]";
                button(label, contentLeft, listY + r * itemH, contentW, 24, () -> {
                    selectedIndex = idx;
                    status = "Selected: " + app.name() + " (" + app.exePath() + ")";
                    rebuildWidgets();
                });
            }
        } else if (currentTab == Tab.PANELS) {
            List<AppWindowEntity> panels = WinCraftClient.surfaces();
            if (panels.isEmpty()) {
                selectedPanel = null;
            } else if (selectedPanel == null || !panels.contains(selectedPanel)) {
                selectedPanel = panels.get(panels.size() - 1);
            }
            int start = scrollOffset;
            for (int r = 0; r < rows && start + r < panels.size(); r++) {
                int idx = start + r;
                AppWindowEntity p = panels.get(idx);
                String prefix = p == selectedPanel ? "[Active] " : "";
                String follow = p.transform.followCamera ? " · FOLLOWING" : "";
                String label = "    " + prefix + trim(p.info.title(), w / 12) + follow + " (" + Math.round(p.transform.width * 10) / 10.0 + "b)";
                button(label, contentLeft, listY + r * itemH, contentW - 30, 24, () -> {
                    selectedPanel = p;
                    status = "Selected panel: " + p.info.title();
                    rebuildWidgets();
                });
                button("X", contentLeft + contentW - 28, listY + r * itemH, 28, 24, () -> {
                    WinCraftClient.detach(p);
                    rebuildWidgets();
                });
            }
        }

        int actionsY = listY + rows * itemH + 3;

        // Navigation row
        int navW = 26;
        button("<", contentLeft, actionsY, navW, () -> {
            if (scrollOffset > 0) {
                scrollOffset = Math.max(0, scrollOffset - visibleRows);
                rebuildWidgets();
            }
        });
        button(">", contentLeft + navW + 2, actionsY, navW, () -> {
            int total = itemCount();
            if (scrollOffset + visibleRows < total) {
                scrollOffset = Math.min(Math.max(0, total - visibleRows), scrollOffset + visibleRows);
                rebuildWidgets();
            }
        });
        button("Refresh", contentLeft + (navW + 2) * 2, actionsY, 52, this::refreshData);

        // Tab-specific action buttons
        if (currentTab == Tab.WINDOWS) {
            button("Launch .exe", contentLeft + 112, actionsY, 84, this::launchExe);
            button("Place Screen in World", contentLeft + 200, actionsY, contentW - 200, this::attachSelectedWindow);
        } else if (currentTab == Tab.APPS) {
            button("Browse .exe", contentLeft + 112, actionsY, 84, this::launchExe);
            button("Launch & Place Screen", contentLeft + 200, actionsY, contentW - 200, this::launchSelectedApp);
        } else if (currentTab == Tab.PANELS && selectedPanel != null) {
            button("Snap", contentLeft + 112, actionsY, 48, () -> {
                WindowControlOverlay.snapToGrid(selectedPanel);
                status = "Snapped " + selectedPanel.info.title() + " to grid";
            });
            button(selectedPanel.transform.followCamera ? "Unfollow" : "Follow", contentLeft + 164, actionsY, 62, () -> {
                WindowInputHandler.toggleFollow(selectedPanel);
                rebuildWidgets();
            });
            button("3D Audio", contentLeft + 228, actionsY, 68, () -> {
                minecraft.gui.setScreen(new AudioMixerScreen(this, selectedPanel));
            });
            button("Drag File", contentLeft + 298, actionsY, 72, this::startFileDrag);
            button("Send Here", contentLeft + 372, actionsY, contentW - 372,
                () -> transferFileToPanel(selectedPanel));

            // Secondary spatial adjustment row for selected panel
            int adjY = actionsY + 23;
            int cell = contentW / 8;
            String[] labels = {"W-", "W+", "H-", "H+", "Scale-", "Scale+", "Yaw", "Pitch"};
            Runnable[] actions = {
                () -> WindowControlOverlay.scale(selectedPanel, -.2f, 0),
                () -> WindowControlOverlay.scale(selectedPanel, .2f, 0),
                () -> WindowControlOverlay.scale(selectedPanel, 0, -.2f),
                () -> WindowControlOverlay.scale(selectedPanel, 0, .2f),
                () -> WindowControlOverlay.scaleAspect(selectedPanel, 0.9f),
                () -> WindowControlOverlay.scaleAspect(selectedPanel, 1.1f),
                () -> WindowControlOverlay.rotate(selectedPanel, 0, .15f, 0),
                () -> WindowControlOverlay.rotate(selectedPanel, .15f, 0, 0)
            };
            for (int i = 0; i < 8; i++) {
                button(labels[i], contentLeft + i * cell, adjY, (i == 7 ? contentW - i * cell : cell) - 2, actions[i]);
            }
            actionsY = adjY;
        }

        // URL box
        int bottomY = actionsY + 23;
        urlBox = addRenderableWidget(new EditBox(font, contentLeft, bottomY, contentW - 80, 20, Component.literal("Website URL")));
        urlBox.setMaxLength(2048);
        urlBox.setHint(Component.literal("https://youtube.com or website URL..."));
        urlBox.setValue(rememberedUrl);
        urlBox.setResponder(val -> rememberedUrl = val);
        button("Open URL", contentLeft + contentW - 76, bottomY, 76, this::openWebsite);

        listBottom = listTop + rows * itemH;
        footerTop = bottomY + 20;
        layoutBottom = Math.min(height - 4, bottomY + 34);
    }

    private void setTab(Tab tab) {
        currentTab = tab;
        scrollOffset = 0;
        selectedIndex = -1;
        applyFilter();
        rebuildWidgets();
    }

    private void refreshSearchResultsPreservingFocus() {
        int cursor = searchBox.getCursorPosition();
        rebuildWidgets();
        searchBox.setCursorPosition(cursor);
        searchBox.setHighlightPos(cursor);
        searchBox.setFocused(true);
        setFocused(searchBox);
    }

    private void drawLauncherIcons(GuiGraphicsExtractor graphics) {
        int start = scrollOffset;
        int rows = Math.min(visibleRows, Math.max(0, itemCount() - start));
        for (int row = 0; row < rows; row++) {
            int x = listLeft + 7, y = listTop + row * 26 + 4;
            switch (currentTab) {
                case WINDOWS -> {
                    WindowHandleInfo window = filteredWindows.get(start + row);
                    drawAppIcon(graphics, x, y, "hwnd:" + Long.toUnsignedString(window.hwnd()), "", window.hwnd(), null);
                }
                case PANELS -> drawRailIcon(graphics, x, y, 0xFF65DDF7, 2);
                case APPS -> {
                    InstalledAppInfo app = filteredApps.get(start + row);
                    long hwnd = runningWindowHandle(app);
                    String path = !app.shortcutPath().isBlank() && hwnd == 0 ? app.shortcutPath() : app.exePath();
                    drawAppIcon(graphics, x, y, hwnd == 0 ? path : "hwnd:" + Long.toUnsignedString(hwnd),
                        path, hwnd, app.category());
                }
            }
        }
    }

    private void drawAppIcon(GuiGraphicsExtractor graphics, int x, int y, String cacheKey,
                             String path, long hwnd, String fallbackCategory) {
        Identifier texture = iconTexture(cacheKey, path, hwnd);
        if (texture == null) {
            if (fallbackCategory == null) drawRailIcon(graphics, x, y, 0xFFB9D5E7, 0);
            else drawCategoryIcon(graphics, x, y, fallbackCategory);
            return;
        }
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF253242);
        // Draw full 32x32 icon scaled to 16x16 display pixels (not just the top-left quadrant)
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 16, 16, 0, 0, 32, 32, 32, 32);
    }

    private Identifier iconTexture(String key, String path, long hwnd) {
        if (key == null || key.isBlank()) return null;
        Identifier cached = iconTextures.get(key);
        if (cached != null) return cached;
        if (missingIcons.contains(key)) return null;
        NativeImage image = null;
        boolean textureOwnsImage = false;
        try {
            byte[] rgba = NativeCaptureBridge.extractIcon(path == null ? "" : path, hwnd);
            if (rgba == null || rgba.length != 32 * 32 * 4) {
                missingIcons.add(key);
                return null;
            }
            boolean visible = false;
            for (int i = 3; i < rgba.length; i += 4) visible |= (rgba[i] & 0xFF) != 0;
            if (!visible) {
                missingIcons.add(key);
                return null;
            }

            image = new NativeImage(NativeImage.Format.RGBA, 32, 32, false);
            for (int py = 0, offset = 0; py < 32; py++) {
                for (int px = 0; px < 32; px++, offset += 4) {
                    int red = rgba[offset] & 0xFF;
                    int green = rgba[offset + 1] & 0xFF;
                    int blue = rgba[offset + 2] & 0xFF;
                    int alpha = rgba[offset + 3] & 0xFF;
                    image.setPixel(px, py, (alpha << 24) | (red << 16) | (green << 8) | blue);
                }
            }
            String textureName = "dynamic/app-icons/" + Integer.toUnsignedString(key.hashCode(), 16);
            Identifier id = Identifier.fromNamespaceAndPath("wincraft", textureName);
            DynamicTexture dynamic = new DynamicTexture(() -> "WinCraft app icon", image);
            minecraft.getTextureManager().register(id, dynamic);
            textureOwnsImage = true;
            iconTextures.put(key, id);
            return id;
        } catch (RuntimeException | LinkageError failure) {
            missingIcons.add(key);
            return null;
        } finally {
            if (image != null && !textureOwnsImage) image.close();
        }
    }

    private static long runningWindowHandle(InstalledAppInfo app) {
        if (app == null || app.shortcutPath() == null || !app.shortcutPath().startsWith("hwnd:")) return 0;
        try { return Long.parseUnsignedLong(app.shortcutPath().substring(5)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static void drawRailIcon(GuiGraphicsExtractor g, int x, int y, int color, int type) {
        if (type == 0) { // running window
            g.fill(x, y + 1, x + 16, y + 13, color);
            g.fill(x + 2, y + 3, x + 14, y + 11, INK);
            g.fill(x + 2, y + 3, x + 14, y + 5, color);
            g.fill(x + 7, y + 5, x + 8, y + 11, color);
            g.fill(x + 2, y + 8, x + 14, y + 9, color);
        } else if (type == 1) { // app grid
            g.fill(x + 1, y + 1, x + 7, y + 7, color);
            g.fill(x + 9, y + 1, x + 15, y + 7, color);
            g.fill(x + 1, y + 9, x + 7, y + 15, color);
            g.fill(x + 9, y + 9, x + 15, y + 15, color);
            g.fill(x + 3, y + 3, x + 5, y + 5, INK);
            g.fill(x + 11, y + 3, x + 13, y + 5, INK);
            g.fill(x + 3, y + 11, x + 5, y + 13, INK);
            g.fill(x + 11, y + 11, x + 13, y + 13, INK);
        } else { // spatial panel
            g.fill(x, y + 1, x + 16, y + 12, color);
            g.fill(x + 2, y + 3, x + 14, y + 10, INK);
            g.fill(x + 7, y + 12, x + 9, y + 14, color);
            g.fill(x + 4, y + 14, x + 12, y + 15, color);
        }
    }

    private static void drawCategoryIcon(GuiGraphicsExtractor g, int x, int y, String category) {
        String kind = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (kind.contains("browser")) {
            g.fill(x + 1, y + 1, x + 15, y + 15, 0xFF357FD2);
            g.fill(x + 4, y + 4, x + 12, y + 12, 0xFFEAF7FF);
            g.fill(x + 5, y + 5, x + 11, y + 11, 0xFF36A5EC);
            g.fill(x + 8, y + 4, x + 9, y + 12, 0xFFEAF7FF);
            g.fill(x + 4, y + 8, x + 12, y + 9, 0xFFEAF7FF);
        } else if (kind.contains("development")) {
            g.fill(x + 1, y + 2, x + 4, y + 4, 0xFF9BE7C0);
            g.fill(x + 3, y + 4, x + 6, y + 6, 0xFF9BE7C0);
            g.fill(x + 1, y + 10, x + 4, y + 12, 0xFF9BE7C0);
            g.fill(x + 3, y + 8, x + 6, y + 10, 0xFF9BE7C0);
            g.fill(x + 10, y + 4, x + 13, y + 6, 0xFF9BE7C0);
            g.fill(x + 12, y + 2, x + 15, y + 4, 0xFF9BE7C0);
            g.fill(x + 10, y + 8, x + 13, y + 10, 0xFF9BE7C0);
            g.fill(x + 12, y + 10, x + 15, y + 12, 0xFF9BE7C0);
            g.fill(x + 8, y + 2, x + 9, y + 13, 0xFFE8F2F8);
        } else if (kind.contains("media")) {
            g.fill(x + 2, y + 2, x + 14, y + 14, 0xFFB798FF);
            g.fill(x + 5, y + 4, x + 6, y + 12, 0xFF181824);
            g.fill(x + 6, y + 5, x + 8, y + 11, 0xFF181824);
            g.fill(x + 8, y + 6, x + 10, y + 10, 0xFF181824);
            g.fill(x + 10, y + 7, x + 12, y + 9, 0xFF181824);
        } else if (kind.contains("game")) {
            g.fill(x + 2, y + 4, x + 14, y + 12, 0xFFFFBA62);
            g.fill(x + 5, y + 5, x + 7, y + 11, 0xFF263344);
            g.fill(x + 3, y + 7, x + 9, y + 9, 0xFF263344);
            g.fill(x + 10, y + 6, x + 11, y + 7, 0xFF263344);
            g.fill(x + 12, y + 9, x + 13, y + 10, 0xFF263344);
        } else {
            g.fill(x + 5, y + 1, x + 11, y + 15, 0xFF79D8E8);
            g.fill(x + 1, y + 5, x + 15, y + 11, 0xFF79D8E8);
            g.fill(x + 6, y + 6, x + 10, y + 10, 0xFF263344);
        }
    }

    private void loadData() {
        try {
            allWindows = NativeCaptureBridge.enumerate().stream()
                .filter(w -> !isExcludedWindow(w))
                .toList();
        } catch (RuntimeException e) {
            allWindows = List.of();
            status = "Window enum error: " + e.getMessage();
        }
        try {
            List<InstalledAppInfo> apps = new ArrayList<>(NativeCaptureBridge.enumerateInstalledApps());
            Set<String> knownNames = new HashSet<>();
            apps.forEach(app -> knownNames.add(app.name().toLowerCase(Locale.ROOT)));
            int runningAdded = 0;
            for (WindowHandleInfo window : allWindows) {
                if (WinCraftClient.isMinecraftWindow(window.hwnd())) continue;
                String name = window.title().trim();
                if (name.isEmpty() || !knownNames.add(name.toLowerCase(Locale.ROOT))) continue;
                apps.add(new InstalledAppInfo(name, "", "Running windows", "hwnd:" + Long.toUnsignedString(window.hwnd())));
                runningAdded++;
            }
            allApps = List.copyOf(apps);
            status = "Found " + (allApps.size() - runningAdded) + " installed apps and " + runningAdded + " running windows.";
        } catch (RuntimeException | LinkageError e) {
            allApps = List.of();
            status = "App discovery failed: " + e.getMessage();
        }
        dataLoaded = true;
    }

    private void refreshData() {
        dataLoaded = false;
        scrollOffset = 0;
        rebuildWidgets();
    }

    private int itemCount() {
        return switch (currentTab) {
            case WINDOWS -> filteredWindows.size();
            case APPS -> filteredApps.size();
            case PANELS -> WinCraftClient.surfaces().size();
        };
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (deltaY != 0) {
            int maxOffset = Math.max(0, itemCount() - visibleRows);
            int step = deltaY < 0 ? 1 : -1;
            int next = Math.clamp(scrollOffset + step, 0, maxOffset);
            if (next != scrollOffset) {
                scrollOffset = next;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    private void applyFilter() {
        String q = searchFilter.trim().toLowerCase(Locale.ROOT);
        if (currentTab == Tab.WINDOWS) {
            filteredWindows = allWindows.stream().filter(w -> {
                if (q.isEmpty()) return true;
                return w.title().toLowerCase(Locale.ROOT).contains(q);
            }).toList();
        } else if (currentTab == Tab.APPS) {
            filteredApps = allApps.stream().filter(a -> {
                // A search is global across every app/category. Category buttons
                // filter the unsearched list only.
                boolean catMatch = !q.isEmpty() || "All".equalsIgnoreCase(selectedCategory) ||
                    a.category().equalsIgnoreCase(selectedCategory);
                if (!catMatch) return false;
                if (q.isEmpty()) return true;
                return a.name().toLowerCase(Locale.ROOT).contains(q) ||
                    a.exePath().toLowerCase(Locale.ROOT).contains(q) ||
                    a.shortcutPath().toLowerCase(Locale.ROOT).contains(q);
            }).toList();

            // If search returns nothing in installed apps, also search running windows by title
            if (!q.isEmpty() && filteredApps.isEmpty()) {
                filteredApps = allWindows.stream()
                    .filter(w -> !WinCraftClient.isMinecraftWindow(w.hwnd()))
                    .filter(w -> w.title().toLowerCase(Locale.ROOT).contains(q))
                    .map(w -> new InstalledAppInfo(w.title(), "", "Running windows",
                        "hwnd:" + Long.toUnsignedString(w.hwnd())))
                    .toList();
            }
        }
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 3)) + "...";
    }

    private void attachSelectedWindow() {
        if (selectedIndex < 0 || selectedIndex >= filteredWindows.size() || minecraft.player == null) return;
        WindowHandleInfo info = filteredWindows.get(selectedIndex);
        WinCraftClient.placePanel(info);
        // Close launcher so the user can operate the panel in-game immediately
        minecraft.gui.setScreen(null);
    }

    private void launchSelectedApp() {
        if (selectedIndex < 0 || selectedIndex >= filteredApps.size()) return;
        InstalledAppInfo app = filteredApps.get(selectedIndex);
        try {
            long runningHwnd = runningWindowHandle(app);
            if (runningHwnd != 0) {
                WindowHandleInfo running = allWindows.stream().filter(w -> w.hwnd() == runningHwnd).findFirst().orElse(null);
                if (running == null || !NativeCaptureBridge.isWindowOpen(runningHwnd)) {
                    status = "That window has closed. Press Refresh to update the app list.";
                    return;
                }
                WinCraftClient.placePanel(running);
                // Close launcher so the user can operate the panel in-game immediately
                minecraft.gui.setScreen(null);
                return;
            }
            List<WindowHandleInfo> before = NativeCaptureBridge.enumerate();
            String executableName = java.nio.file.Path.of(app.exePath().isBlank()
                ? app.shortcutPath() : app.exePath()).getFileName().toString();
            if (hasChromiumBackgroundFlags(executableName) && !app.exePath().isBlank()) {
                if (!NativeCaptureBridge.launchApplication(app.exePath(), chromiumBackgroundArguments())) {
                    throw new IOException("Windows could not open this app");
                }
            } else {
                String launchPath = app.shortcutPath().isBlank() ? app.exePath() : app.shortcutPath();
                if (!NativeCaptureBridge.launchApplication(launchPath)) {
                    throw new IOException("Windows could not open this app shortcut");
                }
            }
            status = "Launched " + app.name() + ". Looking for its window to place...";
            detectAndOfferWindow(before, app.name());
        } catch (IOException | RuntimeException e) {
            status = "Launch failed: " + e.getMessage();
        }
    }

    private void launchExe() {
        minecraft.gui.setScreen(new FilePickerScreen(this, true, this::launchExePath));
    }

    private void launchExePath(java.nio.file.Path selectedExe) {
        try {
            String exe = selectedExe.toString();
            List<WindowHandleInfo> before = NativeCaptureBridge.enumerate();
            boolean launched = hasChromiumBackgroundFlags(java.nio.file.Path.of(exe).getFileName().toString())
                ? NativeCaptureBridge.launchApplication(exe, chromiumBackgroundArguments())
                : NativeCaptureBridge.launchApplication(exe);
            if (!launched) throw new IOException("Windows could not open this executable");
            status = "Launched executable. Looking for its window to place...";
            detectAndOfferWindow(before, java.nio.file.Path.of(exe).getFileName().toString());
        } catch (IOException | RuntimeException e) {
            status = "Launch failed: " + e.getMessage();
        }
    }

    private static String chromiumBackgroundArguments() {
        // Keep WinCraft-launched Chromium windows rendering while Minecraft
        // occludes their desktop windows; use no-activate ShellExecute to leave
        // mouse-look and game input with Minecraft.
        return "--disable-background-timer-throttling " +
            "--disable-backgrounding-occluded-windows " +
            "--disable-renderer-backgrounding";
    }

    private static boolean hasChromiumBackgroundFlags(String executableName) {
        return switch (executableName.toLowerCase(Locale.ROOT)) {
            case "msedge.exe", "chrome.exe", "chromium.exe", "brave.exe", "opera.exe", "vivaldi.exe" -> true;
            default -> false;
        };
    }

    private void launchQuick(String exeOrCmd, String arg) {
        try {
            List<WindowHandleInfo> before = NativeCaptureBridge.enumerate();
            boolean ok = false;
            if (arg != null && !arg.isBlank()) {
                ok = NativeCaptureBridge.launchApplication(exeOrCmd, arg);
            } else {
                ok = NativeCaptureBridge.launchApplication(exeOrCmd);
            }
            if (!ok) {
                // Fallback via ProcessBuilder
                if (arg != null && !arg.isBlank()) {
                    new ProcessBuilder("cmd", "/c", "start", "", exeOrCmd, arg).start();
                } else {
                    new ProcessBuilder("cmd", "/c", "start", "", exeOrCmd).start();
                }
                ok = true;
            }
            status = "Launched " + exeOrCmd + ". Looking for window to place...";
            detectAndOfferWindow(before, exeOrCmd);
        } catch (IOException | RuntimeException e) {
            status = "Launch error: " + e.getMessage();
        }
    }

    private void openWebsite() {
        try {
            String text = urlBox.getValue().trim();
            if (text.isEmpty()) { status = "Enter a website URL first."; return; }
            if (!text.contains("://")) text = "https://" + text;
            URI uri = URI.create(text);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) ||
                uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("Only http/https website URLs are allowed");
            }
            List<WindowHandleInfo> before = NativeCaptureBridge.enumerate();
            boolean launched = NativeCaptureBridge.launchApplication(uri.toString());
            if (!launched) {
                // Fallback using Windows start command
                new ProcessBuilder("cmd", "/c", "start", "", uri.toString()).start();
            }
            status = "Opening browser and looking for its window to place...";
            detectAndOfferWindow(before, "Browser");
        } catch (IOException | RuntimeException e) {
            status = "Website failed: " + e.getMessage();
        }
    }

    private void detectAndOfferWindow(List<WindowHandleInfo> beforeList, String hint) {
        long[] existing = beforeList.stream().mapToLong(WindowHandleInfo::hwnd).toArray();
        Set<Long> existingHandles = new HashSet<>();
        for (long hwnd : existing) existingHandles.add(hwnd);
        Thread.ofPlatform().name("WinCraft Window Detector").daemon(true).start(() -> {
            long newHwnd = NativeCaptureBridge.detectNewWindow(existing, 7000);
            List<WindowHandleInfo> after = NativeCaptureBridge.enumerate();
            // A desktop notification or helper window may be the first new HWND
            // Windows enumerates. Prefer a newly opened title matching the app,
            // then the native detector result, then an existing matching browser
            // window (browsers commonly reuse one for a new URL).
            WindowHandleInfo target = after.stream()
                .filter(info -> !existingHandles.contains(info.hwnd()))
                .filter(info -> !WinCraftClient.isMinecraftWindow(info.hwnd()))
                .filter(info -> matchesWindowHint(info.title(), hint))
                .findFirst().orElse(null);
            if (target == null && newHwnd != 0) {
                target = after.stream().filter(info -> info.hwnd() == newHwnd)
                    .filter(info -> !WinCraftClient.isMinecraftWindow(info.hwnd()))
                    .findFirst().orElse(null);
            }
            if (target == null) {
                target = after.stream()
                    .filter(info -> !WinCraftClient.isMinecraftWindow(info.hwnd()))
                    .filter(info -> matchesWindowHint(info.title(), hint))
                    .findFirst().orElse(null);
            }
            if (target != null) {
                WindowHandleInfo found = target;
                minecraft.execute(() -> {
                    WinCraftClient.placePanel(found);
                    // Close launcher so the user can operate the panel in-game immediately
                    if (minecraft.gui.screen() == this) {
                        minecraft.gui.setScreen(null);
                    }
                });
                return;
            }
            minecraft.execute(() -> {
                status = "No new window found for " + hint + ". Select it in Windows tab and press Place screen.";
                rebuildWidgets();
            });
        });
    }

    private static boolean matchesWindowHint(String title, String hint) {
        String normalizedTitle = title.toLowerCase(Locale.ROOT);
        if ("browser".equalsIgnoreCase(hint)) {
            return List.of("edge", "chrome", "chromium", "firefox", "brave", "opera", "vivaldi")
                .stream().anyMatch(normalizedTitle::contains);
        }
        String normalizedHint = hint.toLowerCase(Locale.ROOT);
        if (normalizedHint.endsWith(".exe")) normalizedHint = normalizedHint.substring(0, normalizedHint.length() - 4);
        return !normalizedHint.isBlank() && normalizedTitle.contains(normalizedHint);
    }

    private void transferFileToPanel(AppWindowEntity panel) {
        if (panel == null) return;
        minecraft.gui.setScreen(new FilePickerScreen(this, false, file -> transferFileToPanel(panel, file)));
    }

    private void transferFileToPanel(AppWindowEntity panel, java.nio.file.Path selectedFile) {
        try {
            String file = selectedFile.toString();
            int px = panel.capture() != null ? panel.capture().width() / 2 : 100;
            int py = panel.capture() != null ? panel.capture().height() / 2 : 100;
            boolean ok = InputBridge.dropFiles(panel.info.hwnd(), px, py, new String[]{ file });
            if (ok) {
                status = "File drop sent for: " + java.nio.file.Path.of(file).getFileName();
            } else {
                status = "App did not accept drop. Use app's File -> Open.";
            }
        } catch (RuntimeException e) {
            status = "Transfer failed: " + e.getMessage();
        }
    }

    private void startFileDrag() {
        minecraft.gui.setScreen(new FilePickerScreen(this, false, this::startFileDrag));
    }

    private void startFileDrag(java.nio.file.Path selectedFile) {
        try {
            String file = selectedFile.toString();
            WindowInputHandler.beginFileDrag(file);
            minecraft.gui.setScreen(null);
        } catch (RuntimeException e) {
            status = "File drag failed: " + e.getMessage();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int shellLeft = layoutLeft - 8;
        int shellRight = layoutLeft + layoutWidth + 8;
        graphics.fill(shellLeft, layoutTop - 8, shellRight, layoutBottom + 6, INK);
        graphics.fill(shellLeft, layoutTop - 8, shellRight, layoutTop - 6, ACCENT);

        // Left rail strip
        int railRight = layoutLeft + 32;
        graphics.fill(layoutLeft - 8, layoutTop - 8, railRight + 2, layoutBottom + 6, RAIL);
        graphics.fill(railRight + 2, layoutTop - 8, railRight + 3, layoutBottom + 6, ACCENT);

        WinCraftBrand.drawMark(graphics, layoutLeft - 4, layoutTop + 2);

        // Selected row gets a slim accent edge outside the vanilla button surface.
        if (selectedIndex >= scrollOffset &&
            selectedIndex < scrollOffset + visibleRows && listWidth > 0) {
            int selectedY = listTop + (selectedIndex - scrollOffset) * 26;
            graphics.fill(listLeft - 3, selectedY + 3, listLeft - 1, selectedY + 21, ACCENT);
        }

        graphics.fill(shellLeft, footerTop, shellRight, layoutBottom + 6, 0xE10D1119);
        graphics.fill(shellLeft, footerTop, shellRight, footerTop + 1, 0xFF303B4A);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        drawLauncherIcons(graphics);
        if (currentTab == Tab.APPS && filteredApps.isEmpty()) {
            String message = allApps.isEmpty() ? "No installed apps or running windows were found. Press Refresh to search again." :
                "No matches. Search checks installed app names, paths, and live window titles.";
            graphics.text(font, trim(message, Math.max(24, layoutWidth / 6)), listLeft, listTop + 8, MUTED, false);
        }
        graphics.text(font, "WINCRAFT DESKTOP", layoutLeft + 26, layoutTop + 2, 0xFFF2FAFF, true);
        graphics.fill(layoutLeft + 6, footerTop + 6, layoutLeft + 11, footerTop + 11, ACCENT);
        String shownStatus = trim(status, Math.max(28, layoutWidth / 7));
        graphics.text(font, shownStatus, layoutLeft + 16, Math.min(height - 10, footerTop + 5), 0xFFD7E5F2, false);

        int count = itemCount();
        if (count > visibleRows && listBottom > listTop) {
            int trackX = layoutLeft + layoutWidth + 2;
            int trackH = listBottom - listTop;
            int thumbH = Math.max(12, trackH * visibleRows / count);
            int maxOffset = Math.max(1, count - visibleRows);
            int thumbY = listTop + (trackH - thumbH) * scrollOffset / maxOffset;
            graphics.fill(trackX, listTop, trackX + 3, listBottom, 0xAA151820);
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, ACCENT);
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void removed() {
        for (Identifier id : iconTextures.values()) minecraft.getTextureManager().release(id);
        iconTextures.clear();
        missingIcons.clear();
        super.removed();
    }
}
