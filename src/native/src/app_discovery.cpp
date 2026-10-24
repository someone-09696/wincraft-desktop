#include "app_discovery.h"
#include <shlobj.h>
#include <shellapi.h>
#include <objbase.h>
#include <psapi.h>
#include <tlhelp32.h>
#include <winrt/base.h>
#include <filesystem>
#include <algorithm>
#include <set>
#include <cwctype>
#include <iterator>
#include <vector>
#pragma comment(lib, "psapi.lib")

namespace fs = std::filesystem;

namespace wc {

class ComApartment {
public:
    ComApartment() : result_(CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED)) {}
    ~ComApartment() { if (SUCCEEDED(result_)) CoUninitialize(); }
    bool available() const { return SUCCEEDED(result_) || result_ == RPC_E_CHANGED_MODE; }
private:
    HRESULT result_;
};

static std::wstring to_lower(std::wstring str) {
    std::transform(str.begin(), str.end(), str.begin(), ::towlower);
    return str;
}

static std::wstring resolve_shortcut(const fs::path& lnk_path) {
    winrt::com_ptr<IShellLinkW> shell_link;
    if (FAILED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_IShellLinkW, shell_link.put_void()))) {
        return {};
    }
    winrt::com_ptr<IPersistFile> persist;
    if (FAILED(shell_link->QueryInterface(IID_IPersistFile, persist.put_void()))) {
        return {};
    }
    if (FAILED(persist->Load(lnk_path.c_str(), STGM_READ))) {
        return {};
    }
    std::vector<wchar_t> target(MAX_PATH * 2, L'\0');
    WIN32_FIND_DATAW fd{};
    if (FAILED(shell_link->GetPath(target.data(), static_cast<int>(target.size()), &fd, SLGP_UNCPRIORITY))) {
        return {};
    }
    return std::wstring(target.data());
}

static std::wstring read_registry_string(HKEY key, const wchar_t* name) {
    DWORD type = 0, bytes = 0;
    if (RegQueryValueExW(key, name, nullptr, &type, nullptr, &bytes) != ERROR_SUCCESS ||
        (type != REG_SZ && type != REG_EXPAND_SZ) || bytes < sizeof(wchar_t)) return {};
    std::vector<wchar_t> buffer(bytes / sizeof(wchar_t) + 1, L'\0');
    if (RegQueryValueExW(key, name, nullptr, &type, reinterpret_cast<BYTE*>(buffer.data()), &bytes) != ERROR_SUCCESS) return {};
    std::wstring value(buffer.data());
    if (type == REG_EXPAND_SZ && !value.empty()) {
        DWORD needed = ExpandEnvironmentStringsW(value.c_str(), nullptr, 0);
        if (needed > 1 && needed < 32768) {
            std::vector<wchar_t> expanded(needed, L'\0');
            if (ExpandEnvironmentStringsW(value.c_str(), expanded.data(), needed)) value.assign(expanded.data());
        }
    }
    return value;
}

static std::wstring executable_from_registry_value(std::wstring value) {
    while (!value.empty() && iswspace(value.front())) value.erase(value.begin());
    if (value.empty()) return {};
    if (value.front() == L'"') {
        size_t end = value.find(L'"', 1);
        if (end != std::wstring::npos) value = value.substr(1, end - 1);
    } else {
        std::wstring lower = to_lower(value);
        size_t end = lower.find(L".exe");
        if (end != std::wstring::npos) value.resize(end + 4);
        else if (value.find(L',') != std::wstring::npos) value.resize(value.find(L','));
    }
    return value;
}

