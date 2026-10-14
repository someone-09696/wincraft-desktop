#include "input_injector.h"
#include <dwmapi.h>
#include <shellapi.h>
#include <shlobj.h>
#include <algorithm>
#include <mutex>
#include <set>
#include <unordered_map>
#include <vector>

namespace wc {

static bool valid(HWND hwnd) { return hwnd && IsWindow(hwnd); }
static std::mutex last_target_mutex;
static std::unordered_map<HWND, HWND> last_target_by_window;
static std::mutex virtual_focus_mutex;
static std::unordered_map<HWND, HWND> virtual_focus_by_window;
struct LastMouseMove {
    HWND target{};
    int x{}, y{};
    unsigned int state{};
    ULONGLONG time_ms{};
    int refresh_direction{1};
};
static std::unordered_map<HWND, LastMouseMove> last_mouse_move_by_window;
// Browser hover controls (for example, fullscreen video controls) use mouse
// activity timers. The in-world cursor can stay at one pixel for many seconds,
// so refresh the same position occasionally while the reticle remains over a
// panel. This stays far below normal input rates and never moves the OS cursor.
static constexpr ULONGLONG MOUSE_MOVE_REFRESH_MS = 120;
struct ActivationHint { HWND target{}; ULONGLONG time_ms{}; };
static std::mutex activation_hint_mutex;
static std::unordered_map<HWND, ActivationHint> activation_hint_by_window;
static constexpr ULONGLONG ACTIVATION_HINT_REFRESH_MS = 500;
static std::mutex key_target_mutex;
static std::unordered_map<HWND, std::unordered_map<unsigned int, HWND>> key_target_by_window;

static HWND focused_window(HWND top_hwnd);

static void activate_focus_for_window(HWND top, HWND target) {
    if (!valid(top)) return;
    if (!valid(target)) target = top;

    // *** Do NOT call SetActiveWindow / SetFocus / AttachThreadInput ***
    // Those make the app become the real OS active window which steals it to
    // the desktop foreground. Instead we inject the WM_ACTIVATE / WM_SETFOCUS
    // messages directly so the app processes input normally while Minecraft
    // keeps OS foreground at all times.

    // Tell the app it is being activated (but don't touch Z-order or OS focus)
    PostMessageW(top, WM_ACTIVATEAPP, TRUE, 0);
    PostMessageW(top, WM_NCACTIVATE, TRUE, 0);
    PostMessageW(top, WM_ACTIVATE, MAKEWPARAM(WA_ACTIVE, FALSE), 0);
    if (target != top) {
        PostMessageW(target, WM_SETFOCUS, 0, 0);
    } else {
        PostMessageW(top, WM_SETFOCUS, 0, 0);
    }

    // Force the app to repaint so captured frames reflect the new state
    InvalidateRect(target, nullptr, FALSE);
    InvalidateRect(top, nullptr, FALSE);
    RedrawWindow(top, nullptr, nullptr, RDW_INVALIDATE | RDW_UPDATENOW | RDW_ALLCHILDREN);
}

void wake_app(HWND top) {
    if (!valid(top)) return;
    if (IsIconic(top)) {
        ShowWindowAsync(top, SW_SHOWNOACTIVATE);
    }
    HWND target = focused_window(top);
    activate_focus_for_window(top, target);
}

// Tell the app under the gaze that it is the active interaction surface, but
// keep Minecraft as the real foreground window.
static void signal_app_active(HWND top, HWND target) {
    if (!valid(top) || !valid(target)) return;
    const ULONGLONG now = GetTickCount64();
    {
        std::lock_guard guard(activation_hint_mutex);
        auto& previous = activation_hint_by_window[top];
        if (previous.target == target && now - previous.time_ms < ACTIVATION_HINT_REFRESH_MS) return;
        previous = {target, now};
    }

    activate_focus_for_window(top, target);
}

static void remember_target(HWND top, HWND target) {
    if (!valid(top) || !valid(target) || (target != top && !IsChild(top, target))) return;
    std::lock_guard guard(last_target_mutex);
    last_target_by_window[top] = target;
}

static HWND remembered_target(HWND top) {
    std::lock_guard guard(last_target_mutex);
    auto it = last_target_by_window.find(top);
    if (it == last_target_by_window.end()) return nullptr;
    if (!valid(it->second) || (it->second != top && !IsChild(top, it->second))) {
        last_target_by_window.erase(it);
        return nullptr;
    }
    return it->second;
}

static void remember_virtual_focus(HWND top, HWND target) {
    if (!valid(top) || !valid(target) || (target != top && !IsChild(top, target))) return;
    std::lock_guard guard(virtual_focus_mutex);
    virtual_focus_by_window[top] = target;
}

static HWND virtual_focus_target(HWND top) {
    std::lock_guard guard(virtual_focus_mutex);
    auto it = virtual_focus_by_window.find(top);
    if (it == virtual_focus_by_window.end()) return nullptr;
    if (!valid(it->second) || (it->second != top && !IsChild(top, it->second))) {
        virtual_focus_by_window.erase(it);
        return nullptr;
    }
    return it->second;
}

// Queue input directly to the app's child HWND. Never attach input queues or
// call SetFocus/SetForegroundWindow: that activates the desktop window. Using
// PostMessage also avoids stalling Minecraft behind a busy browser UI thread.
static bool post_to_target(HWND top, HWND target, UINT msg,
                           WPARAM wparam, LPARAM lparam) {
    if (!valid(target)) return false;
    if (PostMessageW(target, msg, wparam, lparam)) return true;
    return target != top && valid(top) && PostMessageW(top, msg, wparam, lparam);
}

static bool mouse_move_changed(HWND top, HWND target, POINT& point, unsigned int state) {
    std::lock_guard guard(last_target_mutex);
    const ULONGLONG now = GetTickCount64();
    auto it = last_mouse_move_by_window.find(top);
    if (it != last_mouse_move_by_window.end() && it->second.target == target &&
        it->second.x == point.x && it->second.y == point.y && it->second.state == state) {
        if (now - it->second.time_ms < MOUSE_MOVE_REFRESH_MS) return false;
        it->second.time_ms = now;
        if (state == 0) {
            // Some UI toolkits suppress duplicate-coordinate WM_MOUSEMOVE
            // messages, so video controls and hover menus still time out.
            // A tiny, button-up-only nudge creates genuine pointer motion
            // inside the captured child without moving the OS cursor or a drag.
            RECT bounds{};
            if (GetClientRect(target, &bounds)) {
                const LONG width = bounds.right - bounds.left;
                if (width > 1) {
                    int direction = it->second.refresh_direction >= 0 ? 1 : -1;
                    LONG nudged_x = std::clamp<LONG>(point.x + direction * 2, 0, width - 1);
                    if (nudged_x == point.x) {
                        direction = -direction;
                        nudged_x = std::clamp<LONG>(point.x + direction * 2, 0, width - 1);
                    }
                    point.x = nudged_x;
                    it->second.refresh_direction = -direction;
                }
            }
        }
        return true;
    }
    last_mouse_move_by_window[top] = {target, point.x, point.y, state, now};
    return true;
}

static HWND find_target_window(HWND top_hwnd, int x, int y, POINT& out_client_pt, POINT& out_screen_pt) {
    // Resolve children in the target window's own coordinate tree. WindowFromPoint
    // uses desktop z-order and often returns Minecraft because the captured app is
    // behind the game, sending clicks to the wrong HWND or its top-level frame.
    POINT local{x, y};
    out_screen_pt = local;
    ClientToScreen(top_hwnd, &out_screen_pt);

    HWND target = top_hwnd;
    constexpr UINT flags = CWP_SKIPINVISIBLE | CWP_SKIPDISABLED | CWP_SKIPTRANSPARENT;
    for (int depth = 0; depth < 32; ++depth) {
        HWND child = ChildWindowFromPointEx(target, local, flags);
        if (!child || child == target) break;
        MapWindowPoints(target, child, &local, 1);
        target = child;
    }
    out_client_pt = local;
    return target;
}

static HWND focused_window(HWND top_hwnd) {
    // The desktop focus may still point at an address bar or stale control
    // because Minecraft remains the real foreground window. A click inside
    // the projected panel establishes a virtual focus target for this HWND.
    HWND virtual_focus = virtual_focus_target(top_hwnd);
    if (virtual_focus) return virtual_focus;

    DWORD thread = GetWindowThreadProcessId(top_hwnd, nullptr);
    GUITHREADINFO info{};
    info.cbSize = sizeof(info);
    if (thread && GetGUIThreadInfo(thread, &info) && info.hwndFocus &&
        info.hwndFocus != top_hwnd && IsChild(top_hwnd, info.hwndFocus)) {
        return info.hwndFocus;
    }
    HWND remembered = remembered_target(top_hwnd);
    if (remembered) return remembered;
    return top_hwnd;
}

bool mouse(HWND hwnd, UINT msg, int x, int y, unsigned int state) {
    if (!valid(hwnd) || x < 0 || y < 0 || x > 32767 || y > 32767) return false;
    if (msg != WM_MOUSEMOVE && msg != WM_LBUTTONDOWN && msg != WM_LBUTTONUP &&
        msg != WM_RBUTTONDOWN && msg != WM_RBUTTONUP &&
        msg != WM_MOUSEWHEEL && msg != WM_MOUSEHWHEEL) return false;

    // DWM extended frame adjustment for physical pixel alignment
    RECT frame{};
    POINT clientTopLeft{0, 0};
    if (SUCCEEDED(DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, &frame, sizeof(frame))) &&
        ClientToScreen(hwnd, &clientTopLeft)) {
        x += frame.left - clientTopLeft.x;
        y += frame.top - clientTopLeft.y;
    }

