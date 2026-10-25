#include "capture_engine.h"
#include "input_injector.h"
#include "app_discovery.h"
#include "win32_helpers.h"
#include <jni.h>
#include <commdlg.h>
#include <shellapi.h>
#include <objbase.h>
#include <filesystem>
#include <memory>
#include <vector>
#include <type_traits>
#include <winrt/base.h>

namespace {

void throw_java(JNIEnv* env, const char* cls, const char* msg) {
    jclass type = env->FindClass(cls);
    if (type) env->ThrowNew(type, msg);
}

template <class Fn> auto checked(JNIEnv* env, Fn&& fn) -> decltype(fn()) {
    try { return fn(); }
    catch (const winrt::hresult_error& e) {
        std::string msg = "WinRT HRESULT 0x" + std::to_string(static_cast<unsigned long>(e.code())) + ": " + wc::narrow(e.message().c_str());
        throw_java(env, "java/lang/IllegalStateException", msg.c_str());
        using R = decltype(fn());
        if constexpr (!std::is_void_v<R>) return R{};
    }
    catch (const std::exception& e) {
        throw_java(env, "java/lang/IllegalStateException", e.what());
        using R = decltype(fn());
        if constexpr (!std::is_void_v<R>) return R{};
    }
    catch (...) {
        throw_java(env, "java/lang/IllegalStateException", "Unknown native exception occurred");
        using R = decltype(fn());
        if constexpr (!std::is_void_v<R>) return R{};
    }
}

wc::CaptureEngine* session(jlong id) {
    wc::require(id != 0, "Capture session closed");
    return reinterpret_cast<wc::CaptureEngine*>(id);
}

std::wstring jstring_to_wstring(JNIEnv* env, jstring jstr) {
    if (!jstr) return {};
    const jchar* chars = env->GetStringChars(jstr, nullptr);
    jsize len = env->GetStringLength(jstr);
    std::wstring result(reinterpret_cast<const wchar_t*>(chars), len);
    env->ReleaseStringChars(jstr, chars);
    return result;
}

jstring wstring_to_jstring(JNIEnv* env, const std::wstring& wstr) {
    return env->NewString(reinterpret_cast<const jchar*>(wstr.data()), static_cast<jsize>(wstr.size()));
}

}

