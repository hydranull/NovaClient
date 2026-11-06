#version 330
#extension GL_ARB_separate_shader_objects : require

// Hyper Tools: Schweif der Klinge beim Schlagen - die Flaeche, die die Klinge in den letzten Bildern ueberstrichen
// hat. Frisch hell und fast weiss, mit dem Alter in der Klingenfarbe ausblendend; am Griff weich auslaufend.
// Vormultipliziertes Alpha wie die Klinge.

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    float along = clamp(texCoord0.x, 0.0, 1.0);
    float age = clamp(texCoord0.y, 0.0, 1.0);
    float fade = 1.0 - age;
    fade *= fade;
    // zur Spitze hin heller (dort bewegt sich die Klinge am schnellsten), am Griff fast nichts
    float shape = smoothstep(0.12, 0.9, along) * (1.0 - 0.35 * smoothstep(0.94, 1.0, along));
    float a = fade * shape * vertexColor.a;
    vec3 rgb = mix(vertexColor.rgb, vec3(1.0), 0.35 * fade) * a * 0.85;
    if (a < 0.002) {
        discard;
    }
    fragColor = vec4(rgb, a * 0.16);
}
