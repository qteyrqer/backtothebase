package xin.bbtt.backtothebase.util;

import org.joml.Vector3i;
import xin.bbtt.pathfinding.Node;
import xin.bbtt.pathfinding.StandablePositionResolver;
import xin.bbtt.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a configured trapdoor (base) coordinate into walkable stand positions.
 *
 * <p>The bot should go to the base, not to the requesting player. For a floor
 * trapdoor the natural spot is standing on top of it; for a wall trapdoor the
 * surrounding floor blocks are used. When the base chunk is not loaded yet the
 * trapdoor block above is returned optimistically so the pathfinder can head
 * towards the frontier and replan as chunks arrive.</p>
 */
public final class BaseGoalFinder {
    /** Keep the stand position within interaction reach of the trapdoor. */
    private static final double MAX_DISTANCE_SQ = 3.5 * 3.5;

    private BaseGoalFinder() {
    }

    public static List<Node> candidates(World world, Vector3i trapdoor) {
        if (world == null || trapdoor == null) return List.of();
        if (!world.chunkLoaded(trapdoor.x >> 4, trapdoor.z >> 4)) {
            // Unknown terrain: aim at the trapdoor's own column and let PathMovement
            // replan when the chunk (and its real floor) becomes visible.
            return List.of(new Node(trapdoor.x, trapdoor.y + 1, trapdoor.z));
        }

        List<Node> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        // Prefer standing on the trapdoor itself (closed floor trapdoor).
        add(world, trapdoor, trapdoor.x, trapdoor.y + 1, trapdoor.z, result, seen);

        // Otherwise stand on a nearby floor block, same level or one step up/down.
        for (int radius = 1; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy : new int[]{1, 0, 2, -1}) {
                        add(world, trapdoor, trapdoor.x + dx, trapdoor.y + dy,
                                trapdoor.z + dz, result, seen);
                    }
                }
            }
        }

        result.sort(Comparator.comparingDouble(node -> distanceSq(node, trapdoor)));
        return result;
    }

    private static void add(World world, Vector3i trapdoor, int x, int y, int z,
                            List<Node> result, Set<Long> seen) {
        if (!World.isWithinWorldBounds(y)) return;
        if (!world.chunkLoaded(x >> 4, z >> 4)) return;
        if (!seen.add(key(x, y, z))) return;
        if (distanceSq(x, y, z, trapdoor) > MAX_DISTANCE_SQ) return;
        if (StandablePositionResolver.feetY(world, x, y, z).isEmpty()) return;
        result.add(new Node(x, y, z));
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfff);
    }

    private static double distanceSq(Node node, Vector3i trapdoor) {
        return distanceSq(node.x, node.y, node.z, trapdoor);
    }

    private static double distanceSq(int x, int y, int z, Vector3i trapdoor) {
        double dx = (x + 0.5) - (trapdoor.x + 0.5);
        double dy = (y + 0.5) - (trapdoor.y + 0.5);
        double dz = (z + 0.5) - (trapdoor.z + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }
}
