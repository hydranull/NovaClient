#version 330
#extension GL_ARB_separate_shader_objects : require

// VR Menu: schwebendes Glasfenster im Vision-Pro-Stil.
// Milchglas (weichgezeichnete Welt, leicht aufgehellt und gesaettigt), Lichtbrechung an der dicken Glaskante,
// Randlicht von oben links, weicher Glanzfleck, Menue darueber, aussen Schatten, Lichtschein und Fensterleiste.

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0; // Menue (vormultipliertes Alpha)
uniform sampler2D Sampler1; // weichgezeichnete Welt

layout(location = 0) in vec2 panelUv;
layout(location = 1) in vec2 screenUv;
layout(location = 2) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

float roundedBox(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec3 backdrop(vec2 uv) {
    return texture(Sampler1, clamp(uv, vec2(0.001), vec2(0.999))).rgb;
}

// Menue mit vier Abtastpunkten (gedrehtes Raster): bleibt verkleinert und schraeg scharf, ohne zu flimmern
vec4 menu(vec2 uv) {
    vec2 ddx = dFdx(uv);
    vec2 ddy = dFdy(uv);
    vec4 c = texture(Sampler0, uv + 0.125 * ddx + 0.375 * ddy);
    c += texture(Sampler0, uv + 0.375 * ddx - 0.125 * ddy);
    c += texture(Sampler0, uv - 0.125 * ddx - 0.375 * ddy);
    c += texture(Sampler0, uv - 0.375 * ddx + 0.125 * ddy);
    return c * 0.25;
}

void main() {
    float visible = ColorModulator.x;
    vec2 size = ColorModulator.yz;
    float radius = ColorModulator.w;
    float time = ModelOffset.x;
    vec2 lightPos = ModelOffset.yz;
    vec4 tint = TextureMat[0];
    vec3 accent = TextureMat[1].rgb;
    float glowStrength = TextureMat[1].a;
    float shadowStrength = TextureMat[2].x;
    float lightTheme = TextureMat[2].y;
    float contentAlpha = TextureMat[2].z;
    float barAlpha = TextureMat[2].w;
    vec2 barSize = TextureMat[3].xy;
    float pad = TextureMat[3].z;
    float barHover = TextureMat[3].w;
    float barGap = size.y * 0.022;

    // Glas = Menue plus Rand (pad) ringsum, damit nichts an der runden Ecke abgeschnitten wird
    vec2 halfSize = size * 0.5 + pad;
    vec2 p = (panelUv - 0.5) * size;          // Menuepixel, Mitte = 0, y nach unten
    float d = roundedBox(p, halfSize, radius); // < 0 = im Fenster
    float px = max(fwidth(d), 1.0e-4);         // Fensterpixel pro Bildschirmpixel
    float inside = clamp(0.5 - d / px, 0.0, 1.0);

    // Bildschirmpixel in Bildschirm-UV (fuer Versatz beim Hintergrund)
    vec2 pixelUv = vec2(abs(dFdx(screenUv).x), abs(dFdy(screenUv).y));
    float screenScale = 1.0 / max(pixelUv.y, 1.0e-6) / 1080.0;

    // ------------------------------------------------ aussen: Schatten, Lichtschein, Fensterleiste
    float soft = size.y * 0.09;
    float shadowDist = roundedBox(p - vec2(0.0, size.y * 0.045), halfSize * vec2(0.97, 0.95), radius);
    float shadow = shadowStrength * pow(clamp(1.0 - shadowDist / soft, 0.0, 1.0), 2.5);
    float glow = glowStrength * exp(-max(d, 0.0) / (size.y * 0.035));
    vec3 outRgb = accent * glow * 0.55;
    float outA = shadow;

    if (barAlpha > 0.0) {
        vec2 bs = barSize * vec2(1.0 + 0.25 * barHover, 1.0);
        vec2 barCenter = vec2(0.0, halfSize.y + barGap + barSize.y * 0.5);
        float db = roundedBox(p - barCenter, bs * 0.5, bs.y * 0.5);
        float bar = clamp(0.5 - db / px, 0.0, 1.0) * barAlpha;
        // weicher Schatten unter der Leiste
        float barShadowDist = roundedBox(p - barCenter - vec2(0.0, bs.y * 0.45), bs * 0.5, bs.y * 0.5);
        float barShadow = 0.25 * barAlpha * pow(clamp(1.0 - barShadowDist / (bs.y * 1.6), 0.0, 1.0), 2.0);
        outA = outA + barShadow * (1.0 - outA);
        outRgb *= 1.0 - barShadow;
        // Leiste selbst: helles Milchglas
        vec3 barGlass = backdrop(screenUv) * 0.45 + mix(vec3(0.62), vec3(0.95), barHover);
        outRgb = barGlass * bar + outRgb * (1.0 - bar);
        outA = bar + outA * (1.0 - bar);
    }

    // ------------------------------------------------ innen: Glas
    // Normale der Kante (zeigt nach aussen) fuer Randlicht
    vec2 e = vec2(1.0, 0.0);
    vec2 n = vec2(roundedBox(p + e.xy, halfSize, radius) - roundedBox(p - e.xy, halfSize, radius),
                  roundedBox(p + e.yx, halfSize, radius) - roundedBox(p - e.yx, halfSize, radius));
    n /= max(length(n), 1.0e-5);

    // Lichtbrechung: an der dicken Kante wird der Hintergrund nach innen gezogen (mit leichter Farbaufspaltung)
    float edgeWidth = max(radius, size.y * 0.03);
    float edge = 1.0 - smoothstep(0.0, edgeWidth, -d);
    vec2 g = vec2(dFdx(d), dFdy(d));
    vec2 inward = -g / max(length(g), 1.0e-5);
    vec2 bend = inward * (edge * edge * 16.0 * screenScale) * pixelUv;
    vec3 frosted = vec3(backdrop(screenUv + bend * 1.08).r,
                        backdrop(screenUv + bend).g,
                        backdrop(screenUv + bend * 0.92).b);

    // Vibrancy: Farben hinter dem Glas etwas kraeftiger, dann aufhellen und toenen
    float lum = dot(frosted, vec3(0.2126, 0.7152, 0.0722));
    frosted = max(mix(vec3(lum), frosted, 1.25), 0.0);
    vec3 darkGlass = frosted * 0.80 + 0.035;
    vec3 lightGlass = frosted * 0.55 + 0.38;
    vec3 glass = mix(mix(darkGlass, lightGlass, lightTheme), tint.rgb, tint.a);

    // Licht von oben: oben minimal heller, weicher Glanzfleck, der mit der Neigung wandert
    glass += vec3(1.0) * (1.0 - panelUv.y) * 0.022;
    vec2 lp = (panelUv - lightPos) * vec2(size.x / size.y, 1.0);
    glass += vec3(1.0) * exp(-dot(lp, lp) * 3.2) * 0.07;

    // Randlicht oben links hell, unten rechts dunkler (dicke Glasscheibe)
    vec2 lightDir = normalize(vec2(-0.45, -0.9));
    float facing = dot(n, lightDir);
    float rimLine = 1.0 - smoothstep(0.0, 1.4 * px, -d);
    float rim = rimLine * (0.22 + 0.6 * max(facing, 0.0)) * mix(1.0, 0.6, lightTheme);
    float innerGlow = (1.0 - smoothstep(0.0, 10.0 * px, -d)) * 0.06 * (0.4 + 0.6 * max(facing, 0.0));
    float innerShade = (1.0 - smoothstep(0.0, 7.0 * px, -d)) * 0.14 * max(-facing, 0.0);
    glass = glass * (1.0 - innerShade) + vec3(innerGlow);

    // Menue auf dem Glas. Im Rand ringsum wird der aeusserste Bildrand fortgesetzt (Clamp): der weiche
    // Theme-Hintergrund laeuft so ohne sichtbare Kante bis an die Glaskante
    vec4 ui = menu(vec2(panelUv.x, 1.0 - panelUv.y)) * contentAlpha;
    vec3 panelRgb = ui.rgb + glass * (1.0 - ui.a) + vec3(rim);
    // feines Rauschen gegen Farbstufen im weichen Hintergrund
    panelRgb += (hash(gl_FragCoord.xy + fract(time) * 61.0) - 0.5) / 255.0;

    vec3 rgb = panelRgb * inside + outRgb * (1.0 - inside);
    float a = inside + outA * (1.0 - inside);
    fragColor = vec4(rgb, a) * visible * vertexColor.a;
}
