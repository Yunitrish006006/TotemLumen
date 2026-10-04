#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
uniform sampler2D DepthSampler;
uniform sampler2D LightingSampler;
uniform sampler2D SceneSampler;
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

float continuousSlope(float center, float a, float b) {
    // Use the shallower side so a silhouette jump cannot inflate the acceptance radius.
    if (a <= 0.0 || b <= 0.0) return 0.0;
    return min(abs(center - a), abs(b - center));
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    if (TextureMat[1].x > 0.5) {
        // Entire diagnostic opaque frame, including far geometry. Missing geometry is black.
        // Never mix native lit colour or native final-scene depth with the material capture.
        fragColor = vec4(0, 0, 0, 1);
        if (depth <= 0.000001) return;
        vec4 base = texture(SceneSampler, texCoord);
        if (base.a <= 0.0) return;
        float z = TextureMat[0].z > 0.5 ? depth : depth * 2.0 - 1.0;
        vec4 p = ModelViewMat * vec4(texCoord * 2.0 - 1.0, z, 1.0);
        float distance = length(p.xyz / p.w);
        if (isnan(distance) || isinf(distance)) return;
        vec4 lighting = texture(LightingSampler, texCoord);
        vec3 illumination = vec3(0.08);
        vec2 pixel = 1.0 / vec2(textureSize(DepthSampler, 0));
        vec2 footprint = vec2(textureSize(DepthSampler, 0)) / vec2(textureSize(LightingSampler, 0));
        float slopeX = continuousSlope(distance, distanceAt(texCoord - vec2(pixel.x, 0)), distanceAt(texCoord + vec2(pixel.x, 0)));
        float slopeY = continuousSlope(distance, distanceAt(texCoord - vec2(0, pixel.y)), distanceAt(texCoord + vec2(0, pixel.y)));
        // A low-resolution sample is up to half a footprint away on a continuous plane.
        // Constant radial tolerance alone rejects alternating rows on oblique floors.
        float tolerance = max(0.08, distance * 0.01) + 0.5 * dot(vec2(slopeX, slopeY), footprint);
        if (distance < TextureMat[0].x && lighting.a > 0.0
                && abs(lighting.a - distance) <= tolerance) illumination = lighting.rgb;
        fragColor = vec4(base.rgb * illumination, 1);
        return;
    }
    if (depth <= 0.000001) discard;
    float z = TextureMat[0].z > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 p = ModelViewMat * vec4(texCoord * 2.0 - 1.0, z, 1.0);
    float distance = length(p.xyz / p.w);
    if (isnan(distance) || isinf(distance) || distance >= TextureMat[0].x) discard;
    vec4 lighting = texture(LightingSampler, texCoord);
    // Reject mismatched surfaces at silhouettes. Native far geometry and sky stay untouched.
    if (lighting.a <= 0.0 || abs(lighting.a - distance) > max(0.08, distance * 0.01)) discard;
    // Preserve full-resolution vanilla albedo/detail. This remains an approximate correction
    // over already-lit color, not a substitute for a proper material G-buffer.
    fragColor = vec4(texture(SceneSampler, texCoord).rgb * lighting.rgb, 1);
}
