package com.arnis.paper;

import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A bounded worker pool that bakes regions off the main thread, with
 * de-duplication so a region is never baked twice or while a bake is already in
 * flight.
 *
 * <p>Safety model: this only tracks and bakes regions; the caller decides <em>which</em>
 * regions are safe to bake. {@link PlayerTracker} submits only regions the server
 * has not loaded (well ahead of the player), so arnis never overwrites a
 * {@code .mca} the server is holding open — the freshly written file is read
 * cleanly the first time the player's view reaches it.
 */
public final class BakeService {

    /** Give up on a region after this many failed bakes, so a bad area isn't retried forever. */
    private static final int MAX_ATTEMPTS = 3;

    private final Plugin plugin;
    private final RegionBaker baker;
    private final PriorityBlockingQueue<Runnable> queue =
            new PriorityBlockingQueue<>(64, Comparator.comparing(r -> (BakeTask) r));
    private final ExecutorService pool;
    /** Queued-but-not-yet-running tasks, so they can be promoted or dropped. */
    private final Map<Long, BakeTask> queued = new ConcurrentHashMap<>();
    private final AtomicLong sequenced = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    /** Latest player region positions, for ranking and pruning. Replaced wholesale. */
    private volatile List<long[]> playerRegions = List.of();
    private final Set<Long> baked = ConcurrentHashMap.newKeySet();
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** Regions with a region file the server generated itself — candidates for repair. */
    private final Set<Long> dirty = ConcurrentHashMap.newKeySet();
    private final Map<Long, Integer> attempts = new ConcurrentHashMap<>();
    // Callbacks waiting on each in-flight region. Only touched on the main thread
    // (submit and the completion callback both run there), so no lock is needed.
    private final Map<Long, List<Consumer<Boolean>>> waiters = new HashMap<>();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public BakeService(Plugin plugin, RegionBaker baker, int workers) {
        this.plugin = plugin;
        this.baker = baker;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "arnis-bake-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        int size = Math.max(1, workers);
        // Priority-ordered rather than FIFO: a fast or multi-directional party queues
        // regions faster than they bake, and the head of a FIFO queue is soon work
        // nobody is waiting for while a player sits blocked behind it. Tasks are run
        // via execute() so they reach the queue as the Comparable BakeTask itself —
        // submit() would wrap them in a FutureTask and lose the ordering.
        this.pool = new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS, queue, factory);
    }

    /** Relative urgency of a bake. Declaration order is the queue order. */
    public enum Priority {
        /** A player is blocked on this right now: goto, a deferred teleport, the barrier. */
        WAITING,
        /** Baked ahead of a moving player, or asked for by a command. */
        PREFETCH,
        /** Repairing a region the server generated; nobody is waiting on it. */
        REPAIR
    }

    /**
     * A queued bake. Ordered by urgency, then by how far the region was from the
     * nearest player when it was queued, then by arrival.
     */
    private final class BakeTask implements Runnable, Comparable<BakeTask> {
        private final Priority priority;
        private final int distance;
        private final long sequence;
        private final long key;
        private final File worldDir;
        private final int rx;
        private final int rz;

        BakeTask(Priority priority, int distance, long key, File worldDir, int rx, int rz) {
            this.priority = priority;
            this.distance = distance;
            this.sequence = sequenced.getAndIncrement();
            this.key = key;
            this.worldDir = worldDir;
            this.rx = rx;
            this.rz = rz;
        }

        @Override
        public int compareTo(BakeTask other) {
            int byPriority = priority.compareTo(other.priority);
            if (byPriority != 0) {
                return byPriority;
            }
            int byDistance = Integer.compare(distance, other.distance);
            return byDistance != 0 ? byDistance : Long.compare(sequence, other.sequence);
        }

        @Override
        public void run() {
            queued.remove(key, this); // no longer cancellable: it is running
            runBake(worldDir, rx, rz, key);
        }
    }

    static long key(int rx, int rz) {
        return (((long) rx) << 32) | (rz & 0xffffffffL);
    }

    static int rx(long key) {
        return (int) (key >> 32);
    }

    static int rz(long key) {
        return (int) key;
    }

    /**
     * Seed the baked set from the {@link BakedIndex}, and everything else on disk
     * into the dirty set.
     *
     * <p>A region file the server wrote itself (void or vanilla terrain generated
     * because a player arrived before the bake did) has no marker, so it is not
     * treated as baked: the tracker re-bakes it on approach, and the repair sweep
     * gets to it sooner than that.
     *
     * <p>A world with no index at all predates provenance tracking, so its region
     * files are adopted as baked. Re-baking an entire existing world on upgrade
     * would be a far worse surprise than leaving its existing holes to
     * {@code /arnis rebake}.
     */
    public void init(File worldDir) {
        Set<Long> onDisk = BakedIndex.regionFiles(worldDir);
        if (!BakedIndex.exists(worldDir)) {
            for (long k : onDisk) {
                BakedIndex.mark(worldDir, rx(k), rz(k));
            }
            baked.addAll(onDisk);
            if (!onDisk.isEmpty()) {
                plugin.getLogger().info("Adopted " + onDisk.size() + " existing region(s) as baked "
                        + "(no arnis-baked index yet). Regions baked from now on are tracked, and any "
                        + "the server generates itself are re-baked automatically; pre-existing void "
                        + "holes still need '/arnis rebake'.");
            }
            return;
        }

        baked.addAll(BakedIndex.marked(worldDir));
        for (long k : onDisk) {
            if (!baked.contains(k)) {
                dirty.add(k);
            }
        }
        if (!dirty.isEmpty()) {
            plugin.getLogger().info(dirty.size() + " region(s) on disk were generated by the server "
                    + "rather than baked by arnis; they will be re-baked automatically once nothing "
                    + "has them loaded.");
        }
    }

    /**
     * Note that the server has a chunk of region {@code (rx, rz)} loaded. If arnis
     * never baked it, the server is generating it — void, or worse — and that region
     * needs re-baking once it is unloaded again.
     */
    public void noteLoaded(int rx, int rz) {
        long k = key(rx, rz);
        if (!baked.contains(k)) {
            dirty.add(k);
        }
    }

    public boolean isKnown(int rx, int rz) {
        long k = key(rx, rz);
        return baked.contains(k) || inFlight.contains(k);
    }

    /**
     * Whether arnis has actually baked this region — in-flight does not count.
     * This is the question the movement barrier asks: terrain is either there or
     * the player waits.
     */
    public boolean isBaked(int rx, int rz) {
        return baked.contains(key(rx, rz));
    }

    /**
     * Whether the tracker should leave this region alone: already baked, in flight,
     * or failed too often to keep retrying.
     */
    public boolean shouldSkip(int rx, int rz) {
        return isKnown(rx, rz) || attempts.getOrDefault(key(rx, rz), 0) >= MAX_ATTEMPTS;
    }

    /** Snapshot of the regions awaiting repair. */
    public List<Long> dirtyRegions() {
        return new ArrayList<>(dirty);
    }

    public int dirtyCount() {
        return dirty.size();
    }

    public int bakedCount() {
        return baked.size();
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    public long completedCount() {
        return completed.get();
    }

    public long failedCount() {
        return failed.get();
    }

    /**
     * Submit region {@code (rx, rz)} for baking. {@code onDone} (may be null) runs
     * on the main thread when the region is ready, receiving whether it succeeded.
     *
     * <p>De-duplicated: if the region is already baked the callback fires
     * immediately; if a bake is already in flight the callback is queued to run
     * when that bake finishes (so {@code /arnis goto} can await an in-progress
     * bake instead of dropping its teleport). Must be called on the main thread.
     */
    public void submit(File worldDir, int rx, int rz, Consumer<Boolean> onDone) {
        submit(worldDir, rx, rz, onDone, Priority.PREFETCH);
    }

    /** {@link #submit} at an explicit {@link Priority}. */
    public void submit(File worldDir, int rx, int rz, Consumer<Boolean> onDone, Priority priority) {
        long k = key(rx, rz);

        // Already baked: notify now (we're on the main thread).
        if (baked.contains(k)) {
            if (onDone != null) {
                onDone.accept(true);
            }
            return;
        }

        // Queue the callback; the first caller starts the bake, later callers wait.
        if (onDone != null) {
            waiters.computeIfAbsent(k, key -> new ArrayList<>()).add(onDone);
        }
        if (!inFlight.add(k)) {
            // A bake is already queued or running. If this caller is more urgent than
            // whoever queued it, move it up — otherwise a player waits behind the
            // prefetch that happened to ask first.
            promote(k, priority);
            return;
        }
        enqueue(priority, k, worldDir, rx, rz);
    }

    /** Re-queue an already-queued region at a higher priority, if it has not started. */
    private void promote(long k, Priority priority) {
        BakeTask existing = queued.get(k);
        if (existing == null || priority.compareTo(existing.priority) >= 0) {
            return;
        }
        if (!queue.remove(existing)) {
            return; // a worker already took it; it is running anyway
        }
        queued.remove(k, existing);
        enqueue(priority, k, existing.worldDir, existing.rx, existing.rz);
    }

    private void enqueue(Priority priority, long k, File worldDir, int rx, int rz) {
        BakeTask task = new BakeTask(priority, distanceToNearestPlayer(rx, rz), k, worldDir, rx, rz);
        queued.put(k, task);
        pool.execute(task);
    }

    /**
     * Record where players are, for ranking new work and pruning old. Called from the
     * tracker on the main thread; workers and {@link #submit} read the snapshot.
     */
    public void setPlayerRegions(List<long[]> regions) {
        this.playerRegions = List.copyOf(regions);
    }

    /** Chebyshev distance in regions to the closest player, or 0 if nobody is online. */
    private int distanceToNearestPlayer(int rx, int rz) {
        int best = Integer.MAX_VALUE;
        for (long[] p : playerRegions) {
            best = Math.min(best, (int) Math.max(Math.abs(p[0] - rx), Math.abs(p[1] - rz)));
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    /**
     * Drop queued work nobody is heading for any more.
     *
     * <p>A player who turns around or logs off leaves behind a tail of regions that
     * would still be baked — minutes of network fetches for terrain no one will see,
     * ahead of work that matters. Dropped regions are simply forgotten, so the tracker
     * queues them again if anyone does come back.
     *
     * <p>Tasks with a waiter are never dropped: something (a teleport, a goto) is
     * blocked on the callback. Must be called on the main thread, where {@code waiters}
     * is safe to read.
     *
     * @return how many were dropped
     */
    public int pruneQueue(int maxDistance) {
        int removed = 0;
        for (BakeTask task : List.copyOf(queued.values())) {
            if (task.priority == Priority.WAITING || waiters.containsKey(task.key)) {
                continue;
            }
            if (distanceToNearestPlayer(task.rx, task.rz) <= maxDistance) {
                continue;
            }
            if (!queue.remove(task)) {
                continue; // already running
            }
            queued.remove(task.key, task);
            inFlight.remove(task.key);
            removed++;
        }
        dropped.addAndGet(removed);
        return removed;
    }

    public int queuedCount() {
        return queued.size();
    }

    public long droppedCount() {
        return dropped.get();
    }

    /**
     * Like {@link #submit}, but ignores the "already baked" set and always runs a
     * fresh bake.
     *
     * <p>Region-file presence is not proof arnis produced it: if the server loaded
     * an unbaked region it generates void chunks and persists them under the same
     * {@code r.X.Z.mca} name, after which {@link #initFromDisk} counts that region
     * as baked and nothing ever regenerates it. This is the escape hatch for those,
     * and for regions baked by an older/buggier arnis. Still de-duplicated against
     * in-flight bakes. Must be called on the main thread.
     */
    public void rebake(File worldDir, int rx, int rz, Consumer<Boolean> onDone) {
        rebake(worldDir, rx, rz, onDone, Priority.PREFETCH);
    }

    /** {@link #rebake} at an explicit {@link Priority}. */
    public void rebake(File worldDir, int rx, int rz, Consumer<Boolean> onDone, Priority priority) {
        long k = key(rx, rz);
        attempts.remove(k); // an explicit rebake retries a region we had given up on
        if (onDone != null) {
            waiters.computeIfAbsent(k, key -> new ArrayList<>()).add(onDone);
        }
        if (!inFlight.add(k)) {
            promote(k, priority);
            return;
        }
        enqueue(priority, k, worldDir, rx, rz);
    }

    private void runBake(File worldDir, int rx, int rz, long k) {
        RegionBaker.Result res = baker.bake(worldDir, rx, rz);
        // Record provenance here, on the worker, so it is durable even if the
        // server dies before the completion callback runs.
        if (res.ok && !BakedIndex.mark(worldDir, rx, rz)) {
            plugin.getLogger().warning("Could not write the arnis-baked marker for region "
                    + rx + "," + rz + "; it may be re-baked unnecessarily after a restart.");
        }
        runOnMain(() -> {
            inFlight.remove(k);
            if (res.ok) {
                baked.add(k);
                dirty.remove(k);
                attempts.remove(k);
                completed.incrementAndGet();
            } else {
                failed.incrementAndGet();
                int tries = attempts.merge(k, 1, Integer::sum);
                if (tries >= MAX_ATTEMPTS) {
                    plugin.getLogger().warning("Region " + rx + "," + rz + " failed to bake "
                            + tries + " times; giving up on it until a restart or '/arnis rebake'.");
                }
            }
            List<Consumer<Boolean>> callbacks = waiters.remove(k);
            if (callbacks != null) {
                for (Consumer<Boolean> cb : callbacks) {
                    cb.accept(res.ok);
                }
            }
        });
    }

    private void runOnMain(Runnable r) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, r);
        } catch (IllegalStateException | IllegalPluginAccessException ignored) {
            // Plugin is disabling; drop the completion callback.
        }
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
