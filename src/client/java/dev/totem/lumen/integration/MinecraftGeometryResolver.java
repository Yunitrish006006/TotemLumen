package dev.totem.lumen.integration;

import dev.totem.lumen.scene.BlockGeometryCode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Minecraft-facing block-state classifier for Totem Lumen's compact block-local geometry ABI.
 *
 * <p>P14B keeps slabs on their proven dedicated codes and adds compact parameterized families for
 * stairs, fences, walls, panes/bars, doors and trapdoors. Property names are read generically so the
 * renderer does not depend on individual vanilla block implementation classes for every family.</p>
 */
public final class MinecraftGeometryResolver {
    private MinecraftGeometryResolver() {
    }

    public static int geometryCode(BlockState state) {
        if (state.isAir()) {
            return BlockGeometryCode.FULL_CUBE;
        }
        if (state.getBlock() instanceof SlabBlock) {
            SlabType type = state.getValue(SlabBlock.TYPE);
            return switch (type) {
                case BOTTOM -> BlockGeometryCode.SLAB_BOTTOM;
                case TOP -> BlockGeometryCode.SLAB_TOP;
                case DOUBLE -> BlockGeometryCode.FULL_CUBE;
            };
        }

        String sourceId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (sourceId.endsWith("_stairs")) {
            return BlockGeometryCode.stairs(
                    direction(propertyValue(state, "facing", "north")),
                    propertyValue(state, "half", "bottom").equals("top"),
                    stairShape(propertyValue(state, "shape", "straight"))
            );
        }
        if (sourceId.endsWith("_fence")) {
            return BlockGeometryCode.fence(connectionMask(state));
        }
        if (sourceId.endsWith("_wall")) {
            return BlockGeometryCode.wall(
                    wallSide(propertyValue(state, "north", "none")),
                    wallSide(propertyValue(state, "east", "none")),
                    wallSide(propertyValue(state, "south", "none")),
                    wallSide(propertyValue(state, "west", "none")),
                    booleanProperty(state, "up")
            );
        }
        if (sourceId.endsWith("_pane") || sourceId.equals("minecraft:iron_bars")) {
            return BlockGeometryCode.pane(connectionMask(state));
        }
        if (sourceId.endsWith("_trapdoor")) {
            return BlockGeometryCode.trapdoor(
                    direction(propertyValue(state, "facing", "north")),
                    booleanProperty(state, "open"),
                    propertyValue(state, "half", "bottom").equals("top")
            );
        }
        if (sourceId.endsWith("_door")) {
            return BlockGeometryCode.door(
                    direction(propertyValue(state, "facing", "north")),
                    booleanProperty(state, "open"),
                    propertyValue(state, "hinge", "left").equals("right")
            );
        }
        return BlockGeometryCode.FULL_CUBE;
    }

    private static int connectionMask(BlockState state) {
        int mask = 0;
        if (booleanProperty(state, "north")) mask |= BlockGeometryCode.CONNECT_NORTH;
        if (booleanProperty(state, "east")) mask |= BlockGeometryCode.CONNECT_EAST;
        if (booleanProperty(state, "south")) mask |= BlockGeometryCode.CONNECT_SOUTH;
        if (booleanProperty(state, "west")) mask |= BlockGeometryCode.CONNECT_WEST;
        return mask;
    }

    private static boolean booleanProperty(BlockState state, String name) {
        return propertyValue(state, name, "false").equals("true");
    }

    private static int direction(String value) {
        return switch (value) {
            case "east" -> BlockGeometryCode.EAST;
            case "south" -> BlockGeometryCode.SOUTH;
            case "west" -> BlockGeometryCode.WEST;
            default -> BlockGeometryCode.NORTH;
        };
    }

    private static int stairShape(String value) {
        return switch (value) {
            case "inner_left" -> BlockGeometryCode.STAIR_INNER_LEFT;
            case "inner_right" -> BlockGeometryCode.STAIR_INNER_RIGHT;
            case "outer_left" -> BlockGeometryCode.STAIR_OUTER_LEFT;
            case "outer_right" -> BlockGeometryCode.STAIR_OUTER_RIGHT;
            default -> BlockGeometryCode.STAIR_STRAIGHT;
        };
    }

    private static int wallSide(String value) {
        return switch (value) {
            case "tall" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }

    private static String propertyValue(BlockState state, String name, String fallback) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return propertyValue(state, property);
            }
        }
        return fallback;
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
