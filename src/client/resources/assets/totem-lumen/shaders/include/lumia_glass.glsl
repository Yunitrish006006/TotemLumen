// Native terrain provides one optical material per face. All OIT phases evaluate this
// function before the phase-specific output. Even an unpainted clear texel is dielectric.
struct LumiaGlass {
    vec3 transmission;
    float thickness;
    float ior;
    float fresnel;
    float markings;
    float alpha;
};

LumiaGlass lumiaGlassMaterial(int bits, float textureAlpha, vec3 incident, vec3 normal) {
    bool pane = (bits & 2) != 0;
    int dye = (bits >> 2) & 31;
    int entry = 344 + 2 * (clamp(dye, 0, 16) + (pane ? 17 : 0));
    vec4 optical = texelFetch(LumiaLights, ivec2(entry, 0), 0);
    LumiaGlass glass;
    glass.ior = 1.0 + 2.0 * texelFetch(LumiaLights, ivec2(entry + 1, 0), 0).r;
    float cosine = clamp(dot(-incident, normal), 0.0, 1.0);
    float internalCosine = sqrt(max(0.0001, 1.0 - (1.0 - cosine * cosine) / (glass.ior * glass.ior)));
    glass.thickness = (pane ? 0.125 : 1.0) / internalCosine;
    float r = (glass.ior - 1.0) / (glass.ior + 1.0);
    glass.fresnel = r > 0.0 ? r * r + (1.0 - r * r) * pow(1.0 - cosine, 5.0) : 0.0;
    glass.transmission = pow(max(optical.rgb * (1.0 - optical.a), vec3(0.0001)), vec3(glass.thickness));
    // Vanilla stained faces use 102/255 alpha for the unmarked, uniformly dyed
    // interior. The palette already accounts for that dye in transmission.
    glass.markings = dye == 0 ? textureAlpha * 0.55 : max(0.0, textureAlpha - 0.4) * 0.4;
    // Separate the fraction that passes straight through the native blend (including
    // any earlier translucent layers) from the displaced, absorbed and reflected light.
    float straight = min(glass.transmission.r, min(glass.transmission.g, glass.transmission.b));
    float incidentSine = sqrt(max(0.0, 1.0 - cosine * cosine));
    float lateralShift = glass.thickness * incidentSine * (internalCosine - cosine / glass.ior);
    float displaced = clamp(lateralShift, 0.0, 0.8);
    glass.alpha = 1.0 - straight * (1.0 - displaced) * (1.0 - glass.fresnel) * (1.0 - glass.markings);
    return glass;
}

#ifndef OIT_ALPHA_ONLY
uniform sampler2D LumiaOpaqueColor;
uniform sampler2D LumiaOpaqueDepth;

vec3 lumiaGlassProjection(vec3 position) {
    vec4 clip = ProjMat * ModelViewMat * vec4(position, 1.0);
    return vec3(clip.xy / max(clip.w, 0.0001) * 0.5, clip.w);
}

float lumiaOpaqueDistance(vec2 uv) {
    float depth = textureLod(LumiaOpaqueDepth, uv, 0.0).r;
    #ifndef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    depth = depth * 2.0 - 1.0;
    #endif
    return abs(ProjMat[3][2]) / max(abs(depth + ProjMat[2][2]), 0.000001);
}

bool lumiaOnScreen(vec2 uv, vec2 margin) {
    return all(greaterThanEqual(uv, margin)) && all(lessThanEqual(uv, 1.0 - margin));
}

// Trace the reflected ray against the opaque depth captured before translucent terrain.
// Reject crossings over a depth discontinuity and fall back to the voxel/sky ray.
vec3 lumiaGlassReflection(vec3 position, vec3 normal, vec3 ray, vec3 start, vec2 uv, vec2 orientation, vec2 margin, float fog) {
    float travel = 0.0625;
    float previousGap = -1.0;
    for (int i = 0; i < 15; i++) {
        vec3 projected = lumiaGlassProjection(position + normal * 0.015 + ray * travel);
        vec2 sampleUv = uv + (projected.xy - start.xy) * orientation;
        if (projected.z <= 0.05 || !lumiaOnScreen(sampleUv, margin)) break;
        float gap = projected.z - lumiaOpaqueDistance(sampleUv);
        if (gap >= 0.0) {
            if (previousGap < 0.0 && gap <= max(0.08, travel * 0.055)) {
                ivec2 hitPixel = ivec2(sampleUv * vec2(textureSize(LumiaOpaqueColor, 0)));
                return texelFetch(LumiaOpaqueColor, hitPixel, 0).rgb;
            }
            break;
        }
        previousGap = gap;
        travel = travel * 1.45 + 0.08;
    }
    vec3 environment = lumiaReflectedScene(position + LumiaCameraInVolume + normal * 0.03, ray, 0);
    return mix(environment, FogColor.rgb, fog);
}

