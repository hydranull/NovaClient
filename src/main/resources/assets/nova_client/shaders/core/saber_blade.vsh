#version 330
#extension GL_ARB_separate_shader_objects : require

// Hyper Tools: Laserschwert-Klinge und Schweif. Die Vierecke baut die CPU (ToolSkinBlade), sie schauen immer zur
// Kamera. UV0 und Farbe bedeuten je nach Fragment-Shader:
//   saber_blade: UV0.x = quer zur Klinge (-1..1 = Rand des Leuchtens), UV0.y = hinter dem Klingenende (0..1)
//   saber_trail: UV0.x = Emitter (0) .. Spitze (1), UV0.y = Alter (0 = jetzt .. 1 = alt)
// Farbe = Klingenfarbe, Alpha = Helligkeit (Summen, Zuenden, Ausblenden).

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
}
