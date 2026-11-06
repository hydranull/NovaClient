#version 330
#extension GL_ARB_separate_shader_objects : require

// Nova Sky VFX: alle Himmel als Shader. Jeder Bildschirmpixel wird einzeln berechnet - ueberall scharf, die
// Animation laeuft mit voller Bildrate, und jeder Himmel reicht rundherum (auch unter den Horizont, z.B. hoch in
// der Luft): Black Hole und Ringed Giant sind Weltraum rundum. Sie rechnen in linearem Licht und gehen ueber eine
// weiche Tonwert-Kurve auf den Bildschirm (wie eine Kamera).
//
// ColorModulator: x = Zeit (s), y = Himmel (0 Black Hole, 1 Ringed Giant, 2 Dusk, 3 Moonlit; 4 = Weltraum backen),
// z = Helligkeit.
// skyDir: Blickrichtung in der Welt (y = oben), vom Vertex-Shader (Welt: Wuerfel um die Kamera, Menue: Kamerastrahl).

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec3 skyDir;

layout(location = 0) out vec4 fragColor;

const float PI = 3.14159265;
const float TAU = 6.28318531;
const float DEG = 0.0174532925;

// ================================================================ Zufall & Rauschen

// Ganzzahl-Hash - identisch in SkyVfx.java (Blitze hellen dort auch den Nebel auf)
uint hashU(uint x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    x ^= x >> 16;
    return x;
}

float hashF(uint a, uint b) {
    return float(hashU(a * 0x9E3779B9u ^ hashU(b + 0x632BE5ABu)) >> 8) / 16777216.0;
}

// "Hash without Sine" (Dave Hoskins, MIT)
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hash13(vec3 p3) {
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 hash33(vec3 p3) {
    p3 = fract(p3 * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yxz + 33.33);
    return fract((p3.xxy + p3.yxx) * p3.zyx);
}

// Wichtig: Rauschen heisst valueNoise1/2/3, NICHT noise1/2/3 - das sind eingebaute GLSL-Funktionen. NVIDIA erlaubt
// das Ueberschreiben, AMD-/Intel-Treiber brechen dann beim Kompilieren ab (Sky VFX bliebe dort der Vanilla-Himmel).

// Interleaved Gradient Noise (Jimenez): gleichmaessiges Pixelrauschen fuer Dithering und Jitter
float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

float valueNoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float valueNoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);
    float n000 = hash13(i);
    float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash13(i + vec3(1.0, 1.0, 1.0));
    return mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y),
               mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z);
}

// fbm, das Oktaven feiner als ein Pixel ausblendet (footprint = Eingabe-Einheiten je Pixel): kein Flimmern
float fbm2(vec2 p, int octaves, float footprint) {
    float sum = 0.0;
    float norm = 0.0;
    float amp = 0.5;
    float freq = 1.0;
    for (int i = 0; i < octaves; i++) {
        float keep = clamp(1.5 - footprint * freq * 2.0, 0.0, 1.0);
        sum += amp * mix(0.5, valueNoise2(p), keep);
        norm += amp;
        p = mat2(1.6, 1.2, -1.2, 1.6) * p + 7.31;
        freq *= 2.0;
        amp *= 0.5;
    }
    return sum / norm;
}

float fbm3(vec3 p, int octaves) {
    float sum = 0.0;
    float norm = 0.0;
    float amp = 0.5;
    for (int i = 0; i < octaves; i++) {
        sum += amp * valueNoise3(p);
        norm += amp;
        p = p * 2.03 + vec3(3.1, 1.7, 5.3);
        amp *= 0.5;
    }
    return sum / norm;
}

float sq(float v) {
    return v * v;
}

// ================================================================ Sterne

// Sterne als Lichtpunkte: Zellen im Raum um die Kamera, je Zelle hoechstens ein Stern, der sauber in seiner Zelle
// liegt - so wird nie ein Stern an einer Zellgrenze abgeschnitten. Groesse in Pixeln (pa = Winkel je Pixel), damit
// sie bei jeder Aufloesung und jedem Sichtfeld scharf bleiben.
vec3 starLayer(vec3 d, float pa, float scale, float density, float bright, float seed, float t) {
    vec3 p = d * scale;
    vec3 cell = floor(p);
    vec3 key = cell + seed;
    if (hash13(key + 0.37) > density) {
        return vec3(0.0);
    }
    vec3 h = hash33(key);
    vec3 s = normalize(cell + 0.25 + 0.5 * h) * scale;
    vec3 f = s - cell;
    const float MARGIN = 0.22;
    if (any(lessThan(f, vec3(MARGIN))) || any(greaterThan(f, vec3(1.0 - MARGIN)))) {
        return vec3(0.0);
    }
    float dist = length(p - s) / scale;
    float mag = hash13(key + 4.71);
    float size = min(pa * (0.55 + 0.55 * mag), MARGIN / scale * 0.45);
    float shape = exp(-dist * dist / (size * size));
    float twinkle = 0.72 + 0.28 * sin(t * (0.7 + 2.3 * h.x) + h.y * TAU);
    vec3 tint = mix(vec3(0.72, 0.80, 1.00), vec3(1.00, 0.86, 0.70), h.z * h.z);
    return tint * shape * bright * (0.2 + 0.8 * mag * mag) * twinkle;
}

