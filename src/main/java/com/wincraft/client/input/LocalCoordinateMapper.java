package com.wincraft.client.input;

import com.wincraft.client.render.MatrixTransform3D;
import java.util.Optional;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/** Inverse OBB intersection; local surface bounds are [-.5,.5] along x/y. */
public final class LocalCoordinateMapper {
    private LocalCoordinateMapper() { }
    public record Hit(double distance, double u, double v) {
        /** Client-space x, clipped to the valid last pixel. */
        public int pixelX(int width) { return Math.max(0, Math.min(width - 1, (int)Math.floor(u * width))); }
        /** UV v grows upwards; Win32 y grows downwards. */
        public int pixelY(int height) { return Math.max(0, Math.min(height - 1, (int)Math.floor((1 - v) * height))); }
    }
    public static Optional<Hit> intersect(RaycastRay ray, MatrixTransform3D transform, double maxDistance) {
        var unbounded = intersectPlane(ray, transform, maxDistance);
        if (unbounded.isEmpty()) return Optional.empty();
        Hit h = unbounded.get();
        return h.u >= 0 && h.u <= 1 && h.v >= 0 && h.v <= 1 ? unbounded : Optional.empty();
    }
    /** Infinite supporting plane; useful for dragging beyond the panel's original edge. */
    public static Optional<Hit> intersectPlane(RaycastRay ray, MatrixTransform3D transform, double maxDistance) {
        Matrix4d inv = new Matrix4d(transform.model()).invert();
        org.joml.Vector3d o = inv.transformPosition(new Vector3d(ray.origin()));
        org.joml.Vector3d d = inv.transformDirection(new Vector3d(ray.direction()));
        if (Math.abs(d.z) < 1e-9) return Optional.empty();
        double t = -o.z / d.z; // d is inverse-transformed but not renormalized: t remains world-ray distance.
        if (t < 0 || t > maxDistance || !Double.isFinite(t)) return Optional.empty();
        double x = o.x + d.x * t, y = o.y + d.y * t;
        return Optional.of(new Hit(t, x + .5, y + .5));
    }
}
