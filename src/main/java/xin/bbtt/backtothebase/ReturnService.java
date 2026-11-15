package xin.bbtt.backtothebase;

import org.joml.Vector3d;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.MovementSync;
import xin.bbtt.backtothebase.config.ConfigStore;
import xin.bbtt.backtothebase.config.PluginConfig;
import xin.bbtt.backtothebase.movement.PullTrapdoorMovement;
import xin.bbtt.backtothebase.util.BaseGoalFinder;
import xin.bbtt.movements.PathMovement;
import xin.bbtt.pathfinding.DStarLite;
import xin.bbtt.pathfinding.Node;
import xin.bbtt.pathfinding.PathStep;
import xin.bbtt.pathfinding.PathfindingContextBuilder;
import xin.bbtt.pathfinding.StandablePositionResolver;
import xin.bbtt.world.World;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates one "back" request at a time:
 *
 * <ol>
 *   <li>validate the whitelist entry,</li>
 *   <li>announce "received, working on it",</li>
 *   <li>plan a route to the configured base trapdoor,</li>
 *   <li>queue the path and the trapdoor interaction on MovementSync,</li>
 *   <li>report success, failure or timeout back to the player.</li>
 * </ol>
 *
 * State changes on the movement queue are serialised through the single worker
 * thread; replies travel through the normal xinbot chat queue.
 */
public final class ReturnService {
    private static final Logger log = LoggerFactory.getLogger("backtothebase");

    private final BackToTheBasePlugin plugin;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "backtothebase-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "backtothebase-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<Request> active = new AtomicReference<>();

    public ReturnService(BackToTheBasePlugin plugin) {
        this.plugin = plugin;
    }

    public void handleMessage(String playerName, String message) {
        OptionalInt base = BackRequest.parse(message);
        if (base.isEmpty()) return;

        String player = playerName == null ? "" : playerName.trim();
        if (player.isEmpty() || isSelf(player)) return;

        ConfigStore store = plugin.store();
        if (!store.isWhitelisted(player)) {
            plugin.notifyPlayer(player, "backtothebase.msg.denied");
            return;
        }
        String canonical = store.canonicalName(player);
        PluginConfig.Point point = store.point(canonical, base.getAsInt());
        if (point == null) {
            plugin.notifyPlayer(canonical, "backtothebase.msg.unknown_base", base.getAsInt());
            return;
        }

        Request request = new Request(canonical, base.getAsInt(), point);
        if (!active.compareAndSet(null, request)) {
            plugin.notifyPlayer(canonical, "backtothebase.msg.busy");
            return;
        }

        plugin.notifyPlayer(canonical, "backtothebase.msg.accepted");
        int timeout = Math.max(30, plugin.config().requestTimeoutSeconds);
        request.timeout = timer.schedule(() -> onTimeout(request), timeout, TimeUnit.SECONDS);
        worker.execute(() -> plan(request));
    }

    private boolean isSelf(String player) {
        String account = plugin.config().account;
        return account != null && !account.isBlank() && account.equalsIgnoreCase(player);
    }

    private void plan(Request request) {
        if (active.get() != request) return;
        MovementSync sync = MovementSync.INSTANCE;
        if (sync == null) {
            finish(request, PullTrapdoorMovement.Outcome.fail(
                    plugin.message("backtothebase.msg.movementsync_missing")));
            return;
        }
        if (sync.movementService == null) {
            finish(request, PullTrapdoorMovement.Outcome.fail(
                    plugin.message("backtothebase.msg.not_ready")));
            return;
        }

        try {
            World world = sync.getWorld();
            Vector3i trapdoor = request.point.toVector();
            Node start = nodeOf(sync.position.get());

            // The base is the configured trapdoor, not the requesting player.
            // Try the trapdoor top first, then nearby floor blocks as fallbacks.
            List<Node> candidates = BaseGoalFinder.candidates(world, trapdoor);
            if (candidates.isEmpty()) {
                finish(request, PullTrapdoorMovement.Outcome.fail(
                        plugin.message("backtothebase.msg.no_base_goal")));
                return;
            }

            var context = new PathfindingContextBuilder(world)
                    .addNoPlacementStrategies(false).build();
            long generation = -1;
            Node goal = null;
            List<PathStep> path = List.of();
            for (Node candidate : candidates) {
                // beginStaticNavigationRequest sets MovementSync's active goal,
                // which PathMovement compares against on every tick.
                generation = sync.beginStaticNavigationRequest(
                        new Vector3i(candidate.x, candidate.y, candidate.z));
                request.generation = generation;
                if (active.get() != request) {
                    if (sync.isNavigationRequestCurrent(generation)) sync.cancelNavigation();
                    return;
                }
                if (start.equals(candidate)) {
                    goal = candidate;
                    break;
                }
                List<PathStep> candidatePath = new DStarLite(start, candidate, context)
                        .findPath(Math.max(500, plugin.config().pathfindExpansions));
                if (candidatePath.size() > 1) {
                    goal = candidate;
                    path = candidatePath;
                    break;
                }
            }

            if (goal == null) {
                finish(request, PullTrapdoorMovement.Outcome.fail(
                        plugin.message("backtothebase.msg.no_route")));
                return;
            }

            boolean added = true;
            if (path.size() > 1) {
                PathMovement pathMovement = new PathMovement(
                        path, 0.5, false,
                        new Vector3i(goal.x, goal.y, goal.z), -1, generation);
                added = sync.addMovementIfNavigationRequestCurrent(generation, pathMovement);
            }

            if (added && active.get() == request) {
                PullTrapdoorMovement pull = new PullTrapdoorMovement(
                        plugin, request.player, trapdoor,
                        outcome -> finish(request, outcome));
                added = sync.addMovementIfNavigationRequestCurrent(generation, pull);
            }

            if (!added) {
                finish(request, PullTrapdoorMovement.Outcome.fail(
                        plugin.message("backtothebase.msg.nav_lost")));
            } else {
                log.info("Return request for {} (base {}) dispatched: start={}, trapdoor={}, goal={}, path={} nodes",
                        request.player, request.base, start, trapdoor, goal, path.size());
            }
        } catch (Throwable error) {
            log.error("Return request for {} failed", request.player, error);
            String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            finish(request, PullTrapdoorMovement.Outcome.fail(reason));
        }
    }

