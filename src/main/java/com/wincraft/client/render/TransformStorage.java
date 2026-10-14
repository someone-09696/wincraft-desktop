package com.wincraft.client.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Safe local persistence for panel spatial transforms only.
 * NEVER stores raw HWNDs, process IDs, audio/mic data, or pixel contents.
 */
public final class TransformStorage {
    private static final Logger LOG = LoggerFactory.getLogger("wincraft/storage");
    private static final Map<String, SavedTransform> CACHE = new HashMap<>();
    private static boolean loaded = false;

    public record SavedTransform(float width, float height, float pitch, float yaw, float roll,
                                 double x, double y, double z, boolean followCamera,
                                 double followX, double followY, double followZ,
                                 float followPitch, float followYaw, float followRoll) { }

    private static Path storagePath() {
        try {
            var mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                return mc.gameDirectory.toPath().resolve("wincraft_transforms.json");
            }
        } catch (Throwable ignored) { }
        return Path.of("wincraft_transforms.json");
    }

    public static synchronized void save(String title, MatrixTransform3D transform) {
        if (title == null || title.isBlank() || transform == null) return;
        ensureLoaded();
        CACHE.put(cleanTitle(title), new SavedTransform(
            transform.width, transform.height, transform.pitch, transform.yaw, transform.roll,
            transform.position.x, transform.position.y, transform.position.z,
            transform.followCamera, transform.followOffset.x, transform.followOffset.y,
            transform.followOffset.z, transform.followPitch, transform.followYaw, transform.followRoll
        ));
        persistToFile();
    }

    public static synchronized SavedTransform get(String title) {
        if (title == null) return null;
        ensureLoaded();
        return CACHE.get(cleanTitle(title));
    }

    public static synchronized boolean apply(String title, MatrixTransform3D transform) {
        SavedTransform saved = get(title);
        if (saved == null || transform == null) return false;
        try {
            transform.resize(saved.width, saved.height);
            transform.pitch = saved.pitch;
            transform.yaw = saved.yaw;
            transform.roll = saved.roll;
            transform.position.set(saved.x, saved.y, saved.z);
            transform.followCamera = saved.followCamera;
            transform.followOffset.set(saved.followX, saved.followY, saved.followZ);
            transform.followPitch = saved.followPitch;
            transform.followYaw = saved.followYaw;
            transform.followRoll = saved.followRoll;
            return true;
        } catch (IllegalArgumentException e) {
            LOG.warn("Could not apply saved transform for '{}': {}", title, e.getMessage());
            return false;
        }
    }

    private static String cleanTitle(String raw) {
        // Strip dynamic parts like tabs or page counts if standard separators exist
        int dash = raw.lastIndexOf(" - ");
        if (dash > 0) {
            String app = raw.substring(dash + 3).trim();
            if (!app.isEmpty()) return app;
        }
        return raw.trim();
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path p = storagePath();
        if (!Files.exists(p)) return;
        try {
            String content = Files.readString(p, StandardCharsets.UTF_8);
            // Basic lightweight JSON parse
            parseJson(content);
        } catch (IOException e) {
            LOG.warn("Failed to read spatial transforms: {}", e.getMessage());
        }
    }

    private static void parseJson(String json) {
        // Simple line/block parser for key-value object map
        int idx = 0;
        while ((idx = json.indexOf("\"", idx)) != -1) {
            int endKey = json.indexOf("\"", idx + 1);
            if (endKey == -1) break;
            String key = json.substring(idx + 1, endKey);
            int brace = json.indexOf("{", endKey);
            if (brace == -1) break;
            int closeBrace = json.indexOf("}", brace);
            if (closeBrace == -1) break;
            String obj = json.substring(brace + 1, closeBrace);

            float w = parseFloat(obj, "w", 2.2f);
            float h = parseFloat(obj, "h", 1.4f);
            float pitch = parseFloat(obj, "pitch", 0f);
            float yaw = parseFloat(obj, "yaw", 0f);
            float roll = parseFloat(obj, "roll", 0f);
            double x = parseDouble(obj, "x", 0.0);
            double y = parseDouble(obj, "y", 0.0);
            double z = parseDouble(obj, "z", 0.0);
            boolean follow = parseBoolean(obj, "follow", false);
            double followX = parseDouble(obj, "fx", 0.0);
            double followY = parseDouble(obj, "fy", 0.0);
            double followZ = parseDouble(obj, "fz", 0.0);
            float followPitch = parseFloat(obj, "fp", 0f);
            float followYaw = parseFloat(obj, "fyaw", 0f);
            float followRoll = parseFloat(obj, "fr", 0f);

            CACHE.put(key, new SavedTransform(w, h, pitch, yaw, roll, x, y, z,
                follow, followX, followY, followZ, followPitch, followYaw, followRoll));
            idx = closeBrace + 1;
        }
    }

    private static float parseFloat(String body, String field, float def) {
        try {
            int p = body.indexOf("\"" + field + "\":");
            if (p == -1) p = body.indexOf(field + ":");
            if (p == -1) return def;
            int start = body.indexOf(':', p) + 1;
            int end = body.indexOf(',', start);
            if (end == -1) end = body.length();
            return Float.parseFloat(body.substring(start, end).trim());
        } catch (Exception ignored) { return def; }
    }

    private static double parseDouble(String body, String field, double def) {
        try {
            int p = body.indexOf("\"" + field + "\":");
            if (p == -1) p = body.indexOf(field + ":");
            if (p == -1) return def;
            int start = body.indexOf(':', p) + 1;
            int end = body.indexOf(',', start);
            if (end == -1) end = body.length();
            return Double.parseDouble(body.substring(start, end).trim());
        } catch (Exception ignored) { return def; }
    }

    private static boolean parseBoolean(String body, String field, boolean def) {
        try {
            int p = body.indexOf("\"" + field + "\":");
            if (p == -1) return def;
            int start = body.indexOf(':', p) + 1;
            int end = body.indexOf(',', start);
            if (end == -1) end = body.length();
            return Boolean.parseBoolean(body.substring(start, end).trim());
        } catch (Exception ignored) { return def; }
    }

    private static void persistToFile() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        int count = 0;
        for (var entry : CACHE.entrySet()) {
            if (count++ > 0) sb.append(",\n");
            SavedTransform t = entry.getValue();
            sb.append("  \"").append(entry.getKey().replace("\"", "\\\"")).append("\": {");
            sb.append("\"w\":").append(t.width).append(",");
            sb.append("\"h\":").append(t.height).append(",");
            sb.append("\"pitch\":").append(t.pitch).append(",");
            sb.append("\"yaw\":").append(t.yaw).append(",");
            sb.append("\"roll\":").append(t.roll).append(",");
            sb.append("\"x\":").append(t.x).append(",");
            sb.append("\"y\":").append(t.y).append(",");
            sb.append("\"z\":").append(t.z).append(",");
            sb.append("\"follow\":").append(t.followCamera).append(",");
            sb.append("\"fx\":").append(t.followX).append(",");
            sb.append("\"fy\":").append(t.followY).append(",");
            sb.append("\"fz\":").append(t.followZ).append(",");
            sb.append("\"fp\":").append(t.followPitch).append(",");
            sb.append("\"fyaw\":").append(t.followYaw).append(",");
            sb.append("\"fr\":").append(t.followRoll).append("}");
        }
        sb.append("\n}\n");
        try {
            Files.writeString(storagePath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Failed to save transforms: {}", e.getMessage());
        }
    }
}
