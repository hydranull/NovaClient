#version 330
#extension GL_ARB_separate_shader_objects : require

// Nova Sky VFX nach den Bloecken (26.x): wie nova_sky.vsh, aber ganz hinten (Tiefe ~0, die Tiefe ist umgekehrt) - der
// Tiefentest laesst den Himmel nur dort durch, wo noch nichts gezeichnet ist. So rechnet der teure Himmel-Shader nur
// die Pixel, in denen man den Himmel wirklich sieht. ColorModulator.w = z dafuer (je nach Tiefenbereich des Treibers).

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;

layout(location = 0) out vec3 skyDir;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    gl_Position.z = ColorModulator.w * gl_Position.w;
    skyDir = Position;
}
