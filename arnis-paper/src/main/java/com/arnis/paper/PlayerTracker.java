package com.arnis.paper;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;

/**
 * Bakes regions ahead of players so terrain streams in as they explore.
 *
 * <p>Only regions the server has <em>not</em> loaded are baked. Prefetch regions sit
 * 512+ blocks out — well beyond the view distance — so arnis writes their region
 * files before the server ever opens them, and the first chunk-load reads the
 * baked data cleanly (no region-file-cache eviction, no risk of overwriting a
 * live handle). The region a player is standing in is deliberately never baked
 * live; spawn and {@code /arnis goto} pre-bake their target before the player is there.
 */
public final class PlayerTracker extends BukkitRunnable {

    private final World world;
    private final File worldDir;
    private final BakeService bakeService;
    private final int radius;
    private final int maxPerScan;

    public PlayerTracker(World world, BakeService bakeService, int radius, int maxPerScan) {
        this.world = world;
        this.worldDir = world.getWorldFolder();
        this.bakeService = bakeService;
        this.radius = radius;
        this.maxPerScan = maxPerScan;
    }

    @Override
    public void run() {
        int submitted = 0;
        for (Player player : world.getPlayers()) {
            int prx = player.getLocation().getBlockX() >> 9;
            int prz = player.getLocation().getBlockZ() >> 9;

            // Expand ring by ring so the closest ungenerated regions bake first.
            for (int r = 1; r <= radius && submitted < maxPerScan; r++) {
                for (int dz = -r; dz <= r && submitted < maxPerScan; dz++) {
                    for (int dx = -r; dx <= r && submitted < maxPerScan; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue; // only the outermost ring at this radius
                        }
                        int rx = prx + dx;
                        int rz = prz + dz;
                        if (bakeService.isKnown(rx, rz) || isRegionLoaded(rx, rz)) {
                            continue;
                        }
                        bakeService.submit(worldDir, rx, rz, null);
                        submitted++;
                    }
                }
            }
        }
    }

    /**
     * True if any chunk of the region is currently loaded. Sampled at the corners
     * and center rather than all 1024 chunks; prefetch regions are far enough out
     * to be fully unloaded, so this only guards the rare near-player case.
     */
    private boolean isRegionLoaded(int rx, int rz) {
        int baseCx = rx << 5;
        int baseCz = rz << 5;
        int[] samples = {0, 16, 31};
        for (int cx : samples) {
            for (int cz : samples) {
                if (world.isChunkLoaded(baseCx + cx, baseCz + cz)) {
                    return true;
                }
            }
        }
        return false;
    }
}
