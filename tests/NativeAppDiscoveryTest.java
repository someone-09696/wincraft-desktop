import com.wincraft.client.bridge.InstalledAppInfo;
import com.wincraft.client.bridge.NativeCaptureBridge;
import com.wincraft.client.bridge.WindowHandleInfo;
import java.util.List;

public class NativeAppDiscoveryTest {
    public static void main(String[] args) {
        try {
            System.load(new java.io.File("native-build/wincraft_native.dll").getAbsolutePath());
            System.out.println("Native DLL loaded successfully.");

            List<WindowHandleInfo> windows = NativeCaptureBridge.enumerate();
            System.out.println("Discovered " + windows.size() + " running visible windows:");
            for (int i = 0; i < Math.min(5, windows.size()); i++) {
                WindowHandleInfo w = windows.get(i);
                System.out.println("  - HWND " + Long.toHexString(w.hwnd()) + ": " + w.title() + " (" + w.width() + "x" + w.height() + ")");
            }

            List<InstalledAppInfo> apps = NativeCaptureBridge.enumerateInstalledApps();
            System.out.println("Discovered " + apps.size() + " installed Windows applications:");
            for (int i = 0; i < Math.min(8, apps.size()); i++) {
                InstalledAppInfo a = apps.get(i);
                System.out.println("  - [" + a.category() + "] " + a.name() + " -> " + a.exePath());
            }

            byte[] icon = NativeCaptureBridge.extractIcon("C:\\Windows\\System32\\notepad.exe", 0);
            System.out.println("Extracted notepad icon RGBA bytes length: " + (icon != null ? icon.length : 0));
            if (icon == null || icon.length != 32 * 32 * 4) {
                throw new AssertionError("Icon extraction length invalid: " + (icon != null ? icon.length : 0));
            }

            System.out.println("NativeAppDiscoveryTest PASS");
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
