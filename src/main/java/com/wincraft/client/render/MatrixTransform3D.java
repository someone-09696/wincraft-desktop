package com.wincraft.client.render;

import org.joml.Matrix4f;
import org.joml.Vector3d;

/** Centered unit square, facing +Z; T * Rz * Ry * Rx * S in world units. */
public final class MatrixTransform3D {
    public Vector3d position;
    public float pitch, yaw, roll, width, height;
    /** Camera-relative pose used when the panel is pinned to the player's view. */
    public boolean followCamera;
    public final Vector3d followOffset = new Vector3d();
    public float followPitch, followYaw, followRoll;
    public MatrixTransform3D(Vector3d position, float pitch, float yaw, float roll, float width, float height) {
        this.position = new Vector3d(position);
        this.pitch = pitch; this.yaw = yaw; this.roll = roll;
        resize(width, height);
    }
    public void resize(float w, float h) {
        if (!Float.isFinite(w) || !Float.isFinite(h) || w < .2f || h < .2f || w > 32 || h > 32)
            throw new IllegalArgumentException("Dimensions must be in [0.2,32] blocks");
        width = w; height = h;
    }

    public void pinToCamera(Vector3d eye, Vector3d look, float cameraYaw, float cameraPitch) {
        Vector3d forward = new Vector3d(look).normalize();
        Vector3d right = new Vector3d(forward).cross(0, 1, 0).normalize();
        if (right.lengthSquared() < 1.0e-8) right.set(1, 0, 0);
        Vector3d up = new Vector3d(right).cross(forward).normalize();
        Vector3d delta = new Vector3d(position).sub(eye);
        followOffset.set(delta.dot(right), delta.dot(up), delta.dot(forward));

        double yaw = Math.toRadians(cameraYaw);
        double pitchAngle = Math.toRadians(cameraPitch);
        followYaw = (float) wrapAngle(this.yaw - (Math.PI - yaw));
        // Store pitch offset as panel-pitch minus camera-pitch (delta only)
        followPitch = pitch - (float) (-pitchAngle);
        followRoll = roll;
        followCamera = true;
        updateCameraPose(eye, forward, cameraYaw, cameraPitch);
    }

    public void updateCameraPose(Vector3d eye, Vector3d look, float cameraYaw, float cameraPitch) {
        if (!followCamera) return;
        Vector3d forward = new Vector3d(look).normalize();
        Vector3d right = new Vector3d(forward).cross(0, 1, 0).normalize();
        if (right.lengthSquared() < 1.0e-8) right.set(1, 0, 0);
        Vector3d up = new Vector3d(right).cross(forward).normalize();
        position.set(eye).fma(followOffset.x, right).fma(followOffset.y, up).fma(followOffset.z, forward);
        double yaw = Math.toRadians(cameraYaw);
        yaw = Math.PI - yaw + followYaw;
        yaw = wrapAngle(yaw);
        this.yaw = (float)yaw;
        // Camera XRot: positive = looking down; negate for JOML pitch
        this.pitch = (float) -Math.toRadians(cameraPitch) + followPitch;
        this.roll = followRoll;
    }

    private static double wrapAngle(double angle) {
        while (angle <= -Math.PI) angle += Math.PI * 2;
        while (angle > Math.PI) angle -= Math.PI * 2;
        return angle;
    }
    public Matrix4f model() {
        return new Matrix4f().translation((float) position.x, (float) position.y, (float) position.z)
                .rotateZ(roll).rotateY(yaw).rotateX(pitch).scale(width, height, 1);
    }
}
