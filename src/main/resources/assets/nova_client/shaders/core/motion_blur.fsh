#version 330
#extension GL_ARB_separate_shader_objects : require

// Motion Blur wie in Videoschnitt-Programmen: jeder Pixel wird entlang SEINER Bewegung auf dem Bildschirm verwischt.
// Aus der Tiefe wird seine Position in der Welt rekonstruiert und mit der Kamera des letzten Bildes zurueckprojiziert
// (ModelViewMat = VorherigeVP * Kameraversatz * inverse(AktuelleVP)). Drehen verwischt das ganze Bild, seitliches Laufen
// nahe Dinge staerker als ferne. Der Verschluss liegt mittig um das Bild (wie ein 180-Grad-Shutter), gemittelt wird im
// linearen Licht (helle Stellen ziehen echte Streifen statt grau zu werden), die Abtastungen sind je Pixel versetzt
// (weicher Verlauf statt Treppenstufen).

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// Interleaved Gradient Noise (Jimenez): fast blaues Rauschen, ideal zum Versetzen der Abtastungen
float noise(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// Bewegung dieses Pixels seit dem letzten Bild in UV (aktuell - vorher)
vec2 velocity(vec2 uv) {
    float depth = texture(DepthSampler, uv).r;
    float z = ColorModulator.z > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 previous = ModelViewMat * vec4(uv * 2.0 - 1.0, z, 1.0);
    if (previous.w <= 1.0e-5) {
        return vec2(0.0); // lag im letzten Bild hinter der Kamera
    }
    return uv - (previous.xy / previous.w * 0.5 + 0.5);
}

void main() {
    vec2 size = vec2(textureSize(InSampler, 0));
    vec4 center = texture(InSampler, texCoord);

    vec2 motion = velocity(texCoord) * ColorModulator.x;
    float len = length(motion);
    if (len > ColorModulator.w) {
        motion *= ColorModulator.w / len; // Teleport, schneller Schwenk: nicht ueber den halben Bildschirm schmieren
    }
    // Unter einem halben Pixel sieht man nichts - Rechenzeit sparen
    if (length(motion * size) < 0.5) {
        fragColor = vec4(center.rgb, 1.0);
        return;
    }

    int samples = int(ColorModulator.y + 0.5);
    float jitter = noise(gl_FragCoord.xy + ModelOffset.x * 97.0) - 0.5;
    vec2 lo = 0.5 / size;
    vec2 hi = 1.0 - lo;
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 64; i++) {
        if (i >= samples) {
            break;
        }
        // Gleichmaessig ueber die Verschlusszeit, mittig um das aktuelle Bild
        float t = (float(i) + 0.5 + jitter) / float(samples) - 0.5;
        vec3 c = texture(InSampler, clamp(texCoord + motion * t, lo, hi)).rgb;
        sum += c * c; // sRGB grob linearisieren (Gamma 2)
    }
    fragColor = vec4(sqrt(sum / float(samples)), 1.0);
}
