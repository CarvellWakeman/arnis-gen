package com.arnis.paper;

import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import java.io.File;
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

    /** Seed the "already baked" set from region files present on disk. */
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
     * Submit region {@code (rx, rz)} for baking unless it is already baked or in
     * flight. {@code onDone} (may be null) runs on the main thread after the bake
     * completes, receiving whether it succeeded.
     */
    public void submit(File worldDir, int rx, int rz, Consumer<Boolean> onDone) {
        long k = key(rx, rz);
        if (baked.contains(k) || !inFlight.add(k)) {
            return;
        }
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
                if (onDone != null) {
                    onDone.accept(res.ok);
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
