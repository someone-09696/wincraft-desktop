# Install WinCraft Desktop on Windows (developer build, **not a verified release**)

There is currently **no tested, ready-to-install WinCraft JAR**. The source archive must be built on Windows. You will need to report any errors and test the apps you actually use before it can be called fully working.

## Requirements

- Windows 10 1903+ or Windows 11, **x64**. Minecraft Java Edition **26.2**, not 1.26.2. A graphics driver supporting **WGL_NV_DX_interop** on the GPU Minecraft uses. Select **OpenGL** (not Vulkan) in Minecraft's graphics settings.
- Fabric Loader for Minecraft 26.2 and a matching **Fabric API `+26.2`** JAR in the game directory's `mods` folder. Start the Fabric profile once to create the folder.
- **JDK 25**, `JAVA_HOME` set to that JDK; Visual Studio 2022 with **Desktop development with C++** and Windows 10/11 SDK (including C++/WinRT headers); CMake on PATH. Gradle 9.5.1 wrapper is included.

## Build and copy (PowerShell)

1. Unzip `wincraft-desktop` from the source archive to a normal folder, e.g. `C:\Projects\wincraft-desktop` (do not run the script while files are still inside the ZIP).
2. In 64-bit PowerShell, set `JAVA_HOME` if needed: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'` (replace with your actual JDK path).
3. From the project root run:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\build-and-install.ps1 -Install
   ```
   If your launcher stores Minecraft elsewhere, add `-MinecraftDir 'D:\Games\Minecraft\.minecraft'`.
4. If the build succeeds, select the **Fabric 26.2** installation in Minecraft Launcher, ensure the game uses **OpenGL**, and enter a world. Press **B** to open WinCraft, choose a visible running window, click **Hold app**, then left-click to place it. Esc cancels placement; F8 disables/enables gaze input.
5. If you want **optional 3D app sound**, read [AUDIO_SETUP.md](AUDIO_SETUP.md). A separate virtual audio cable driver is required for no-doubled-audio routing. Your microphone remains the app's Windows device and is not captured by WinCraft.
6. If the native build fails, **do not copy an older JAR** or a `-sources.jar` to `mods`. Save the complete PowerShell output and your `latest.log` from the Minecraft `logs` folder and send them for debugging.

## Important limitations

The current build only enumerates *running visible windows*; it does not list every installed application with icons as in your example. You can choose an `.exe` or open a URL in your default browser, then refresh and choose its window. Minimized/cloaked windows cannot be captured reliably. Browser text selection, genuine OS clipboard shortcuts, cross-app OLE file dragging and compatibility with **every** application are **not yet implemented or verified**. Running this script will **not** make those claims true.

## What to send if you want it debugged for your PC

Windows version, GPU(s), driver version, the exact Fabric/Minecraft versions, apps to test (browser/Explorer/Kdenlive/etc.), PowerShell build output, Minecraft `latest.log`, and a short recording of the failure. Never share passwords, private browser tabs, or full desktop recordings with sensitive content.
