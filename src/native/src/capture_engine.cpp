#include "capture_engine.h"
#include "win32_helpers.h"
#include <d3d11.h>
#include <d3d11_4.h>
#include <dxgi.h>
#include <dwmapi.h>
#include <gl/GL.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>
#include <mutex>
#include <stdexcept>
#include <utility>
#include <algorithm>
#include <cwctype>
#include <string>

namespace wc {
namespace WGC = winrt::Windows::Graphics::Capture;
namespace D3D = winrt::Windows::Graphics::DirectX::Direct3D11;
using winrt::com_ptr;
// WGL_NV_DX_interop (WGL headers are driver-extension specific, so resolve at runtime).
using OpenDevice = HANDLE (WINAPI*)(void*);
using CloseDevice = BOOL (WINAPI*)(HANDLE);
using RegisterObject = HANDLE (WINAPI*)(HANDLE, void*, GLuint, GLenum, GLenum);
using UnregisterObject = BOOL (WINAPI*)(HANDLE, HANDLE);
using LockObjects = BOOL (WINAPI*)(HANDLE, GLint, HANDLE*);
using UnlockObjects = BOOL (WINAPI*)(HANDLE, GLint, HANDLE*);
constexpr GLenum ACCESS_READ_ONLY = 0x0000;

// Direct3D 11 operations can throw Win32 SEH exceptions (e.g. 0xC0000005 ACCESS_VIOLATION)
// when an application window suddenly closes, resizes, or resets its DirectX device.
// C++ catch (...) does NOT catch Win32 SEH exceptions on MSVC; we use dedicated __try/__except wrappers.
static bool safe_copy_resource(ID3D11DeviceContext* ctx, ID3D11Resource* dst, ID3D11Resource* src) {
    if (!ctx || !dst || !src) return false;
    __try {
        ctx->CopyResource(dst, src);
        return true;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

static bool safe_flush(ID3D11DeviceContext* ctx) {
    if (!ctx) return false;
    __try {
        ctx->Flush();
        return true;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

static bool safe_unlock(UnlockObjects unlock_fn, HANDLE wgl_dev, HANDLE wgl_obj) {
    if (!unlock_fn || !wgl_dev || !wgl_obj) return false;
    __try {
        return unlock_fn(wgl_dev, 1, &wgl_obj) != FALSE;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

static bool safe_lock(LockObjects lock_fn, HANDLE wgl_dev, HANDLE wgl_obj) {
    if (!lock_fn || !wgl_dev || !wgl_obj) return false;
    __try {
        return lock_fn(wgl_dev, 1, &wgl_obj) != FALSE;
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return false;
    }
}

std::vector<WindowInfo> enumerate_windows() {
    std::vector<WindowInfo> result;
    EnumWindows([](HWND hwnd, LPARAM arg) -> BOOL {
        if (!IsWindow(hwnd) || !IsWindowVisible(hwnd)) return TRUE;
        HWND owner = GetWindow(hwnd, GW_OWNER);
        LONG_PTR exStyle = GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
        if (owner && !(exStyle & WS_EX_APPWINDOW)) return TRUE;
        if (exStyle & WS_EX_TOOLWINDOW) return TRUE;

        BOOL cloaked = FALSE;
        DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, &cloaked, sizeof(cloaked));
        if (cloaked) return TRUE;

        int size = GetWindowTextLengthW(hwnd);
        if (size <= 0 || size > 4096) return TRUE;
        std::wstring title(size + 1, L'\0');
        int count = GetWindowTextW(hwnd, title.data(), size + 1);
        if (count <= 0) return TRUE;
        title.resize(count);

        RECT rect{};
        GetClientRect(hwnd, &rect);
        int w = rect.right, h = rect.bottom;
        if (IsIconic(hwnd)) {
            WINDOWPLACEMENT wp{sizeof(wp)};
            if (GetWindowPlacement(hwnd, &wp)) {
                w = wp.rcNormalPosition.right - wp.rcNormalPosition.left;
                h = wp.rcNormalPosition.bottom - wp.rcNormalPosition.top;
            }
        }
        if (w < 120 || h < 80) return TRUE;

        // Filter out AMD DVR overlays, Radeon overlays, GeForce overlays, and internal shell windows
        std::wstring lower = title;
        std::transform(lower.begin(), lower.end(), lower.begin(), [](wchar_t c) { return static_cast<wchar_t>(std::towlower(c)); });
        if (lower.find(L"amd dvr") != std::wstring::npos ||
            lower.find(L"amd:dvr") != std::wstring::npos ||
            lower.find(L"radeon") != std::wstring::npos ||
            lower.find(L"overlay") != std::wstring::npos ||
            lower.find(L"geforce") != std::wstring::npos ||
            lower.find(L"nvidia share") != std::wstring::npos ||
            lower.find(L"program manager") != std::wstring::npos ||
            lower.find(L"desktopwindowxamlsource") != std::wstring::npos ||
            lower.find(L"msctfime") != std::wstring::npos ||
            lower.find(L"windows input experience") != std::wstring::npos) {
            return TRUE;
        }

        reinterpret_cast<std::vector<WindowInfo>*>(arg)->push_back({hwnd, std::move(title), w, h});
        return TRUE;
    }, reinterpret_cast<LPARAM>(&result));
    return result;
}

std::vector<OwnedWindowInfo> enumerate_owned_popups(const std::vector<HWND>& owners) {
    std::vector<OwnedWindowInfo> result;
    if (owners.empty()) return result;
    struct Context { const std::vector<HWND>* owners; std::vector<OwnedWindowInfo>* result; } context{&owners, &result};
    EnumWindows([](HWND hwnd, LPARAM arg) -> BOOL {
        auto& ctx = *reinterpret_cast<Context*>(arg);
        if (!IsWindowVisible(hwnd) || IsIconic(hwnd)) return TRUE;
        HWND owner = GetWindow(hwnd, GW_OWNER);
        if (!owner) return TRUE;
        HWND root_owner = GetAncestor(hwnd, GA_ROOTOWNER);
        HWND matched_owner = nullptr;
        for (HWND candidate : *ctx.owners) {
            if (candidate == owner || candidate == root_owner || candidate == GetAncestor(owner, GA_ROOT)) {
                matched_owner = candidate;
                break;
            }
        }
        if (!matched_owner) return TRUE;

        // Ordinary owned dialogs can be hosted by a helper process (for
        // example, a browser's file dialog). Allow same-session helpers, while
        // keeping Windows credential and elevation brokers on the desktop.
        DWORD owner_pid = 0, popup_pid = 0;
        GetWindowThreadProcessId(matched_owner, &owner_pid);
        GetWindowThreadProcessId(hwnd, &popup_pid);
        if (!owner_pid || !popup_pid) return TRUE;
        if (owner_pid != popup_pid) {
            DWORD owner_session = 0, popup_session = 0;
            if (!ProcessIdToSessionId(owner_pid, &owner_session) ||
                !ProcessIdToSessionId(popup_pid, &popup_session) || owner_session != popup_session) return TRUE;

            HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, popup_pid);
            if (!process) return TRUE;
            std::wstring image(32768, L'\0');
            DWORD image_size = static_cast<DWORD>(image.size());
            BOOL got_image = QueryFullProcessImageNameW(process, 0, image.data(), &image_size);
            CloseHandle(process);
            if (!got_image) return TRUE;
            image.resize(image_size);
            std::wstring executable = image.substr(image.find_last_of(L"\\/") == std::wstring::npos
                ? 0 : image.find_last_of(L"\\/") + 1);
            std::transform(executable.begin(), executable.end(), executable.begin(),
                [](wchar_t c) { return static_cast<wchar_t>(std::towlower(c)); });
            constexpr const wchar_t* blocked_processes[]{
                L"credentialuibroker.exe", L"credentialui.exe", L"consent.exe",
                L"logonui.exe", L"lockapp.exe", L"securityhealthhost.exe"
            };
            for (const wchar_t* blocked : blocked_processes) {
                if (executable == blocked) return TRUE;
            }
        }

        BOOL cloaked = FALSE;
        DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, &cloaked, sizeof(cloaked));
        if (cloaked) return TRUE;
        int title_size = GetWindowTextLengthW(hwnd);
        if (title_size <= 0 || title_size > 4096) return TRUE;
        std::wstring title(static_cast<size_t>(title_size) + 1, L'\0');
        int copied = GetWindowTextW(hwnd, title.data(), title_size + 1);
        if (copied <= 0) return TRUE;
        title.resize(static_cast<size_t>(copied));
        std::wstring lower = title;
        std::transform(lower.begin(), lower.end(), lower.begin(), [](wchar_t c) { return static_cast<wchar_t>(std::towlower(c)); });
        constexpr const wchar_t* blocked_titles[]{
            L"windows security", L"passkey", L"credential", L"windows hello",
            L"authentication", L"authenticate", L"sign in", L"sign-in", L"consent"
        };
        for (const wchar_t* blocked : blocked_titles) {
            if (lower.find(blocked) != std::wstring::npos) return TRUE;
        }

        RECT rect{};
        if (!GetClientRect(hwnd, &rect) || rect.right < 96 || rect.bottom < 48) return TRUE;
        ctx.result->push_back({hwnd, std::move(title), rect.right, rect.bottom, matched_owner});
        return TRUE;
    }, reinterpret_cast<LPARAM>(&context));
    return result;
}

struct CaptureEngine::Impl {
    HWND hwnd{};
    com_ptr<ID3D11Device> device;
    com_ptr<ID3D11DeviceContext> immediate;
    com_ptr<ID3D11Multithread> multithread;
    D3D::IDirect3DDevice winrt_device{nullptr};
    WGC::GraphicsCaptureItem item{nullptr};
    WGC::Direct3D11CaptureFramePool pool{nullptr};
    WGC::GraphicsCaptureSession session{nullptr};
    winrt::event_token token{};
    std::atomic<bool> closing{false};
    std::mutex mutex;
    com_ptr<ID3D11Texture2D> pending;
    com_ptr<ID3D11Texture2D> target;
    unsigned long long serial = 0, displayed = 0;
    int pending_w = 0, pending_h = 0, target_w = 0, target_h = 0;
    DXGI_FORMAT pending_format = DXGI_FORMAT_B8G8R8A8_UNORM;
    DXGI_FORMAT target_format = DXGI_FORMAT_B8G8R8A8_UNORM;
    OpenDevice open{}; CloseDevice close{}; RegisterObject reg{}; UnregisterObject unreg{};
    LockObjects lock{}; UnlockObjects unlock{};
    HANDLE wgl_device{}, wgl_object{};
    GLuint texture = 0;
    bool locked = false;

    winrt::Windows::Graphics::SizeInt32 pool_size{};

    static com_ptr<ID3D11Texture2D> make_texture(ID3D11Device* d, int w, int h, DXGI_FORMAT fmt = DXGI_FORMAT_B8G8R8A8_UNORM) {
        if (!d || w <= 0 || h <= 0) return nullptr;
        D3D11_TEXTURE2D_DESC desc{};
        desc.Width = static_cast<UINT>(w); desc.Height = static_cast<UINT>(h);
        desc.MipLevels = 1; desc.ArraySize = 1; desc.Format = fmt;
        desc.SampleDesc.Count = 1; desc.Usage = D3D11_USAGE_DEFAULT;
        desc.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;
        com_ptr<ID3D11Texture2D> out;
        HRESULT hr = d->CreateTexture2D(&desc, nullptr, out.put());
        if (FAILED(hr)) return nullptr;
        return out;
    }
    void arrive(WGC::Direct3D11CaptureFramePool const& sender) {
        if (closing.load(std::memory_order_acquire)) return;
        if (!device || FAILED(device->GetDeviceRemovedReason())) return;
        try {
            auto frame = sender.TryGetNextFrame();
            if (!frame) return;
            auto size = frame.ContentSize();
            if (size.Width > 0 && size.Height > 0 && size.Width <= 16384 && size.Height <= 16384) {
                auto access = frame.Surface().as<Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
                com_ptr<ID3D11Texture2D> source;
                HRESULT hr = access->GetInterface(__uuidof(ID3D11Texture2D), source.put_void());
                if (SUCCEEDED(hr) && source) {
                    D3D11_TEXTURE2D_DESC source_desc{};
                    source->GetDesc(&source_desc);
                    // During resize, WGC can report new content dimensions while the pool
                    // still supplies an old-sized texture. Recreate the pool; skip that frame.
                    if (source_desc.Width == static_cast<UINT>(size.Width) &&
                        source_desc.Height == static_cast<UINT>(size.Height)) {
                        std::lock_guard guard(mutex);
                        if (!closing.load(std::memory_order_acquire)) {
                            if (device && SUCCEEDED(device->GetDeviceRemovedReason())) {
                                if (pending_w != size.Width || pending_h != size.Height || pending_format != source_desc.Format) {
                                    pending = make_texture(device.get(), size.Width, size.Height, source_desc.Format);
                                    pending_w = size.Width;
                                    pending_h = size.Height;
                                    pending_format = source_desc.Format;
                                }
                                if (pending && source) {
                                    if (safe_copy_resource(immediate.get(), pending.get(), source.get())) {
                                        ++serial;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            frame.Close();
            if (closing.load(std::memory_order_acquire)) return;
            if (pool_size.Width != size.Width || pool_size.Height != size.Height) {
                if (winrt_device && SUCCEEDED(device->GetDeviceRemovedReason())) {
                    sender.Recreate(winrt_device, winrt::Windows::Graphics::DirectX::DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, size);
                    pool_size = size;
                }
            }
        } catch (...) {
            // Frame pool can close/device can be lost asynchronously. Next frame may recover.
        }
    }
    void load_wgl() {
        require(wglGetCurrentContext() != nullptr, "GL render context required on create/update");
        open = reinterpret_cast<OpenDevice>(wglGetProcAddress("wglDXOpenDeviceNV"));
        close = reinterpret_cast<CloseDevice>(wglGetProcAddress("wglDXCloseDeviceNV"));
        reg = reinterpret_cast<RegisterObject>(wglGetProcAddress("wglDXRegisterObjectNV"));
        unreg = reinterpret_cast<UnregisterObject>(wglGetProcAddress("wglDXUnregisterObjectNV"));
        lock = reinterpret_cast<LockObjects>(wglGetProcAddress("wglDXLockObjectsNV"));
        unlock = reinterpret_cast<UnlockObjects>(wglGetProcAddress("wglDXUnlockObjectsNV"));
        require(open && close && reg && unreg && lock && unlock, "WGL_NV_DX_interop unavailable: OpenGL-compatible driver required");
        wgl_device = open(device.get());
        require(wgl_device != nullptr, "WGL/D3D adapter interop failed (different GPU or unsupported driver)");
    }
    bool release_binding() {
        if (locked) {
            safe_unlock(unlock, wgl_device, wgl_object);
            locked = false;
        }
        if (wgl_object) {
            if (wgl_device && unreg) {
                unreg(wgl_device, wgl_object);
            }
            wgl_object = nullptr;
        }
        target = nullptr;
        texture = 0;
        return true;
    }
};

CaptureEngine::CaptureEngine(HWND hwnd) : impl_(std::make_shared<Impl>()) {
    require(IsWindow(hwnd), "Window invalid");
    if (IsIconic(hwnd)) {
        ShowWindow(hwnd, SW_RESTORE);
        Sleep(60);
    }
    require(!IsIconic(hwnd), "Window is minimized");
    auto& x = *impl_;
    x.hwnd = hwnd;
    // Render thread may already have COM initialized as STA or MTA (e.g. by GLFW, OpenAL, or Windows shell).
    // CoInitializeEx returns S_OK, S_FALSE (already initialized MTA), or RPC_E_CHANGED_MODE (already initialized STA).
    // In all these cases COM is usable by WGC/DXGI; only fail if unexpected error.
    HRESULT co_hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(co_hr) && co_hr != RPC_E_CHANGED_MODE) {
        winrt::check_hresult(co_hr);
    }
    UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT;
    D3D_FEATURE_LEVEL actual{};
    D3D_FEATURE_LEVEL levels[]{ D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0 };
    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags,
        levels, 2, D3D11_SDK_VERSION, x.device.put(), &actual, x.immediate.put());
    if (hr == E_INVALIDARG) {
        hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags,
            &levels[1], 1, D3D11_SDK_VERSION, x.device.put(), &actual, x.immediate.put());
    }
    winrt::check_hresult(hr);
    if (x.immediate) {
        x.multithread = x.immediate.try_as<ID3D11Multithread>();
        if (x.multithread) {
            x.multithread->SetMultithreadProtected(TRUE);
        }
    }
    x.load_wgl();
    com_ptr<IDXGIDevice> dxgi = x.device.as<IDXGIDevice>();
    com_ptr<::IInspectable> inspectable;
    winrt::check_hresult(CreateDirect3D11DeviceFromDXGIDevice(dxgi.get(), inspectable.put()));
    x.winrt_device = inspectable.as<D3D::IDirect3DDevice>();
    auto factory = winrt::get_activation_factory<WGC::GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
    winrt::check_hresult(factory->CreateForWindow(hwnd, winrt::guid_of<WGC::GraphicsCaptureItem>(), winrt::put_abi(x.item)));
    auto size = x.item.Size();
    require(size.Width > 0 && size.Height > 0, "Invalid capture dimensions");
    x.pool_size = size;
    x.pool = WGC::Direct3D11CaptureFramePool::CreateFreeThreaded(x.winrt_device,
        winrt::Windows::Graphics::DirectX::DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, size);
    x.token = x.pool.FrameArrived([weak = std::weak_ptr<Impl>(impl_)](auto const& pool, auto const&) {
        if (auto self = weak.lock()) {
            self->arrive(pool);
        }
    });
    x.session = x.pool.CreateCaptureSession(x.item);
    // The mod draws a single reticle-aligned cursor in the Minecraft HUD.
    // Capturing the desktop cursor as well produces a second, unrelated pointer.
    x.session.IsCursorCaptureEnabled(false);
    x.session.StartCapture();
}
CaptureEngine::~CaptureEngine() {
    if (!impl_) return;
    auto& x = *impl_;
    x.closing.store(true, std::memory_order_release);
    // WGC Close can wait for an in-flight FrameArrived callback. This destructor
    // runs on a daemon cleanup thread, and each Close is guarded so driver/session
    // teardown errors never escape a C++ destructor into process termination.
    try { if (x.pool) x.pool.FrameArrived(x.token); } catch (...) { }
    try { if (x.session) x.session.Close(); } catch (...) { }
    try { if (x.pool) x.pool.Close(); } catch (...) { }
    std::lock_guard guard(x.mutex);
    x.release_binding();
    if (x.wgl_device) {
        if (x.close) x.close(x.wgl_device);
        x.wgl_device = nullptr;
    }
}
int CaptureEngine::update(unsigned int gl_texture) {
    auto& x = *impl_;
    require(wglGetCurrentContext() != nullptr, "update requires active OpenGL context");
    require(gl_texture != 0 && glIsTexture(gl_texture), "GL texture name must exist on render thread");
    if (!x.device || FAILED(x.device->GetDeviceRemovedReason())) {
        throw std::runtime_error("Direct3D 11 device removed or lost");
    }
    std::lock_guard guard(x.mutex);
    if (x.locked) {
        safe_unlock(x.unlock, x.wgl_device, x.wgl_object);
        x.locked = false;
    }
    if (!x.pending) return 0;
    if (x.target_w != x.pending_w || x.target_h != x.pending_h || x.target_format != x.pending_format || x.texture != gl_texture) {
        x.release_binding();
        x.target = Impl::make_texture(x.device.get(), x.pending_w, x.pending_h, x.pending_format);
        if (!x.target) return 0;
        x.target_w = x.pending_w; x.target_h = x.pending_h; x.target_format = x.pending_format;
        x.texture = gl_texture;
        x.wgl_object = x.reg(x.wgl_device, x.target.get(), gl_texture, GL_TEXTURE_2D, ACCESS_READ_ONLY);
        if (!x.wgl_object) return 0;
        x.displayed = 0;
    }
    bool changed = x.displayed != x.serial;
    if (changed) {
        if (!x.target || !x.pending) return 0;
        if (safe_copy_resource(x.immediate.get(), x.target.get(), x.pending.get())) {
            safe_flush(x.immediate.get());
            x.displayed = x.serial;
        }
    }
    if (safe_lock(x.lock, x.wgl_device, x.wgl_object)) {
        x.locked = true;  // GL may sample only until unlock(), even if no frame changed.
    }
    return changed ? 1 : 0;
}
void CaptureEngine::unlock() {
    auto& x = *impl_;
    std::lock_guard guard(x.mutex);
    if (x.locked) {
        safe_unlock(x.unlock, x.wgl_device, x.wgl_object);
        x.locked = false;
    }
}
void CaptureEngine::detach_gl() {
    auto& x = *impl_;
    require(wglGetCurrentContext() != nullptr, "GL interop teardown requires active OpenGL context");
    std::lock_guard guard(x.mutex);
    x.release_binding();
    if (x.wgl_device) {
        if (x.close) x.close(x.wgl_device);
        x.wgl_device = nullptr;
    }
}
int CaptureEngine::width() const { return impl_->target_w; }
int CaptureEngine::height() const { return impl_->target_h; }
}
