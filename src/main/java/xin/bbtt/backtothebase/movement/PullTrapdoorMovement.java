package xin.bbtt.backtothebase.movement;

import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import xin.bbtt.Block.BlockState;
import xin.bbtt.MovementSync;
import xin.bbtt.backtothebase.BackToTheBasePlugin;
import xin.bbtt.backtothebase.util.TrapdoorLocator;
import xin.bbtt.collision.CollisionBox;
import xin.bbtt.collision.CollisionShapeRegistry;
import xin.bbtt.inventory.InventoryManager;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.movement.Movement;

import java.util.function.Consumer;

/**
 * Final leg of a "back" request: look at the trapdoor and right-click it, exactly
 * like a player would in game. The current open/closed state is not inspected.
 */
public final class PullTrapdoorMovement extends Movement {
    private static final Logger log = LoggerFactory.getLogger("backtothebase");
    /** Server-side interaction reach is 4.5 blocks; stay a little inside it. */
    private static final double MAX_REACH = 4.0;
    private static final int AIM_TICKS = 2;
    private static final int SEND_CONFIRM_TICKS = 2;

    public record Outcome(boolean success, String error) {
        public static Outcome ok() {
            return new Outcome(true, null);
        }

        public static Outcome fail(String error) {
            return new Outcome(false, error);
        }
    }

    private final BackToTheBasePlugin plugin;
    private final String player;
    private final Vector3i configured;
    private final Consumer<Outcome> callback;

    private Vector3i target;
    private Vector3d center;
    private Hand hand = Hand.MAIN_HAND;
    private int ticks;
    private boolean sent;
    private boolean reported;

    public PullTrapdoorMovement(BackToTheBasePlugin plugin, String player, Vector3i configured,
                                Consumer<Outcome> callback) {
        this.plugin = plugin;
        this.player = player;
        this.configured = new Vector3i(configured);
        this.callback = callback;
    }

    @Override
    public void init() {
        MovementSync sync = MovementSync.INSTANCE;
        if (sync == null) {
            report(Outcome.fail(plugin.message("backtothebase.msg.movementsync_missing")));
            return;
        }
        target = TrapdoorLocator.resolve(sync.getWorld(), configured, 4);
        if (target == null) {
            report(Outcome.fail(plugin.message("backtothebase.msg.no_trapdoor")));
            return;
        }
        center = TrapdoorLocator.aimPoint(sync.getWorld(), target);

        double distance = sync.getHeadPosition().distance(center);
        if (distance > MAX_REACH) {
            report(Outcome.fail(plugin.message("backtothebase.msg.trapdoor_far", distance)));
            return;
        }

        // A block in hand would place a block instead of toggling the trapdoor
        // (the arm still swings, which is exactly the "swing but nothing happens"
        // symptom), so prefer an empty hotbar slot and fall back to an empty offhand.
        hand = selectInteractionHand(sync);

        // The path movement has finished when this movement starts: the player is
        // close enough, so announce arrival and start aiming at the trapdoor.
        sync.directLookAt(center);
        plugin.notifyPlayer(player, "backtothebase.msg.arrived");
        log.info("Arrived near {}; right-clicking trapdoor at {} with {}", player, target, hand);
    }

    @Override
    public void onTick() {
        MovementSync sync = MovementSync.INSTANCE;
        if (finished || sync == null || center == null) return;

        sync.directLookAt(center);
        if (!sent) {
            if (++ticks >= AIM_TICKS) {
                sendUseItemOn(sync);
                sent = true;
                ticks = 0;
            }
            return;
        }
        if (++ticks >= SEND_CONFIRM_TICKS) {
            report(Outcome.ok());
        }
    }

    /**
     * Right-clicking with a block in hand places that block instead of toggling
     * the trapdoor. Select an empty hotbar slot when possible; if the hotbar is
     * full, use the offhand when it is empty.
     */
    private Hand selectInteractionHand(MovementSync sync) {
        InventoryManager inventory = sync.getInventoryManager();
        ItemStack[] items = inventory.getInventory();
        if (items == null || items.length < 45) return Hand.MAIN_HAND;

        int held = inventory.getHeldSlot();
        if (isEmpty(items[36 + held])) return Hand.MAIN_HAND;

        for (int slot = 0; slot < 9; slot++) {
            if (!isEmpty(items[36 + slot])) continue;
            try {
                inventory.switchToSlot(slot);
                log.info("Switched to empty hotbar slot {} before interacting", slot);
                return Hand.MAIN_HAND;
            } catch (RuntimeException e) {
                log.warn("Could not switch to hotbar slot {}: {}", slot, e.toString());
                break;
            }
        }

        if (items.length > 45 && isEmpty(items[45])) {
            log.info("Hotbar is full; using the empty offhand for the interaction");
            return Hand.OFF_HAND;
        }
        log.warn("No empty hand available; the held item may block the trapdoor interaction");
        return Hand.MAIN_HAND;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getAmount() <= 0 || stack.getId() == 0;
    }

