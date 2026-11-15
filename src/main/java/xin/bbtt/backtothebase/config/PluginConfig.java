package xin.bbtt.backtothebase.config;

import org.joml.Vector3i;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted plugin configuration ({@code backtothebase.json} in the plugin directory).
 *
 * <pre>
 * {
 *   "account": "YourBot",
 *   "requestTimeoutSeconds": 300,
 *   "pathfindExpansions": 5000,
 *   "whitelist": {
 *     "Steve": {
 *       "1": { "x": 100, "y": 64, "z": 200 }
 *     }
 *   }
 * }
 * </pre>
 *
 * The whitelist maps a player name to a map of base id (as string) to the trapdoor
 * coordinate of that base.
 */
public final class PluginConfig {
    /** Name of the account xinbot logs in with; used to ignore our own private messages. */
    public String account = "";

    /** Hard timeout for one "back" request, in seconds. */
    public int requestTimeoutSeconds = 300;

    /** Upper bound for D* Lite node expansions when planning a route. */
    public int pathfindExpansions = 5000;

    /** player name -> base id -> trapdoor coordinate. */
    public Map<String, Map<String, Point>> whitelist = new LinkedHashMap<>();

    /** A trapdoor block coordinate. */
    public static final class Point {
        public int x;
        public int y;
        public int z;

        public Point() {
        }

        public Point(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Vector3i toVector() {
            return new Vector3i(x, y, z);
        }

        @Override
        public String toString() {
            return "(" + x + ", " + y + ", " + z + ")";
        }
    }
}
