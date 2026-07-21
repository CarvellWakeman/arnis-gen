package com.arnis.paper;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;

/**
 * Immutable snapshot of the plugin configuration (see {@code config.yml}).
 *
 * <p>The {@code origin} anchors the shared coordinate frame: it maps to Minecraft
 * {@code (0,0)} and is passed to every {@code arnis --bake-region} invocation, so
 * independently baked regions line up seamlessly.
 */
public final class ArnisConfig {
    public final String worldName;
    public final double originLat;
    public final double originLng;
    public final double scale;
    public final int bakeMargin;
    public final int groundLevel;
    /** The command actually run — the configured value resolved to a path where possible. */
    public final String arnisBinary;
    /** The raw {@code arnis-binary} config value, for messages that should echo the config. */
    public final String arnisBinaryConfigured;
    /** Whether an executable was located; false disables baking with an actionable warning. */
    public final boolean arnisBinaryFound;
    /** How {@link #arnisBinary} was arrived at, for the startup log. */
    public final String arnisBinaryNote;
    public final boolean bakeSpawnOnEnable;
    public final int spawnX;
    public final int spawnZ;

    // Vertical mapping (shared across bakes so regions line up vertically).
    public final double verticalScale;
    public final double elevationBase;

    // Automatic streaming (Phase 2).
    public final boolean streamingEnabled;
    public final int prefetchRadius;
    public final int workers;
    public final int intervalTicks;
    public final int maxPerScan;
    /** Re-bake regions the server generated itself when a player outran streaming. */
    public final boolean repairUnbaked;
    public final int maxRepairsPerScan;
    /** Hold players at the edge of baked terrain rather than letting them outrun it. */
    public final boolean barrier;
    /** Seconds of travel to keep baked ahead of a moving player; 0 disables prediction. */
    public final double leadSeconds;

    private ArnisConfig(
            String worldName,
            double originLat,
            double originLng,
            double scale,
            int bakeMargin,
            int groundLevel,
            String arnisBinaryConfigured,
            ArnisBinary.Resolved arnisBinary,
            boolean bakeSpawnOnEnable,
            int spawnX,
            int spawnZ,
            double verticalScale,
            double elevationBase,
            boolean streamingEnabled,
            int prefetchRadius,
            int workers,
            int intervalTicks,
            int maxPerScan,
            boolean repairUnbaked,
            int maxRepairsPerScan,
            boolean barrier,
            double leadSeconds) {
        this.worldName = worldName;
        this.originLat = originLat;
        this.originLng = originLng;
        this.scale = scale;
        this.bakeMargin = bakeMargin;
        this.groundLevel = groundLevel;
        this.arnisBinary = arnisBinary.command;
        this.arnisBinaryConfigured = arnisBinaryConfigured;
        this.arnisBinaryFound = arnisBinary.found;
        this.arnisBinaryNote = arnisBinary.note;
        this.bakeSpawnOnEnable = bakeSpawnOnEnable;
        this.spawnX = spawnX;
        this.spawnZ = spawnZ;
        this.verticalScale = verticalScale;
        this.elevationBase = elevationBase;
        this.streamingEnabled = streamingEnabled;
        this.prefetchRadius = prefetchRadius;
        this.workers = workers;
        this.intervalTicks = intervalTicks;
        this.maxPerScan = maxPerScan;
        this.repairUnbaked = repairUnbaked;
        this.maxRepairsPerScan = maxRepairsPerScan;
        this.barrier = barrier;
        this.leadSeconds = leadSeconds;
    }

    /**
     * Reads an {@link ArnisConfig} from a Bukkit {@link FileConfiguration}.
     *
     * @param dataFolder the plugin data folder, used to resolve a relative
     *                   {@code arnis-binary} (see {@link ArnisBinary})
     */
    public static ArnisConfig from(FileConfiguration c, File dataFolder) {
        double scale = c.getDouble("scale", 1.0);
        String binary = c.getString("arnis-binary", "arnis");
        return new ArnisConfig(
                c.getString("world", "arnis"),
                c.getDouble("origin.lat", 0.0),
                c.getDouble("origin.lng", 0.0),
                scale,
                c.getInt("bake-margin", 64),
                c.getInt("ground-level", -62),
                binary,
                ArnisBinary.resolve(binary, dataFolder),
                c.getBoolean("bake-spawn-on-enable", true),
                c.getInt("spawn.x", 256),
                c.getInt("spawn.z", 256),
                c.getDouble("vertical-scale", scale),
                c.getDouble("elevation-base", 0.0),
                c.getBoolean("streaming.enabled", true),
                Math.max(0, c.getInt("streaming.prefetch-radius", 2)),
                Math.max(1, c.getInt("streaming.workers", 2)),
                Math.max(1, c.getInt("streaming.interval-ticks", 40)),
                Math.max(1, c.getInt("streaming.max-per-scan", 8)),
                c.getBoolean("streaming.repair-unbaked", true),
                Math.max(0, c.getInt("streaming.max-repairs-per-scan", 2)),
                c.getBoolean("streaming.barrier", true),
                Math.max(0.0, c.getDouble("streaming.lead-seconds", 60.0)));
    }
}
