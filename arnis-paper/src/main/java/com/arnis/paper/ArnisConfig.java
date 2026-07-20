package com.arnis.paper;

import org.bukkit.configuration.file.FileConfiguration;

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
    public final String arnisBinary;
    public final boolean bakeSpawnOnEnable;
    public final int spawnX;
    public final int spawnZ;

    private ArnisConfig(
            String worldName,
            double originLat,
            double originLng,
            double scale,
            int bakeMargin,
            int groundLevel,
            String arnisBinary,
            boolean bakeSpawnOnEnable,
            int spawnX,
            int spawnZ) {
        this.worldName = worldName;
        this.originLat = originLat;
        this.originLng = originLng;
        this.scale = scale;
        this.bakeMargin = bakeMargin;
        this.groundLevel = groundLevel;
        this.arnisBinary = arnisBinary;
        this.bakeSpawnOnEnable = bakeSpawnOnEnable;
        this.spawnX = spawnX;
        this.spawnZ = spawnZ;
    }

    /** Reads an {@link ArnisConfig} from a Bukkit {@link FileConfiguration}. */
    public static ArnisConfig from(FileConfiguration c) {
        return new ArnisConfig(
                c.getString("world", "arnis"),
                c.getDouble("origin.lat", 0.0),
                c.getDouble("origin.lng", 0.0),
                c.getDouble("scale", 1.0),
                c.getInt("bake-margin", 64),
                c.getInt("ground-level", -62),
                c.getString("arnis-binary", "arnis"),
                c.getBoolean("bake-spawn-on-enable", true),
                c.getInt("spawn.x", 0),
                c.getInt("spawn.z", 0));
    }
}
