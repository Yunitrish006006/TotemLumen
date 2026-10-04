#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D DepthSampler;
// Upper half: radiance. Lower half: raw IEEE float distance transported in RGBA8 bytes.
uniform sampler2D TotemSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

float rayDepth(ivec2 pixel, ivec2 extent) {
    uvec4 bytes = uvec4(round(texelFetch(TotemSampler, pixel + ivec2(0, extent.y), 0) * 255.0));
    return uintBitsToFloat(bytes.r | (bytes.g << 8u) | (bytes.b << 16u) | (bytes.a << 24u));
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    // Reverse-Z clear/sky has no surface. Keep the raster sky and all distant terrain.
    if (depth <= 0.000001) discard;
    float clipZ = TextureMat[3].w > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 relative = ModelViewMat * vec4(texCoord * 2.0 - 1.0, clipZ, 1.0);
    if (abs(relative.w) < 0.000001) discard;
    vec3 currentPosition = relative.xyz / relative.w;
    vec3 previousPosition = currentPosition + TextureMat[3].xyz;
    float forwardDistance = dot(previousPosition, TextureMat[2].xyz);
    float limit = TextureMat[2].w;
    float currentDistance = length(currentPosition);
    if (forwardDistance <= 0.0 || currentDistance >= limit) discard;
    vec2 previousNdc = vec2(dot(previousPosition, TextureMat[0].xyz)
            / (forwardDistance * TextureMat[0].w * TextureMat[1].w),
            dot(previousPosition, TextureMat[1].xyz) / (forwardDistance * TextureMat[0].w));
    vec2 uv = previousNdc * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) discard;
    ivec2 extent = textureSize(TotemSampler, 0) / ivec2(1, 2);
    ivec2 pixel = ivec2(uv * vec2(extent));
    float tracedDistance = rayDepth(pixel, extent);
    if (isnan(tracedDistance) || isinf(tracedDistance) || tracedDistance <= 0.0) discard;
    // Never paint a ray miss, a different foreground surface, or a disoccluded background.
    float tolerance = max(0.03, length(previousPosition) * TextureMat[0].w / float(extent.y));
    if (abs(tracedDistance - length(previousPosition)) > tolerance) discard;
    float weight = 1.0 - smoothstep(limit * ColorModulator.x, limit,
            max(currentDistance, tracedDistance));
    if (weight <= 0.0) discard;
    fragColor = vec4(texelFetch(TotemSampler, pixel, 0).rgb, weight);
}
