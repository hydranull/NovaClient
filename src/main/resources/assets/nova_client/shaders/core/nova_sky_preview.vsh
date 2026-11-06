#version 330
#extension GL_ARB_separate_shader_objects : require

// Nova Sky VFX, Vorschau im Menue: ein Dreieck ueber das ganze Bild, je Ecke der Kamerastrahl.
// TextureMat: Spalte 0 = rechts, 1 = oben, 2 = vorne (Welt), [3].xy = tan(halbes Sichtfeld) waagerecht/senkrecht,
// [3].z = 1, wenn das Bild gespiegelt (oben/unten) gezeichnet werden soll.

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) out vec3 skyDir;

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    vec2 ndc = uv * 2.0 - 1.0;
    gl_Position = vec4(ndc, 0.0, 1.0);
    float flip = TextureMat[3].z > 0.5 ? -1.0 : 1.0;
    vec2 tanHalf = TextureMat[3].xy;
    skyDir = TextureMat[2].xyz + ndc.x * tanHalf.x * TextureMat[0].xyz + flip * ndc.y * tanHalf.y * TextureMat[1].xyz;
}
