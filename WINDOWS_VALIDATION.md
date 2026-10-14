# Windows Acceptance Checklist & Validation Report

Tested on: **Windows 11 Pro Build 26200 x64**, **AMD Radeon RX 6400** (Driver 32.0.21045.5002), **JDK 25.0.3**, **MSVC 19.44 / Visual Studio 2022 Build Tools**, **Windows SDK 10.0.26100.0**, Minecraft Java Edition **26.2** (Fabric Loader 0.19.3+, Fabric API 0.161.0+26.2).

---

## 1. Feature Checklist

| Feature | Status | Notes |
|---|---|---|
| **Windows Native C++ DLL Build** | **PASS** | Built `wincraft_native.dll` via MSVC 19.44, C++20, and Windows SDK 10.0.26100 with Ninja. |
| **Java 25 & Gradle 9.5.1 Build** | **PASS** | `./gradlew.bat compileJava` succeeds with zero errors against Minecraft 26.2. |
| **Math & Coordinate Raycast Tests** | **PASS** | `MathSmoke.java` passes JOML intersection tests, rotated panel mapping, miss and range tests. |
| **Transform Storage Persistence** | **PASS** | `TransformStorageTest.java` passes clean-title matching, serialization, and coordinate restoration. |
| **Block & Panel Snapping Math** | **PASS** | `SnappingTest.java` passes 0.2 block snapping, panel-to-panel snapping, and aspect ratio preservation. |
| **Native Installed App Discovery** | **PASS** | `NativeAppDiscoveryTest.java` enumerates Start Menu shortcuts, categorizes apps, and resolves targets. |
| **Win32 Icon Extraction** | **PASS** | `NativeAppDiscoveryTest.java` successfully extracts 32x32 RGBA icon bitmaps (4096 bytes) via Win32 shell API. |
| **Child Window Input Routing** | **PASS** | Screen-to-client child mapping (`WindowFromPoint`) routes pointer messages to target child HWNDs (e.g. Chromium renderers). |
| **Native Context Menu Trigger** | **PASS** | `WM_CONTEXTMENU` posted with screen coordinates on right-click up invokes real Win32 context menus. |
| **Adaptive Cursors & Gizmos** | **PASS** | Real cursor query (`GetCursorInfo`) and labeled heuristics render I-beam, Hand, Resize <->, ^v, and diagonal arrows. |
| **Focus Broker Mode (Opt-In)** | **PASS** | `F7` toggles focus broker mode with `AttachThreadInput`/`SetForegroundWindow`, `F8` unconditionally exits to Minecraft. |
| **File Transfer / Drag-Drop** | **PASS** | Constructs genuine `DROPFILES` structure in global shared memory. Clear fallback import dialog for apps that reject OLE drop. |
| **Multi-Panel Spatial Audio** | **PASS** | Refactored `SpatialAudioService` into a multi-stream architecture supporting concurrent independent virtual cables. |

---

## 2. Tested Application Compatibility Matrix

### Windows Notepad
- **WGC Capture**: **PASS** (Clear BGRA capture, accurate window frame bounds)
- **Click & Typing**: **PASS** (Direct text entry and cursor navigation)
- **Text Selection**: **PASS** (Click + drag renders real in-app selection highlight)
- **Context Menu**: **PASS** (Right-click displays native Notepad context menu)
- **File Transfer**: **PASS** (Accepts `WM_DROPFILES` global struct; opens dropped text files)
- **Focus / Escape**: **PASS** (F8 instantly returns focus to Minecraft)

### Windows File Explorer
- **WGC Capture**: **PASS** (Captures folder panes and toolbars)
- **Click & Navigation**: **PASS** (Double-click opens folders, scrolling works)
- **Context Menu**: **PASS** (Native Windows context menu appears at cursor location)
- **File Drag-Drop**: **PASS** (Explorer accepts dropped files into destination folders)

### Chromium / Microsoft Edge / Google Chrome
- **WGC Capture**: **PASS** (GPU D3D11 capture with accurate UV orientation)
- **Child Window Routing**: **PASS** (Events delivered to `Chrome_RenderWidgetHostHWND`)
- **Text Selection**: **PASS** (Click and drag renders real Chromium highlight)
- **Context Menu**: **PASS** (Right-click displays Chromium context menu)
- **Keyboard Shortcuts (Ctrl+C / Ctrl+V)**:
  - *Best-Effort Mode*: Standard `WM_COPY`/`WM_PASTE` fallback works on text input fields.
  - *Focus Broker Mode (`F7`)*: **PASS** (Syncs OS key state so Chromium receives native Ctrl+C / Ctrl+V shortcuts).
- **File Drop**: **PASS / FALLBACK** (Accepts drop on standard upload drop zones; for complex web apps, file picker fallback is provided).

### Visual Studio Code
- **WGC Capture**: **PASS** (Captures editor tabs and terminal)
- **Child Window Input**: **PASS** (Clicking editor routes to Electron child view)
- **Text Selection**: **PASS** (Real cursor updates and editor highlights appear)
- **Clipboard Shortcuts**: **PASS** with Focus Broker mode active.
- **File Transfer**: **FALLBACK** (Electron drop targets often reject cross-process synthetic drops without active COM OLE loop; the launcher's "Transfer File..." dialog provides user-directed file import).

---

## 3. Known Limitations & Technical Realities

1. **OpenGL vs. Vulkan**:
   - The native capture texture sharing relies on `WGL_NV_DX_interop`. Minecraft must use the **OpenGL** backend. Vulkan is not supported by this WGL bridge.
2. **Minimized / Cloaked / Protected Windows**:
   - Windows Graphics Capture (WGC) does not compose minimized or cloaked windows. This is an intentional Windows OS security and compositor boundary, not a bypassable bug.
3. **Multi-App Spatial Audio Isolation**:
   - WinCraft captures audio from virtual audio cables (e.g., VB-CABLE A, VB-CABLE B). If two applications are routed to the *same* virtual cable in Windows Sound Settings, their audio is mixed by the driver. To have truly isolated 3D sound from multiple applications, each app must be assigned to its own virtual cable in Windows Volume Mixer.
4. **Microphone Privacy**:
   - WinCraft strictly excludes recording devices whose name indicates a physical microphone. Applications (Discord, browsers) retain their normal Windows microphone devices. WinCraft does not record the user's microphone.
