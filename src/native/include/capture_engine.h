#pragma once
#include <windows.h>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
namespace wc {
struct WindowInfo { HWND hwnd; std::wstring title; int width; int height; };
struct OwnedWindowInfo { HWND hwnd; std::wstring title; int width; int height; HWND owner; };
std::vector<WindowInfo> enumerate_windows();
std::vector<OwnedWindowInfo> enumerate_owned_popups(const std::vector<HWND>& owners);
// Session methods update/bind/unbind/destroy must only be called from the active WGL render context thread.
class CaptureEngine {
public:
    explicit CaptureEngine(HWND hwnd);
    ~CaptureEngine();
    CaptureEngine(const CaptureEngine&) = delete;
    CaptureEngine& operator=(const CaptureEngine&) = delete;
    // 1 = newly copied, 0 = no new frame. Throws on interop errors.
    int update(unsigned int gl_texture);
    void unlock();
    // Release WGL objects while the owning OpenGL context is current. After this,
    // destruction can safely finish on a background thread.
    void detach_gl();
    int width() const;
    int height() const;
private:
    struct Impl;
    std::shared_ptr<Impl> impl_;
};
}
