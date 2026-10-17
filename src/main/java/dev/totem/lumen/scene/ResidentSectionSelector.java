package dev.totem.lumen.scene;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Deterministic, view-aware selection for a bounded exact-voxel resident set.
 *
 * <p>Previously resident sections receive a small distance and frustum-boundary allowance. This
 * prevents tiny camera movements from continuously exchanging equally useful sections at the
 * capacity cutoff while still allowing meaningfully closer or newly visible sections to enter.</p>
 */
public final class ResidentSectionSelector {
    private static final double SECTION_HALF_EXTENT = 8.0;
    private static final double SECTION_BOUNDING_RADIUS = Math.sqrt(3.0) * SECTION_HALF_EXTENT;
    private static final double VIEW_PRIORITY_DISTANCE = 16.0;

    private ResidentSectionSelector() {
    }

    public static List<SectionSnapshot> select(
            Collection<SectionSnapshot> snapshots,
            FrameSnapshot frame,
            Set<SectionKey> residents,
            int capacity,
            double aspectRatio,
            double residentHoldDistance
    ) {
        if (snapshots == null || frame == null || residents == null) {
            throw new IllegalArgumentException("snapshots, frame and residents are required");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (!Double.isFinite(aspectRatio) || aspectRatio <= 0.0) {
            throw new IllegalArgumentException("aspectRatio must be finite and positive");
        }
        if (!Double.isFinite(residentHoldDistance) || residentHoldDistance < 0.0) {
            throw new IllegalArgumentException("residentHoldDistance must be finite and non-negative");
        }

        CameraBasis basis = CameraBasis.from(frame);
        double verticalHalfFov = Math.toRadians(clamp(frame.fovDegrees(), 1.0f, 179.0f) * 0.5);
        double verticalTangent = Math.tan(verticalHalfFov);
        double horizontalTangent = verticalTangent * aspectRatio;

        Comparator<Candidate> ordering = Comparator
                .comparingDouble(Candidate::rankDistance)
                .thenComparingDouble(Candidate::distanceSquared)
                .thenComparingInt(candidate -> candidate.snapshot().key().x())
                .thenComparingInt(candidate -> candidate.snapshot().key().y())
                .thenComparingInt(candidate -> candidate.snapshot().key().z());

        return snapshots.stream()
                .filter(snapshot -> snapshot.key().dimensionId().equals(frame.dimensionId()))
                .map(snapshot -> candidate(
                        snapshot,
                        frame,
                        basis,
                        residents.contains(snapshot.key()),
                        horizontalTangent,
                        verticalTangent,
                        residentHoldDistance
                ))
                .sorted(ordering)
                .limit(capacity)
                .map(Candidate::snapshot)
                .toList();
    }

    private static Candidate candidate(
            SectionSnapshot snapshot,
            FrameSnapshot frame,
            CameraBasis basis,
            boolean resident,
            double horizontalTangent,
            double verticalTangent,
            double residentHoldDistance
    ) {
        double dx = snapshot.key().x() * 16.0 + SECTION_HALF_EXTENT - frame.cameraX();
        double dy = snapshot.key().y() * 16.0 + SECTION_HALF_EXTENT - frame.cameraY();
        double dz = snapshot.key().z() * 16.0 + SECTION_HALF_EXTENT - frame.cameraZ();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        double distance = Math.sqrt(distanceSquared);
        double viewX = basis.dotRight(dx, dy, dz);
        double viewY = basis.dotUp(dx, dy, dz);
        double viewZ = basis.dotForward(dx, dy, dz);
        double boundaryAllowance = SECTION_BOUNDING_RADIUS + (resident ? residentHoldDistance : 0.0);
        boolean inFrustum = intersectsConservativeFrustum(
                viewX,
                viewY,
                viewZ,
                horizontalTangent,
                verticalTangent,
                boundaryAllowance
        );
        double rankDistance = Math.max(
                0.0,
                distance
                        - (resident ? residentHoldDistance : 0.0)
                        - (inFrustum ? VIEW_PRIORITY_DISTANCE : 0.0)
        );
        return new Candidate(snapshot, rankDistance, distanceSquared);
    }

    private static boolean intersectsConservativeFrustum(
            double x,
            double y,
            double z,
            double horizontalTangent,
            double verticalTangent,
            double boundaryAllowance
    ) {
        if (z + boundaryAllowance <= 0.0) {
            return false;
        }
        double forwardDistance = Math.max(0.0, z);
        double horizontalAllowance = boundaryAllowance * Math.sqrt(1.0 + horizontalTangent * horizontalTangent);
        double verticalAllowance = boundaryAllowance * Math.sqrt(1.0 + verticalTangent * verticalTangent);
        return Math.abs(x) <= forwardDistance * horizontalTangent + horizontalAllowance
                && Math.abs(y) <= forwardDistance * verticalTangent + verticalAllowance;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record Candidate(
            SectionSnapshot snapshot,
            double rankDistance,
            double distanceSquared
    ) {
    }

    private record CameraBasis(
            double rightX,
            double rightY,
            double rightZ,
            double upX,
            double upY,
            double upZ,
            double forwardX,
            double forwardY,
            double forwardZ
    ) {
        private static CameraBasis from(FrameSnapshot frame) {
            double[] right = rotate(frame, 1.0, 0.0, 0.0);
            double[] up = rotate(frame, 0.0, 1.0, 0.0);
            double[] forward = rotate(frame, 0.0, 0.0, -1.0);
            return new CameraBasis(
                    right[0], right[1], right[2],
                    up[0], up[1], up[2],
                    forward[0], forward[1], forward[2]
            );
        }

        private double dotRight(double x, double y, double z) {
            return x * rightX + y * rightY + z * rightZ;
        }

        private double dotUp(double x, double y, double z) {
            return x * upX + y * upY + z * upZ;
        }

        private double dotForward(double x, double y, double z) {
            return x * forwardX + y * forwardY + z * forwardZ;
        }

        private static double[] rotate(FrameSnapshot frame, double vx, double vy, double vz) {
            double qx = frame.rotationX();
            double qy = frame.rotationY();
            double qz = frame.rotationZ();
            double qw = frame.rotationW();
            double tx = 2.0 * (qy * vz - qz * vy);
            double ty = 2.0 * (qz * vx - qx * vz);
            double tz = 2.0 * (qx * vy - qy * vx);
            double rx = vx + qw * tx + (qy * tz - qz * ty);
            double ry = vy + qw * ty + (qz * tx - qx * tz);
            double rz = vz + qw * tz + (qx * ty - qy * tx);
            double length = Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (length < 0.000001) {
                return new double[]{vx, vy, vz};
            }
            return new double[]{rx / length, ry / length, rz / length};
        }
    }
}
