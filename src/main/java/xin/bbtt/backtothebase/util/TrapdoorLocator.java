package xin.bbtt.backtothebase.util;

import org.joml.Vector3d;
import org.joml.Vector3i;
import xin.bbtt.Block.BlockState;
import xin.bbtt.collision.CollisionBox;
import xin.bbtt.collision.CollisionShapeRegistry;
import xin.bbtt.world.World;

/** Finds the trapdoor the bot should operate, tolerating slightly stale coordinates. */
public final class TrapdoorLocator {
    private TrapdoorLocator() {
    }

    /**
     * Returns the configured block when it is a trapdoor, otherwise the nearest
     * trapdoor in the surrounding area (horizontal radius {@code radius}, vertical
     * radius 3).
     */
    public static Vector3i resolve(World world, Vector3i configured, int radius) {
        if (world == null || configured == null) return null;
        if (isTrapdoor(world, configured)) return configured;

        Vector3i best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        int horizontal = Math.max(1, radius);
        for (int dx = -horizontal; dx <= horizontal; dx++) {
            for (int dz = -horizontal; dz <= horizontal; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    int y = configured.y + dy;
                    if (!World.isWithinWorldBounds(y)) continue;
                    Vector3i candidate = new Vector3i(configured.x + dx, y, configured.z + dz);
                    if (!isTrapdoor(world, candidate)) continue;
                    double distanceSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (distanceSq < bestDistanceSq) {
                        bestDistanceSq = distanceSq;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    public static boolean isTrapdoor(World world, Vector3i position) {
        if (world == null || position == null) return false;
        if (!world.chunkLoaded(position.x >> 4, position.z >> 4)) return false;
        BlockState state = world.getBlockStateAt(center(position));
        String name = state == null ? null : state.blockName();
        return name != null && name.endsWith("_trapdoor");
    }

    public static Vector3d center(Vector3i position) {
        return new Vector3d(position.x + 0.5, position.y + 0.5, position.z + 0.5);
    }

    /**
     * Centre of the trapdoor's actual collision shape. A block centre can lie
     * outside a thin or half-block trapdoor, which makes an aimed ray miss it.
     */
    public static Vector3d aimPoint(World world, Vector3i position) {
        Vector3d fallback = center(position);
        if (world == null || position == null) return fallback;
        BlockState state = world.getBlockStateAt(fallback);
        if (state == null) return fallback;

        CollisionBox best = null;
        double bestVolume = -1;
        for (CollisionBox box : CollisionShapeRegistry.getInstance().boxesFor(state.stateId())) {
            double volume = (box.maxX() - box.minX()) * (box.maxY() - box.minY())
                    * (box.maxZ() - box.minZ());
            if (volume > bestVolume) {
                bestVolume = volume;
                best = box;
            }
        }
        if (best == null) return fallback;
        return new Vector3d(
                position.x + (best.minX() + best.maxX()) * 0.5,
                position.y + (best.minY() + best.maxY()) * 0.5,
                position.z + (best.minZ() + best.maxZ()) * 0.5);
    }
}
