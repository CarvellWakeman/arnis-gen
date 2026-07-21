package com.arnis.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Holds players at the edge of baked terrain.
 *
 * <p>Streaming is a race — a flying player crosses a 512-block region in ~23 s while
 * a cold bake takes 30–60 s — and losing it is expensive: the server generates the
 * unbaked region, persists it, and (because it now has that region file open and
 * cached) will not pick up a later external bake of it without a restart. Prevention
 * is the only complete fix; {@link PlayerTracker}'s repair sweep is the safety net
 * for what slips through.
 *
 * <p>The barrier triggers on the player's <em>view</em>, not their position: standing
 * one block short of a region border still loads chunks across it. So a move is
 * blocked when the destination's {@link ArnisPlugin#regionsAroundView view footprint}
 * includes anything arnis has not baked — which stops players about a view distance
 * short of the unbaked edge, before the server generates anything.
 *
 * <p>Deliberately permissive in two cases: teleports are exempt (they bake their own
 * destination first, and cancelling them would break {@code /arnis goto}), and a
 * player who is <em>already</em> in unbaked space is never blocked, so a barrier can
 * never trap someone in a hole.
 */
public final class MovementBarrier implements Listener {

    /** Permission to ignore the barrier entirely. */
    public static final String BYPASS = "arnis.bypass";

    /** Minimum gap between notices (and bake requests) per player. */
    private static final long NOTICE_INTERVAL_MS = 2000;

    private final ArnisPlugin plugin;
    private final World world;
    private final BakeService bakeService;
    private final File worldDir;
    private final Map<UUID, Long> lastNotice = new HashMap<>();

    public MovementBarrier(ArnisPlugin plugin, World world, BakeService bakeService) {
        this.plugin = plugin;
        this.world = world;
        this.bakeService = bakeService;
        this.worldDir = world.getWorldFolder();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return; // goto/tp bake their destination first; cancelling would break them
        }
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || !world.equals(to.getWorld())) {
            return;
        }
        // Only worth checking when the destination is a different chunk: that is the
        // granularity at which new terrain gets loaded, and it keeps this off the hot
        // path for the many moves that stay put.
        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4)
                && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4)) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        if (viewIsBaked(to.getBlockX(), to.getBlockZ())) {
            return;
        }
        // Already standing somewhere unbaked: let them move freely rather than
        // pinning them inside a hole.
        if (!viewIsBaked(from.getBlockX(), from.getBlockZ())) {
            return;
        }

        event.setCancelled(true);

        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueId());
        if (last != null && now - last < NOTICE_INTERVAL_MS) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        player.sendActionBar(Component.text("Generating terrain ahead..."));
        requestBakes(to.getBlockX(), to.getBlockZ());
    }

    /** Whether every region a player at {@code (x, z)} would load is baked. */
    private boolean viewIsBaked(int x, int z) {
        for (int[] region : plugin.regionsAroundView(x, z)) {
            if (!bakeService.isBaked(region[0], region[1])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Queue the regions the player is waiting on, so the wall lifts as soon as
     * possible rather than at the tracker's next convenience. Regions the server has
     * loaded are left alone — baking over an open region file is what corrupts it —
     * but the barrier stops players far enough short that they should not be.
     */
    private void requestBakes(int x, int z) {
        for (int[] region : plugin.regionsAroundView(x, z)) {
            int rx = region[0];
            int rz = region[1];
            if (bakeService.shouldSkip(rx, rz) || isRegionLoaded(rx, rz)) {
                continue;
            }
            bakeService.submit(worldDir, rx, rz, null);
        }
    }

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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastNotice.remove(event.getPlayer().getUniqueId());
    }
}
