package dev.totem.lumen.gameplay.light;

/** Snapshot of one server gameplay light source. */
record LightSource(ServerBlockKey position, char packedRgb) {
}
