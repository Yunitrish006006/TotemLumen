#ifndef TOTEM_LUMIA_SCENE
#define TOTEM_LUMIA_SCENE
layout(std140) uniform LumiaEnvironment {
    mat4 LumiaHeldLights;
    mat4 LumiaDirections;
    vec4 LumiaSky;
    vec3 LumiaCameraInVolume;
};
layout(std140) uniform LumiaMovingActors {
    mat4 LumiaCenters; // Camera-window space, RGB24 tint in w.
    mat4 LumiaInvRadii;
    vec4 LumiaActorCount;
    vec3 LumiaActorPadding;
};
uniform sampler2D LumiaVoxels;
uniform sampler2D LumiaLights;

// The CPU publishes a tile entry only once that immutable section is resident.
int lumiaTile(ivec3 section) {
    if (any(lessThan(section, ivec3(0))) || any(greaterThanEqual(section, ivec3(6)))) return -1;
    int slot = (section.y * 6 + section.z) * 6 + section.x;
    return int(round(texelFetch(LumiaLights, ivec2(128 + slot, 0), 0).r * 255.0)) - 1;
}

vec4 lumiaVoxel(ivec3 p, int tile) {
    if (tile < 0) return vec4(0);
    ivec3 local = p & ivec3(15);
    return texelFetch(LumiaVoxels, ivec2((tile % 16) * 16 + local.x,
            (tile / 16) * 256 + local.y * 16 + local.z), 0);
}

int lumiaContainingActors(vec3 point, float padding) {
    int mask = 0;
    for (int i = 0; i < int(LumiaActorCount.x); ++i) {
        vec3 invR = LumiaInvRadii[i].xyz;
        if (all(lessThanEqual(abs(point - LumiaCenters[i].xyz) * invR, vec3(1.0) + padding * invR)))
            mask |= 1 << i;
    }
    return mask;
}

// Length inside a pane arm, clipped to the portion of this DDA step inside the voxel.
float lumiaSlabLength(vec2 start, vec2 dir, vec2 lo, vec2 hi, float extent) {
    vec2 inv = 1.0 / mix(vec2(1e-8), dir, greaterThan(abs(dir), vec2(1e-8)));
    vec2 a = (lo - start) * inv;
    vec2 b = (hi - start) * inv;
    vec2 enter = min(a, b), leave = max(a, b);
    return max(0.0, min(extent, min(leave.x, leave.y)) - max(0.0, max(enter.x, enter.y)));
}

vec3 lumiaGlassFilter(vec4 voxel, int tag, vec3 localStart, vec3 ray, float extent) {
    bool shaped = tag >= 80 && tag <= 96;
    int material = shaped ? 17 + tag - 80 : tag - 129;
    float thickness = extent;
    if (shaped) {
        int arms = int(round(voxel.r * 255.0));
        const float a = 7.0 / 16.0, b = 9.0 / 16.0;
        float west = (arms & 1) != 0 ? 0.0 : a;
        float east = (arms & 2) != 0 ? 1.0 : b;
        float north = (arms & 4) != 0 ? 0.0 : a;
        float south = (arms & 8) != 0 ? 1.0 : b;
        thickness = lumiaSlabLength(localStart.xz, ray.xz, vec2(west,a), vec2(east,b), extent);
        if (north < a) thickness += lumiaSlabLength(localStart.xz, ray.xz, vec2(a,north), vec2(b,a), extent);
        if (south > b) thickness += lumiaSlabLength(localStart.xz, ray.xz, vec2(a,b), vec2(b,south), extent);
    } else if (material >= 17) {
        // Server-defined emitting panes retain full RGB in the payload.
        thickness *= 0.125;
    }
    vec4 optical = texelFetch(LumiaLights, ivec2(344 + 2 * material, 0), 0);
    return pow(clamp(optical.rgb * (1.0 - optical.a), vec3(0.001), vec3(1)), vec3(thickness));
}

