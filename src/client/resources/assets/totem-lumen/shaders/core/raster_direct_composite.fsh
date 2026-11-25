#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>

uniform sampler2D DepthSampler;
uniform sampler2D IndirectSampler;
uniform sampler2D NativeSceneSampler;
uniform sampler2D UnlitAlbedoSampler;
uniform sampler2D DirectSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

float distanceAt(vec2 uv) {
    float depth = texture(DepthSampler, uv).r;
    if (depth <= 0.000001) return -1.0;
    float z = TextureMat[0].z > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 p = ModelViewMat * vec4(uv * 2.0 - 1.0, z, 1.0);
    float d = length(p.xyz / p.w);
    return isnan(d) || isinf(d) ? -1.0 : d;
}

bool indirectMatches(float distance, vec4 indirect) {
    return indirect.a > 0.0
            && abs(indirect.a - distance) <= max(0.08, distance * 0.01);
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    if (depth <= 0.000001) discard;

    float distance = distanceAt(texCoord);
    if (distance <= 0.0) discard;

    vec4 indirect = texture(IndirectSampler, texCoord);
    bool nearIndirect = distance < TextureMat[0].x && indirectMatches(distance, indirect);

    vec4 albedo = texture(UnlitAlbedoSampler, texCoord);
    vec4 direct = texture(DirectSampler, texCoord);

    // Independent-lighting preview is bounded to material-covered near surfaces. Everything else
    // preserves the accepted native-lit fallback.
    if (distance < TextureMat[0].x && albedo.a > 0.0 && direct.a > 0.0) {
        vec3 giCorrection = nearIndirect ? clamp(indirect.rgb, vec3(0.0), vec3(2.0)) : vec3(1.0);
        vec3 ambient = vec3(0.08) * giCorrection;
        fragColor = vec4(albedo.rgb * (ambient + direct.rgb), 1.0);
        return;
    }

    if (!nearIndirect) discard;
    fragColor = vec4(texture(NativeSceneSampler, texCoord).rgb * indirect.rgb, 1.0);
}
