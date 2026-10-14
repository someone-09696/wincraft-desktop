package com.wincraft.client.gui;

import com.wincraft.client.render.AppWindowEntity;
import com.wincraft.client.render.TransformStorage;
import net.minecraft.world.phys.Vec3;

/** Accessible controls for moving, resizing, rotating, and snapping panels. */
public final class WindowControlOverlay {
    private WindowControlOverlay() { }

    public static void scale(AppWindowEntity panel, float deltaWidth, float deltaHeight) {
        if (panel == null) return;
        panel.transform.resize(Math.max(.2f, Math.min(32, panel.transform.width + deltaWidth)),
                               Math.max(.2f, Math.min(32, panel.transform.height + deltaHeight)));
        TransformStorage.save(panel.info.title(), panel.transform);
    }

    public static void scaleAspect(AppWindowEntity panel, float factor) {
        if (panel == null) return;
        float w = Math.max(.2f, Math.min(32, panel.transform.width * factor));
        float h = Math.max(.2f, Math.min(32, panel.transform.height * factor));
        panel.transform.resize(w, h);
        TransformStorage.save(panel.info.title(), panel.transform);
    }

    public static void rotate(AppWindowEntity panel, float pitch, float yaw, float roll) {
        if (panel == null) return;
        panel.transform.pitch += pitch;
        panel.transform.yaw += yaw;
        panel.transform.roll += roll;
        TransformStorage.save(panel.info.title(), panel.transform);
    }

    public static void move(AppWindowEntity panel, Vec3 offset) {
        if (panel == null) return;
        panel.transform.position.add(offset.x, offset.y, offset.z);
        TransformStorage.save(panel.info.title(), panel.transform);
    }

    public static void snapToGrid(AppWindowEntity panel) {
        if (panel == null) return;
        // Snap coordinates to nearest half/integer block
        panel.transform.position.x = Math.round(panel.transform.position.x * 2.0) / 2.0;
        panel.transform.position.y = Math.round(panel.transform.position.y * 2.0) / 2.0;
        panel.transform.position.z = Math.round(panel.transform.position.z * 2.0) / 2.0;

        // Snap yaw to nearest 90-degree cardinal direction
        double halfPi = Math.PI / 2.0;
        panel.transform.yaw = (float)(Math.round(panel.transform.yaw / halfPi) * halfPi);
        panel.transform.pitch = 0f;
        panel.transform.roll = 0f;
        TransformStorage.save(panel.info.title(), panel.transform);
    }

    public static void restoreSaved(AppWindowEntity panel) {
        if (panel == null) return;
        TransformStorage.apply(panel.info.title(), panel.transform);
    }
}