vec3 stars(vec3 d, float pa, float t, float density) {
    return starLayer(d, pa, 190.0, 0.60 * density, 0.55, 0.0, t)
         + starLayer(d, pa, 80.0, 0.50 * density, 0.95, 17.0, t)
         + starLayer(d, pa, 34.0, 0.40 * density, 1.60, 41.0, t);
}

// ================================================================ Gemeinsam: Tonwerte, gebackene Textur

float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

float valueNoise1(float x) {
    float i = floor(x);
    float f = fract(x);
    return mix(hash11(i), hash11(i + 1.0), f * f * (3.0 - 2.0 * f));
}

// Lineares Licht (HDR) -> Bildschirm: dunkle Werte bleiben wie sie sind, helle laufen weich gegen Weiss
vec3 toDisplay(vec3 hdr) {
    return pow(vec3(1.0) - exp(-max(hdr, 0.0)), vec3(1.0 / 2.2));
}

// In der Welt (SPACE_BAKED) liegt eine vorberechnete Rundum-Textur in Sampler0: der Weltraum (Nebel, Milchstrasse)
// fuer Black Hole und Ringed Giant. SkyVfx backt sie einmal.
#ifdef SPACE_BAKED
uniform sampler2D Sampler0;
#endif

// Richtung -> Textur (Breite = Azimut, Hoehe = Winkel vom Zenit); Umkehrung in main() beim Backen
vec2 spaceUv(vec3 d) {
    return vec2(atan(d.x, d.z) * (0.5 / PI) + 0.5, acos(clamp(d.y, -1.0, 1.0)) / PI);
}

// ================================================================ Black Hole

// Schwarzes Loch, echt durchgerechnet (Schwarzschild, Einheit: Schwarzschild-Radius rs = 1): fuer jeden Pixel wird
// der Lichtstrahl von der Kamera aus rueckwaerts verfolgt und von der Schwerkraft gebogen (Photonen-Bahn mit
// a = -1.5 * h^2 * r / |r|^5). Daraus entstehen von selbst: der schwarze Schatten (Strahl faellt hinein), der hauchduenne
// Photonenring, die hintere Scheibenhaelfte, die sich ueber und unter den Schatten biegt (wie in "Interstellar"),
// weitere Ringbilder und der verzerrte Sternenhimmel drumherum. Die Scheibe leuchtet als Schwarzkoerper mit
// Novikov-Thorne-Temperaturprofil; Doppler-Effekt und Gravitations-Rotverschiebung machen die auf uns zu drehende
// Seite heller und blauer, die andere dunkler und roeter. Das Gas zieht in Schlieren und Klumpen um das Loch
// (innen schneller - die Muster scheren sich zu Spiralen), heisses Gas ueber und unter der Scheibe legt einen
// weichen Schein darum, und das Ganze geht ueber eine filmische Tonwert-Kurve wie bei einer echten Aufnahme.
// Sterne, deren Licht sehr nah am Loch vorbeikam, werden ausgeblendet (sie wuerden zu flimmernden Strichen).
const vec3 HOLE_DIR = vec3(0.0, 0.46947, -0.88295); // Azimut 180 Grad, 28 Grad ueber dem Horizont
const float HOLE_R = 7.5 * DEG;                     // scheinbarer Radius des Schattens
const float BH_CRIT = 2.5980762;                    // kritischer Stossparameter 3*sqrt(3)/2
const float BH_ELEV = 0.055;                        // Kamera knapp ueber der Scheibenebene (rad)
const float BH_TILT = 0.10;                         // Scheibe leicht gekippt (rad, um die Blickachse)
const float DISK_IN = 3.0;                          // innerste stabile Kreisbahn (ISCO)
const float DISK_OUT = 11.0;
const float DISK_KELVIN = 3900.0;                   // heisseste Stelle der Scheibe (gold, innen heller)
const int BH_STEPS = 240;

const vec3 SPACE_BAND = vec3(0.29966, 0.82906, -0.46948); // normalize(0.30, 0.83, -0.47)
// Gebackener Weltraum: Farbe + OFFSET, mal SCALE (passt so in 8 Bit, ohne dunkle Stufen)
const float SPACE_OFFSET = 0.02;
const float SPACE_SCALE = 2.5;

