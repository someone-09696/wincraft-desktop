#version 330 core
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTexture;
uniform sampler2D uSceneDepth;
uniform vec2 uViewportSize;
uniform int uUseSceneDepth;
uniform int uReverseDepth;
void main() {
    if (uUseSceneDepth != 0) {
        vec2 screenUv = gl_FragCoord.xy / uViewportSize;
        float sceneDepth = texture(uSceneDepth, screenUv).r;
        bool behind = uReverseDepth != 0
            ? gl_FragCoord.z < sceneDepth - 0.00002
            : gl_FragCoord.z > sceneDepth + 0.00002;
        if (behind) discard;
    }
    // WGC's shared BGRA texture can carry an undefined/zero alpha channel even
    // when its RGB pixels are valid. Treat captured desktop windows as opaque.
    fragColor = vec4(texture(uTexture, vUv).rgb, 1.0);
}
