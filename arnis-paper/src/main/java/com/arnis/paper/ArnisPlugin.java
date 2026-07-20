package com.arnis.paper;

import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

/**
 * Phase 1 of the on-demand server terrain generator: a minimal Paper plugin that
 * bootstraps a void world and bakes arnis region files into it on request.
 *
 * <p>Baked terrain streams in as chunks load fresh. Hot-reloading a region the
 * server already has resident is best-effort (see {@link #reloadRegionChunks});
 * a server restart always loads baked regions cleanly. Automatic, safe streaming
 * is Phase 2.
 */
public final class ArnisPlugin extends JavaPlugin {

    private ArnisConfig config;
    private RegionBaker baker;
    private World arnisWorld;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = ArnisConfig.from(getConfig());
        baker = new RegionBaker(this, config);

        getLogger().info("Enabling: origin " + config.originLat + "," + config.originLng
                + " -> MC (0,0), scale " + config.scale + " blocks/m, arnis '" + config.arnisBinary + "'");

        arnisWorld = new WorldCreator(config.worldName)
                .generator(new VoidChunkGenerator(config.spawnX, config.spawnZ))
                .createWorld();
        if (arnisWorld == null) {
            getLogger().severe("Failed to create/load arnis world '" + config.worldName + "'.");
        } else {
            getLogger().info("Arnis world '" + config.worldName + "' ready.");
        }

        PluginCommand command = getCommand("arnis");
        if (command != null) {
            command.setExecutor(new ArnisCommand(this));
        } else {
            getLogger().severe("Command 'arnis' is not defined in plugin.yml.");
        }

        if (arnisWorld != null && config.bakeSpawnOnEnable) {
            bakeSpawnRegionAsync();
        }
    }

    @Override
    public void onDisable() {
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

    /** Bakes the spawn region, then sets spawn Y from the terrain and reloads it. */
    private void bakeSpawnRegionAsync() {
        int rx = config.spawnX >> 9;
        int rz = config.spawnZ >> 9;
        File worldDir = arnisWorld.getWorldFolder();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            RegionBaker.Result res = baker.bake(worldDir, rx, rz);
            getServer().getScheduler().runTask(this, () -> {
                if (res.ok) {
                    // The bake itself is the success; the region file is on disk now.
                    // Loading it into the live world and moving spawn onto the terrain
                    // is best-effort (see reloadRegionChunks).
                    getLogger().info("Spawn region baked.");
                    try {
                        reloadRegionChunks(arnisWorld, rx, rz);
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
        });
    }

    /**
     * Bakes a list of regions off-thread, then reloads their chunks on the main
     * thread and reports back to {@code feedback}.
     */
    public void prewarmAsync(World world, List<int[]> regions, CommandSender feedback) {
        File worldDir = world.getWorldFolder();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            int ok = 0;
            for (int[] r : regions) {
                if (baker.bake(worldDir, r[0], r[1]).ok) {
                    ok++;
                }
            }
            final int baked = ok;
            getServer().getScheduler().runTask(this, () -> {
                for (int[] r : regions) {
                    reloadRegionChunks(world, r[0], r[1]);
                }
                feedback.sendMessage("Prewarm complete: " + baked + "/" + regions.size()
                        + " region(s) baked. Newly entered chunks will show the baked terrain; "
                        + "restart the server if resident chunks still look void.");
            });
        });
    }

    /**
     * Best-effort reload of a region's 32x32 chunks from disk so a freshly baked
     * region appears without a restart. Discards the in-memory (void) copy first.
     *
     * <p>Limitation: the server may keep a region file handle cached, so a chunk
     * it has already persisted may not pick up arnis's external write until a
     * restart. Reliable live streaming is Phase 2.
     */
    private void reloadRegionChunks(World world, int rx, int rz) {
        int baseCx = rx * 32;
        int baseCz = rz * 32;
        for (int cx = baseCx; cx < baseCx + 32; cx++) {
            for (int cz = baseCz; cz < baseCz + 32; cz++) {
                if (world.isChunkLoaded(cx, cz)) {
                    world.unloadChunk(cx, cz, false); // drop the void copy without saving
                }
                world.loadChunk(cx, cz);            // read the baked data from disk
                world.unloadChunkRequest(cx, cz);    // let it unload again if unused
            }
        }
    }
}
