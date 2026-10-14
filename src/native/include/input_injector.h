#pragma once
#include <windows.h>
#include <vector>
#include <string>

namespace wc {
// Targeted and child-window-aware message routing
bool mouse(HWND hwnd, UINT message, int x, int y, unsigned int state);
bool key(HWND hwnd, UINT message, unsigned int vk, unsigned int scan, bool repeat);
bool text(HWND hwnd, unsigned int utf16_unit);
int get_cursor_shape(HWND hwnd, int x, int y);
bool drop_files(HWND hwnd, int x, int y, const std::vector<std::wstring>& files);
HWND detect_new_window(const std::vector<HWND>& existing, DWORD timeout_ms);
void wake_app(HWND hwnd);
}
