#version 330
#extension GL_ARB_separate_shader_objects : require

// Glowing Outlines: scharfe Kontur + weicher Glow, nur ausserhalb des Items (das Item selbst bleibt sichtbar)

uniform sampler2D MaskSampler;
uniform sampler2D BlurSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform CombineConfig {
    float Intensity;
    float OutlineWidth;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 texel = 1.0 / OutSize;
    float mask = texture(MaskSampler, texCoord).a;

    // Kontur: Item-Silhouette um OutlineWidth Pixel "aufblasen"
    int width = int(clamp(OutlineWidth, 0.0, 6.0) + 0.5);
    float edge = 0.0;
    vec3 edgeColor = vec3(0.0);
    for (int x = -6; x <= 6; x++) {
        for (int y = -6; y <= 6; y++) {
            if (x * x + y * y > width * width + width) {
                continue;
            }
            vec4 s = texture(MaskSampler, texCoord + vec2(x, y) * texel);
            if (s.a > edge) {
                edge = s.a;
                edgeColor = s.rgb;
            }
        }
    }

    vec4 blur = texture(BlurSampler, texCoord);
    float glow = clamp(blur.a * Intensity, 0.0, 1.0);
    vec3 glowColor = blur.a > 0.001 ? blur.rgb / blur.a : edgeColor;

    vec3 color = edge >= glow ? edgeColor : glowColor;
    float alpha = max(edge, glow) * (1.0 - mask);
    // Wird einmal ins Bild geblendet; so deckt der Glow genauso wie frueher (zweimal eingeblendet)
    alpha = alpha * (2.0 - alpha);
    fragColor = vec4(color, alpha);
}