static std::wstring categorize_app(const std::wstring& name, const std::wstring& path, const std::wstring& subfolder) {
    std::wstring lower_name = to_lower(name);
    std::wstring lower_path = to_lower(path);
    std::wstring lower_sub = to_lower(subfolder);

    if (lower_name.find(L"chrome") != std::wstring::npos ||
        lower_name.find(L"edge") != std::wstring::npos ||
        lower_name.find(L"firefox") != std::wstring::npos ||
        lower_name.find(L"brave") != std::wstring::npos ||
        lower_name.find(L"opera") != std::wstring::npos ||
        lower_name.find(L"vivaldi") != std::wstring::npos ||
        lower_name.find(L"browser") != std::wstring::npos) {
        return L"Browsers";
    }
    if (lower_name.find(L"whatsapp") != std::wstring::npos ||
        lower_name.find(L"discord") != std::wstring::npos ||
        lower_name.find(L"telegram") != std::wstring::npos ||
        lower_name.find(L"signal") != std::wstring::npos ||
        lower_name.find(L"slack") != std::wstring::npos ||
        lower_name.find(L"teams") != std::wstring::npos ||
        lower_name.find(L"zoom") != std::wstring::npos ||
        lower_name.find(L"skype") != std::wstring::npos ||
        lower_name.find(L"messenger") != std::wstring::npos ||
        lower_name.find(L"chat") != std::wstring::npos) {
        return L"Social";
    }
    if (lower_name.find(L"visual studio") != std::wstring::npos ||
        lower_name.find(L"code") != std::wstring::npos ||
        lower_name.find(L"terminal") != std::wstring::npos ||
        lower_name.find(L"git") != std::wstring::npos ||
        lower_name.find(L"cmd") != std::wstring::npos ||
        lower_name.find(L"powershell") != std::wstring::npos ||
        lower_sub.find(L"develop") != std::wstring::npos) {
        return L"Development";
    }
    if (lower_name.find(L"media") != std::wstring::npos ||
        lower_name.find(L"player") != std::wstring::npos ||
        lower_name.find(L"spotify") != std::wstring::npos ||
        lower_name.find(L"vlc") != std::wstring::npos ||
        lower_name.find(L"kdenlive") != std::wstring::npos ||
        lower_name.find(L"obs") != std::wstring::npos ||
        lower_name.find(L"audio") != std::wstring::npos ||
        lower_name.find(L"video") != std::wstring::npos) {
        return L"Media";
    }
    if (lower_name.find(L"game") != std::wstring::npos ||
        lower_name.find(L"steam") != std::wstring::npos ||
        lower_name.find(L"minecraft") != std::wstring::npos ||
        lower_name.find(L"roblox") != std::wstring::npos ||
        lower_sub.find(L"game") != std::wstring::npos) {
        return L"Games";
    }
    if (lower_name.find(L"notepad") != std::wstring::npos ||
        lower_name.find(L"calculator") != std::wstring::npos ||
        lower_name.find(L"paint") != std::wstring::npos ||
        lower_name.find(L"explorer") != std::wstring::npos ||
        lower_name.find(L"word") != std::wstring::npos ||
        lower_name.find(L"excel") != std::wstring::npos ||
        lower_sub.find(L"access") != std::wstring::npos ||
        lower_sub.find(L"system") != std::wstring::npos ||
        lower_sub.find(L"tool") != std::wstring::npos) {
        return L"Utilities";
    }
    return L"General";
}

// ---------------------------------------------------------------------------
// System tray app discovery
// Enumerates processes that own icons in the Windows system tray (notification
// area) via the Shell_TrayWnd toolbar button list.  These are apps like
// WhatsApp, Discord, Cloudflare WARP, etc. that normally have no visible main
// window that would show up in a standard EnumWindows scan.
// ---------------------------------------------------------------------------

struct TrayIconInfo {
    std::wstring name;
    std::wstring exe_path;
    HWND hwnd;
};

