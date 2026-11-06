#version 330
#extension GL_ARB_separate_shader_objects : require

// Hyper Tools: physikalisch basiertes Licht pro Pixel. Spiegelung einer Studio-Umgebung (vorgefiltert nach Rauheit),
// Fresnel, scharfer GGX-Glanz eines Hauptlichts, Feinrelief aus der Hoehenkarte (ohne Tangenten), eingebackene
// Verschattung, Glut/Funkeln ueber Albedo-Alpha, Welt-Licht ueber die Lightmap, ACES-Tonemapping.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

uniform sampler2D Sampler0;
uniform sampler2D MaterialSampler;
uniform sampler2D EnvSampler;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec4 lightMapColor;
layout(location = 4) in vec4 overlayColor;
layout(location = 5) in vec2 texCoord0;
layout(location = 6) in vec3 viewPos;
layout(location = 7) in vec3 viewNormal;

layout(location = 0) out vec4 fragColor;

const float PI = 3.14159265;
const float LEVELS = 4.0;
// Tiefe des Feinreliefs (Hoehe 0..1 -> Bloecke)
const float BUMP = 0.0035;

vec3 decodeEnv(vec3 c) {
    // Umgebung ist als c / (1 + c) gespeichert (helle Lichter ueber 1 in 8 Bit)
    return c / max(vec3(1.0) - c, vec3(0.03));
}

vec3 envRow(vec2 uv, float level) {
    return decodeEnv(textureLod(EnvSampler, vec2(uv.x, (uv.y * 0.96 + 0.02 + level) / LEVELS), 0.0).rgb);
}

// Umgebung in Blickrichtung dir, level 0 = spiegelglatt .. 3 = sehr rau
vec3 env(vec3 dir, float level) {
    vec2 uv = vec2(atan(dir.x, dir.z) / (2.0 * PI) + 0.5, acos(clamp(dir.y, -1.0, 1.0)) / PI);
    float l0 = floor(level);
    float l1 = min(l0 + 1.0, LEVELS - 1.0);
    return mix(envRow(uv, l0), envRow(uv, l1), level - l0);
}

void main() {
    vec4 albedo = texture(Sampler0, texCoord0);
    vec4 material = texture(MaterialSampler, texCoord0);
    float metal = material.r;
    float rough = clamp(material.g, 0.035, 1.0);
    float cavity = material.b;

    bool ortho = ProjMat[3][3] > 0.5;
    vec3 n = normalize(viewNormal);
    if (!gl_FrontFacing) {
        n = -n;
    }
    vec3 v = ortho ? vec3(0.0, 0.0, 1.0) : normalize(-viewPos);
    if (ortho) {
        // GUI spiegelt Y
        n.y = -n.y;
    }

    // Feinrelief (Mikkelsen: Oberflaechengradient aus Bildschirm-Ableitungen)
    vec3 dpdx = dFdx(viewPos);
    vec3 dpdy = dFdy(viewPos);
    float hx = dFdx(material.a);
    float hy = dFdy(material.a);
    vec3 r1 = cross(dpdy, n);
    vec3 r2 = cross(n, dpdx);
    float det = dot(dpdx, r1);
    vec3 grad = sign(det) * (hx * r1 + hy * r2);
    vec3 bumped = abs(det) * n - BUMP * grad;
    if (dot(bumped, bumped) > 1.0e-12 && !ortho) {
        n = normalize(bumped);
    }

    vec3 base = pow(albedo.rgb, vec3(2.2));
    float nv = clamp(dot(n, v), 0.0, 1.0);
    vec3 f0 = mix(vec3(0.04), base, metal);
    vec3 fresnel = f0 + (max(vec3(1.0 - rough), f0) - f0) * pow(1.0 - nv, 5.0);
    vec3 r = reflect(-v, n);
    vec3 spec = env(r, rough * (LEVELS - 1.0));
    vec3 irradiance = env(n, LEVELS - 1.0);

    // Hauptlicht oben links vorne: scharfer GGX-Glanz
    vec3 l = normalize(vec3(-0.45, 0.65, 0.62));
    vec3 h = normalize(l + v);
    float nl = max(dot(n, l), 0.0);
    float nh = max(dot(n, h), 0.0);
    float a = rough * rough;
    float a2 = a * a;
    float d = nh * nh * (a2 - 1.0) + 1.0;
    float dist = a2 / (PI * d * d);
    float k = (rough + 1.0) * (rough + 1.0) / 8.0;
    float geo = nl / (nl * (1.0 - k) + k) * nv / (nv * (1.0 - k) + k);
    vec3 fl = f0 + (1.0 - f0) * pow(1.0 - max(dot(h, v), 0.0), 5.0);
    vec3 sun = dist * geo * fl / max(4.0 * nl * nv, 0.001) * nl * 2.2;

    float ao = vertexColor.r * mix(1.0, cavity, 0.9);
    vec3 diffuse = (1.0 - metal) * base * (irradiance * 0.85 + nl * 0.55) * (1.0 - fresnel);
    vec3 color = (diffuse + spec * fresnel) * ao + sun * mix(1.0, ao, 0.6);

    // Glanzstreifen beim Ziehen
    color += vertexColor.b * (0.6 + 1.4 * metal) * mix(vec3(1.0), base + 0.4, metal);

    // Welt-Licht: in Hoehlen und nachts dunkler
    color *= lightMapColor.rgb;

    // Leuchten: Albedo-Alpha < 1 = Leuchtmaske, Staerke aus der Ecke
    color += base * (1.0 - albedo.a) * vertexColor.g * 2.6;

    // Belichtung + ACES auf der Helligkeit (Farbe bleibt satt, auch bei leuchtendem Plasma), zurueck nach sRGB
    color *= 1.35;
    float lum = max(dot(color, vec3(0.2126, 0.7152, 0.0722)), 1.0e-4);
    float mapped = clamp((lum * (2.51 * lum + 0.03)) / (lum * (2.43 * lum + 0.59) + 0.14), 0.0, 1.0);
    color = clamp(color * (mapped / lum), 0.0, 1.0);
    color = pow(color, vec3(1.0 / 2.2));

    // Treffer-Blinken wie Vanilla
    color = mix(overlayColor.rgb, color, overlayColor.a);
    fragColor = apply_fog(vec4(color, 1.0), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
            FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
