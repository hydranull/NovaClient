#version 330
#extension GL_ARB_separate_shader_objects : require

// VR Menu: Hintergrund hinter dem Glas. Modus 0 verkleinert (4x4-Mittel), Modus 1 ist ein Gauss in eine Richtung.

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 texel = 1.0 / vec2(textureSize(InSampler, 0));
    vec3 color;
    if (ColorModulator.z < 0.5) {
        // jede lineare Abtastung mittelt 2x2 Pixel -> zusammen 4x4
        color = texture(InSampler, texCoord + texel * vec2(-1.0, -1.0)).rgb;
        color += texture(InSampler, texCoord + texel * vec2(1.0, -1.0)).rgb;
        color += texture(InSampler, texCoord + texel * vec2(-1.0, 1.0)).rgb;
        color += texture(InSampler, texCoord + texel * vec2(1.0, 1.0)).rgb;
        color *= 0.25;
    } else {
        // 9-Tap-Gauss ueber 5 lineare Abtastungen
        vec2 dir = ColorModulator.xy * texel * ColorModulator.w;
        color = texture(InSampler, texCoord).rgb * 0.2270270270;
        color += texture(InSampler, texCoord + dir * 1.3846153846).rgb * 0.3162162162;
        color += texture(InSampler, texCoord - dir * 1.3846153846).rgb * 0.3162162162;
        color += texture(InSampler, texCoord + dir * 3.2307692308).rgb * 0.0702702703;
        color += texture(InSampler, texCoord - dir * 3.2307692308).rgb * 0.0702702703;
    }
    fragColor = vec4(color, 1.0);
}
