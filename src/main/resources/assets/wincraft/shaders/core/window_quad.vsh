#version 330 core
layout(location=0) in vec2 aPosition;
layout(location=1) in vec2 aUv;
uniform mat4 uMvp;
out vec2 vUv;
// Windows Graphics Capture frames use a top-left origin. OpenGL samples the
// shared WGL/D3D texture from the opposite vertical origin, so flip V here.
void main() { vUv = vec2(aUv.x, 1.0 - aUv.y); gl_Position = uMvp * vec4(aPosition, 0.0, 1.0); }