// Read the text of a toolbar button from an out-of-process toolbar via
// SendMessage + ReadProcessMemory.  Returns the button tooltip string.
static std::wstring read_toolbar_tooltip(HWND toolbar, DWORD remote_pid, int btn_index) {
    HANDLE hProc = OpenProcess(PROCESS_VM_READ | PROCESS_QUERY_INFORMATION, FALSE, remote_pid);
    if (!hProc) return {};

    // Allocate enough remote memory for TBBUTTON + tooltip buffer
    constexpr size_t BUF_SIZE = 512;
    void* remote_buf = VirtualAllocEx(hProc, nullptr, BUF_SIZE, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    std::wstring result;

    if (remote_buf) {
        TBBUTTONINFOW tbi{};
        tbi.cbSize = sizeof(tbi);
        tbi.dwMask = TBIF_TEXT | TBIF_BYINDEX;
        tbi.pszText = static_cast<LPWSTR>(remote_buf);
        tbi.cchText = static_cast<int>(BUF_SIZE / sizeof(wchar_t));

        // Write the TBBUTTONINFOW into remote process memory
        void* remote_tbi = static_cast<uint8_t*>(remote_buf) + 256;
        if (WriteProcessMemory(hProc, remote_tbi, &tbi, sizeof(tbi), nullptr)) {
            // Ask the toolbar for button info; text lands in remote_buf
            SendMessage(toolbar, TB_GETBUTTONINFOW, static_cast<WPARAM>(btn_index),
                        reinterpret_cast<LPARAM>(remote_tbi));

            wchar_t local_text[256]{};
            ReadProcessMemory(hProc, remote_buf, local_text, sizeof(local_text) - sizeof(wchar_t), nullptr);
            result = local_text;
        }
        VirtualFreeEx(hProc, remote_buf, 0, MEM_RELEASE);
    }
    CloseHandle(hProc);
    return result;
}

static std::wstring exe_path_for_pid(DWORD pid) {
    HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!h) return {};
    wchar_t buf[32768]{};
    DWORD sz = static_cast<DWORD>(std::size(buf));
    QueryFullProcessImageNameW(h, 0, buf, &sz);
    CloseHandle(h);
    return buf;
}

