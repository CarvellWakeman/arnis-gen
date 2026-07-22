package com.arnis.paper;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Flags regions the server loads that arnis never baked.
 *
 * <p>Any chunk load in an unbaked region means the server is about to generate that
 * region itself — void, and then persist it. Noting it here queues the region for
 * repair as soon as the player moves on, so outrunning the streamer costs a few
 * seconds of empty space rather than a permanent hole.
 *
 * <p>Deliberately does not ask whether the chunk is <em>new</em>: that distinction
 * needs API whose availability varies by server version, and it isn't needed. A
 * region arnis baked is in the index and never reaches the dirty set; a region it
 * didn't needs re-baking whether this particular chunk was generated just now or
 * generated on an earlier visit.
 */
public final class UnbakedRegionWatcher implements Listener {

    private final World world;
    private final BakeService bakeService;

    public UnbakedRegionWatcher(World world, BakeService bakeService) {
        this.world = world;
        this.bakeService = bakeService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        if (!chunk.getWorld().equals(world)) {
            return;
        }
        bakeService.noteLoaded(chunk.getX() >> 5, chunk.getZ() >> 5);
    }
}