// Weltraum ohne Sterne: Milchstrassen-Band mit Staubbahnen, ferne farbige Nebel. Aendert sich nie - in der Welt wird
// er einmal in eine Textur gebacken (SPACE_BAKED) statt fuer jeden Pixel neu gerechnet (zwoelf 3D-Rauschwerte).
vec3 spaceNebula(vec3 d) {
    float b = dot(d, SPACE_BAND);
    float band = exp(-b * b / 0.030);
    float wide = exp(-b * b / 0.20);
    float neb = fbm3(d * 2.6 + 3.0, 4);
    float lanes = smoothstep(0.48, 0.70, fbm3(d * 5.0 + 23.0, 3));
    float glow = band * (0.35 + 0.8 * neb) * (1.0 - 0.75 * lanes * band) + wide * 0.18 * neb;
    vec3 tint = mix(vec3(0.42, 0.30, 0.85), vec3(0.95, 0.62, 0.40), smoothstep(0.35, 0.75, valueNoise3(d * 1.4 + 41.0)));
    vec3 col = vec3(0.004, 0.005, 0.014) + tint * glow * 0.20 + tint * (neb - 0.5) * band * 0.08;
    // ferne farbige Nebel
    float cloud = smoothstep(0.55, 0.85, fbm3(d * 1.8 + 77.0, 3));
    col += mix(vec3(0.30, 0.10, 0.45), vec3(0.08, 0.20, 0.45), valueNoise3(d * 3.3)) * cloud * 0.10;
    return col;
}

// Weltraum rundum: Nebel (gebacken oder gerechnet), dazu Sterne (im Band dichter, starGain 0..1)
vec3 spaceBackground(vec3 d, float t, float pa, float starGain) {
#ifdef SPACE_BAKED
    vec3 col = textureLod(Sampler0, spaceUv(d), 0.0).rgb / SPACE_SCALE - SPACE_OFFSET;
#else
    vec3 col = spaceNebula(d);
#endif
    float b = dot(d, SPACE_BAND);
    col += stars(d, pa, t, 0.55 + 0.45 * exp(-b * b / 0.030)) * starGain;
    return col;
}

// Farbe eines Schwarzkoerpers (Naeherung nach Tanner Helland), groesster Kanal = 1
vec3 blackbody(float kelvin) {
    float t = clamp(kelvin, 1000.0, 40000.0) / 100.0;
    vec3 c;
    c.r = t <= 66.0 ? 1.0 : 1.29293618 * pow(max(t - 60.0, 1.0), -0.1332047592);
    c.g = t <= 66.0 ? 0.39008158 * log(t) - 0.63184144 : 1.12989086 * pow(max(t - 60.0, 1.0), -0.0755148492);
    c.b = t >= 66.0 ? 1.0 : (t <= 19.0 ? 0.0 : 0.54320679 * log(max(t - 10.0, 1.0)) - 1.19625409);
    return clamp(c, 0.0, 1.0);
}

// Temperaturprofil einer duennen Scheibe, 1 an der heissesten Stelle (r = 49/36 * ISCO)
float diskTemperature(float r) {
    float x = DISK_IN / r;
    return pow(x, 0.75) * pow(max(1.0 - sqrt(x), 0.0), 0.25) / 0.48778;
}

// Licht der Scheibe dort, wo der Strahl die Scheibenebene durchstoesst: rgb = Leuchten, a = Deckkraft
vec4 diskSample(vec3 hit, vec3 vel, float t, float px) {
    float r = length(hit.xz);
    if (r < DISK_IN || r > DISK_OUT) {
        return vec4(0.0);
    }
    // Gas kreist (innen schneller, Kepler): Muster dreht mit und schert sich zu Spiralen. Rauschen im Raum
    // (cos, sin, r): ohne Naht rundum; entlang der Bahn langsam, quer dazu schnell veraenderlich = Schlieren
    float phi = atan(hit.z, hit.x);
    float a = phi + t * 1.6 * pow(r, -1.5);
    vec2 ring = vec2(cos(a), sin(a));
    float clumps = fbm3(vec3(ring * 1.8, r * 1.1) + 4.0, 4);
    float streaks = fbm3(vec3(ring * 0.9, r * 4.2) + 11.0, 3);
    float wisps = smoothstep(0.30, 0.78, streaks);
    float edge = smoothstep(DISK_IN, DISK_IN + 0.5, r) * (1.0 - smoothstep(DISK_OUT - 5.5, DISK_OUT, r));
    float density = edge * clamp(0.12 + 1.05 * clumps * (0.45 + 0.85 * wisps), 0.0, 1.3);
    // Bahngeschwindigkeit (gemessen vom ruhenden Beobachter) und Rotverschiebung bis zur Kamera
    float v = sqrt(0.5 / (r - 1.0));
    float gamma = inversesqrt(1.0 - v * v);
    vec3 tangent = normalize(vec3(-hit.z, 0.0, hit.x));
    vec3 toCamera = -normalize(vel);
    float g = sqrt(1.0 - 1.0 / r) / (gamma * (1.0 - v * dot(tangent, toCamera)));
    float temp = diskTemperature(r);
    float bright = pow(temp, 2.2) * pow(g, 3.5);
    vec3 col = blackbody(DISK_KELVIN * temp * g) * bright * 3.0;
    return vec4(col * density, clamp(density * 0.9, 0.0, 0.93));
}

