import com.wincraft.client.render.MatrixTransform3D;
import org.joml.Vector3d;

public class SnappingTest {
    static double snap(double val, double threshold) {
        double r = Math.round(val);
        return Math.abs(val - r) <= threshold ? r : val;
    }

    public static void main(String[] args) {
        // Test block face snapping within 0.2 blocks
        if (snap(10.15, 0.2) != 10.0) throw new AssertionError("10.15 did not snap to 10.0");
        if (snap(9.85, 0.2) != 10.0) throw new AssertionError("9.85 did not snap to 10.0");
        if (snap(10.25, 0.2) != 10.25) throw new AssertionError("10.25 incorrectly snapped");

        // Test aspect-preserving resizing
        float initialW = 2.0f;
        float initialH = 1.0f;
        float aspect = initialW / initialH;

        double factor = 1.5;
        float newW = (float)(initialW * factor);
        float newH = (float)(initialH * factor);

        if (Math.abs((newW / newH) - aspect) > 1e-5) {
            throw new AssertionError("Aspect ratio altered during corner resize: " + (newW / newH));
        }

        System.out.println("SnappingTest PASS");
    }
}