// Intersect ellipsoids inscribed in the current visible entities' bounds. Shorten the
// voxel ray to the nearest proxy so a wall in front of the proxy still wins.
vec4 lumiaTrace(vec3 origin, vec3 direction, float limit, int skipActors, out vec3 face, out int actorHit, out vec3 transmission) {
    transmission = vec3(1);
    int nearest = -1;
    for (int i = 0; i < int(LumiaActorCount.x); ++i) {
        if ((skipActors & (1 << i)) != 0) continue;
        vec3 invR = LumiaInvRadii[i].xyz;
        vec3 q = (origin - LumiaCenters[i].xyz) * invR;
        vec3 d = direction * invR;
        float a = dot(d, d), b = dot(q, d), c = dot(q, q) - 1.0;
        float discriminant = b * b - a * c;
        if (discriminant < 0.0 || (b >= 0.0 && c >= 0.0)) continue;
        float distance = max(0.0, (-b - sqrt(discriminant)) / max(a, 1e-8));
        if (distance < limit) {
            nearest = i;
            limit = distance;
        }
    }
    actorHit = -1;
    ivec3 cell = ivec3(floor(origin));
    ivec3 stride = ivec3(sign(direction));
    vec3 delta = 1.0 / max(abs(direction), vec3(0.00001));
    vec3 next = abs(vec3(cell) + step(vec3(0), direction) - origin) * delta;
    face = -direction;
    float entered = 0.0;
    ivec3 section = cell >> 4;
    int tile = lumiaTile(section);
    for (int i = 0; i < 64; ++i) {
        ivec3 nextSection = cell >> 4;
        if (any(notEqual(nextSection, section))) {
            section = nextSection;
            tile = lumiaTile(section);
        }
        vec4 v = lumiaVoxel(cell, tile);
        if (v.a < 0.25) break;
        if (v.a >= 0.75) return v;
        float t = min(next.x, min(next.y, next.z));
        int tag = int(round(v.a * 255.0));
        if ((tag >= 80 && tag <= 96) || (tag >= 129 && tag <= 162)) {
            transmission *= lumiaGlassFilter(v, tag, origin + entered * direction - vec3(cell),
                    direction, max(0.0, min(t, limit) - entered));
        }
        if (t >= limit) break;
        entered = t;
        vec3 axis = vec3(lessThanEqual(next, vec3(t + 0.00001)));
        face = normalize(-axis * vec3(stride));
        cell += ivec3(axis) * stride;
        next += axis * delta;
    }
    if (nearest >= 0) {
        actorHit = nearest;
        vec3 gradient = (origin + limit * direction - LumiaCenters[nearest].xyz)
                * LumiaInvRadii[nearest].xyz * LumiaInvRadii[nearest].xyz;
        face = gradient * inversesqrt(max(dot(gradient, gradient), 1e-10));
        return vec4(0, 0, 0, 1);
    }
    return vec4(0);
}

vec3 lumiaSkyRay(vec3 direction) {
    return LumiaSky.rgb * mix(.30, 1.0, smoothstep(-.15, .75, direction.y));
}

vec3 lumiaReflectedScene(vec3 origin, vec3 ray, int skipActors) {
    vec3 hitNormal;
    int actorHit;
    vec3 transmission;
    vec4 hit = lumiaTrace(origin, ray, 32.0, skipActors, hitNormal, actorHit, transmission);
    vec3 reflected = lumiaSkyRay(ray);
    if (hit.a >= .75) {
        vec3 tint;
        if (actorHit >= 0) {
            uint rgb = uint(LumiaCenters[actorHit].w);
            tint = vec3((rgb >> 16u) & 255u, (rgb >> 8u) & 255u, rgb & 255u) / 255.0;
        } else {
            int material = int(round(hit.a * 255.0)) - 192;
            tint = texelFetch(LumiaLights, ivec2(64 + clamp(material, 0, 63), 0), 0).rgb;
        }
        reflected = tint * (.14 + LumiaSky.rgb * .35 + LumiaSky.a * max(dot(hitNormal, LumiaDirections[0].xyz), 0.0)) + hit.rgb;
    }
    return reflected * transmission;
}

