package com.arnis.paper;

import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

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

    private static final Pattern REGION_FILE = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

    private final Plugin plugin;
    private final RegionBaker baker;
    private final ExecutorService pool;
    private final Set<Long> baked = ConcurrentHashMap.newKeySet();
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
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
        this.pool = Executors.newFixedThreadPool(Math.max(1, workers), factory);
    }

    static long key(int rx, int rz) {
        return (((long) rx) << 32) | (rz & 0xffffffffL);
    }

    /**
     * Seed the "already baked" set from region files present on disk.
     *
     * <p>Presence is a proxy, not proof: the server writes a region file for any
     * region it loads, so one it generated as void looks identical here. Such a
     * region is then never baked again — see {@link #rebake} for the way out.
     */
    public void initFromDisk(File worldDir) {
        File regionDir = new File(worldDir, "region");
        File[] files = regionDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            var m = REGION_FILE.matcher(f.getName());
            if (m.matches()) {
                baked.add(key(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
            }
        }
    }

    public boolean isKnown(int rx, int rz) {
        long k = key(rx, rz);
        return baked.contains(k) || inFlight.contains(k);
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
            return; // a bake is already running for this region
        }
        startBake(worldDir, rx, rz, k);
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
        long k = key(rx, rz);
        if (onDone != null) {
            waiters.computeIfAbsent(k, key -> new ArrayList<>()).add(onDone);
        }
        if (!inFlight.add(k)) {
            return; // a bake is already running for this region
        }
        startBake(worldDir, rx, rz, k);
    }

    private void startBake(File worldDir, int rx, int rz, long k) {
        pool.submit(() -> {
            RegionBaker.Result res = baker.bake(worldDir, rx, rz);
            runOnMain(() -> {
                inFlight.remove(k);
                if (res.ok) {
                    baked.add(k);
                    completed.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                }
                List<Consumer<Boolean>> callbacks = waiters.remove(k);
                if (callbacks != null) {
                    for (Consumer<Boolean> cb : callbacks) {
                        cb.accept(res.ok);
                    }
                }
            });
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
