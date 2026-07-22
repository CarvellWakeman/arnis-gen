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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * destination first — see {@link #onTeleport}), and a player who is <em>already</em>
 * in unbaked space is never blocked, so a barrier can never trap someone in a hole.
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
    private final boolean barrier;
    private final boolean safeTeleport;
    private final Map<UUID, Long> lastNotice = new HashMap<>();
    /** Destination each waiting player will be sent to once their terrain is baked. */
    private final Map<UUID, Location> pendingTeleports = new HashMap<>();
    /** Players whose teleport we are re-issuing ourselves, so it passes straight through. */
    private final Set<UUID> reissuing = new HashSet<>();

    public MovementBarrier(ArnisPlugin plugin, World world, BakeService bakeService,
                           boolean barrier, boolean safeTeleport) {
        this.plugin = plugin;
        this.world = world;
        this.bakeService = bakeService;
        this.worldDir = world.getWorldFolder();
        this.barrier = barrier;
        this.safeTeleport = safeTeleport;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!barrier || event instanceof PlayerTeleportEvent) {
            return; // teleports are handled by onTeleport, which waits rather than blocks
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

    /**
     * Defers a teleport into unbaked terrain until its arrival view is baked.
     *
     * <p>A teleport is the worst case the barrier cannot cover: it drops a player
     * somewhere arbitrary with no lead time at all, and blocking it outright would
     * only strand them. So the teleport is cancelled, the destination's view footprint
     * is baked, and the teleport is then re-issued — the same thing
     * {@code /arnis goto} has always done, generalised to every other teleport.
     *
     * <p>Regions the server already has loaded are not waited on: baking over an open
     * region file is what corrupts it, and such a region is already in the repair
     * sweep's hands.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!safeTeleport) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (reissuing.contains(id)) {
            return; // our own re-issue of an already-prepared destination
        }
        Location to = event.getTo();
        if (to == null || !world.equals(to.getWorld()) || player.hasPermission(BYPASS)) {
            return;
        }

        List<int[]> needed = bakeableUnbaked(to.getBlockX(), to.getBlockZ());
        if (needed.isEmpty()) {
            return; // destination is ready (this is the path /arnis goto takes)
        }

        event.setCancelled(true);
        Location target = to.clone();
        pendingTeleports.put(id, target);
        player.sendMessage("Preparing terrain at your destination (" + needed.size()
                + " region(s)); you will be moved when it is ready.");

        // Arrive only once every region is done. Callbacks all run on the main thread,
        // so these counters need no locking — the same pattern /arnis goto uses.
        int[] remaining = {needed.size()};
        boolean[] allOk = {true};
        for (int[] region : needed) {
            bakeService.submit(worldDir, region[0], region[1], ok -> {
                if (!ok) {
                    allOk[0] = false;
                }
                if (--remaining[0] > 0) {
                    return;
                }
                if (!target.equals(pendingTeleports.get(id))) {
                    return; // a later teleport request superseded this one
                }
                pendingTeleports.remove(id);
                if (!player.isOnline()) {
                    return;
                }
                if (!allOk[0]) {
                    player.sendMessage("Could not prepare terrain there; teleport cancelled.");
                    return;
                }
                reissuing.add(id);
                try {
                    player.teleport(target);
                } finally {
                    reissuing.remove(id);
                }
            }, BakeService.Priority.WAITING);
        }
    }

    /** Regions of this view footprint that are unbaked and safe for arnis to write. */
    private List<int[]> bakeableUnbaked(int x, int z) {
        List<int[]> out = new ArrayList<>();
        for (int[] region : plugin.regionsAroundView(x, z)) {
            if (bakeService.isBaked(region[0], region[1]) || isRegionLoaded(region[0], region[1])) {
                continue;
            }
            out.add(region);
        }
        return out;
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
            bakeService.submit(worldDir, rx, rz, null, BakeService.Priority.WAITING);
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
        UUID id = event.getPlayer().getUniqueId();
        lastNotice.remove(id);
        pendingTeleports.remove(id);
    }
}