    POINT client_pt{}, screen_pt{};
    HWND target = find_target_window(hwnd, x, y, client_pt, screen_pt);
    remember_target(hwnd, target);
    if (msg == WM_LBUTTONDOWN || msg == WM_RBUTTONDOWN) remember_virtual_focus(hwnd, target);
    if (msg == WM_MOUSEMOVE || msg == WM_LBUTTONDOWN || msg == WM_RBUTTONDOWN ||
        msg == WM_MOUSEWHEEL || msg == WM_MOUSEHWHEEL) {
        signal_app_active(hwnd, target);
    }

    // Minecraft stays in the foreground, so ordinary PostMessage clicks skip
    // Windows' mouse-activation handshake. Give the target control that same
    // hint and invalidate the app window so tabs/controls that paused while
    // hidden get a chance to repaint, without bringing the desktop over Minecraft.
    if (msg == WM_LBUTTONDOWN || msg == WM_RBUTTONDOWN) {
        DWORD_PTR activation = 0;
        SendMessageTimeoutW(target, WM_MOUSEACTIVATE, reinterpret_cast<WPARAM>(hwnd),
            MAKELPARAM(HTCLIENT, msg), SMTO_ABORTIFHUNG | SMTO_BLOCK, 15, &activation);
        RedrawWindow(hwnd, nullptr, nullptr, RDW_INVALIDATE | RDW_ALLCHILDREN);
    }

