#version 330
#extension GL_ARB_separate_shader_objects : require

// VR Menu: Raster des schwebenden Glasfensters. Die Eckpunkte sind schon perspektivisch auf den Bildschirm
// projiziert (VrMenu.java), UV0 ist die Position auf dem Fenster (0..1 = Menue, darueber hinaus = Rand fuer Schatten).

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 panelUv;
layout(location = 1) out vec2 screenUv;
layout(location = 2) out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    panelUv = UV0;
    // Bildschirm-Koordinate fuer den weichgezeichneten Hintergrund (gleiche Konvention wie Post-Effekte)
    screenUv = gl_Position.xy / gl_Position.w * 0.5 + 0.5;
    vertexColor = Color;
}
