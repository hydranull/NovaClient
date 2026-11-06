#version 330
#extension GL_ARB_separate_shader_objects : require

// Vivid: kraeftigere, sauberere Farben fuer die Spielwelt. Je Pixel drei kleine Schritte:
//  1. leicht nachschaerfen: Kreuz aus vier Nachbarn, begrenzt auf deren hellsten/dunkelsten Wert (keine hellen Saeume)
//  2. Vibrance: matte Farben kraeftig anheben, schon satte nur wenig (Gras und Himmel werden nicht grell)
//  3. Mitten etwas heller, dazu eine sanfte S-Kurve fuer mehr Tiefe
// ColorModulator.x = Staerke 0..1 (beim Ein- und Ausschalten weich ueberblendet).

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main() {
    vec2 texel = 1.0 / vec2(textureSize(InSampler, 0));
    vec4 center = texture(InSampler, texCoord);
    vec3 c = center.rgb;
    vec3 n = texture(InSampler, texCoord - vec2(0.0, texel.y)).rgb;
    vec3 s = texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb;
    vec3 w = texture(InSampler, texCoord - vec2(texel.x, 0.0)).rgb;
    vec3 e = texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb;
    vec3 lo = min(c, min(min(n, s), min(w, e)));
    vec3 hi = max(c, max(max(n, s), max(w, e)));
    vec3 graded = clamp(c + (4.0 * c - n - s - w - e) * 0.2, lo, hi);

    float luma = dot(graded, LUMA);
    float sat = max(graded.r, max(graded.g, graded.b)) - min(graded.r, min(graded.g, graded.b));
    graded = clamp(mix(vec3(luma), graded, 1.12 + 0.38 * (1.0 - sat)), 0.0, 1.0);

    graded = pow(graded, vec3(0.94));
    graded = mix(graded, graded * graded * (3.0 - 2.0 * graded), 0.22);

    fragColor = vec4(mix(c, graded, ColorModulator.x), center.a);
}