    if (msg == WM_MOUSEWHEEL || msg == WM_MOUSEHWHEEL) {
        SHORT delta = static_cast<SHORT>(HIWORD(state));
        if (delta == 0) delta = static_cast<SHORT>(state);
        WPARAM wparam = MAKEWPARAM(LOWORD(state), delta);
        LPARAM pos = MAKELPARAM(static_cast<SHORT>(screen_pt.x), static_cast<SHORT>(screen_pt.y));

        HWND wheel_target = target;
        if (!valid(wheel_target)) wheel_target = virtual_focus_target(hwnd);
        if (!valid(wheel_target)) wheel_target = hwnd;

        if (valid(wheel_target)) {
            PostMessageW(wheel_target, msg, wparam, pos);
        }
        if (valid(hwnd) && hwnd != wheel_target) {
            PostMessageW(hwnd, msg, wparam, pos);
        }
        // Fallback for classic controls that respond to WM_VSCROLL
        if (msg == WM_MOUSEWHEEL && delta != 0) {
            WPARAM scroll_cmd = delta > 0 ? SB_LINEUP : SB_LINEDOWN;
            PostMessageW(wheel_target, WM_VSCROLL, scroll_cmd, 0);
        }
        return true;
    }

    if (msg == WM_MOUSEMOVE && !mouse_move_changed(hwnd, target, client_pt, state)) return true;
    LPARAM pos = MAKELPARAM(static_cast<WORD>(client_pt.x), static_cast<WORD>(client_pt.y));
    return post_to_target(hwnd, target, msg, static_cast<WPARAM>(state), pos);
}

