#version 330
#extension GL_ARB_separate_shader_objects : require

// Hyper Tools: Eckpunkte fuer den Werkzeug-Shader. Color.r = eingebackene Verschattung, Color.g = Leuchtstaerke
// (pulsiert), Color.b = Glanzstreifen beim Ziehen. Licht wird pro Pixel in hyper_tool.fsh gerechnet.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec4 overlayColor;
layout(location = 5) out vec2 texCoord0;
layout(location = 6) out vec3 viewPos;
layout(location = 7) out vec3 viewNormal;

void main() {
    vec4 pos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * pos;

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

    vertexColor = Color;
    lightMapColor = texture(Sampler2, clamp((vec2(UV2) / 256.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
    viewPos = pos.xyz;
    viewNormal = mat3(ModelViewMat) * Normal;
}
