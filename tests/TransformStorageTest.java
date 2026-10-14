import com.wincraft.client.render.MatrixTransform3D;
import com.wincraft.client.render.TransformStorage;
import org.joml.Vector3d;

public class TransformStorageTest {
    public static void main(String[] args) {
        MatrixTransform3D t = new MatrixTransform3D(new Vector3d(10.5, 64.0, -15.2), 0.1f, 1.57f, 0f, 2.5f, 1.8f);
        TransformStorage.save("Test Notepad Window - Notepad", t);

        MatrixTransform3D t2 = new MatrixTransform3D(new Vector3d(0, 0, 0), 0f, 0f, 0f, 1f, 1f);
        boolean applied = TransformStorage.apply("Notepad", t2);
        if (!applied) throw new AssertionError("Failed to apply saved transform by clean title");

        if (Math.abs(t2.width - 2.5f) > 1e-4) throw new AssertionError("Width mismatch: " + t2.width);
        if (Math.abs(t2.height - 1.8f) > 1e-4) throw new AssertionError("Height mismatch: " + t2.height);
        if (Math.abs(t2.position.x - 10.5) > 1e-4) throw new AssertionError("X pos mismatch: " + t2.position.x);
        if (Math.abs(t2.position.y - 64.0) > 1e-4) throw new AssertionError("Y pos mismatch: " + t2.position.y);
        if (Math.abs(t2.position.z - (-15.2)) > 1e-4) throw new AssertionError("Z pos mismatch: " + t2.position.z);

        System.out.println("TransformStorageTest PASS");
    }
}