bool key(HWND hwnd, UINT msg, unsigned int vk, unsigned int scan, bool repeat) {
    if (!valid(hwnd) || (msg != WM_KEYDOWN && msg != WM_KEYUP) || scan > 0xFFFF) return false;
    if (vk == 0 || vk > 255) return false;

    HWND target = nullptr;
    {
        std::lock_guard guard(key_target_mutex);
        auto window_it = key_target_by_window.find(hwnd);
        if (window_it != key_target_by_window.end()) {
            auto key_it = window_it->second.find(vk);
            if (key_it != window_it->second.end()) {
                if (valid(key_it->second)) target = key_it->second;
                if (msg == WM_KEYUP) window_it->second.erase(key_it);
            }
            if (window_it->second.empty()) key_target_by_window.erase(window_it);
        }
    }
    if (!target) target = focused_window(hwnd);
    // Java supplies GLFW's virtual-key token translated to a Windows VK. Its
    // scan argument is GLFW's platform scancode, not a Win32 scan code.
    UINT mapped_scan = MapVirtualKeyExW(vk, MAPVK_VK_TO_VSC_EX, GetKeyboardLayout(0));
    if (mapped_scan) scan = mapped_scan;
    scan &= 0xFFFF;

    LPARAM data = 1 | (static_cast<LPARAM>(scan & 0xFF) << 16);
    if ((scan & 0xFF00) == 0xE000) data |= 1L << 24;
    if (repeat || msg == WM_KEYUP) data |= 1L << 30;
    if (msg == WM_KEYUP) data |= 1L << 31;

    bool posted = post_to_target(hwnd, target, msg, vk, data);
    if (posted && msg == WM_KEYDOWN) {
        std::lock_guard guard(key_target_mutex);
        key_target_by_window[hwnd][vk] = target;

        // Force immediate redraw so keystrokes (Enter, Backspace, arrows) visually show in-game!
        InvalidateRect(target, nullptr, FALSE);
        InvalidateRect(hwnd, nullptr, FALSE);
        RedrawWindow(hwnd, nullptr, nullptr, RDW_INVALIDATE | RDW_UPDATENOW | RDW_ALLCHILDREN);
    }
    return posted;
}

bool text(HWND hwnd, unsigned int unit) {
    if (!valid(hwnd) || unit == 0 || unit > 0xFFFF) return false;
    HWND target = focused_window(hwnd);
    bool posted = post_to_target(hwnd, target, WM_CHAR, unit, 1);
    if (posted) {
        // Force immediate redraw so typed characters appear in the captured panel instantly!
        InvalidateRect(target, nullptr, FALSE);
        InvalidateRect(hwnd, nullptr, FALSE);
        RedrawWindow(hwnd, nullptr, nullptr, RDW_INVALIDATE | RDW_UPDATENOW | RDW_ALLCHILDREN);
    }
    return posted;
}

