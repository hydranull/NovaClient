#version 330
#extension GL_ARB_separate_shader_objects : require

// Glowing Outlines: Gauss-Weichzeichner in eine Richtung (zweimal ausgefuehrt: waagrecht + senkrecht)

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform GlowConfig {
    vec2 BlurDir;
    float Radius;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    float radius = floor(clamp(Radius, 0.0, 96.0));
    if (radius < 1.0) {
        fragColor = texture(InSampler, texCoord);
        return;
    }
    vec2 sampleStep = BlurDir / InSize;
    float sigma = max(radius / 2.0, 1.0);
    vec4 sum = vec4(0.0);
    float weights = 0.0;
    // Hoechstens ~49 Abtastungen, bei grossem Radius mit groesseren Schritten (Bilinear glaettet dazwischen)
    float stepSize = max(1.0, radius / 24.0);
    for (float i = -radius; i <= radius; i += stepSize) {
        float w = exp(-(i * i) / (2.0 * sigma * sigma));
        sum += texture(InSampler, texCoord + sampleStep * i) * w;
        weights += w;
    }
    fragColor = sum / weights;
}