    private static Node nodeOf(Vector3d position) {
        return new Node((int) Math.floor(position.x),
                StandablePositionResolver.nodeY(position.y),
                (int) Math.floor(position.z));
    }

    private void onTimeout(Request request) {
        if (!active.compareAndSet(request, null)) return;
        log.warn("Return request for {} timed out", request.player);
        plugin.notifyPlayer(request.player, "backtothebase.msg.timeout");
        cancelNavigationIfCurrent(request);
    }

    private void finish(Request request, PullTrapdoorMovement.Outcome outcome) {
        if (!active.compareAndSet(request, null)) return;
        if (request.timeout != null) request.timeout.cancel(false);
        if (outcome.success()) {
            plugin.notifyPlayer(request.player, "backtothebase.msg.success");
            // finish() can run under the movement controller's state lock; the
            // goal is cleared on the timer thread to keep MovementSync's
            // navigationLock -> stateLock order intact.
            try {
                timer.execute(() -> clearGoalIfCurrent(request));
            } catch (RejectedExecutionException ignored) {
                // Timer already shut down during plugin disable.
            }
        } else {
            plugin.notifyPlayer(request.player, "backtothebase.msg.failed", outcome.error());
            // finish() may run from Movement.init()/onStop(), which the controller
            // calls while holding its state lock. Cancelling navigation there would
            // invert MovementSync's navigationLock -> stateLock order, so defer it.
            try {
                timer.execute(() -> cancelNavigationIfCurrent(request));
            } catch (RejectedExecutionException ignored) {
                // Timer already shut down during plugin disable.
            }
        }
    }

    private void clearGoalIfCurrent(Request request) {
        MovementSync sync = MovementSync.INSTANCE;
        if (sync == null || request.generation < 0) return;
        if (sync.isNavigationRequestCurrent(request.generation)) sync.setActiveGoal(null);
    }

    private void cancelNavigationIfCurrent(Request request) {
        MovementSync sync = MovementSync.INSTANCE;
        if (sync == null || request.generation < 0) return;
        if (sync.isNavigationRequestCurrent(request.generation)) {
            sync.cancelNavigation();
        }
    }

    /** Console {@code cancel}; returns false when nothing was running. */
    public boolean cancel() {
        Request request = active.getAndSet(null);
        if (request == null) return false;
        if (request.timeout != null) request.timeout.cancel(false);
        cancelNavigationIfCurrent(request);
        plugin.notifyPlayer(request.player, "backtothebase.msg.cancelled");
        log.info("Return request for {} cancelled from console", request.player);
        return true;
    }

    public String status() {
        Request request = active.get();
        if (request == null) return plugin.message("backtothebase.console.idle");
        long seconds = (System.currentTimeMillis() - request.startedAt) / 1000L;
        return plugin.message("backtothebase.console.running",
                request.player, request.base, seconds);
    }

    public boolean isBusy() {
        return active.get() != null;
    }

    public void shutdown() {
        Request request = active.getAndSet(null);
        if (request != null && request.timeout != null) request.timeout.cancel(false);
        timer.shutdownNow();
        worker.shutdownNow();
        MovementSync sync = MovementSync.INSTANCE;
        if (sync != null && request != null && request.generation >= 0
                && sync.isNavigationRequestCurrent(request.generation)) {
            sync.cancelNavigation();
        }
    }

    private static final class Request {
        final String player;
        final int base;
        final PluginConfig.Point point;
        final long startedAt = System.currentTimeMillis();
        volatile long generation = -1;
        volatile ScheduledFuture<?> timeout;

        Request(String player, int base, PluginConfig.Point point) {
            this.player = player;
            this.base = base;
            this.point = point;
        }
    }
}
