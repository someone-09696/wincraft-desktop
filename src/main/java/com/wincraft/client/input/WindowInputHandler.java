package com.wincraft.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.wincraft.client.WinCraftClient;
import com.wincraft.client.bridge.InputBridge;
import com.wincraft.client.render.AppWindowEntity;
import com.wincraft.client.render.MatrixTransform3D;
import com.wincraft.client.render.TransformStorage;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ray-based in-world pointer/keyboard events go to the hovered panel.
 * F8 toggles spatial transform mode; ordinary app interaction works on hover.
 * Double right-click pins/unpins a panel at its current camera-relative pose.
 */
public final class WindowInputHandler {
    private static final Logger LOG = LoggerFactory.getLogger("wincraft/input");
    private static boolean transformMode = false; // F8 toggles spatial transforms only
    private static long heldHwnd;
    private static AppWindowEntity heldPanel;
    private static AppWindowEntity selectedPanel;
    private static AppWindowEntity lockedPanel;
    private static AppWindowEntity keyboardFocusPanel;
    private static LocalCoordinateMapper.Hit lockedHit;
    private static PendingRightClick pendingRightClick;
    private static int heldButton = -1, heldX, heldY;
    private static final Map<Integer, HeldKey> HELD_KEYS = new HashMap<>();
    private static boolean textCursor;
    private static int nativeCursorShape = -1;
    private static double verticalScrollRemainder, horizontalScrollRemainder;
    private static boolean launcherKeyAwaitingRelease;
    private static boolean rightClickAwaitingRelease;
    private static boolean fileDropAwaitingRelease;
    private static boolean fileDragCancelAwaitingRelease;
    private static String fileDragPath;
    private static int readOnlyClickAwaitingRelease = -1;
    /** Throttle wakeApp to at most once per 500 ms to avoid per-frame spam. */
    private static long lastWakeAppMs = 0;
    private static long lastWakeHwnd = 0;

    public enum Handle { MOVE, WIDTH, HEIGHT, CORNER, ROTATE, TILT }
    private static class Drag {
        final AppWindowEntity panel;
        final MatrixTransform3D initial;
        final RaycastRay pressRay;
        final LocalCoordinateMapper.Hit start;
        final Handle handle;
        final int xSign, ySign;
        double distance;
        Drag(AppWindowEntity panel, MatrixTransform3D initial, RaycastRay pressRay,
             LocalCoordinateMapper.Hit start, Handle handle, int xSign, int ySign, double distance) {
            this.panel = panel; this.initial = initial; this.pressRay = pressRay;
            this.start = start; this.handle = handle; this.xSign = xSign; this.ySign = ySign;
            this.distance = distance;
        }
    }
    private static Drag drag;
    private record PendingRightClick(AppWindowEntity panel, int x, int y, long pressedAt) { }
    private record HeldKey(long hwnd, int virtualKey, int scan) { }
    public record Target(AppWindowEntity panel, LocalCoordinateMapper.Hit hit) { }

    private WindowInputHandler() { }

    private static void showOverlay(String message) {
        var mc = Minecraft.getInstance();
        if (mc.gui != null && mc.gui.hud != null) {
            mc.gui.hud.setOverlayMessage(Component.literal(message), false);
        }
    }

    public static boolean transformMode() { return transformMode; }
    public static AppWindowEntity selectedPanel() { return selectedPanel; }
    public static void selectPanel(AppWindowEntity p) { selectedPanel = p; }

    public static void toggle() {
        transformMode = !transformMode;
        release();
    }

    public static void release() {
        flushPendingRightClick(true);
        if (heldHwnd != 0 && heldButton >= 0) {
            InputBridge.mouse(heldHwnd, heldButton == 0 ? InputBridge.LEFT_UP : InputBridge.RIGHT_UP, heldX, heldY, 0);
        }
        heldHwnd = 0; heldButton = -1; heldPanel = null;
        lockedPanel = null; lockedHit = null;
        keyboardFocusPanel = null;
        for (var key : HELD_KEYS.entrySet()) {
            InputBridge.key(key.getValue().hwnd, InputBridge.KEY_UP,
                key.getValue().virtualKey, key.getValue().scan, false);
        }
        HELD_KEYS.clear();
        rightClickAwaitingRelease = false;
        fileDropAwaitingRelease = false;
        fileDragCancelAwaitingRelease = false;
        fileDragPath = null;
        readOnlyClickAwaitingRelease = -1;
        textCursor = false;
        nativeCursorShape = -1;
        if (drag != null) {
            TransformStorage.save(drag.panel.info.title(), drag.panel.transform);
            drag = null;
        }
    }

