cmake_minimum_required(VERSION 3.25)
project(wincraft_native LANGUAGES CXX)
if(NOT WIN32 OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "WinCraft native requires Windows x64")
endif()
find_package(JNI REQUIRED)
add_library(wincraft_native SHARED 
    src/capture_engine.cpp 
    src/input_injector.cpp 
    src/app_discovery.cpp 
    src/native_bridge.cpp
)
target_compile_features(wincraft_native PRIVATE cxx_std_20)
target_compile_definitions(wincraft_native PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX UNICODE _UNICODE)
target_include_directories(wincraft_native PRIVATE include ${JNI_INCLUDE_DIRS})
target_link_libraries(wincraft_native PRIVATE d3d11 dxgi windowsapp user32 comdlg32 opengl32 dwmapi shell32 ole32)
set_target_properties(wincraft_native PROPERTIES OUTPUT_NAME wincraft_native)