// Drehung um die Blickachse (Scheibe leicht schraeg)
vec2 tilt(vec2 p, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return vec2(c * p.x - s * p.y, s * p.x + c * p.y);
}

// Haze fuer Temperaturen bis 6600 K (immer, die Scheibe ist hoechstens 4100 K heiss): gleiche Werte wie blackbody(),
// nur ohne die Zweige, die dort nie greifen
vec3 blackbodyCool(float kelvin) {
    float t = clamp(kelvin, 1000.0, 6600.0) / 100.0;
    vec3 c = vec3(1.0, 0.39008158 * log(t) - 0.63184144,
                  t <= 19.0 ? 0.0 : 0.54320679 * log(max(t - 10.0, 1.0)) - 1.19625409);
    return clamp(c, 0.0, 1.0);
}

// Ausserhalb dieses Abstands passiert nichts mehr ausser Lichtbeugung (Haze nur bis |y| < HAZE_Y, Scheibe bis 11):
// sqrt((DISK_OUT * 1.3)^2 + HAZE_Y^2) = 15.15
const float BH_QUIET = 15.2;
// Haze weiter als so weit ueber/unter der Scheibe ist unsichtbar (exp(-2.2 * 5) = 0.00002)
const float HAZE_Y = 5.0;
// Strahlen mehr als so weit (rad) neben dem Loch kommen ihm nie naeher als BH_QUIET: nur die Beugung, kein Durchrechnen
const float BH_SKIP = 0.9054070;
const float BH_FIT_LO = 0.88540700;

// Beugung (rad) eines Strahls, der psi (rad) neben dem Loch startet, fuer psi > BH_SKIP: genau so wie beim
// Durchrechnen unten (inkl. Restbeugung nach dem Austritt), auf 0.00008 rad genau angenaehert (Polynom, Grad 12)
float bhDeflection(float psi) {
    float x = 2.0 * (psi - BH_FIT_LO) / (PI - BH_FIT_LO) - 1.0;
    float y = -8.931125477e-3;
    y = y * x - 9.779703236e-4;
    y = y * x + 2.666873642e-2;
    y = y * x + 4.719640992e-3;
    y = y * x - 3.359560242e-2;
    y = y * x - 3.886873511e-3;
    y = y * x + 1.702229275e-2;
    y = y * x + 8.726300055e-3;
    y = y * x - 2.092342286e-3;
    y = y * x - 3.925670123e-3;
    y = y * x - 4.135605865e-2;
    y = y * x + 6.089863449e-2;
    y = y * x - 2.328140024e-2;
    return y;
}

