import com.wincraft.client.bridge.InputBridge;
import com.wincraft.client.bridge.NativeCaptureBridge;

public class InputAndFocusTest {
    public static void main(String[] args) {
        try {
            NativeCaptureBridge.load();
            System.out.println("Testing cursor shape query on dummy/desktop handle...");
            int shape = InputBridge.getCursorShape(0, 100, 100);
            System.out.println("Cursor shape result: " + shape);

            System.out.println("Testing dropFiles parameter marshalling...");
            // Non-existent HWND safely returns false without crashing
            boolean dropResult = InputBridge.dropFiles(0, 50, 50, new String[]{"C:\\Windows\\notepad.exe"});
            if (dropResult) throw new AssertionError("Drop on HWND 0 should fail");
            System.out.println("Drop files safety check: PASS");

            System.out.println("Testing focus broker safety...");
            boolean brokerResult = InputBridge.setFocusBroker(0, 0, false);
            System.out.println("Focus broker safety release check: " + brokerResult);

            System.out.println("InputAndFocusTest PASS");
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
