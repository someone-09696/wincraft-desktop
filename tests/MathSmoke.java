import com.wincraft.client.input.*;
import com.wincraft.client.render.MatrixTransform3D;
import org.joml.Vector3d;
public class MathSmoke {
    static void near(double got, double expected) {
        if (Math.abs(got-expected)>1e-5) throw new AssertionError(got+" != "+expected);
    }
    public static void main(String[] args) {
        var panel = new MatrixTransform3D(new Vector3d(0,0,3),0,0,0,2,2);
        var ray = new RaycastRay(new Vector3d(0,0,0),new Vector3d(0,0,1));
        var hit = LocalCoordinateMapper.intersect(ray,panel,8).orElseThrow();
        near(hit.distance(),3); near(hit.u(),.5); near(hit.v(),.5);
        if(hit.pixelX(100)!=50||hit.pixelY(100)!=50) throw new AssertionError("pixel center");
        panel.yaw=(float)Math.PI/4;
        hit=LocalCoordinateMapper.intersect(ray,panel,8).orElseThrow();
        near(hit.u(),.5); near(hit.v(),.5);
        panel.pitch=(float)Math.PI/6; panel.roll=(float)Math.PI/3;
        hit=LocalCoordinateMapper.intersect(ray,panel,8).orElseThrow();
        near(hit.u(),.5); near(hit.v(),.5);
        if(LocalCoordinateMapper.intersect(new RaycastRay(new Vector3d(0,0,0),new Vector3d(0,1,0)),panel,8).isPresent())
            throw new AssertionError("miss");
        if(LocalCoordinateMapper.intersect(ray,panel,2).isPresent()) throw new AssertionError("range");
        System.out.println("MathSmoke PASS");
    }
}