vec3 blackHoleSky(vec3 d, float t, float pa) {
    // Welt -> Kamera: Mitte = Richtung zum Loch
    vec3 center = HOLE_DIR;
    vec3 rightW = normalize(cross(vec3(0.0, 1.0, 0.0), center));
    vec3 upW = cross(center, rightW);
    vec3 view = vec3(dot(d, rightW), dot(d, upW), dot(d, center));
    view.xy = tilt(view.xy, BH_TILT);

    // Weit neben dem Loch: der Strahl wird nur gebeugt (das Loch ist kugelsymmetrisch -> Beugung haengt nur vom
    // Winkel zum Loch ab) - gleiches Ergebnis wie das Durchrechnen, ohne die bis zu 240 Schritte
    float psi = acos(clamp(view.z, -1.0, 1.0));
    if (psi > BH_SKIP) {
        float psiOut = psi + bhDeflection(psi);
        vec2 side = view.xy / max(length(view.xy), 1.0e-6);
        vec3 back = vec3(side * sin(psiOut), cos(psiOut));
        back.xy = tilt(back.xy, -BH_TILT);
        return spaceBackground(normalize(back.x * rightW + back.y * upW + back.z * center), t, pa, 1.0);
    }

    // Kamera im Koordinatensystem des Lochs (Scheibe in der x-z-Ebene), Abstand passend zum scheinbaren Schatten
    float dist = BH_CRIT / tan(HOLE_R);
    vec3 camPos = dist * vec3(0.0, sin(BH_ELEV), -cos(BH_ELEV));
    vec3 f = normalize(-camPos);
    vec3 camR = normalize(cross(vec3(0.0, 1.0, 0.0), f));
    vec3 camU = cross(f, camR);

    vec3 pos = camPos;
    vec3 vel = normalize(view.x * camR + view.y * camU + view.z * f);
    vec3 l = cross(pos, vel);
    float h2 = dot(l, l);
    float px = pa / HOLE_R * BH_CRIT; // Pixelgroesse in rs am Loch (gegen Flimmern im Scheibenmuster)

    vec3 disk = vec3(0.0);
    float haze = 0.0;
    float hazeHeat = 0.0;
    float alpha = 0.0;
    bool captured = false;
    bool escaped = false;
    float rmin = 1.0e9;
    for (int i = 0; i < BH_STEPS; i++) {
        float r2 = dot(pos, pos);
        float r = sqrt(r2);
        rmin = min(rmin, r);
        if (r < 1.0) {
            captured = true;
            break;
        }
        bool outward = dot(pos, vel) > 0.0;
        if (r > dist * 1.6 && outward) {
            escaped = true;
            break;
        }
        // Schrittweite: nah am Loch fein (Photonenring), weit weg grob; auf dem Rueckweg ausserhalb von BH_QUIET
        // passiert nichts mehr ausser leichter Beugung -> grosse Schritte
        float dt = r > BH_QUIET && outward ? clamp(0.16 * (r - 0.85), 0.012, 5.0) : clamp(0.1 * (r - 0.85), 0.012, 1.2);
        vec3 prev = pos;
        // Leapfrog (stabil, haelt die Bahnen sauber)
        vel += -1.5 * h2 * pos / (r2 * r2 * r) * (dt * 0.5);
        pos += vel * dt;
        r2 = dot(pos, pos);
        r = sqrt(r2);
        vel += -1.5 * h2 * pos / (r2 * r2 * r) * (dt * 0.5);
        // heisses Gas ueber und unter der Scheibe: weicher Schein (wirkt wie Ueberstrahlung)
        if (abs(pos.y) < HAZE_Y) {
            float rd = length(pos.xz);
            if (rd > DISK_IN * 0.8 && rd < DISK_OUT * 1.3) {
                float x = DISK_IN / max(rd, DISK_IN + 0.01);
                float w = x * x * x * x * exp(-abs(pos.y) * 2.4) * dt * (1.0 - alpha);
                haze += w;
                hazeHeat += w * x;
            }
        }
        // Scheibenebene durchstossen?
        if (prev.y * pos.y < 0.0) {
            vec3 hit = mix(prev, pos, prev.y / (prev.y - pos.y));
            vec4 sampleColor = diskSample(hit, vel, t, px);
            disk += (1.0 - alpha) * sampleColor.rgb;
            alpha += (1.0 - alpha) * sampleColor.a;
            if (alpha > 0.97) {
                break;
            }
        }
    }

    vec3 col = vec3(0.0);
    // Sterne nahe am Loch (Licht kam sehr nah vorbei) ausblenden: sie wuerden zu flimmernden Strichen gezogen
    float starGain = smoothstep(3.0, 9.0, rmin);
    if (!captured && alpha < 0.97) {
        // Strahl entkommt: dort steht der (verzerrte) Sternenhimmel
        vec3 v = normalize(vel);
        if (escaped) {
            // restliche Beugung bis ins Unendliche (gerade weiter gedacht), damit die Richtung nicht davon abhaengt,
            // wo genau der letzte Schritt endete
            float s0 = dot(pos, v);
            float r0 = length(pos);
            v = normalize(v - (pos - v * s0) * (h2 * (2.0 + s0 / r0) / (2.0 * r0 * r0 * sq(r0 + s0) * dot(vel, vel))));
        }
        vec3 back = vec3(dot(v, camR), dot(v, camU), dot(v, f));
        back.xy = tilt(back.xy, -BH_TILT);
        vec3 world = normalize(back.x * rightW + back.y * upW + back.z * center);
        col = spaceBackground(world, t, pa, starGain) * (1.0 - alpha);
    }
    // Licht in linearen Werten: Scheibe, Schein des heissen Gases, Streiflicht der Photonensphaere (r = 1.5)
    vec3 glow = haze > 0.0 ? blackbodyCool(DISK_KELVIN * pow(hazeHeat / haze, 0.75)) * haze : vec3(0.0);
    // Schein zur Grenze der Durchrechnung hin ausblenden (dahinter gibt es keinen) - sonst sieht man eine Kante
    glow *= smoothstep(BH_SKIP, BH_SKIP - 0.25, psi);
    vec3 light = disk * 0.95 + (captured ? vec3(0.0) : glow * 0.32);
    if (!captured) {
        light += vec3(1.0, 0.74, 0.48) * 0.10 * exp(-max(rmin - 1.5, 0.0) * 4.0);
    }
    // etwas Saettigung zurueck (die Tonwert-Kurve nimmt sie), dann wie eine Kamera belichten
    float grey = dot(light, vec3(0.2126, 0.7152, 0.0722));
    light = max(mix(vec3(grey), light, 1.25), 0.0);
    return toDisplay(pow(col, vec3(2.2)) + light);
}

// ================================================================ Ringed Giant

