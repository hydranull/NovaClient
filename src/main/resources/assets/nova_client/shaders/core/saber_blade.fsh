#version 330
#extension GL_ARB_separate_shader_objects : require

// Hyper Tools: Laserschwert-Klinge, ganz aus dem Abstand zur Klingenachse gerechnet - keine Textur, keine Kanten,
// in jeder Groesse glatt. Innen ein deckend weisser Kern mit geglaettetem Rand (fwidth: genau ein Pixel weich), der zum
// Rand hin leicht die Klingenfarbe annimmt; direkt daran ein satter Farbmantel, aussen ein weicher Schein in zwei
// Stufen. Vormultipliziertes Alpha: der Kern deckt, Mantel und Schein leuchten dazu und dunkeln den Hintergrund nur
// teilweise ab (bleibt auch vor hellem Himmel farbig).

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

// Kernradius als Anteil am Leuchtradius
const float CORE = 0.085;

void main() {
    float d = length(texCoord0);
    float aa = max(fwidth(d), 1.0e-4) * 1.25;
    float core = 1.0 - smoothstep(CORE - aa, CORE + aa, d);
    float o = max(d - CORE, 0.0);
    float sheath = exp(-o * o / 0.008);
    float halo = exp(-d * d / 0.06) * 0.75 + exp(-d * d / 0.35) * 0.38;
    halo *= 1.0 - smoothstep(0.72, 1.0, d);

    float k = vertexColor.a;
    vec3 tint = vertexColor.rgb;
    float edge = smoothstep(0.35, 1.0, d / CORE);
    vec3 coreColor = mix(vec3(1.0), mix(tint, vec3(1.0), 0.55), edge * edge);
    vec3 glow = min(tint * (sheath * 1.4 + halo * 1.25) * k, vec3(1.0));
    vec3 rgb = coreColor * core + glow * (1.0 - core);
    float alpha = clamp(core + (sheath * 0.65 + halo * 0.3) * k * (1.0 - core), 0.0, 1.0);
    if (alpha < 0.002 && max(rgb.r, max(rgb.g, rgb.b)) < 0.002) {
        discard;
    }
    fragColor = vec4(rgb, alpha);
}
