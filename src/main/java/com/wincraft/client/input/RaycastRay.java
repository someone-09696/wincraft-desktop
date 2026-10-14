package com.wincraft.client.input;

import org.joml.Vector3d;
/** World space ray with normalized direction. */
public record RaycastRay(Vector3d origin, Vector3d direction) {
    public RaycastRay { origin = new Vector3d(origin); direction = new Vector3d(direction).normalize(); }
}
