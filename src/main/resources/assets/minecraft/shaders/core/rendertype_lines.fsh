#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// Nova Client: Linien (ESP-Umrisse, Chunk-Raender, Hitboxen ...) ohne Nebel. Vanilla mischt sie mit der Entfernung
// in die Nebelfarbe - nachts/in Hoehlen fast schwarz -, dadurch wurden weit entfernte Umrisse dunkel statt zu leuchten.
// Sonst wie Vanilla 26.3 (mit den Durchgaengen der reihenfolge-unabhaengigen Transparenz).
vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif
    // Haelt den Fog-Uniform-Block aktiv (die Pipeline bindet ihn weiterhin); die Bedingung ist nie erfuellt.
    if (FogEnvironmentalEnd < -1.0e30) {
        color = FogColor;
    }
    return color;
}

void main() {
    vec4 color = vertexColor * ColorModulator;
    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