int get_cursor_shape(HWND hwnd, int x, int y) {
    // Return codes: 0=arrow, 1=ibeam, 2=hand, 3=resize_h, 4=resize_v, 5=resize_corner, -1=unknown
    CURSORINFO ci{};
    ci.cbSize = sizeof(ci);
    if (GetCursorInfo(&ci) && (ci.flags & CURSOR_SHOWING)) {
        static HCURSOR c_arrow = LoadCursorW(nullptr, IDC_ARROW);
        static HCURSOR c_ibeam = LoadCursorW(nullptr, IDC_IBEAM);
        static HCURSOR c_hand = LoadCursorW(nullptr, IDC_HAND);
        static HCURSOR c_size_h = LoadCursorW(nullptr, IDC_SIZEWE);
        static HCURSOR c_size_v = LoadCursorW(nullptr, IDC_SIZENS);
        static HCURSOR c_size_nwse = LoadCursorW(nullptr, IDC_SIZENWSE);
        static HCURSOR c_size_nesw = LoadCursorW(nullptr, IDC_SIZENESW);

        if (ci.hCursor == c_ibeam) return 1;
        if (ci.hCursor == c_hand) return 2;
        if (ci.hCursor == c_size_h) return 3;
        if (ci.hCursor == c_size_v) return 4;
        if (ci.hCursor == c_size_nwse || ci.hCursor == c_size_nesw) return 5;
        if (ci.hCursor == c_arrow) return 0;
    }
    return -1;
}

bool drop_files(HWND hwnd, int x, int y, const std::vector<std::wstring>& files) {
    if (!valid(hwnd) || files.empty()) return false;
    POINT pt_client{}, pt_screen{};
    // The capture covers the DWM extended frame, while HWND messages use the
    // client coordinate system. Map the pointer back to the app's client area.
    RECT frame{};
    POINT client_top_left{0, 0};
    if (SUCCEEDED(DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, &frame, sizeof(frame))) &&
        ClientToScreen(hwnd, &client_top_left)) {
        x += frame.left - client_top_left.x;
        y += frame.top - client_top_left.y;
    }
    find_target_window(hwnd, x, y, pt_client, pt_screen);
    POINT root_client = pt_screen;
    if (!ScreenToClient(hwnd, &root_client)) return false;

    size_t chars = 0;
    for (const auto& f : files) chars += f.size() + 1;
    chars += 1;

    size_t total_bytes = sizeof(DROPFILES) + chars * sizeof(wchar_t);
    HGLOBAL h_global = GlobalAlloc(GHND | GMEM_SHARE, total_bytes);
    if (!h_global) return false;

    DROPFILES* df = static_cast<DROPFILES*>(GlobalLock(h_global));
    if (!df) {
        GlobalFree(h_global);
        return false;
    }
    df->pFiles = sizeof(DROPFILES);
    df->pt = root_client;
    df->fNC = FALSE;
    df->fWide = TRUE;

    wchar_t* dest = reinterpret_cast<wchar_t*>(reinterpret_cast<char*>(df) + sizeof(DROPFILES));
    for (const auto& f : files) {
        wcscpy_s(dest, f.size() + 1, f.c_str());
        dest += f.size() + 1;
    }
    *dest = L'\0';
    GlobalUnlock(h_global);

    // WM_DROPFILES is delivered to the destination app's top-level HWND; child
    // controls commonly neither opt into nor handle this legacy drop message.
    if (!PostMessageW(hwnd, WM_DROPFILES, reinterpret_cast<WPARAM>(h_global), 0)) {
        GlobalFree(h_global);
        return false;
    }
    return true;
}

HWND detect_new_window(const std::vector<HWND>& existing, DWORD timeout_ms) {
    DWORD start = GetTickCount();
    std::set<HWND> old_set(existing.begin(), existing.end());
    while (GetTickCount() - start < timeout_ms) {
        HWND found = nullptr;
        EnumWindows([](HWND hwnd, LPARAM arg) -> BOOL {
            if (!IsWindowVisible(hwnd) || IsIconic(hwnd)) return TRUE;
            BOOL cloaked = FALSE;
            DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, &cloaked, sizeof(cloaked));
            if (cloaked) return TRUE;
            int len = GetWindowTextLengthW(hwnd);
            if (len <= 0) return TRUE;
            *reinterpret_cast<HWND*>(arg) = hwnd;
            return FALSE;
        }, reinterpret_cast<LPARAM>(&found));
        if (found && !old_set.count(found)) return found;
        Sleep(50);
    }
    return nullptr;
}

}