static std::vector<TrayIconInfo> enumerate_tray_icons() {
    std::vector<TrayIconInfo> result;

    // Find the taskbar toolbar that holds notification-area (tray) icons.
    HWND tray_wnd   = FindWindowW(L"Shell_TrayWnd", nullptr);
    HWND notify_wnd = tray_wnd ? FindWindowExW(tray_wnd, nullptr, L"TrayNotifyWnd", nullptr) : nullptr;
    HWND sys_pager  = notify_wnd ? FindWindowExW(notify_wnd, nullptr, L"SysPager", nullptr) : nullptr;
    HWND toolbar    = sys_pager  ? FindWindowExW(sys_pager, nullptr, L"ToolbarWindow32", nullptr) : nullptr;

    if (!toolbar) return result;

    DWORD remote_pid = 0;
    GetWindowThreadProcessId(toolbar, &remote_pid);
    if (!remote_pid) return result;

    int btn_count = static_cast<int>(SendMessage(toolbar, TB_BUTTONCOUNT, 0, 0));

    HANDLE hProc = OpenProcess(PROCESS_VM_READ | PROCESS_QUERY_INFORMATION | PROCESS_VM_OPERATION, FALSE, remote_pid);
    if (!hProc) return result;

    // Allocate remote buffer for TBBUTTON struct
    constexpr size_t BUF = 256;
    void* remote_btn = VirtualAllocEx(hProc, nullptr, BUF, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);

    if (remote_btn) {
        for (int i = 0; i < btn_count; ++i) {
            SendMessage(toolbar, TB_GETBUTTON, static_cast<WPARAM>(i), reinterpret_cast<LPARAM>(remote_btn));

            // The TBBUTTON iString field holds a pointer to the tooltip string in
            // the remote process.  We also care about dwData which is a
            // NOTIFYICONIDENTIFIER-like structure pointer.  Read raw bytes.
            uint8_t local_btn[BUF]{};
            SIZE_T read_bytes = 0;
            if (!ReadProcessMemory(hProc, remote_btn, local_btn, BUF, &read_bytes) || read_bytes < 64) continue;

            // On x64 the TBBUTTON layout is:
            //   0: iBitmap (int, 4 bytes)
            //   4: idCommand (int, 4 bytes)
            //   8: fsState (byte), fsStyle (byte), bReserved[2]
            //  12: dwData (DWORD_PTR, 8 bytes on x64)
            //  20: iString (INT_PTR, 8 bytes on x64)
            DWORD_PTR dw_data  = *reinterpret_cast<DWORD_PTR*>(local_btn + 12);
            INT_PTR   i_string = *reinterpret_cast<INT_PTR*>(local_btn + 20);

            // dwData encodes the HWND of the owner window in the lower 32 bits
            HWND owner_hwnd = reinterpret_cast<HWND>(static_cast<uintptr_t>(dw_data & 0xFFFFFFFF));

            // Read tooltip string (iString is either an index or a remote pointer)
            std::wstring tooltip;
            if (i_string > 0xFFFF) {
                // It's a remote pointer to a wide string
                wchar_t tip_buf[256]{};
                SIZE_T tr = 0;
                ReadProcessMemory(hProc, reinterpret_cast<void*>(i_string), tip_buf, sizeof(tip_buf) - 2, &tr);
                if (tr > 0) tooltip = tip_buf;
            }

            if (tooltip.empty()) {
                // Fall back to the TB_GETBUTTONINFOW approach
                tooltip = read_toolbar_tooltip(toolbar, remote_pid, i);
            }

            // Clean up the tooltip: trim null chars and trailing spaces
            while (!tooltip.empty() && (tooltip.back() == L'\0' || iswspace(tooltip.back()))) {
                tooltip.pop_back();
            }
            // Tooltips often have a newline followed by status text; keep first line only
            size_t nl = tooltip.find(L'\n');
            if (nl != std::wstring::npos) tooltip.resize(nl);

            if (tooltip.empty()) continue;

            // Get the PID of the owner window to find its exe path
            DWORD owner_pid = 0;
            if (owner_hwnd && IsWindow(owner_hwnd)) {
                GetWindowThreadProcessId(owner_hwnd, &owner_pid);
            }
            if (!owner_pid) owner_pid = remote_pid;

            std::wstring exe = exe_path_for_pid(owner_pid);
            result.push_back({tooltip, exe, owner_hwnd});
        }
        VirtualFreeEx(hProc, remote_btn, 0, MEM_RELEASE);
    }

    CloseHandle(hProc);

    // Also scan the overflow tray ("hidden" icons) - same structure, different window
    HWND overflow = FindWindowW(L"NotifyIconOverflowWindow", nullptr);
    if (overflow) {
        HWND overflow_toolbar = FindWindowExW(overflow, nullptr, L"ToolbarWindow32", nullptr);
        if (overflow_toolbar) {
            DWORD ov_pid = 0;
            GetWindowThreadProcessId(overflow_toolbar, &ov_pid);
            if (ov_pid) {
                int ov_count = static_cast<int>(SendMessage(overflow_toolbar, TB_BUTTONCOUNT, 0, 0));
                HANDLE hOvProc = OpenProcess(PROCESS_VM_READ | PROCESS_QUERY_INFORMATION | PROCESS_VM_OPERATION, FALSE, ov_pid);
                if (hOvProc) {
                    void* ov_btn = VirtualAllocEx(hOvProc, nullptr, BUF, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
                    if (ov_btn) {
                        for (int i = 0; i < ov_count; ++i) {
                            SendMessage(overflow_toolbar, TB_GETBUTTON, static_cast<WPARAM>(i), reinterpret_cast<LPARAM>(ov_btn));
                            uint8_t local_ov[BUF]{};
                            SIZE_T rb = 0;
                            if (!ReadProcessMemory(hOvProc, ov_btn, local_ov, BUF, &rb) || rb < 64) continue;
                            DWORD_PTR dw = *reinterpret_cast<DWORD_PTR*>(local_ov + 12);
                            INT_PTR   is = *reinterpret_cast<INT_PTR*>(local_ov + 20);
                            HWND owner  = reinterpret_cast<HWND>(static_cast<uintptr_t>(dw & 0xFFFFFFFF));
                            std::wstring tip;
                            if (is > 0xFFFF) {
                                wchar_t tb[256]{};
                                SIZE_T tr = 0;
                                ReadProcessMemory(hOvProc, reinterpret_cast<void*>(is), tb, sizeof(tb)-2, &tr);
                                if (tr > 0) tip = tb;
                            }
                            while (!tip.empty() && (tip.back() == L'\0' || iswspace(tip.back()))) tip.pop_back();
                            size_t nl = tip.find(L'\n');
                            if (nl != std::wstring::npos) tip.resize(nl);
                            if (tip.empty()) continue;
                            DWORD owner_pid = 0;
                            if (owner && IsWindow(owner)) GetWindowThreadProcessId(owner, &owner_pid);
                            if (!owner_pid) owner_pid = ov_pid;
                            std::wstring exe = exe_path_for_pid(owner_pid);
                            result.push_back({tip, exe, owner});
                        }
                        VirtualFreeEx(hOvProc, ov_btn, 0, MEM_RELEASE);
                    }
                    CloseHandle(hOvProc);
                }
            }
        }
    }

    return result;
}

std::vector<InstalledAppInfo> enumerate_installed_apps() {
    std::vector<InstalledAppInfo> apps;
    // Shell-link resolution uses COM. This function is called from a JNI
    // thread, which has no guarantee that the JVM initialized COM for us.
    // RPC_E_CHANGED_MODE means the caller already initialized another
    // apartment; COM remains usable there and must not be uninitialized here.
    ComApartment com;
    if (!com.available()) return apps;

    std::set<std::wstring> seen_paths;
    std::set<std::wstring> seen_names;

    auto add_app = [&](const std::wstring& name, const std::wstring& exe_path,
                       const std::wstring& shortcut_path, const std::wstring& category) {
        if (name.empty() || (exe_path.empty() && shortcut_path.empty())) return;
        std::wstring lower_name = to_lower(name);
        if (seen_names.count(lower_name)) return;
        std::wstring lower_exe = to_lower(exe_path);
        // Many packaged Start Menu shortcuts point to explorer.exe with an
        // AppsFolder argument. Keep each shortcut instead of deduping them as Explorer.
        bool shared_shell_host = !shortcut_path.empty() &&
            to_lower(fs::path(exe_path).filename().wstring()) == L"explorer.exe";
        if (!lower_exe.empty() && !shared_shell_host && seen_paths.count(lower_exe)) return;
        if (!lower_exe.empty() && !shared_shell_host) seen_paths.insert(lower_exe);
        if (!shortcut_path.empty()) seen_paths.insert(to_lower(shortcut_path));
        seen_names.insert(std::move(lower_name));
        apps.push_back({name, exe_path, category, shortcut_path});
    };

    auto scan_dir = [&](const fs::path& base_dir) {
        if (!fs::exists(base_dir)) return;
        std::error_code ec;
        for (const auto& entry : fs::recursive_directory_iterator(base_dir, fs::directory_options::skip_permission_denied, ec)) {
            if (ec) break;
            if (!entry.is_regular_file(ec)) continue;
            if (to_lower(entry.path().extension().wstring()) != L".lnk") continue;

            std::wstring name = entry.path().stem().wstring();
            std::wstring lower_name = to_lower(name);
            if (lower_name.find(L"uninstall") != std::wstring::npos ||
                lower_name.find(L"help") != std::wstring::npos ||
                lower_name.find(L"readme") != std::wstring::npos) {
                continue;
            }

            std::wstring target = resolve_shortcut(entry.path());
            std::wstring subfolder = entry.path().parent_path().filename().wstring();
            std::wstring category = categorize_app(name, target, subfolder);
            // Keep the actual .lnk as the launch source. It preserves shortcut
            // arguments and supports packaged/Store apps whose target is not an EXE.
            add_app(name, target, entry.path().wstring(), category);
        }
    };

    auto scan_app_paths = [&](HKEY root, const wchar_t* key_path, REGSAM view) {
        HKEY key{};
        if (RegOpenKeyExW(root, key_path, 0, KEY_READ | view, &key) != ERROR_SUCCESS) return;
        for (DWORD index = 0;; ++index) {
            wchar_t child_name[512]{};
            DWORD child_len = static_cast<DWORD>(std::size(child_name));
            LONG result = RegEnumKeyExW(key, index, child_name, &child_len, nullptr, nullptr, nullptr, nullptr);
            if (result != ERROR_SUCCESS) break;
            HKEY child{};
            if (RegOpenKeyExW(key, child_name, 0, KEY_READ, &child) != ERROR_SUCCESS) continue;
            std::wstring exe = executable_from_registry_value(read_registry_string(child, nullptr));
            RegCloseKey(child);
            std::error_code ec;
            if (to_lower(fs::path(exe).extension().wstring()) != L".exe" || !fs::is_regular_file(exe, ec)) continue;
            std::wstring name = fs::path(child_name).stem().wstring();
            add_app(name, exe, L"", categorize_app(name, exe, L""));
        }
        RegCloseKey(key);
    };

    auto scan_uninstall = [&](HKEY root, const wchar_t* key_path, REGSAM view) {
        HKEY key{};
        if (RegOpenKeyExW(root, key_path, 0, KEY_READ | view, &key) != ERROR_SUCCESS) return;
        for (DWORD index = 0;; ++index) {
            wchar_t child_name[512]{};
            DWORD child_len = static_cast<DWORD>(std::size(child_name));
            LONG result = RegEnumKeyExW(key, index, child_name, &child_len, nullptr, nullptr, nullptr, nullptr);
            if (result != ERROR_SUCCESS) break;
            HKEY child{};
            if (RegOpenKeyExW(key, child_name, 0, KEY_READ, &child) != ERROR_SUCCESS) continue;
            std::wstring name = read_registry_string(child, L"DisplayName");
            std::wstring icon = read_registry_string(child, L"DisplayIcon");
            RegCloseKey(child);
            if (name.empty()) continue;
            std::wstring exe = executable_from_registry_value(icon);
            std::error_code ec;
            if (to_lower(fs::path(exe).extension().wstring()) != L".exe" || !fs::is_regular_file(exe, ec)) continue;
            add_app(name, exe, L"", categorize_app(name, exe, L""));
        }
        RegCloseKey(key);
    };

    PWSTR common_path = nullptr;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_CommonPrograms, 0, nullptr, &common_path))) {
        scan_dir(common_path);
        CoTaskMemFree(common_path);
    }

    PWSTR user_path = nullptr;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_Programs, 0, nullptr, &user_path))) {
        scan_dir(user_path);
        CoTaskMemFree(user_path);
    }

    // Include registered desktop apps that have no Start Menu shortcut.
    for (REGSAM view : {KEY_WOW64_64KEY, KEY_WOW64_32KEY}) {
        scan_app_paths(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\App Paths", view);
        scan_app_paths(HKEY_LOCAL_MACHINE, L"Software\\Microsoft\\Windows\\CurrentVersion\\App Paths", view);
        scan_uninstall(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", view);
        scan_uninstall(HKEY_LOCAL_MACHINE, L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", view);
    }

    // Add standard Windows utilities if not already present
    auto add_builtin = [&](const wchar_t* name, const wchar_t* rel_exe, const wchar_t* cat) {
        wchar_t win_dir[MAX_PATH];
        if (GetWindowsDirectoryW(win_dir, MAX_PATH)) {
            fs::path p = fs::path(win_dir) / rel_exe;
            std::wstring lower_p = to_lower(p.wstring());
            if (!seen_paths.count(lower_p) && fs::exists(p)) {
                add_app(name, p.wstring(), L"", cat);
            }
        }
    };

    add_builtin(L"File Explorer", L"explorer.exe", L"Utilities");
    add_builtin(L"Notepad", L"System32\\notepad.exe", L"Utilities");
    add_builtin(L"Calculator", L"System32\\calc.exe", L"Utilities");
    add_builtin(L"Task Manager", L"System32\\Taskmgr.exe", L"Utilities");

    // Add system tray apps (WhatsApp, Discord, Cloudflare WARP, etc.)
    // that don't appear in Start Menu or registry but live in the taskbar tray.
    try {
        auto tray_icons = enumerate_tray_icons();
        for (const auto& ti : tray_icons) {
            if (ti.name.empty()) continue;
            std::wstring lower_name = to_lower(ti.name);
            // Skip generic OS tray items
            if (lower_name.find(L"action center") != std::wstring::npos ||
                lower_name.find(L"network") != std::wstring::npos ||
                lower_name.find(L"volume") != std::wstring::npos ||
                lower_name.find(L"battery") != std::wstring::npos ||
                lower_name.find(L"clock") != std::wstring::npos ||
                lower_name.find(L"language") != std::wstring::npos ||
                lower_name.find(L"ime") != std::wstring::npos ||
                lower_name.find(L"input indicator") != std::wstring::npos ||
                lower_name.find(L"windows security") != std::wstring::npos ||
                lower_name.find(L"show hidden icons") != std::wstring::npos) {
                continue;
            }
            std::wstring cat = categorize_app(ti.name, ti.exe_path, L"");
            add_app(ti.name, ti.exe_path, L"", cat);
        }
    } catch (...) {
        // Tray enumeration is best-effort; don't fail the whole function
    }

    std::sort(apps.begin(), apps.end(), [](const auto& a, const auto& b) {
        return a.name < b.name;
    });

    return apps;
}

std::vector<uint8_t> extract_icon_rgba(const std::wstring& path, HWND hwnd, int size) {
    std::vector<uint8_t> result;
    HICON hIcon = nullptr;

    if (hwnd != nullptr && IsWindow(hwnd)) {
        SendMessageTimeoutW(hwnd, WM_GETICON, ICON_SMALL, 0, SMTO_ABORTIFHUNG, 50, reinterpret_cast<PDWORD_PTR>(&hIcon));
        if (!hIcon) {
            SendMessageTimeoutW(hwnd, WM_GETICON, ICON_BIG, 0, SMTO_ABORTIFHUNG, 50, reinterpret_cast<PDWORD_PTR>(&hIcon));
        }
        if (!hIcon) {
            hIcon = reinterpret_cast<HICON>(GetClassLongPtrW(hwnd, GCLP_HICONSM));
        }
        if (!hIcon) {
            hIcon = reinterpret_cast<HICON>(GetClassLongPtrW(hwnd, GCLP_HICON));
        }
    }

    if (!hIcon && !path.empty()) {
        SHFILEINFOW sfi{};
        if (SHGetFileInfoW(path.c_str(), 0, &sfi, sizeof(sfi), SHGFI_ICON | SHGFI_SMALLICON) && sfi.hIcon) {
            hIcon = sfi.hIcon;
        }
    }

    if (!hIcon) {
        // Return 32x32 transparent
        result.resize(size * size * 4, 0);
        return result;
    }

    ICONINFO ii{};
    if (GetIconInfo(hIcon, &ii)) {
        HDC hdc = GetDC(nullptr);
        HDC memDC = CreateCompatibleDC(hdc);
        BITMAPINFO bmi{};
        bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
        bmi.bmiHeader.biWidth = size;
        bmi.bmiHeader.biHeight = -size; // top-down
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = BI_RGB;

        void* bits = nullptr;
        HBITMAP dib = CreateDIBSection(hdc, &bmi, DIB_RGB_COLORS, &bits, nullptr, 0);
        if (dib && bits) {
            HGDIOBJ oldBmp = SelectObject(memDC, dib);
            DrawIconEx(memDC, 0, 0, hIcon, size, size, 0, nullptr, DI_NORMAL);
            SelectObject(memDC, oldBmp);

            uint8_t* p = static_cast<uint8_t*>(bits);
            result.resize(size * size * 4);
            bool hasAlpha = false;
            for (int i = 0; i < size * size; ++i) {
                if (p[i * 4 + 3] != 0) {
                    hasAlpha = true;
                    break;
                }
            }
            for (int i = 0; i < size * size; ++i) {
                uint8_t b = p[i * 4];
                uint8_t g = p[i * 4 + 1];
                uint8_t r = p[i * 4 + 2];
                uint8_t a = hasAlpha ? p[i * 4 + 3] : ((r == 0 && g == 0 && b == 0) ? 0 : 255);
                result[i * 4] = r;
                result[i * 4 + 1] = g;
                result[i * 4 + 2] = b;
                result[i * 4 + 3] = a;
            }
            DeleteObject(dib);
        }
        DeleteDC(memDC);
        ReleaseDC(nullptr, hdc);
        if (ii.hbmColor) DeleteObject(ii.hbmColor);
        if (ii.hbmMask) DeleteObject(ii.hbmMask);
    }

    DestroyIcon(hIcon);
    return result;
}

}