vec3 lumiaShade(vec3 base, vec3 cameraRelative, vec3 normal, vec2 lightUv01, float reflectivity) {
    vec3 n = normal * inversesqrt(max(dot(normal, normal), 1e-8));
    vec3 pos = cameraRelative + LumiaCameraInVolume;
    vec3 origin = pos + n * 0.03;
    vec3 sun = LumiaDirections[0].xyz;
    vec3 hitNormal;
    int actorHit;
    vec3 transmission;
    int skipActors = 0;
    #if defined(LUMIA_ENTITY) || defined(LUMIA_ITEM)
    // A hand or limb can protrude from the simplified body. Skip nearby own proxies.
    skipActors = lumiaContainingActors(pos, .3);
    #endif
    float ndl = max(dot(n, sun), 0.0);
    vec3 directSun = vec3(0);
    if (ndl > 0.0 && LumiaSky.a > 0.0) {
        float blocker = lumiaTrace(origin, sun, 32.0, skipActors, hitNormal, actorHit, transmission).a;
        directSun = (1.0 - step(.75, blocker)) * ndl * LumiaSky.a * transmission;
    }
    float overhead = lumiaTrace(origin, vec3(0, 1, 0), 32.0, skipActors, hitNormal, actorHit, transmission).a;
    vec3 diffuse = vec3(.055) + LumiaSky.rgb * mix(.42, .07, step(.75, overhead))
            * (.72 + .28 * n.y) + vec3(1.0, .93, .80) * directSun;

    int heldCount = int(LumiaDirections[0].w);
    for (int i = 0; i < 34; ++i) {
        vec3 lightPos, emission;
        float radius;
        bool held = i < heldCount;
        if (held) {
            vec4 hand = LumiaHeldLights[i * 2];
            lightPos = hand.xyz;
            radius = hand.w;
            emission = LumiaHeldLights[i * 2 + 1].rgb;
        } else {
            int slot = i - heldCount;
            if (slot >= 32) break;
            vec4 packedPos = texelFetch(LumiaLights, ivec2(slot * 2, 0), 0);
            if (packedPos.a < .5) break;
            lightPos = floor(packedPos.rgb * 255.0 + .5) + .5;
            if (dot(lightPos - origin, lightPos - origin) > 169.0) continue;
            emission = texelFetch(LumiaLights, ivec2(slot * 2 + 1, 0), 0).rgb;
            radius = 12.5 * max(emission.r, max(emission.g, emission.b));
        }
        vec3 toLight = lightPos - origin;
        float d2 = dot(toLight, toLight);
        if (d2 > radius * radius || d2 < .0001) continue;
        float d = sqrt(d2);
        vec3 l = toLight / d;
        float cosine = max(dot(l, n), 0.0);
        if (d >= radius || cosine <= 0.0) continue;
        // Stop at the emitting cell's boundary so solid emitters cannot shadow themselves.
        float inside = (held ? 0.0 : .5) / max(abs(l.x), max(abs(l.y), abs(l.z)));
        int ignore = skipActors;
        if (held) ignore |= lumiaContainingActors(lightPos, .3);
        if (lumiaTrace(origin, l, max(0.0, d - inside - .002), ignore, hitNormal, actorHit, transmission).a >= .75) continue;
        float attenuation = 1.0 - d / radius;
        diffuse += transmission * emission * (2.2 * attenuation * attenuation * cosine);
    }
    ivec3 surfaceCell = ivec3(floor(pos - n * .03));
    vec4 surfaceVoxel = lumiaVoxel(surfaceCell, lumiaTile(surfaceCell >> 4));
    int surfaceTag = int(round(surfaceVoxel.a * 255.0));
    vec3 selfEmission = surfaceTag >= 80 && surfaceTag <= 96 ? vec3(0) : surfaceVoxel.rgb;
    vec3 color = base * (diffuse + selfEmission);
    if (reflectivity > 0.0) {
        vec3 incident = cameraRelative * inversesqrt(max(dot(cameraRelative, cameraRelative), 1e-8));
        vec3 ray = reflect(incident, n);
        vec3 reflected = lumiaReflectedScene(origin, ray, skipActors);
        float fresnel = .06 + .94 * pow(1.0 - clamp(dot(-incident, n), 0.0, 1.0), 5.0);
        color = mix(color, reflected, clamp(reflectivity * fresnel, 0.0, 1.0));
    }
    return color;
}
#endif