    /** Drop any in-game input state that was attached to a desktop window that closed. */
    public static void sourceClosed(AppWindowEntity panel) {
        if (heldPanel == panel) { heldHwnd = 0; heldButton = -1; heldPanel = null; }
        if (lockedPanel == panel) { lockedPanel = null; lockedHit = null; }
        if (keyboardFocusPanel == panel) keyboardFocusPanel = null;
        if (selectedPanel == panel) selectedPanel = null;
        if (pendingRightClick != null && pendingRightClick.panel == panel) pendingRightClick = null;
        if (drag != null && drag.panel == panel) drag = null;
        if (HELD_KEYS.values().stream().anyMatch(key -> key.hwnd == panel.info.hwnd())) releaseKeys();
        textCursor = false;
        nativeCursorShape = -1;
    }

    private static RaycastRay currentRay(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        return new RaycastRay(new Vector3d(eye.x, eye.y, eye.z), new Vector3d(look.x, look.y, look.z));
    }

    public static Target target() {
        Target nearest = nearestTarget();
        return nearest != null && nearest.panel.readOnly() ? null : nearest;
    }

    private static Target nearestTarget() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.screen() != null) return null;
        RaycastRay ray = currentRay(mc);
        // Match input occlusion to the renderer: a panel behind a solid block is
        // neither visible nor interactive.
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getLookAngle().scale(8));
        var blockHit = mc.level.clip(new ClipContext(eye, end,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        double blockDistance = blockHit.getType() == HitResult.Type.MISS
            ? Double.POSITIVE_INFINITY : eye.distanceTo(blockHit.getLocation());
        Target nearest = null;
        for (AppWindowEntity panel : WinCraftClient.surfaces()) {
            if (panel.sourceClosed() || panel.capture() == null) continue;
            var hit = LocalCoordinateMapper.intersect(ray, panel.transform, 8);
            if (hit.isPresent() && hit.get().distance() < blockDistance &&
                (nearest == null || hit.get().distance() < nearest.hit.distance())) {
                nearest = new Target(panel, hit.get());
            }
        }
        return nearest;
    }

    private static boolean overReadOnlyPanel() {
        Target nearest = nearestTarget();
        return nearest != null && nearest.panel.readOnly();
    }

    private static Target activeTarget() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.screen() != null) return null;
        return target();
    }

    public static boolean cursorActive() {
        Target nearest = nearestTarget();
        return (nearest != null && nearest.panel.readOnly()) || target() != null;
    }
    public static boolean panelLocked() { return lockedPanel != null; }
    public static boolean toggleFollow(AppWindowEntity panel) {
        Minecraft mc = Minecraft.getInstance();
        if (panel == null || mc.player == null) return false;
        if (panel.transform.followCamera) {
            panel.transform.followCamera = false;
            lockedPanel = null;
            lockedHit = null;
        } else {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 look = mc.player.getLookAngle();
            panel.transform.pinToCamera(new Vector3d(eye.x, eye.y, eye.z),
                new Vector3d(look.x, look.y, look.z), mc.player.getYRot(), mc.player.getXRot());
            lockedPanel = null;
            lockedHit = null;
        }
        TransformStorage.save(panel.info.title(), panel.transform);
        return panel.transform.followCamera;
    }
    public static boolean textCursor() { return textCursor; }
    public static int cursorShape() { return nativeCursorShape; }
    public static boolean fileDragActive() { return fileDragPath != null; }
    public static String fileDragName() {
        if (fileDragPath == null) return "";
        var name = java.nio.file.Path.of(fileDragPath).getFileName();
        return name == null ? fileDragPath : name.toString();
    }

    public static void beginFileDrag(String path) {
        if (path == null || path.isBlank()) return;
        release();
        fileDragPath = java.nio.file.Path.of(path).toAbsolutePath().normalize().toString();
    }

    public static Handle hoverHandle() {
        Target t = target();
        if (t == null) return null;

        var win = Minecraft.getInstance().getWindow();
        boolean altHeld = win != null && (
            GLFW.glfwGetKey(win.handle(), GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS ||
            GLFW.glfwGetKey(win.handle(), GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS
        );

        if (!transformMode && !altHeld) return null;
        if (altHeld && !transformMode) return Handle.MOVE;

        // F8 transform mode: plain drag, no modifier needed.
        double u = t.hit.u(), v = t.hit.v();
        // Top center turns the panel; bottom center tilts it forward/backward.
        if (u >= .40 && u <= .60 && v >= .85) return Handle.ROTATE;
        if (u >= .40 && u <= .60 && v <= .15) return Handle.TILT;
        int sx = u < .08 ? -1 : (u > .92 ? 1 : 0);
        int sy = v < .08 ? -1 : (v > .92 ? 1 : 0);
        if (sx != 0 && sy != 0) return Handle.CORNER;
        if (sx != 0) return Handle.WIDTH;
        if (sy != 0) return Handle.HEIGHT;
        // The rest of the panel is the MOVE zone!
        return Handle.MOVE;
    }

    public static boolean button(int button, boolean pressed, int modifiers) {
        if (readOnlyClickAwaitingRelease == button) {
            if (!pressed) readOnlyClickAwaitingRelease = -1;
            return true;
        }
        if (fileDropAwaitingRelease && button == 0) {
            if (!pressed) fileDropAwaitingRelease = false;
            return true;
        }
        if (rightClickAwaitingRelease && button == 1) {
            if (!pressed) rightClickAwaitingRelease = false;
            return true;
        }
        if (fileDragPath != null) {
            if (button == 1) {
                if (pressed) {
                    fileDragPath = null;
                    rightClickAwaitingRelease = true;
                }
                return true;
            }
            if (button == 0) {
                if (pressed) {
                    fileDropAwaitingRelease = true;
                    Target destination = target();
                    if (destination != null && destination.panel.capture() != null) {
                        int width = destination.panel.capture().width();
                        int height = destination.panel.capture().height();
                        if (width > 0 && height > 0 && InputBridge.dropFiles(
                            destination.panel.info.hwnd(), destination.hit.pixelX(width),
                            destination.hit.pixelY(height), new String[]{fileDragPath})) {
                            fileDragPath = null;
                        }
                    }
                }
                return true;
            }
            return true;
        }
        if ((button == 0 || button == 1) && overReadOnlyPanel()) {
            if (pressed) readOnlyClickAwaitingRelease = button;
            return true;
        }
        if (button != 0 && button != 1) return false;
        if (drag != null && button == 0) {
            if (!pressed) {
                TransformStorage.save(drag.panel.info.title(), drag.panel.transform);
                drag = null;
            }
            return true;
        }

        Target t = transformMode ? target() : activeTarget();
        if (transformMode && button == 1 && pressed && t != null) {
            boolean following = toggleFollow(t.panel);
            showOverlay(following ? "§aScreen is now Floating (moves with you)" : "§eScreen is now Fixed in World");
            return true;
        }
        if (!transformMode && button == 1 && pressed && t != null) {
            long now = System.nanoTime();
            if (pendingRightClick != null && pendingRightClick.panel == t.panel &&
                now - pendingRightClick.pressedAt <= 500_000_000L) {
                pendingRightClick = null;
                boolean following = toggleFollow(t.panel);
                showOverlay(following ? "§aScreen is now Floating (moves with you)" : "§eScreen is now Fixed in World");
                keyboardFocusPanel = null;
                rightClickAwaitingRelease = true;
                releaseKeys();
                textCursor = false;
                nativeCursorShape = -1;
                return true;
            }
            flushPendingRightClick(true);
            int width = t.panel.capture().width(), height = t.panel.capture().height();
            pendingRightClick = new PendingRightClick(t.panel,
                t.hit.pixelX(width), t.hit.pixelY(height), now);
            rightClickAwaitingRelease = true;
            return true;
        }
        // Left click drag
        if (pressed && button == 0 && t != null) {
            selectedPanel = t.panel;
            Handle handle = hoverHandle();
            if (handle != null) {
                double u = t.hit.u(), v = t.hit.v();
                int sx = u < .5 ? -1 : 1;
                int sy = v < .5 ? -1 : 1;
                MatrixTransform3D transform = t.panel.transform;
                var snapshot = new MatrixTransform3D(transform.position, transform.pitch,
                    transform.yaw, transform.roll, transform.width, transform.height);
                RaycastRay ray = currentRay(Minecraft.getInstance());
                double dist = t.panel.transform.position.distance(new Vector3d(ray.origin()));
                if (dist < 0.5 || dist > 30.0) dist = Math.max(1.0, Math.min(10.0, t.hit.distance()));
                drag = new Drag(t.panel, snapshot, ray, t.hit, handle, sx, sy, dist);
                return true;
            }
        }

        if (!pressed && heldButton == button && heldHwnd != 0) {
            long hwnd = heldHwnd;
            if (t == null || t.panel.info.hwnd() != hwnd) {
                InputBridge.mouse(hwnd, button == 0 ? InputBridge.LEFT_UP : InputBridge.RIGHT_UP, heldX, heldY, 0);
            } else {
                send(t, button == 0 ? InputBridge.LEFT_UP : InputBridge.RIGHT_UP, 0);
            }
            heldHwnd = 0; heldButton = -1; heldPanel = null;
            return true;
        }

        if (t == null) return false;
        selectedPanel = t.panel;

        // F8 is spatial-edit-only: clicks on a panel are reserved for its
        // transform handles and never reach the desktop app.
        if (transformMode) return true;

        if (pressed) {
            heldHwnd = t.panel.info.hwnd();
            heldPanel = t.panel;
            heldButton = button;
            if (button == 0) {
                keyboardFocusPanel = t.panel;
                textCursor = true;
            }
            send(t, button == 0 ? InputBridge.LEFT_DOWN : InputBridge.RIGHT_DOWN,
                button == 0 ? InputBridge.MK_LBUTTON : InputBridge.MK_RBUTTON);
        }
        return true;
    }

    private static void send(Target t, int msg, int flags) {
        int w = t.panel.capture().width(), h = t.panel.capture().height();
        int px = t.hit.pixelX(w), py = t.hit.pixelY(h);
        if (heldHwnd == t.panel.info.hwnd()) { heldX = px; heldY = py; }
        InputBridge.mouse(t.panel.info.hwnd(), msg, px, py, flags);
    }

    public static boolean scroll(double horizontal, double vertical) {
        if (drag != null && drag.handle == Handle.MOVE) {
            drag.distance = Math.clamp(drag.distance + vertical * 0.35, 0.6, 25.0);
            updateDrag();
            return true;
        }
        if (transformMode) {
            Target editTarget = target();
            if (editTarget == null || vertical == 0 || !Double.isFinite(vertical)) return false;
            var win = Minecraft.getInstance().getWindow();
            boolean shiftHeld = win != null && (
                GLFW.glfwGetKey(win.handle(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(win.handle(), GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS
            );
            if (shiftHeld) {
                // Shift+Scroll tilts the screen forward/backward
                MatrixTransform3D transform = editTarget.panel.transform;
                transform.pitch = (float)Math.clamp(
                    transform.pitch + vertical * Math.toRadians(5.0),
                    -Math.toRadians(80.0), Math.toRadians(80.0));
                TransformStorage.save(editTarget.panel.info.title(), transform);
            } else {
                // Plain Scroll pushes screen farther or pulls closer
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    Vec3 eye = mc.player.getEyePosition();
                    Vector3d eyeVec = new Vector3d(eye.x, eye.y, eye.z);
                    Vector3d dir = new Vector3d(editTarget.panel.transform.position).sub(eyeVec);
                    double d = dir.length();
                    if (d > 0.1) {
                        dir.normalize();
                        double newD = Math.clamp(d + vertical * 0.35, 0.8, 25.0);
                        editTarget.panel.transform.position.set(eyeVec).fma(newD, dir);
                        if (editTarget.panel.transform.followCamera) {
                            Vec3 look = mc.player.getLookAngle();
                            editTarget.panel.transform.pinToCamera(eyeVec, new Vector3d(look.x, look.y, look.z),
                                mc.player.getYRot(), mc.player.getXRot());
                        }
                        TransformStorage.save(editTarget.panel.info.title(), editTarget.panel.transform);
                    }
                }
            }
            return true;
        }
        if (overReadOnlyPanel()) return true;
        Target t = activeTarget();
        if (t == null) return false;
        int verticalDelta = accumulateWheel(vertical, false);
        int horizontalDelta = accumulateWheel(horizontal, true);
        if (verticalDelta == 0 && vertical != 0) verticalDelta = vertical > 0 ? 120 : -120;
        if (horizontalDelta == 0 && horizontal != 0) horizontalDelta = horizontal > 0 ? 120 : -120;
        if (verticalDelta != 0) send(t, InputBridge.WHEEL, packWheel(verticalDelta));
        if (horizontalDelta != 0) send(t, InputBridge.HWHEEL, packWheel(horizontalDelta));
        return true;
    }

    private static int accumulateWheel(double amount, boolean horizontal) {
        if (!Double.isFinite(amount)) return 0;
        double remainder = horizontal ? horizontalScrollRemainder : verticalScrollRemainder;
        double accumulated = Math.clamp(amount * 120.0 + remainder, -32768.0, 32767.0);
        int notches = (int)(accumulated / 120.0);
        int delta = notches * 120;
        double leftover = accumulated - delta;
        if (horizontal) horizontalScrollRemainder = leftover;
        else verticalScrollRemainder = leftover;
        return delta;
    }

    private static int packWheel(int delta) { return (delta & 0xFFFF) << 16; }

    public static void frame() {
        flushPendingRightClick(false);
        if (drag != null) { updateDrag(); return; }
        Target t = activeTarget();
        if (heldPanel != null && !heldPanel.sourceClosed() && (t == null || t.panel != heldPanel) &&
            WinCraftClient.surfaces().contains(heldPanel) && Minecraft.getInstance().player != null) {
            var hit = LocalCoordinateMapper.intersectPlane(currentRay(Minecraft.getInstance()), heldPanel.transform, 32);
            if (hit.isPresent()) {
                var h = hit.get();
                int w = heldPanel.capture().width(), height = heldPanel.capture().height();
                if (w > 0 && height > 0) {
                    heldX = new LocalCoordinateMapper.Hit(h.distance(), Math.clamp(h.u(), 0, 1), Math.clamp(h.v(), 0, 1)).pixelX(w);
                    heldY = new LocalCoordinateMapper.Hit(h.distance(), Math.clamp(h.u(), 0, 1), Math.clamp(h.v(), 0, 1)).pixelY(height);
                    InputBridge.mouse(heldHwnd, InputBridge.MOVE, heldX, heldY,
                        heldButton == 0 ? InputBridge.MK_LBUTTON : InputBridge.MK_RBUTTON);
                }
            }
            releaseKeys();
            return;
        }

        if (t == null) {
            if (heldButton < 0) releaseKeys();
            keyboardFocusPanel = null;
            textCursor = false;
            nativeCursorShape = -1;
            return;
        }

        selectedPanel = t.panel;
        if (keyboardFocusPanel != null && keyboardFocusPanel != t.panel) keyboardFocusPanel = null;
        if (HELD_KEYS.values().stream().anyMatch(key -> key.hwnd != t.panel.info.hwnd())) releaseKeys();

        // Send active wake and revive signal to the window/tab so it refreshes and stays awake!
        // Throttled: only send once per 500 ms per hwnd to avoid per-frame Windows message spam.
        long hwnd = t.panel.info.hwnd();
        long now = System.currentTimeMillis();
        if (hwnd != lastWakeHwnd || now - lastWakeAppMs >= 500) {
            InputBridge.wakeApp(hwnd);
            lastWakeAppMs = now;
            lastWakeHwnd = hwnd;
        }

        if (transformMode) {
            releaseKeys();
            textCursor = false;
            nativeCursorShape = -1;
            return;
        }

        int w = t.panel.capture().width(), h = t.panel.capture().height();
        if (w <= 0 || h <= 0) return; // WGC warm-up: placeholder visible but no pixel coords yet

        int px = t.hit.pixelX(w), py = t.hit.pixelY(h);

        // The desktop cursor is not the cursor at the projected app pixel. Keep
        // the HUD pointer stable and avoid showing a second captured cursor.
        nativeCursorShape = -1;

        send(t, InputBridge.MOVE, heldHwnd == t.panel.info.hwnd()
            ? (heldButton == 0 ? InputBridge.MK_LBUTTON : InputBridge.MK_RBUTTON) : 0);
    }

    private static void flushPendingRightClick(boolean force) {
        PendingRightClick click = pendingRightClick;
        if (click == null) return;
        if (!force && System.nanoTime() - click.pressedAt <= 500_000_000L) return;
        pendingRightClick = null;
        if (!WinCraftClient.surfaces().contains(click.panel) || click.panel.capture() == null) return;
        long hwnd = click.panel.info.hwnd();
        InputBridge.mouse(hwnd, InputBridge.RIGHT_DOWN, click.x, click.y, InputBridge.MK_RBUTTON);
        InputBridge.mouse(hwnd, InputBridge.RIGHT_UP, click.x, click.y, 0);
    }

    private static void updateDrag() {
        Drag d = drag;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || d.panel.sourceClosed() || !WinCraftClient.surfaces().contains(d.panel)) { drag = null; return; }
        RaycastRay now = currentRay(mc);

        if (d.handle == Handle.MOVE) {
            Vector3d oldOwnerPos = new Vector3d(d.panel.transform.position);
            Vector3d newPos = new Vector3d(now.origin()).fma(d.distance, now.direction());

            // Snap against block surfaces if looking towards a block
            if (mc.level != null && mc.player != null) {
                Vec3 eye = mc.player.getEyePosition();
                Vec3 look = mc.player.getLookAngle();
                Vec3 rayEnd = eye.add(look.scale(d.distance + 0.6));
                var blockHit = mc.level.clip(new net.minecraft.world.level.ClipContext(
                    eye, rayEnd, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
                if (blockHit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                    double hitDist = eye.distanceTo(blockHit.getLocation());
                    if (hitDist <= d.distance + 0.3 && hitDist > 0.5) {
                        Vec3 blockPos = blockHit.getLocation().subtract(look.scale(0.08));
                        newPos.set(blockPos.x, blockPos.y, blockPos.z);
                    }
                }
            }

            // Snapping to other nearby panels
            double snap = 0.2;
            for (AppWindowEntity other : WinCraftClient.surfaces()) {
                if (other == d.panel) continue;
                Vector3d op = other.transform.position;
                if (Math.abs(newPos.x - op.x) <= snap) newPos.x = op.x;
                if (Math.abs(newPos.y - op.y) <= snap) newPos.y = op.y;
                if (Math.abs(newPos.z - op.z) <= snap) newPos.z = op.z;
                if (Math.abs(d.panel.transform.yaw - other.transform.yaw) <= 0.15f) {
                    d.panel.transform.yaw = other.transform.yaw;
                }
            }
            d.panel.transform.position.set(newPos);

            if (!d.panel.transform.followCamera) {
                d.panel.transform.yaw = (float)Math.atan2(-now.direction().x, -now.direction().z);
                d.panel.transform.pitch = (float)Math.asin(Math.clamp(now.direction().y, -1, 1));
            } else {
                Vec3 eye = mc.player.getEyePosition();
                Vec3 look = mc.player.getLookAngle();
                d.panel.transform.pinToCamera(new Vector3d(eye.x, eye.y, eye.z),
                    new Vector3d(look.x, look.y, look.z), mc.player.getYRot(), mc.player.getXRot());
            }

            Vector3d moveDelta = new Vector3d(d.panel.transform.position).sub(oldOwnerPos);
            for (AppWindowEntity other : WinCraftClient.surfaces()) {
                if (other != d.panel && other.ownedPopup && other.ownerHwnd == d.panel.info.hwnd()) {
                    other.transform.position.add(moveDelta);
                    other.transform.yaw = d.panel.transform.yaw;
                    other.transform.pitch = d.panel.transform.pitch;
                }
            }
            return;
        }

        var hit = LocalCoordinateMapper.intersectPlane(now, d.initial, 32);
        if (hit.isEmpty()) return;
        double du = hit.get().u() - d.start.u(), dv = hit.get().v() - d.start.v();

        if (d.handle == Handle.CORNER) {
            // Aspect-preserving corner resize
            double factor = Math.max(.1, Math.min(5, 1 + (du * d.xSign + dv * d.ySign) / 2));
            d.panel.transform.resize(
                (float)Math.clamp(d.initial.width * factor, .2, 32),
                (float)Math.clamp(d.initial.height * factor, .2, 32)
            );
        } else if (d.handle == Handle.ROTATE) {
            d.panel.transform.yaw = d.initial.yaw + (float)(du * Math.PI * 2.0);
        } else if (d.handle == Handle.TILT) {
            d.panel.transform.pitch = (float)Math.clamp(
                d.initial.pitch + dv * Math.PI * 2.0,
                -Math.toRadians(80.0), Math.toRadians(80.0));
        } else if (d.handle == Handle.WIDTH) {
            // Independent width resize
            d.panel.transform.resize(
                (float)Math.clamp(d.initial.width * (1 + du * d.xSign), .2, 32),
                d.initial.height
            );
        } else {
            // Independent height resize
            d.panel.transform.resize(
                d.initial.width,
                (float)Math.clamp(d.initial.height * (1 + dv * d.ySign), .2, 32)
            );
        }
    }

    private static void releaseKeys() {
        for (var key : HELD_KEYS.entrySet()) {
            InputBridge.key(key.getValue().hwnd, InputBridge.KEY_UP,
                key.getValue().virtualKey, key.getValue().scan, false);
        }
        HELD_KEYS.clear();
    }

    private static boolean printableKey(int key) {
        if ((key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) ||
            (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) ||
            (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9)) return true;
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_APOSTROPHE, GLFW.GLFW_KEY_COMMA,
                GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_PERIOD, GLFW.GLFW_KEY_SLASH,
                GLFW.GLFW_KEY_SEMICOLON, GLFW.GLFW_KEY_EQUAL,
                GLFW.GLFW_KEY_LEFT_BRACKET, GLFW.GLFW_KEY_BACKSLASH,
                GLFW.GLFW_KEY_RIGHT_BRACKET, GLFW.GLFW_KEY_GRAVE_ACCENT,
                GLFW.GLFW_KEY_WORLD_1, GLFW.GLFW_KEY_WORLD_2,
                GLFW.GLFW_KEY_KP_DECIMAL, GLFW.GLFW_KEY_KP_DIVIDE,
                GLFW.GLFW_KEY_KP_MULTIPLY, GLFW.GLFW_KEY_KP_SUBTRACT,
                GLFW.GLFW_KEY_KP_ADD, GLFW.GLFW_KEY_KP_EQUAL -> true;
            default -> false;
        };
    }

    /** GLFW key tokens differ from Windows VK values for navigation/modifier keys. */
    private static int windowsVirtualKey(int key) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) return key;
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) return key;
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F24)
            return 0x70 + key - GLFW.GLFW_KEY_F1;
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9)
            return 0x60 + key - GLFW.GLFW_KEY_KP_0;
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> 0x20;
            case GLFW.GLFW_KEY_APOSTROPHE -> 0xDE;
            case GLFW.GLFW_KEY_COMMA -> 0xBC;
            case GLFW.GLFW_KEY_MINUS -> 0xBD;
            case GLFW.GLFW_KEY_PERIOD -> 0xBE;
            case GLFW.GLFW_KEY_SLASH -> 0xBF;
            case GLFW.GLFW_KEY_SEMICOLON -> 0xBA;
            case GLFW.GLFW_KEY_EQUAL -> 0xBB;
            case GLFW.GLFW_KEY_LEFT_BRACKET -> 0xDB;
            case GLFW.GLFW_KEY_BACKSLASH -> 0xDC;
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> 0xDD;
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> 0xC0;
            case GLFW.GLFW_KEY_ESCAPE -> 0x1B;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> 0x0D;
            case GLFW.GLFW_KEY_TAB -> 0x09;
            case GLFW.GLFW_KEY_BACKSPACE -> 0x08;
            case GLFW.GLFW_KEY_INSERT -> 0x2D;
            case GLFW.GLFW_KEY_DELETE -> 0x2E;
            case GLFW.GLFW_KEY_RIGHT -> 0x27;
            case GLFW.GLFW_KEY_LEFT -> 0x25;
            case GLFW.GLFW_KEY_DOWN -> 0x28;
            case GLFW.GLFW_KEY_UP -> 0x26;
            case GLFW.GLFW_KEY_PAGE_UP -> 0x21;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 0x22;
            case GLFW.GLFW_KEY_HOME -> 0x24;
            case GLFW.GLFW_KEY_END -> 0x23;
            case GLFW.GLFW_KEY_CAPS_LOCK -> 0x14;
            case GLFW.GLFW_KEY_SCROLL_LOCK -> 0x91;
            case GLFW.GLFW_KEY_NUM_LOCK -> 0x90;
            case GLFW.GLFW_KEY_PRINT_SCREEN -> 0x2C;
            case GLFW.GLFW_KEY_PAUSE -> 0x13;
            case GLFW.GLFW_KEY_LEFT_SHIFT -> 0xA0;
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> 0xA1;
            case GLFW.GLFW_KEY_LEFT_CONTROL -> 0xA2;
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> 0xA3;
            case GLFW.GLFW_KEY_LEFT_ALT -> 0xA4;
            case GLFW.GLFW_KEY_RIGHT_ALT -> 0xA5;
            case GLFW.GLFW_KEY_LEFT_SUPER -> 0x5B;
            case GLFW.GLFW_KEY_RIGHT_SUPER -> 0x5C;
            case GLFW.GLFW_KEY_MENU -> 0x5D;
            case GLFW.GLFW_KEY_KP_DECIMAL -> 0x6E;
            case GLFW.GLFW_KEY_KP_DIVIDE -> 0x6F;
            case GLFW.GLFW_KEY_KP_MULTIPLY -> 0x6A;
            case GLFW.GLFW_KEY_KP_SUBTRACT -> 0x6D;
            case GLFW.GLFW_KEY_KP_ADD -> 0x6B;
            default -> 0;
        };
    }

    public static boolean key(int action, int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE && (fileDragPath != null || fileDragCancelAwaitingRelease)) {
            if (action == 1) {
                fileDragPath = null;
                fileDragCancelAwaitingRelease = true;
            } else if (action == 0) {
                fileDragCancelAwaitingRelease = false;
            }
            return true;
        }
        if (key == InputConstants.KEY_B) {
            if (launcherKeyAwaitingRelease) {
                if (action == 0) launcherKeyAwaitingRelease = false;
                return true;
            }

            Target underPointer = activeTarget();
            boolean appOwnsKeyboard = underPointer != null && keyboardFocusPanel == underPointer.panel;
            // B opens the launcher only when the pointer is outside every app
            // panel. Otherwise it belongs to the app (when clicked for focus),
            // or is swallowed so a stray B cannot open Minecraft's GUI while
            // the user is interacting with a desktop surface.
            if (!appOwnsKeyboard && (underPointer != null || keyboardFocusPanel != null)) {
                if (action == 1) launcherKeyAwaitingRelease = true;
                return true;
            }
            if (!appOwnsKeyboard && Minecraft.getInstance().gui.screen() == null) {
                if (action == 1) { WinCraftClient.openLauncher(); launcherKeyAwaitingRelease = true; }
                return true;
            }
        }
        // F8: toggle spatial panel transform mode only.
        if (key == InputConstants.KEY_F8) {
            if (action == 1) {
                transformMode = !transformMode;
                release();
                LOG.info("WinCraft panel transform mode: {}", transformMode ? "ON (F8 to exit)" : "OFF");
            }
            return true;
        }
        if (transformMode) {
            if (action == 1) {
                Target target = target();
                AppWindowEntity p = target != null ? target.panel : selectedPanel;
                if (key == GLFW.GLFW_KEY_F) {
                    if (p != null) {
                        boolean following = toggleFollow(p);
                        showOverlay(following ? "§aScreen is now Floating (moves with you)" : "§eScreen is now Fixed in World");
                    }
                    return true;
                }
                if (key == GLFW.GLFW_KEY_G) {
                    if (p != null) {
                        com.wincraft.client.gui.WindowControlOverlay.snapToGrid(p);
                        showOverlay("§bSnapped screen to grid");
                    }
                    return true;
                }
                if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
                    if (p != null) {
                        WinCraftClient.detach(p);
                        showOverlay("§cRemoved screen");
                    }
                    return true;
                }
                if (key == GLFW.GLFW_KEY_ESCAPE) {
                    transformMode = false;
                    release();
                    return true;
                }
            }
            return false;
        }

        HeldKey held = HELD_KEYS.get(key);
        if (action == 0 && held != null) {
            InputBridge.key(held.hwnd, InputBridge.KEY_UP, held.virtualKey, held.scan, false);
            HELD_KEYS.remove(key);
            return true;
        }
        // A key can go down in Minecraft just before the reticle reaches a
        // panel. Let its unmatched key-up reach Minecraft too, or movement
        // bindings such as W can remain stuck after hovering the panel.
        if (action == 0) return false;
        Target t = activeTarget();
        // Aiming at a screen should not swallow Minecraft movement keys. The
        // app only owns keyboard input after a left-click gives it focus.
        if (t == null || keyboardFocusPanel != t.panel) return false;
        // Printable input is supplied by charTyped as WM_CHAR. Sending WM_KEYDOWN
        // too makes several Windows controls and browsers produce two characters.
        // Keep native key messages for shortcuts and navigation keys.
        boolean shortcut = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) != 0;
        if (printableKey(key) && !shortcut) return true;
        int virtualKey = windowsVirtualKey(key);
        if (virtualKey == 0) return false;
        long hwnd = t.panel.info.hwnd();
        if (action == 1 || action == 2) {
            if (held != null && held.hwnd != hwnd) {
                InputBridge.key(held.hwnd, InputBridge.KEY_UP, held.virtualKey, held.scan, false);
                HELD_KEYS.remove(key);
            }
            InputBridge.key(hwnd, InputBridge.KEY_DOWN, virtualKey, scan, action == 2);
            HELD_KEYS.put(key, new HeldKey(hwnd, virtualKey, scan));
        }
        return true;
    }

    public static boolean text(int codepoint) {
        if (transformMode) return false;
        Target t = activeTarget();
        if (t == null || keyboardFocusPanel != t.panel || !Character.isValidCodePoint(codepoint)) return false;
        for (char unit : Character.toChars(codepoint)) {
            InputBridge.text(t.panel.info.hwnd(), unit);
        }
        return true;
    }
}
