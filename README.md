<div align="center">

# 🪟 WinCraft Desktop
### *Your entire Windows OS, natively embedded in your Minecraft world.*

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](https://opensource.org/licenses/MIT)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-green?style=for-the-badge&logo=minecraft)](https://fabricmc.net/)
[![Platform](https://img.shields.io/badge/Platform-Windows_10_%2F_11_Only-0078D6?style=for-the-badge&logo=windows)](https://microsoft.com/windows)
[![Loader](https://img.shields.io/badge/Mod_Loader-Fabric-F0D68A?style=for-the-badge)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25+-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net/)

<br/>

<img src="https://i.ibb.co/tPk7PtBT/1.png" alt="WinCraft In-Game Screen" width="100%" style="border-radius: 12px; box-shadow: 0 8px 32px rgba(0,0,0,0.6);" />

<br/>
<br/>

**WinCraft Desktop** bridges the gap between Windows desktop software and Minecraft. Run web browsers, code editors, YouTube videos, Discord calls, or native applications as interactive, physical, spatial panels inside your world — with zero Alt-Tabbing.

</div>

---

## 📸 Showcase & Multi-Window Setup

<div align="center">
<img src="https://i.ibb.co/7dcM47Jy/2.png" alt="Multi-Screen Workspace in Minecraft" width="100%" style="border-radius: 12px; box-shadow: 0 8px 32px rgba(0,0,0,0.6);" />
</div>

---

## ⚡ Key Highlights

* 🚀 **Zero-Copy Native Pipeline:** Powered by the modern **Windows Graphics Capture API (WGC)**, Direct3D 11, and `WGL_NV_DX_interop`. Frames are swapped directly between GPUs in VRAM with zero CPU pixel copies.
* 🖱️ **In-World Raycast Input:** Your Minecraft center reticle acts as your desktop cursor. Hover over panels to click, scroll, drag, or type into text fields.
* 🛡️ **Seamless Virtual Focus:** Minecraft retains 100% of the true OS foreground window. Keystrokes and mouse messages are safely dispatched via targeted Win32 messaging (`PostMessageW`), preventing desktop focus-stealing or game window drops.
* 🎯 **Follow & Pin Mode (`F` Key):** Look at any screen and press **`F`** to toggle **Floating / Follow Mode**. The window locks to your viewpoint and moves with you across dimensions and caves. Press `F` again to anchor it to blocks in the world.
* 🎛️ **Spatial Workspace Gizmo (`F8`):** Enter transform mode to freely grab handles, resize screens, rotate yaw/pitch, and arrange ergonomic multi-monitor setups.
* 🌐 **100% Client-Side:** Safe for multiplayer! WinCraft operates entirely on your local machine. Connect to vanilla servers, Realms, or modded SMPs without needing server-side plugins or risking desyncs.
* 🧹 **Auto-Lifecycle Management:** Closing an app on your desktop automatically detaches and despawns the in-game screen.

---

## 🎮 In-Game Controls & Keybinds

| Key / Input | Mode | Action |
| :---: | :---: | :--- |
| **`B`** | *Anywhere* | **Open App Launcher:** Browse open windows, installed apps, or open a URL |
| **`F`** | *Hovering Panel* | **Toggle Follow / Pin Mode:** Makes screen follow your camera or stay fixed |
| **`F8`** | *World* | **Toggle Transform Gizmo:** Move, resize, tilt, and rotate screens |
| **`Mouse Wheel`** | *Hovering Panel* | **Window Scroll:** Smooth vertical and horizontal document/page scroll |
| **`Left Click`** | *Hovering Panel* | **Click / Focus Element:** Interact with web links, buttons, and text fields |
| **`Esc`** | *Anywhere* | Release keyboard lock / Close active launcher menu |

---

## 🏗️ Architecture & Technical Flow

```
┌────────────────────────────────────────────────────────┐
│                      Windows OS                        │
│   [Edge / Chrome]      [Code Editor]      [Discord]    │
│          │                   │                 │       │
│          └───────────────────┼─────────────────┘       │
│                              ▼                         │
│               Windows Graphics Capture (WGC)           │
│                              │                         │
│                    Direct3D 11 Surface                 │
│                              │                         │
│                  NV_DX_interop (GPU-to-GPU)            │
└──────────────────────────────┼─────────────────────────┘
                               ▼
┌────────────────────────────────────────────────────────┐
│                   Minecraft Fabric Mod                 │
│                                                        │
│   [OpenGL / Blaze3D] ───► World Depth Pass             │
│   [Reticle Raycast]  ───► Local Coordinate Mapping     │
│   [Input Injector]   ───► Win32 Pure Message Queue     │
└────────────────────────────────────────────────────────┘
```

---

## 📋 System Requirements

> **Windows-Only:** WinCraft relies directly on Microsoft Windows DirectComposition and Windows Graphics Capture APIs. It **cannot run on macOS or Linux**.

* **Operating System:** Windows 10 (1903+) or Windows 11 (x86_64)
* **Minecraft Version:** `26.2`
* **Mod Loader:** [Fabric Loader](https://fabricmc.net/) `0.19.3+`
* **Java Runtime:** Java 25 or newer
* **Dependencies:** [Fabric API](https://modrinth.com/mod/fabric-api)

---

## 🛠️ Building from Source

### Prerequisites
1. **JDK 25** with `JAVA_HOME` configured.
2. **Visual Studio 2022** (Desktop C++ Development + Windows 10/11 SDK).
3. **CMake** and **Ninja** (or MSBuild).

### Step-by-Step Compilation

1. **Clone the Repository:**
   ```bash
   git clone https://github.com/your-username/wincraft-desktop.git
   cd wincraft-desktop
   ```

2. **Build Native Interop DLL (`wincraft_native.dll`):**
   Open a **Developer Command Prompt for VS 2022**:
   ```cmd
   cmake -S src/native -B native-build -G "Visual Studio 17 2022" -A x64
   cmake --build native-build --config Release
   ```

3. **Stage the Native DLL:**
   ```cmd
   mkdir src\main\resources\natives\windows-x86_64
   copy native-build\Release\wincraft_native.dll src\main\resources\natives\windows-x86_64\wincraft_native.dll
   ```

4. **Build the Mod JAR:**
   ```cmd
   .\gradlew.bat build
   ```
   The completed client mod will be generated in `build/libs/wincraft-desktop-0.1.0-alpha.jar`.

---

## 🤝 Contributing & Issue Reporting

Contributions, bug reports, and pull requests are warmly welcomed!
- Found a bug or an app that doesn't capture? Open an Issue on GitHub.
- Want to optimize the interop pipelines? Submit a Pull Request.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
<br/>
*Minecraft is a trademark of Mojang Synergies AB. WinCraft Desktop is not affiliated with Mojang or Microsoft.*
