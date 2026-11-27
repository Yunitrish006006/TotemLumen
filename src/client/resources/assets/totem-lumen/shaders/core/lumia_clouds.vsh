#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(std140) uniform CloudInfo {
    vec4 CloudColor;
    vec3 CloudOffset;
    vec3 CellSize;
};
uniform isamplerBuffer CloudFaces;
layout(location = 0) out vec3 relativePos;
layout(location = 1) flat out vec3 surfaceNormal;
layout(location = 2) flat out float opacity;

void main() {
    // Mojang's CloudFaces buffer stores three integers per quad. Reuse its packed
    // corners and inside-face winding for fast, fancy and inside-cloud geometry.
    int first = (gl_VertexIndex / 4) * 3;
    int flags = texelFetch(CloudFaces, first + 2).r;
    int direction = flags & 7;
    int cellX = (texelFetch(CloudFaces, first).r << 1) | ((flags >> 7) & 1);
    int cellZ = (texelFetch(CloudFaces, first + 1).r << 1) | ((flags >> 6) & 1);
    int corner = gl_VertexIndex % 4;
    int bit = direction * 4 + ((flags & 16) != 0 ? 3 - corner : corner);
    vec3 vertex = vec3((0xF03CC3 >> bit) & 1, (0x6666F0 >> bit) & 1, (0xC3F066 >> bit) & 1);
    relativePos = (vertex + vec3(cellX, 0, cellZ)) * CellSize + CloudOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(relativePos, 1.0);
    const vec3 normals[6] = vec3[6](vec3(0,-1,0), vec3(0,1,0), vec3(0,0,-1),
            vec3(0,0,1), vec3(-1,0,0), vec3(1,0,0));
    surfaceNormal = normals[direction];
    opacity = CloudColor.a;
}
