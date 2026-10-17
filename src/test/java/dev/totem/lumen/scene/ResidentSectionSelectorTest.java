package dev.totem.lumen.scene;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResidentSectionSelectorTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final double ASPECT_RATIO = 16.0 / 9.0;
    private static final double HOLD_DISTANCE = 8.0;

    @Test
    void selectionIsIndependentOfSnapshotInsertionOrder() {
        FrameSnapshot frame = frame(8.0, 8.0, 8.0);
        List<SectionSnapshot> snapshots = List.of(
                snapshot(-2, 0, -3),
                snapshot(0, 0, -2),
                snapshot(1, 0, -3),
                snapshot(-1, 0, -2),
                snapshot(0, 1, -3),
                snapshot(0, -1, -3)
        );
        List<SectionKey> expected = keys(select(snapshots, frame, Set.of(), 4));

        List<SectionSnapshot> reversed = new ArrayList<>(snapshots);
        Collections.reverse(reversed);
        List<SectionSnapshot> rotated = new ArrayList<>(snapshots);
        Collections.rotate(rotated, 2);

        assertEquals(expected, keys(select(reversed, frame, Set.of(), 4)));
        assertEquals(expected, keys(select(rotated, frame, Set.of(), 4)));
    }

    @Test
    void equalDistanceUsesCompleteSectionKeyTieBreak() {
        FrameSnapshot frame = frame(8.0, 8.0, 8.0);
        SectionSnapshot negativeX = snapshot(-1, 0, -2);
        SectionSnapshot positiveX = snapshot(1, 0, -2);

        List<SectionSnapshot> selected = select(List.of(positiveX, negativeX), frame, Set.of(), 1);

        assertEquals(List.of(negativeX.key()), keys(selected));
    }

    @Test
    void visibleSectionWinsOverEquallyDistantSectionBehindCamera() {
        FrameSnapshot frame = frame(8.0, 8.0, 8.0);
        SectionSnapshot visible = snapshot(0, 0, -2);
        SectionSnapshot behind = snapshot(0, 0, 2);

        List<SectionSnapshot> selected = select(List.of(behind, visible), frame, Set.of(), 1);

        assertEquals(List.of(visible.key()), keys(selected));
    }

    @Test
    void nearbySectionBehindCameraWinsOverMuchFartherVisibleSection() {
        FrameSnapshot frame = frame(8.0, 8.0, 8.0);
        SectionSnapshot nearbyBehind = snapshot(0, 0, 1);
        SectionSnapshot farVisible = snapshot(0, 0, -4);

        List<SectionSnapshot> selected = select(List.of(farVisible, nearbyBehind), frame, Set.of(), 1);

        assertEquals(List.of(nearbyBehind.key()), keys(selected));
    }

    @Test
    void cameraRotationChangesViewPriority() {
        FrameSnapshot facingPositiveZ = new FrameSnapshot(
                1L,
                DIMENSION,
                8.0,
                8.0,
                8.0,
                0.0f,
                1.0f,
                0.0f,
                0.0f,
                70.0f,
                false
        );
        SectionSnapshot positiveZ = snapshot(0, 0, 2);
        SectionSnapshot negativeZ = snapshot(0, 0, -2);

        List<SectionSnapshot> selected = select(
                List.of(negativeZ, positiveZ),
                facingPositiveZ,
                Set.of(),
                1
        );

        assertEquals(List.of(positiveZ.key()), keys(selected));
    }

    @Test
    void filtersOtherDimensionsAndHonorsCapacity() {
        FrameSnapshot frame = frame(8.0, 8.0, 8.0);
        SectionSnapshot nearest = snapshot(0, 0, -1);
        SectionSnapshot next = snapshot(0, 0, -2);
        SectionSnapshot farther = snapshot(0, 0, -3);
        SectionSnapshot nether = snapshot("minecraft:the_nether", 0, 0, -1);

        List<SectionSnapshot> selected = select(
                List.of(farther, nether, next, nearest),
                frame,
                Set.of(),
                2
        );

        assertEquals(List.of(nearest.key(), next.key()), keys(selected));
    }

    @Test
    void residentHysteresisPreventsSmallCutoffExchange() {
        SectionSnapshot left = snapshot(-1, 0, -3);
        SectionSnapshot right = snapshot(1, 0, -3);
        Set<SectionKey> residents = Set.of(left.key());

        List<SectionSnapshot> smallMove = select(
                List.of(right, left),
                frame(9.0, 8.0, 8.0),
                residents,
                1
        );
        List<SectionSnapshot> decisiveMove = select(
                List.of(right, left),
                frame(25.0, 8.0, 8.0),
                residents,
                1
        );

        assertEquals(List.of(left.key()), keys(smallMove));
        assertEquals(List.of(right.key()), keys(decisiveMove));
    }

    @Test
    void removedResidentIsReplacedDeterministically() {
        FrameSnapshot frame = frame(8.0, 8.0, 8.0);
        SectionSnapshot removed = snapshot(0, 0, -2);
        SectionSnapshot first = snapshot(-1, 0, -2);
        SectionSnapshot second = snapshot(1, 0, -2);

        List<SectionSnapshot> selected = select(
                List.of(second, first),
                frame,
                Set.of(removed.key()),
                1
        );

        assertEquals(List.of(first.key()), keys(selected));
    }

    private static List<SectionSnapshot> select(
            List<SectionSnapshot> snapshots,
            FrameSnapshot frame,
            Set<SectionKey> residents,
            int capacity
    ) {
        return ResidentSectionSelector.select(
                snapshots,
                frame,
                residents,
                capacity,
                ASPECT_RATIO,
                HOLD_DISTANCE
        );
    }

    private static List<SectionKey> keys(List<SectionSnapshot> snapshots) {
        return snapshots.stream().map(SectionSnapshot::key).toList();
    }

    private static FrameSnapshot frame(double x, double y, double z) {
        return new FrameSnapshot(1L, DIMENSION, x, y, z, 0.0f, 0.0f, 0.0f, 1.0f, 70.0f, false);
    }

    private static SectionSnapshot snapshot(int x, int y, int z) {
        return snapshot(DIMENSION, x, y, z);
    }

    private static SectionSnapshot snapshot(String dimension, int x, int y, int z) {
        int[] materialIds = new int[SectionVoxelData.VOXEL_COUNT];
        materialIds[0] = 1;
        return new SectionSnapshot(
                new SectionKey(dimension, x, y, z),
                1L,
                new SectionVoxelData(materialIds)
        );
    }
}