    private record Hit(Direction face, float cursorX, float cursorY, float cursorZ) {
    }

    /**
     * Ray-casts the current eye direction against the trapdoor's collision shape,
     * yielding the face and cursor a vanilla client would report.
     */
    private Hit raycast(MovementSync sync) {
        BlockState state = sync.getWorld().getBlockStateAt(center);
        if (state == null) return null;

        Vector3d eye = sync.getHeadPosition();
        double ox = eye.x - target.x;
        double oy = eye.y - target.y;
        double oz = eye.z - target.z;
        double yaw = Math.toRadians(sync.yaw.get());
        double pitch = Math.toRadians(sync.pitch.get());
        double dx = -Math.sin(yaw) * Math.cos(pitch);
        double dy = -Math.sin(pitch);
        double dz = Math.cos(yaw) * Math.cos(pitch);
        double[] origin = {ox, oy, oz};
        double[] direction = {dx, dy, dz};

        double best = Double.MAX_VALUE;
        Hit bestHit = null;
        for (CollisionBox box : CollisionShapeRegistry.getInstance().boxesFor(state.stateId())) {
            double[] min = {box.minX(), box.minY(), box.minZ()};
            double[] max = {box.maxX(), box.maxY(), box.maxZ()};
            double enter = 0.0;
            double exit = MAX_REACH;
            Direction face = null;
            boolean miss = false;
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(direction[axis]) < 1.0e-10) {
                    if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
                        miss = true;
                        break;
                    }
                    continue;
                }
                double t1 = (min[axis] - origin[axis]) / direction[axis];
                double t2 = (max[axis] - origin[axis]) / direction[axis];
                double near = Math.min(t1, t2);
                double far = Math.max(t1, t2);
                if (near > enter) {
                    enter = near;
                    face = axis == 0
                            ? (direction[axis] > 0 ? Direction.WEST : Direction.EAST)
                            : axis == 1
                            ? (direction[axis] > 0 ? Direction.DOWN : Direction.UP)
                            : (direction[axis] > 0 ? Direction.NORTH : Direction.SOUTH);
                }
                exit = Math.min(exit, far);
                if (enter > exit) {
                    miss = true;
                    break;
                }
            }
            if (miss || face == null || enter > best) continue;
            best = enter;
            bestHit = new Hit(face,
                    (float) clamp01(origin[0] + direction[0] * enter),
                    (float) clamp01(origin[1] + direction[1] * enter),
                    (float) clamp01(origin[2] + direction[2] * enter));
        }
        return bestHit;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private void sendUseItemOn(MovementSync sync) {
        org.cloudburstmc.math.vector.Vector3i block =
                org.cloudburstmc.math.vector.Vector3i.from(target.x, target.y, target.z);
        Hit hit = raycast(sync);
        Direction face;
        float cursorX;
        float cursorY;
        float cursorZ;
        if (hit != null) {
            face = hit.face();
            cursorX = hit.cursorX();
            cursorY = hit.cursorY();
            cursorZ = hit.cursorZ();
        } else {
            // Aiming is done every tick; this is only a rounding/edge fallback.
            face = faceTowardEyes(sync);
            cursorX = 0.5f;
            cursorY = face == Direction.UP ? 1.0f : face == Direction.DOWN ? 0.0f : 0.5f;
            cursorZ = 0.5f;
            if (face == Direction.EAST || face == Direction.WEST) cursorX = face == Direction.EAST ? 1.0f : 0.0f;
            if (face == Direction.SOUTH || face == Direction.NORTH) cursorZ = face == Direction.SOUTH ? 1.0f : 0.0f;
        }
        sync.queueBeforeMovement(new ServerboundUseItemOnPacket(
                block, face, hand,
                cursorX, cursorY, cursorZ, false, false,
                Bot.INSTANCE.getAndIncreaseSequence()));
        sync.queueBeforeMovement(new ServerboundSwingPacket(hand));
        log.info("Sent right-click for trapdoor {} (face={}, cursor=({}, {}, {}), hand={})",
                target, face, cursorX, cursorY, cursorZ, hand);
    }

    private Direction faceTowardEyes(MovementSync sync) {
        Vector3d eyes = sync.getHeadPosition();
        double dx = eyes.x - center.x;
        double dy = eyes.y - center.y;
        double dz = eyes.z - center.z;
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private void report(Outcome outcome) {
        if (reported) return;
        reported = true;
        finished = true;
        callback.accept(outcome);
    }

    @Override
    public long getTime() {
        return 15_000;
    }

    @Override
    public void onStop() {
        if (!reported) {
            report(Outcome.fail(plugin.message("backtothebase.msg.cancelled")));
        }
    }
}
