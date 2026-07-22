package com.arnis.paper;

import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
    public void onLoad() {
        saveDefaultConfig();
        config = ArnisConfig.from(getConfig(), getDataFolder());
        // Bake the spawn area here, before the server loads its worlds. Then the
        // server reads real terrain from disk instead of generating void there, and
        // arnis never overwrites a region file the server already has open (which it
        // logs as a "corrupt regionfile" and has to recover from).
        if (config.bakeSpawnOnEnable && arnisBinaryConfigured()) {
            preBakeSpawnArea();
        }
    }

    /**
     * Synchronously bake every region the server will load around spawn, before it
     * loads the world. Just the spawn region isn't enough: the initial view spills
     * into neighbouring regions (especially when spawn sits near a region edge), the
     * server generates those as void, and the streaming tracker never bakes over a
     * region the server already has loaded — so they'd stay a void hole at spawn.
     */
    private void preBakeSpawnArea() {
        File worldFolder = new File(getServer().getWorldContainer(), config.worldName);

        List<int[]> toBake = new ArrayList<>();
        for (int[] r : regionsAroundView(config.spawnX, config.spawnZ)) {
            File rf = new File(new File(worldFolder, "region"), "r." + r[0] + "." + r[1] + ".mca");
            if (!rf.isFile()) {
                toBake.add(r);
            }
        }
        if (toBake.isEmpty()) {
            return; // already baked in a previous run
        }
        getLogger().info("Pre-baking " + toBake.size() + " spawn-area region(s) into '"
                + config.worldName + "' before world load (first start; each takes ~30-60s on a "
                + "debug arnis build; use a release build and a region-centered spawn to speed this up)...");
        RegionBaker baker = new RegionBaker(this, config);
        for (int[] r : toBake) {
            if (baker.bake(worldFolder, r[0], r[1]).ok) {
                // Record provenance here too: this path predates the BakeService, and
                // an unmarked region would be treated as one the server generated.
                BakedIndex.mark(worldFolder, r[0], r[1]);
            } else {
                getLogger().warning("Spawn-area pre-bake failed for region " + r[0] + "," + r[1] + ".");
            }
        }
        getLogger().info("Spawn area pre-baked.");
    }

    /**
     * Every region the server will load when a player stands at block {@code (x, z)}:
     * the whole view distance, plus two chunks of margin.
     *
     * <p>Anywhere a player is placed, all of these must already be baked. A player
     * dropped into a region without its neighbours sees the view spill across the
     * region border, the server generate the neighbour as void, and — because the
     * tracker never bakes a region the server already has loaded, and the server
     * persists that void under the same region-file name — the seam becomes
     * permanent. Used by both the spawn pre-bake and {@code /arnis goto}.
     */
    public List<int[]> regionsAroundView(int x, int z) {
        int viewChunks = 10;
        try {
            viewChunks = Math.max(2, getServer().getViewDistance());
        } catch (Throwable ignored) {
            // Server not far enough along to report view distance; assume the default.
        }
        int radius = (viewChunks + 2) * 16;
        List<int[]> out = new ArrayList<>();
        for (int rz = (z - radius) >> 9; rz <= (z + radius) >> 9; rz++) {
            for (int rx = (x - radius) >> 9; rx <= (x + radius) >> 9; rx++) {
                out.add(new int[] {rx, rz});
            }
        }
        return out;
    }

    @Override
    public void onEnable() {
        if (config == null) { // normally set in onLoad
            saveDefaultConfig();
            config = ArnisConfig.from(getConfig(), getDataFolder());
        }
        baker = new RegionBaker(this, config);
        bakeService = new BakeService(this, baker, config.workers);

        getLogger().info("Enabling: origin " + config.originLat + "," + config.originLng
                + " -> MC (0,0), scale " + config.scale + " blocks/m, arnis " + config.arnisBinaryNote);

        PluginCommand command = getCommand("arnis");
        if (command != null) {
            // Both, deliberately: setExecutor does not register the tab completer, and
            // without one Bukkit completes online player names instead of subcommands.
            ArnisCommand handler = new ArnisCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        } else {
            getLogger().severe("Command 'arnis' is not defined in plugin.yml.");
        }

        // World setup runs after the server has loaded its worlds. Bukkit forbids
        // creating worlds during STARTUP, and if arnis is the primary world it is
        // loaded by the server (via getDefaultWorldGenerator); either way we do the
        // rest on the first tick, when the world is available or safe to create.
        getServer().getScheduler().runTask(this, this::setUpWorld);
    }

    /** Acquire (or create) the arnis world, then start baking and streaming. */
    private void setUpWorld() {
        // If arnis is the server's primary/already-loaded world, use it; otherwise
        // create it now (post-startup, so createWorld is allowed).
        arnisWorld = getServer().getWorld(config.worldName);
        if (arnisWorld == null) {
            arnisWorld = new WorldCreator(config.worldName)
                    .generator(new VoidChunkGenerator(config.spawnX, config.spawnZ))
                    .createWorld();
        }
        if (arnisWorld == null) {
            getLogger().severe("Failed to create/load arnis world '" + config.worldName + "'.");
            return;
        }
        getLogger().info("Arnis world '" + config.worldName + "' ready.");

        // A world the server loaded before this plugin ran keeps whatever generator it
        // was created with. If that isn't ours, unbaked area comes up as vanilla
        // terrain instead of void — solid, plausible-looking, and persisted over the
        // top of anything arnis bakes there later.
        if (!(arnisWorld.getGenerator() instanceof VoidChunkGenerator)) {
            getLogger().warning("World '" + config.worldName + "' is not using the ArnisGen void "
                    + "generator, so unbaked area will be generated as ordinary Minecraft terrain. "
                    + "Add it to bukkit.yml:  worlds:\n    " + config.worldName
                    + ":\n      generator: ArnisGen\nand restart (see SERVER_SETUP.md). Terrain "
                    + "already generated the wrong way needs '/arnis rebake'.");
        }

        releaseSpawnChunks(arnisWorld);

        bakeService.init(arnisWorld.getWorldFolder());
        getServer().getPluginManager().registerEvents(
                new UnbakedRegionWatcher(arnisWorld, bakeService), this);

        // If the arnis binary is a path that doesn't exist, don't even try to bake:
        // warn once with an actionable message and leave the world void until it's set.
        if (!arnisBinaryConfigured()) {
            if (config.arnisBinaryFound) {
                getLogger().warning("arnis executable '" + config.arnisBinary + "' is not marked "
                        + "executable, so terrain baking is disabled. Run: chmod +x '"
                        + config.arnisBinary + "' and restart the server.");
            } else {
                getLogger().warning("arnis executable " + config.arnisBinaryNote + ", so terrain "
                        + "baking is disabled. Point 'arnis-binary' in plugins/" + getName()
                        + "/config.yml at the arnis executable — a path relative to the server "
                        + "directory or an enclosing checkout works, e.g. 'target/release/arnis' — "
                        + "then restart the server (see SERVER_SETUP.md).");
            }
            return;
        }

        if (config.bakeSpawnOnEnable) {
            bakeSpawnRegion();
        }
        if (config.streamingEnabled) {
            trackerTask = new PlayerTracker(this, arnisWorld, bakeService, config.prefetchRadius,
                    config.maxPerScan, config.repairUnbaked ? config.maxRepairsPerScan : 0,
                    config.leadSeconds, config.intervalTicks)
                    .runTaskTimer(this, config.intervalTicks, config.intervalTicks);
            getLogger().info("Streaming enabled: prefetch radius " + config.prefetchRadius
                    + " region(s), " + config.workers + " worker(s), scan every "
                    + config.intervalTicks + " ticks, repair "
                    + (config.repairUnbaked ? "on (max " + config.maxRepairsPerScan + "/scan)" : "off")
                    + ", lookahead " + (config.leadSeconds > 0 ? config.leadSeconds + "s" : "off")
                    + ", barrier " + (config.barrier ? "on" : "off")
                    + ", safe-teleport " + (config.safeTeleport ? "on" : "off") + ".");

            // Both only make sense alongside streaming: without something baking the
            // frontier, the barrier would be a wall that never lifts and a deferred
            // teleport would never arrive.
            if (config.barrier || config.safeTeleport) {
                getServer().getPluginManager().registerEvents(
                        new MovementBarrier(this, arnisWorld, bakeService,
                                config.barrier, config.safeTeleport), this);
            }
        }
    }

    /**
     * Stops the server keeping the world's spawn chunks resident.
     *
     * <p>Otherwise the server holds the void spawn chunks it generated at startup and
     * saves them back over the region arnis bakes there — leaving a void hole at spawn,
     * while everything else (baked ahead of players, never resident as void) is fine.
     *
     * <p>The mechanism differs across server versions, and referencing the wrong one
     * directly throws {@link NoSuchFieldError} / {@link NoSuchMethodError} at the call
     * site — which previously aborted world setup before baking ever started. So the
     * game rule is looked up by name, the older API is reached reflectively, and every
     * failure is contained here: a spawn that needs one `/arnis rebake` is a far better
     * outcome than a generator that never runs.
     */
    private void releaseSpawnChunks(World world) {
        // 1.20.5+: the spawnChunkRadius game rule. Resolved by name because the
        // GameRule constant for it is not present on every server build.
        try {
            GameRule<?> rule = GameRule.getByName("spawnChunkRadius");
            if (rule != null && Integer.class.equals(rule.getType())) {
                @SuppressWarnings("unchecked")
                GameRule<Integer> radius = (GameRule<Integer>) rule;
                if (world.setGameRule(radius, 0)) {
                    return;
                }
            }
        } catch (Throwable t) {
            getLogger().fine("spawnChunkRadius game rule unavailable: " + t);
        }

        // Pre-1.20.5: the equivalent World method, deprecated and eventually removed —
        // called reflectively so this compiles and runs against either API.
        try {
            World.class.getMethod("setKeepSpawnInMemory", boolean.class).invoke(world, false);
            return;
        } catch (Throwable t) {
            getLogger().fine("setKeepSpawnInMemory unavailable: " + t);
        }

        getLogger().warning("Could not stop the server keeping spawn chunks loaded on this "
                + "server version. Terrain streamed to players is unaffected, but the spawn "
                + "region may come up as a void hole — fix it with '/arnis rebake' and a "
                + "restart, or move spawn with the 'spawn' setting in config.yml.");
    }

    /**
     * Whether the arnis binary was located — as an absolute path, relative to the
     * server or the enclosing checkout, or on {@code PATH} (see {@link ArnisBinary}).
     * When it wasn't, baking is suppressed and the reason is logged once rather than
     * failing per region.
     */
    private boolean arnisBinaryConfigured() {
        return config.arnisBinaryFound && !ArnisBinary.needsExecutableBit(config.arnisBinary);
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
