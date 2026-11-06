#version 330
#extension GL_ARB_separate_shader_objects : require

// Motion Blur "Smooth": das letzte fertige Bild wird mit der Deckkraft ColorModulator.x ueber das neue gelegt - so zieht
// jede Bewegung eine kurze, weiche Spur hinter sich her (wie bei einer langen Belichtung). Die Deckkraft wird pro Bild
// aus der Bildzeit berechnet, damit die Spur bei 60 und bei 240 FPS gleich lang ist. Ein Hauch Rauschen verhindert
// sichtbare Farbstufen, wenn die Spur ausklingt.

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

float noise(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void main() {
    vec3 previous = texture(InSampler, texCoord).rgb;
    previous += (noise(gl_FragCoord.xy + ModelOffset.x * 97.0) - 0.5) / 255.0;
    fragColor = vec4(previous, ColorModulator.x);
}