// Gasplanet mit Ringen am Himmel, in einiger Entfernung, im Weltraum (Sterne und Nebel wie beim Black Hole). Alles
// exakt gerechnet: Kugel- und Ringebene werden mit dem Blickstrahl geschnitten. Der Planet hat Wolkenbaender mit
// Turbulenz, einen grossen Sturm und einen dunstigen Rand; die Ringe bestehen aus vielen feinen Ringeln mit
// Cassini-Teilung. Die Sonne steht fast hinter dir - Planet und Ringe sind voll beleuchtet, ohne Schatten. Dazu zwei
// Monde.
const vec3 RG_PLANET = vec3(0.62250, 0.30121, 0.72235);   // Richtung zur Planetenmitte (Azimut 41, 17.5 Grad hoch)
const float RG_SIN = 0.1391731;                            // sin(8 Grad): scheinbarer Radius
const vec3 RG_SUN = vec3(-0.66086, -0.17022, -0.73095);    // Richtung zur Sonne: fast hinter dir
const vec3 RG_AXIS = vec3(0.30151, 0.90453, -0.30151);     // Drehachse = Normale der Ringebene
const float RING_IN = 1.24;
const float RING_OUT = 2.30;
const vec3 MOON_A = vec3(0.24830, 0.47062, 0.84668);       // Mond 1 (Richtung)
const vec3 MOON_B = vec3(0.86413, 0.18530, 0.46792);       // Mond 2

// Dichte der Ringe beim Abstand rr (Planetenradien): Ringe C, B, Cassini-Teilung, A mit Encke-Luecke, F-Ring.
// foot = Aenderung von rr je Pixel: feine Ringel, die kleiner als ein Pixel waeren, mitteln sich weg.
float ringDensity(float rr, float foot) {
    if (rr < RING_IN || rr > RING_OUT) {
        return 0.0;
    }
    float d = 0.0;
    d += 0.16 * smoothstep(1.24, 1.27, rr) * (1.0 - smoothstep(1.50, 1.53, rr));          // C-Ring
    d += 0.92 * smoothstep(1.52, 1.56, rr) * (1.0 - smoothstep(1.93, 1.95, rr));          // B-Ring
    d += 0.06 * smoothstep(1.95, 1.97, rr) * (1.0 - smoothstep(2.01, 2.03, rr));          // Cassini-Teilung
    d += 0.62 * smoothstep(2.02, 2.04, rr) * (1.0 - smoothstep(2.25, 2.27, rr));          // A-Ring
    d *= 1.0 - 0.9 * exp(-sq((rr - 2.205) / 0.004));                                       // Encke-Luecke
    d += 0.55 * exp(-sq((rr - 2.295) / 0.0035));                                           // F-Ring
    // feine Ringel in mehreren Groessen
    float fine = 0.0;
    float norm = 0.0;
    float freq = 40.0;
    float amp = 1.0;
    for (int i = 0; i < 4; i++) {
        float keep = clamp(1.0 - foot * freq * 2.0, 0.0, 1.0);
        fine += amp * mix(0.5, valueNoise1(rr * freq + float(i) * 17.0), keep);
        norm += amp;
        freq *= 2.7;
        amp *= 0.6;
    }
    return clamp(d * (0.55 + 0.9 * (fine / norm)), 0.0, 1.0);
}

vec3 ringColor(float rr) {
    vec3 inner = vec3(0.55, 0.48, 0.40);
    vec3 mid = vec3(0.92, 0.84, 0.70);
    vec3 outer = vec3(0.80, 0.76, 0.70);
    vec3 c = mix(inner, mid, smoothstep(1.45, 1.70, rr));
    return mix(c, outer, smoothstep(2.0, 2.25, rr)) * (0.9 + 0.2 * valueNoise1(rr * 13.0));
}

// Kugel (Mitte c, Radius 1) mit Strahl ab Ursprung: Abstand zum vorderen Treffer oder -1
float hitSphere(vec3 d, vec3 c, float radius) {
    float b = dot(d, c);
    float h = b * b - dot(c, c) + radius * radius;
    if (h < 0.0) {
        return -1.0;
    }
    float t = b - sqrt(h);
    return t > 0.0 ? t : -1.0;
}

// Wolkenbaender des Planeten an der Oberflaechen-Normale n
vec3 giantSurface(vec3 n, float t, float pa) {
    vec3 e1 = normalize(cross(RG_AXIS, vec3(0.0, 0.0, 1.0)));
    vec3 e2 = cross(RG_AXIS, e1);
    float lat = dot(n, RG_AXIS);
    // langsame Drehung: Laengengrad verschieben
    float spin = t * 0.004;
    vec2 lon = vec2(dot(n, e1), dot(n, e2));
    lon = vec2(lon.x * cos(spin) - lon.y * sin(spin), lon.x * sin(spin) + lon.y * cos(spin));
    vec3 q = vec3(lon * 2.4, lat * 3.0);
    float turb = fbm3(q * 1.6 + vec3(0.0, 0.0, t * 0.002), 4);
    float bands = sin(lat * 26.0 + turb * 4.2) * 0.5 + 0.5;
    float fine = sin(lat * 71.0 + turb * 7.0) * 0.5 + 0.5;
    vec3 cream = vec3(0.93, 0.86, 0.72);
    vec3 ochre = vec3(0.78, 0.58, 0.38);
    vec3 brown = vec3(0.52, 0.36, 0.25);
    vec3 col = mix(ochre, cream, bands);
    col = mix(col, brown, smoothstep(0.65, 1.0, fine) * 0.35);
    // Pole kuehler
    col = mix(col, vec3(0.55, 0.62, 0.70), smoothstep(0.62, 0.95, abs(lat)) * 0.6);
    // grosser Sturm (Oval) auf der Suedhalbkugel
    vec2 storm = vec2(lon.x - 0.55, (lat + 0.32) * 2.2);
    float oval = exp(-dot(storm, storm) / 0.018);
    float swirl = fbm3(vec3(storm * 9.0, t * 0.01), 3);
    col = mix(col, vec3(0.80, 0.42, 0.28), oval * (0.55 + 0.45 * swirl));
    return col;
}

