#version 330
#extension GL_ARB_separate_shader_objects : require

// Nova Sky VFX in der Welt: Wuerfel um die Kamera (nur Drehung), der Fragment-Shader rechnet je Pixel aus der
// Richtung den Himmel.

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;

layout(location = 0) out vec3 skyDir;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    skyDir = Position;
}
