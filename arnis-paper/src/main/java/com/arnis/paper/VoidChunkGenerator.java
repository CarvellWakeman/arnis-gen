package com.arnis.paper;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

/**
 * Generates empty (air) chunks.
 *
 * <p>The arnis world uses this so that chunks arnis has already baked load
 * verbatim from their region files (chunk storage is consulted before the
 * generator), while everything not yet baked is cheap void rather than vanilla
 * terrain. As players explore into a region the plugin has baked ahead of them,
 * its real terrain appears; unbaked areas stay empty.
 */
public final class VoidChunkGenerator extends ChunkGenerator {

    private final int spawnX;
    private final int spawnZ;

    public VoidChunkGenerator(int spawnX, int spawnZ) {
        this.spawnX = spawnX;
        this.spawnZ = spawnZ;
    }

    // Take over every generation stage and place nothing, yielding a void world.
    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    /**
     * A fixed spawn so the server does not spend startup searching a void world
     * for solid ground. The plugin resets spawn Y to the real surface once the
     * spawn region is baked.
     */
    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, spawnX + 0.5, 100.0, spawnZ + 0.5);
    }
}
