#pragma once
#include <windows.h>
#include <stdexcept>
#include <string>
namespace wc {
inline void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
inline std::string narrow(const std::wstring& s) {
    if (s.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0, nullptr, nullptr);
    std::string out(n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), out.data(), n, nullptr, nullptr);
    return out;
}
}