// A linear color lookup can bleed a foreground silhouette even when the nearest
// depth texel belongs to the background. Filter the four taps with their own depths.
vec3 lumiaTransmittedColor(vec2 shifted, vec2 uv, float frontDistance, vec2 size) {
    vec2 p = shifted * size - 0.5;
    ivec2 origin = ivec2(floor(p));
    vec2 fraction = fract(p);
    vec3 color = vec3(0.0);
    float total = 0.0;
    for (int y = 0; y <= 1; y++) {
        for (int x = 0; x <= 1; x++) {
            ivec2 pixel = clamp(origin + ivec2(x, y), ivec2(0), ivec2(size) - 1);
            vec2 weights = mix(1.0 - fraction, fraction, vec2(x, y));
            float weight = weights.x * weights.y;
            if (lumiaOpaqueDistance((vec2(pixel) + 0.5) / size) < frontDistance) continue;
            color += texelFetch(LumiaOpaqueColor, pixel, 0).rgb * weight;
            total += weight;
        }
    }
    if (total <= 0.0001) return texelFetch(LumiaOpaqueColor, ivec2(uv * size), 0).rgb;
    return color / total;
}

vec3 lumiaGlassColor(LumiaGlass glass, vec3 markingColor, vec3 position, vec3 normal, vec3 incident, vec2 orientation, float fog) {
    vec2 size = vec2(textureSize(LumiaOpaqueColor, 0));
    vec2 uv = gl_FragCoord.xy / size;
    vec2 margin = 0.5 / size;
    vec3 start = lumiaGlassProjection(position);
    vec3 bent = refract(incident, normal, 1.0 / glass.ior);
    // A plane-parallel slab returns to the incident direction on exit. Only its
    // lateral displacement persists into the background; panes use their 2/16 width.
    vec3 projected = lumiaGlassProjection(position + bent * glass.thickness);
    vec3 straightExit = lumiaGlassProjection(position + incident * glass.thickness);
    float backgroundDistance = lumiaOpaqueDistance(uv);
    vec2 displacement = (projected.xy - straightExit.xy) * orientation;
    vec2 shifted = uv + displacement * clamp(projected.z / max(backgroundDistance, 0.05), 0.0, 1.0);
    if (projected.z <= 0.05 || !lumiaOnScreen(shifted, margin)
            || lumiaOpaqueDistance(shifted) < start.z || lumiaOpaqueDistance(mix(uv, shifted, 0.5)) < start.z) {
        shifted = uv;
    }
    vec3 refracted;
    if (all(lessThan(abs(shifted - uv) * size, vec2(0.001)))) {
        refracted = texelFetch(LumiaOpaqueColor, ivec2(gl_FragCoord.xy), 0).rgb;
    } else {
        refracted = lumiaTransmittedColor(shifted, uv, start.z, size);
    }
    vec3 reflected = lumiaGlassReflection(position, normal, reflect(incident, normal), start, uv, orientation, margin, fog);
    vec3 transmittedWeight = glass.transmission * (1.0 - glass.fresnel) * (1.0 - glass.markings);
    // The native blend/OIT resolve supplies the straight-through part. Avoid
    // compositing an opaque background copy over the other translucent surfaces.
    // Captured opaque pixels already include atmospheric fog. Only the surface
    // markings, the off-screen fallback, and light lost to absorption need its
    // contribution between the observer and the glass.
    vec3 absorption = (1.0 - glass.transmission) * (1.0 - glass.fresnel) * (1.0 - glass.markings);
    vec3 layer = absorption * FogColor.rgb * fog + refracted * max(transmittedWeight - (1.0 - glass.alpha), vec3(0.0))
            + reflected * glass.fresnel * (1.0 - glass.markings) + mix(markingColor, FogColor.rgb, fog) * glass.markings;
    return layer / max(glass.alpha, 0.0001);
}
#endif