vec3 ringedGiantSky(vec3 d, float t, float pa) {
    float dist = 1.0 / RG_SIN;
    vec3 c = RG_PLANET * dist;
    vec3 sunCol = vec3(1.0, 0.96, 0.90) * 2.2;

    // Hintergrund: Weltraum, Sonne, Monde
    vec3 col = pow(spaceBackground(d, t, pa, 1.0), vec3(2.2));
    float sunDot = dot(d, RG_SUN);
    col += vec3(1.0, 0.92, 0.80) * (exp((sunDot - 1.0) * 9000.0) * 40.0 + exp((sunDot - 1.0) * 120.0) * 0.25
            + exp((sunDot - 1.0) * 12.0) * 0.04);
    vec3 moons[2] = vec3[2](MOON_A, MOON_B);
    float moonR[2] = float[2](0.020, 0.011);
    for (int i = 0; i < 2; i++) {
        float tm = hitSphere(d, moons[i] * 40.0, moonR[i] * 40.0);
        if (tm > 0.0) {
            vec3 mn = normalize(d * tm - moons[i] * 40.0);
            float light = smoothstep(-0.05, 0.3, dot(mn, RG_SUN));
            float rough = 0.75 + 0.25 * fbm3(mn * 6.0 + float(i) * 9.0, 3);
            col = vec3(0.62, 0.60, 0.58) * rough * light * sunCol * 0.6;
        }
    }

    // Planet
    float tp = hitSphere(d, c, 1.0);
    vec3 planet = vec3(0.0);
    if (tp > 0.0) {
        vec3 p = d * tp;
        vec3 n = normalize(p - c);
        float ndl = dot(n, RG_SUN);
        vec3 albedo = giantSurface(n, t, pa);
        float day = smoothstep(-0.12, 0.35, ndl) * max(ndl + 0.12, 0.0) / 1.12;
        planet = albedo * sunCol * day * 0.55;
        // Dunst am Rand (von der Sonne her angestrahlt)
        float rim = pow(1.0 - clamp(dot(n, -d), 0.0, 1.0), 3.0);
        planet += vec3(0.45, 0.55, 0.75) * rim * smoothstep(-0.2, 0.4, ndl) * 0.35;
        // Nachtseite: schwacher Schein der Ringe
        planet += albedo * vec3(0.012, 0.012, 0.016) * (1.0 - smoothstep(-0.1, 0.2, ndl));
        col = planet;
    }

    // Ringe (vor oder hinter dem Planeten)
    float dn = dot(d, RG_AXIS);
    if (abs(dn) > 1.0e-4) {
        float tr = dot(c, RG_AXIS) / dn;
        if (tr > 0.0 && (tp < 0.0 || tr < tp)) {
            vec3 p = d * tr;
            vec3 m = p - c;
            float rr = length(m);
            float foot = fwidth(rr);
            float density = ringDensity(rr, foot);
            if (density > 0.0) {
                float lightSide = sign(dot(RG_SUN, RG_AXIS)) * sign(dn) < 0.0 ? 1.0 : 0.35;
                vec3 ring = ringColor(rr) * sunCol * 0.32 * lightSide;
                col = mix(col, ring, density * 0.92);
            }
        }
    }
    return toDisplay(col);
}

// ================================================================ Dusk

// Ruhiger Abendhimmel - bewusst schlicht: weicher Verlauf von tiefem Blau oben ueber Violett zu einem warmen
// Schimmer am Horizont, im Westen etwas Rest-Glimmen der Sonne, oben wenige ruhige Sterne und ein hauchduenner
// Schleier, der sich kaum bewegt. Unter dem Horizont dunkelt er sanft ab. Nichts blinkt, nichts lenkt ab.
const vec3 DUSK_SUN = vec3(-0.92388, 0.0, 0.38268); // Westen, leicht nach Sueden

