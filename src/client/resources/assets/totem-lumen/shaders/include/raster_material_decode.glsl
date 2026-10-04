#ifndef TOTEM_LUMEN_RASTER_MATERIAL_DECODE_GLSL
#define TOTEM_LUMEN_RASTER_MATERIAL_DECODE_GLSL

const uint RASTER_MATERIAL_WORDS = 16u;
const uint RASTER_MATERIALS_PER_ROW = 256u;

uint rasterByte(float value) {
    return uint(round(clamp(value, 0.0, 1.0) * 255.0));
}

uint rasterMaterialId(vec4 encoded) {
    return rasterByte(encoded.r) | (rasterByte(encoded.g) << 8u);
}

uint rasterSurfaceSetId(vec4 encoded) {
    return rasterByte(encoded.b) | (rasterByte(encoded.a) << 8u);
}

uint rasterMaterialWord(sampler2D lut, uint materialId, uint word) {
    uint x = (materialId & 255u) * RASTER_MATERIAL_WORDS + word;
    uint y = materialId >> 8u;
    vec4 encoded = texelFetch(lut, ivec2(int(x), int(y)), 0);
    return rasterByte(encoded.r)
            | (rasterByte(encoded.g) << 8u)
            | (rasterByte(encoded.b) << 16u)
            | (rasterByte(encoded.a) << 24u);
}

float rasterMaterialFloat(sampler2D lut, uint materialId, uint word) {
    return uintBitsToFloat(rasterMaterialWord(lut, materialId, word));
}

#endif