extern "C" JNIEXPORT jobjectArray JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nEnumerate(JNIEnv* env, jclass) {
    return checked(env, [&]() -> jobjectArray {
        auto windows = wc::enumerate_windows();
        jclass cls = env->FindClass("com/wincraft/client/bridge/WindowHandleInfo");
        if (!cls) return nullptr;
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(JLjava/lang/String;II)V");
        if (!ctor) return nullptr;
        jobjectArray out = env->NewObjectArray(static_cast<jsize>(windows.size()), cls, nullptr);
        if (!out) return nullptr;
        for (jsize i = 0; i < static_cast<jsize>(windows.size()); ++i) {
            auto& w = windows[i];
            auto title = wc::narrow(w.title);
            jstring str = env->NewStringUTF(title.c_str());
            if (!str) return nullptr;
            jobject info = env->NewObject(cls, ctor, static_cast<jlong>(reinterpret_cast<intptr_t>(w.hwnd)), str, w.width, w.height);
            env->SetObjectArrayElement(out, i, info);
            env->DeleteLocalRef(str);
            env->DeleteLocalRef(info);
            if (env->ExceptionCheck()) return nullptr;
        }
        return out;
    });
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nEnumerateOwnedPopups(JNIEnv* env, jclass, jlongArray owners_arr) {
    return checked(env, [&]() -> jobjectArray {
        std::vector<HWND> owners;
        if (owners_arr) {
            jsize len = env->GetArrayLength(owners_arr);
            jlong* values = env->GetLongArrayElements(owners_arr, nullptr);
            if (!values) return nullptr;
            owners.reserve(static_cast<size_t>(len));
            for (jsize i = 0; i < len; ++i) {
                owners.push_back(reinterpret_cast<HWND>(static_cast<intptr_t>(values[i])));
            }
            env->ReleaseLongArrayElements(owners_arr, values, JNI_ABORT);
        }
        auto windows = wc::enumerate_owned_popups(owners);
        jclass cls = env->FindClass("com/wincraft/client/bridge/OwnedWindowInfo");
        if (!cls) return nullptr;
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(JLjava/lang/String;IIJ)V");
        if (!ctor) return nullptr;
        jobjectArray out = env->NewObjectArray(static_cast<jsize>(windows.size()), cls, nullptr);
        if (!out) return nullptr;
        for (jsize i = 0; i < static_cast<jsize>(windows.size()); ++i) {
            const auto& w = windows[static_cast<size_t>(i)];
            jstring title = wstring_to_jstring(env, w.title);
            if (!title) return nullptr;
            jobject item = env->NewObject(cls, ctor,
                static_cast<jlong>(reinterpret_cast<intptr_t>(w.hwnd)), title,
                w.width, w.height, static_cast<jlong>(reinterpret_cast<intptr_t>(w.owner)));
            env->SetObjectArrayElement(out, i, item);
            env->DeleteLocalRef(title);
            env->DeleteLocalRef(item);
            if (env->ExceptionCheck()) return nullptr;
        }
        return out;
    });
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nEnumerateInstalledApps(JNIEnv* env, jclass) {
    return checked(env, [&]() -> jobjectArray {
        auto apps = wc::enumerate_installed_apps();
        jclass cls = env->FindClass("com/wincraft/client/bridge/InstalledAppInfo");
        if (!cls) return nullptr;
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (!ctor) return nullptr;
        jobjectArray out = env->NewObjectArray(static_cast<jsize>(apps.size()), cls, nullptr);
        if (!out) return nullptr;
        for (jsize i = 0; i < static_cast<jsize>(apps.size()); ++i) {
            auto& a = apps[i];
            jstring name = wstring_to_jstring(env, a.name);
            jstring path = wstring_to_jstring(env, a.exe_path);
            jstring cat = wstring_to_jstring(env, a.category);
            jstring shortcut = wstring_to_jstring(env, a.shortcut_path);
            jobject info = env->NewObject(cls, ctor, name, path, cat, shortcut);
            env->SetObjectArrayElement(out, i, info);
            env->DeleteLocalRef(name);
            env->DeleteLocalRef(path);
            env->DeleteLocalRef(cat);
            env->DeleteLocalRef(shortcut);
            env->DeleteLocalRef(info);
            if (env->ExceptionCheck()) return nullptr;
        }
        return out;
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nExtractIcon(JNIEnv* env, jclass, jstring jpath, jlong hwnd) {
    return checked(env, [&]() -> jbyteArray {
        std::wstring path = jstring_to_wstring(env, jpath);
        auto rgba = wc::extract_icon_rgba(path, reinterpret_cast<HWND>(static_cast<intptr_t>(hwnd)), 32);
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(rgba.size()));
        if (arr && !rgba.empty()) {
            env->SetByteArrayRegion(arr, 0, static_cast<jsize>(rgba.size()), reinterpret_cast<const jbyte*>(rgba.data()));
        }
        return arr;
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nDetectNewWindow(JNIEnv* env, jclass, jlongArray existing_arr, jlong timeout_ms) {
    return checked(env, [&]() -> jlong {
        std::vector<HWND> existing;
        if (existing_arr) {
            jsize len = env->GetArrayLength(existing_arr);
            jlong* elements = env->GetLongArrayElements(existing_arr, nullptr);
            for (jsize i = 0; i < len; ++i) {
                existing.push_back(reinterpret_cast<HWND>(static_cast<intptr_t>(elements[i])));
            }
            env->ReleaseLongArrayElements(existing_arr, elements, JNI_ABORT);
        }
        HWND found = wc::detect_new_window(existing, static_cast<DWORD>(timeout_ms));
        return reinterpret_cast<jlong>(found);
    });
}

extern "C" JNIEXPORT jstring JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nChooseExecutable(JNIEnv* env, jclass) {
    std::vector<wchar_t> filename(32768, L'\0');
    OPENFILENAMEW dialog{};
    dialog.lStructSize = sizeof(dialog);
    dialog.lpstrFile = filename.data();
    dialog.nMaxFile = static_cast<DWORD>(filename.size());
    dialog.lpstrFilter = L"Applications (*.exe)\0*.exe\0All files (*.*)\0*.*\0\0";
    dialog.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_EXPLORER | OFN_NOCHANGEDIR;
    if (!GetOpenFileNameW(&dialog)) return nullptr;
    return env->NewString(reinterpret_cast<const jchar*>(filename.data()), static_cast<jsize>(wcslen(filename.data())));
}

extern "C" JNIEXPORT jstring JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nChooseFile(JNIEnv* env, jclass) {
    std::vector<wchar_t> filename(32768, L'\0');
    OPENFILENAMEW dialog{};
    dialog.lStructSize = sizeof(dialog);
    dialog.lpstrFile = filename.data();
    dialog.nMaxFile = static_cast<DWORD>(filename.size());
    dialog.lpstrFilter = L"All files (*.*)\0*.*\0\0";
    dialog.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_EXPLORER | OFN_NOCHANGEDIR;
    if (!GetOpenFileNameW(&dialog)) return nullptr;
    return env->NewString(reinterpret_cast<const jchar*>(filename.data()), static_cast<jsize>(wcslen(filename.data())));
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nLaunchApplication(JNIEnv* env, jclass, jstring jpath) {
    std::wstring path = jstring_to_wstring(env, jpath);
    if (path.empty()) return JNI_FALSE;
    std::wstring target_path = path;
    if (target_path.find(L":") == std::wstring::npos && target_path.find(L"!") != std::wstring::npos) {
        target_path = L"shell:AppsFolder\\" + target_path;
    }
    SHELLEXECUTEINFOW request{};
    request.cbSize = sizeof(request);
    request.fMask = SEE_MASK_FLAG_NO_UI;
    request.lpVerb = L"open";
    request.lpFile = target_path.c_str();
    request.nShow = SW_SHOWNORMAL;
    return ShellExecuteExW(&request) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nLaunchApplicationWithArguments(
    JNIEnv* env, jclass, jstring jpath, jstring jarguments) {
    std::wstring path = jstring_to_wstring(env, jpath);
    if (path.empty()) return JNI_FALSE;
    std::wstring arguments = jstring_to_wstring(env, jarguments);
    SHELLEXECUTEINFOW request{};
    request.cbSize = sizeof(request);
    request.fMask = SEE_MASK_FLAG_NO_UI;
    request.lpVerb = L"open";
    request.lpFile = path.c_str();
    request.lpParameters = arguments.empty() ? nullptr : arguments.c_str();
    request.nShow = SW_SHOWNOACTIVATE;
    return ShellExecuteExW(&request) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nIsWindow(JNIEnv*, jclass, jlong hwnd) {
    HWND handle = reinterpret_cast<HWND>(static_cast<intptr_t>(hwnd));
    return handle && IsWindow(handle) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nCreate(JNIEnv* env, jclass, jlong hwnd) {
    return checked(env, [&]() -> jlong {
        auto ptr = std::make_unique<wc::CaptureEngine>(reinterpret_cast<HWND>(static_cast<intptr_t>(hwnd)));
        return reinterpret_cast<jlong>(ptr.release());
    });
}

extern "C" JNIEXPORT jint JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nUpdate(JNIEnv* env, jclass, jlong id, jint gl) {
    return checked(env, [&]() -> jint { return session(id)->update(static_cast<unsigned int>(gl)); });
}

extern "C" JNIEXPORT void JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nUnlock(JNIEnv* env, jclass, jlong id) {
    checked(env, [&] { session(id)->unlock(); });
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nDetachGl(JNIEnv* env, jclass, jlong id) {
    return checked(env, [&]() -> jboolean { session(id)->detach_gl(); return JNI_TRUE; });
}

extern "C" JNIEXPORT jint JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nWidth(JNIEnv* env, jclass, jlong id) {
    return checked(env, [&]() -> jint { return session(id)->width(); });
}

extern "C" JNIEXPORT jint JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nHeight(JNIEnv* env, jclass, jlong id) {
    return checked(env, [&]() -> jint { return session(id)->height(); });
}

extern "C" JNIEXPORT void JNICALL Java_com_wincraft_client_bridge_NativeCaptureBridge_nDestroy(JNIEnv* env, jclass, jlong id) {
    HRESULT com = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(com) && com != RPC_E_CHANGED_MODE) {
        checked(env, [&] { winrt::check_hresult(com); });
        return;
    }
    checked(env, [&] { delete session(id); });
    if (SUCCEEDED(com)) CoUninitialize();
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_wincraft_client_bridge_InputBridge_nMouse(JNIEnv*, jclass, jlong hwnd, jint msg, jint x, jint y, jint state) {
    return wc::mouse(reinterpret_cast<HWND>(static_cast<intptr_t>(hwnd)), msg, x, y, static_cast<unsigned int>(state));
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_wincraft_client_bridge_InputBridge_nKey(JNIEnv*, jclass, jlong hwnd, jint msg, jint vk, jint scan, jboolean repeat) {
    return wc::key(reinterpret_cast<HWND>(static_cast<intptr_t>(hwnd)), msg, vk, scan, repeat == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_wincraft_client_bridge_InputBridge_nText(JNIEnv*, jclass, jlong hwnd, jint utf16) {
    return wc::text(reinterpret_cast<HWND>(static_cast<intptr_t>(hwnd)), static_cast<unsigned int>(utf16));
}

extern "C" JNIEXPORT jint JNICALL Java_com_wincraft_client_bridge_InputBridge_nGetCursorShape(JNIEnv*, jclass, jlong hwnd, jint x, jint y) {
    return wc::get_cursor_shape(reinterpret_cast<HWND>(static_cast<intptr_t>(hwnd)), x, y);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_wincraft_client_bridge_InputBridge_nDropFiles(JNIEnv* env, jclass, jlong hwnd, jint x, jint y, jobjectArray files_arr) {
    return checked(env, [&]() -> jboolean {
        if (!files_arr) return JNI_FALSE;
        std::vector<std::wstring> files;
        jsize len = env->GetArrayLength(files_arr);
        for (jsize i = 0; i < len; ++i) {
            jstring s = static_cast<jstring>(env->GetObjectArrayElement(files_arr, i));
            if (s) {
                files.push_back(jstring_to_wstring(env, s));
                env->DeleteLocalRef(s);
            }
        }
        bool ok = wc::drop_files(reinterpret_cast<HWND>(static_cast<intptr_t>(hwnd)), x, y, files);
        return ok ? JNI_TRUE : JNI_FALSE;
    });
}

extern "C" JNIEXPORT void JNICALL Java_com_wincraft_client_bridge_InputBridge_nWakeApp(JNIEnv*, jclass, jlong hwnd) {
    wc::wake_app(reinterpret_cast<HWND>(static_cast<intptr_t>(hwnd)));
}