vec3 duskSky(vec3 d, float t, float pa) {
    float up = max(d.y, 0.0);
    vec3 zenith = vec3(0.010, 0.014, 0.046);
    vec3 mid = vec3(0.040, 0.036, 0.110);
    vec3 horizon = vec3(0.30, 0.16, 0.21);
    vec3 col = mix(mid, zenith, smoothstep(0.0, 0.8, pow(up, 0.6)));
    vec2 flat2 = d.xz / max(length(d.xz), 1.0e-4);
    float toward = max(dot(flat2, DUSK_SUN.xz), 0.0);
    float band = exp(-up * 9.0) * (0.5 + 0.5 * toward * toward);
    col = mix(col, horizon, band * 0.85);
    col += vec3(0.32, 0.15, 0.07) * exp(-up * 20.0) * pow(toward, 8.0) * 0.55;
    if (d.y < 0.0) {
        col = mix(mix(horizon, mid, 0.55) * 0.55, vec3(0.008, 0.008, 0.020), smoothstep(0.0, 0.35, -d.y));
    }
    float high = smoothstep(0.15, 0.6, up);
    col += stars(d, pa, t * 0.3, 0.32) * high * 0.5;
    float veil = valueNoise3(d * 3.5 + vec3(t * 0.006, 0.0, t * 0.004));
    col += vec3(0.045, 0.030, 0.085) * veil * smoothstep(0.03, 0.3, up) * (1.0 - smoothstep(0.45, 0.95, up)) * 0.6;
    return toDisplay(col);
}

// ================================================================ Moonlit

// Stille Mondnacht: tiefes Nachtblau, zum Horizont etwas heller, ein klarer Mond mit weichem Hof und feiner
// Oberflaeche, ruhige Sterne (in Mondnaehe weniger, wie in echt). Bewegt sich kaum - zum Entspannen.
const vec3 MOON_DIR = vec3(0.61950, 0.52955, -0.57954); // Azimut 133 Grad, 32 Grad ueber dem Horizont
const float MOON_R = 2.4 * DEG;

vec3 moonlitSky(vec3 d, float t, float pa) {
    float up = max(d.y, 0.0);
    vec3 zenith = vec3(0.003, 0.006, 0.020);
    vec3 horizon = vec3(0.018, 0.032, 0.072);
    vec3 col = mix(horizon, zenith, smoothstep(0.0, 0.65, pow(up, 0.7)));
    float angle = acos(clamp(dot(d, MOON_DIR), -1.0, 1.0));
    col += vec3(0.11, 0.14, 0.22) * exp(-angle * 7.5) * 0.55 + vec3(0.04, 0.06, 0.11) * exp(-angle * 2.2) * 0.3;
    float stars0 = smoothstep(0.04, 0.4, up) * (1.0 - exp(-angle * 5.0));
    col += stars(d, pa, t * 0.25, 0.55) * stars0 * 0.75;
    float disc = smoothstep(MOON_R + pa, MOON_R - pa, angle);
    if (disc > 0.0) {
        float maria = valueNoise3(d * 160.0) * 0.6 + valueNoise3(d * 420.0) * 0.4;
        vec3 moon = vec3(0.96, 0.94, 0.88) * (0.78 + 0.26 * maria) * (0.86 + 0.14 * (1.0 - angle / MOON_R));
        col = mix(col, moon * 1.7, disc);
    }
    if (d.y < 0.0) {
        col = mix(horizon * 0.7, vec3(0.003, 0.004, 0.010), smoothstep(0.0, 0.3, -d.y));
    }
    return toDisplay(col);
}

// ================================================================

void main() {
    int style = int(ColorModulator.y + 0.5);
    if (style >= 4) {
        // Backen (SkyVfx): Pixel -> Richtung (Umkehrung von spaceUv), ColorModulator.zw = Texturgroesse.
        // 4 = Weltraum (Nebel, Milchstrasse) fuer Black Hole und Ringed Giant
        vec2 uv = gl_FragCoord.xy / ColorModulator.zw;
        float azimuth = (uv.x - 0.5) * TAU;
        float polar = uv.y * PI;
        vec3 dir = vec3(sin(polar) * sin(azimuth), cos(polar), sin(polar) * cos(azimuth));
        fragColor = vec4(clamp((spaceNebula(dir) + SPACE_OFFSET) * SPACE_SCALE, 0.0, 1.0), 1.0);
        return;
    }
    vec3 d = normalize(skyDir);
    float pa = max(max(length(dFdx(d)), length(dFdy(d))), 1.0e-5);
    float t = ColorModulator.x;
    vec3 col;
    if (style == 3) {
        col = moonlitSky(d, t, pa);
    } else if (style == 2) {
        col = duskSky(d, t, pa);
    } else if (style == 1) {
        col = ringedGiantSky(d, t, pa);
    } else {
        col = blackHoleSky(d, t, pa);
    }
    col *= ColorModulator.z;
    // Dithering gegen Farbstufen in den dunklen Verlaeufen
    col += (ign(gl_FragCoord.xy) - 0.5) / 255.0;
    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
