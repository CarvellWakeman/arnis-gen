package com.arnis.paper;

import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;

/**
 * The on-demand server terrain generator.
 *
 * <p>Bootstraps a void world and streams arnis-baked region files into it: a
 * {@link PlayerTracker} bakes regions ahead of players via a bounded
 * {@link BakeService}, so terrain appears as they explore. Regions are only baked
 * before the server loads them, so the baked files are read cleanly on approach —
 * no restart needed for streamed terrain. The spawn region (loaded at startup)
 * and manual prewarms are the exception: they use a best-effort reload, with a
 * restart as the reliable fallback.
 */
public final class ArnisPlugin extends JavaPlugin {

    private ArnisConfig config;
    private RegionBaker baker;
    private BakeService bakeService;
    private BukkitTask trackerTask;
    private World arnisWorld;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = ArnisConfig.from(getConfig());
        baker = new RegionBaker(this, config);
        bakeService = new BakeService(this, baker, config.workers);

        getLogger().info("Enabling: origin " + config.originLat + "," + config.originLng
                + " -> MC (0,0), scale " + config.scale + " blocks/m, arnis '" + config.arnisBinary + "'");

        arnisWorld = new WorldCreator(config.worldName)
                .generator(new VoidChunkGenerator(config.spawnX, config.spawnZ))
                .createWorld();
        if (arnisWorld == null) {
            getLogger().severe("Failed to create/load arnis world '" + config.worldName + "'.");
            return;
        }
        getLogger().info("Arnis world '" + config.worldName + "' ready.");
        bakeService.initFromDisk(arnisWorld.getWorldFolder());

        PluginCommand command = getCommand("arnis");
        if (command != null) {
            command.setExecutor(new ArnisCommand(this));
        } else {
            getLogger().severe("Command 'arnis' is not defined in plugin.yml.");
        }

        if (config.bakeSpawnOnEnable) {
            bakeSpawnRegion();
        }
        if (config.streamingEnabled) {
            trackerTask = new PlayerTracker(arnisWorld, bakeService, config.prefetchRadius, config.maxPerScan)
                    .runTaskTimer(this, config.intervalTicks, config.intervalTicks);
            getLogger().info("Streaming enabled: prefetch radius " + config.prefetchRadius
                    + " region(s), " + config.workers + " worker(s), scan every "
                    + config.intervalTicks + " ticks.");
        }
    }

    @Override
    public void onDisable() {
        if (trackerTask != null) {
            trackerTask.cancel();
        }
        if (bakeService != null) {
            bakeService.shutdown();
        }
        getLogger().info("Arnis generator disabled.");
    }

    /**
     * Lets a world configured with {@code generator: ArnisGen} in bukkit.yml use
     * the void generator too, not only the plugin-created world.
     */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        int sx = config != null ? config.spawnX : 0;
        int sz = config != null ? config.spawnZ : 0;
        return new VoidChunkGenerator(sx, sz);
    }

    public ArnisConfig config() {
        return config;
    }

    public World arnisWorld() {
        return arnisWorld;
    }

    public BakeService bakeService() {
        return bakeService;
    }

    /** Bakes the spawn region, then sets spawn Y from the terrain and reloads it. */
    private void bakeSpawnRegion() {
        int rx = config.spawnX >> 9;
        int rz = config.spawnZ >> 9;
        File worldDir = arnisWorld.getWorldFolder();
        bakeService.submit(worldDir, rx, rz, ok -> {
            if (ok) {
                // The bake itself is the success; the region file is on disk now.
                getLogger().info("Spawn region baked.");
                try {
                    reloadRegionChunks(rx, rz);
                    int y = arnisWorld.getHighestBlockYAt(config.spawnX, config.spawnZ) + 1;
                    arnisWorld.setSpawnLocation(config.spawnX, y, config.spawnZ);
                    getLogger().info("Spawn set to " + config.spawnX + "," + y + "," + config.spawnZ);
                } catch (Exception e) {
                    getLogger().warning("Post-bake spawn setup failed (terrain is baked; "
                            + "restart to load it): " + e.getMessage());
                }
            } else {
                getLogger().warning("Spawn region bake failed; the world will be void at spawn.");
            }
        });
    }

    /**
     * Best-effort reflection of a freshly baked region in the live world: drops the
     * stale (void) copies of only the chunks the server currently holds resident,
     * so they re-read arnis's baked data from disk on next access. Used for regions
     * the server already has loaded (spawn, manual prewarm/goto). Streaming never
     * needs this — prefetched regions are unloaded and load cleanly on approach.
     *
     * <p>Deliberately does NOT force-load the region's chunks: loading all 1024 on
     * the main thread would generate the unloaded ones and freeze the server.
     * Unloaded chunks load fresh from disk on demand with no action here.
     *
     * <p>Limitation: the server may keep a region file handle cached, so a chunk it
     * has already persisted may not pick up arnis's external write until a restart.
     */
    public void reloadRegionChunks(int rx, int rz) {
        int baseCx = rx * 32;
        int baseCz = rz * 32;
        for (int cx = baseCx; cx < baseCx + 32; cx++) {
            for (int cz = baseCz; cz < baseCz + 32; cz++) {
                if (arnisWorld.isChunkLoaded(cx, cz)) {
                    arnisWorld.unloadChunk(cx, cz, false); // drop void copy; reloads from disk lazily
                }
            }
        }
    }
}
