#pragma once
#include <windows.h>
#include <string>
#include <vector>
#include <cstdint>

namespace wc {
struct InstalledAppInfo {
    std::wstring name;
    std::wstring exe_path;
    std::wstring category;
    std::wstring shortcut_path;
};

std::vector<InstalledAppInfo> enumerate_installed_apps();
std::vector<uint8_t> extract_icon_rgba(const std::wstring& path, HWND hwnd, int size = 32);
}
