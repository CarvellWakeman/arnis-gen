package com.arnis.paper;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bakes regions ahead of players so terrain streams in as they explore, and
 * re-bakes regions the server generated itself when a player outran streaming.
 *
 * <p>Only regions the server does <em>not</em> have loaded are baked. Prefetch
 * regions sit 512+ blocks out — well beyond the view distance — so arnis writes
 * their region files before the server ever opens them, and the first chunk-load
 * reads the baked data cleanly (no region-file-cache eviction, no risk of
 * overwriting a live handle). The region a player is standing in is deliberately
 * never baked live; spawn and {@code /arnis goto} pre-bake their target first.
 *
 * <p>The repair sweep is the safety net for when prefetch loses that race: a player
 * flying faster than regions can bake reaches unbaked space, the server generates
 * and persists it, and — without a record of who wrote what — that region would
 * look baked forever after. Repair waits until the player has moved on and nothing
 * holds the region loaded, then bakes over it.
 */
public final class PlayerTracker extends BukkitRunnable {

    /** How far past the prefetch radius a damaged region is still swept up. */
    private static final int REPAIR_MARGIN = 2;

    /** Cap on predicted lookahead, so a fast player cannot flood the pool. */
    private static final int MAX_LOOKAHEAD = 8;

    /**
     * How far past the prefetch radius queued work survives. Generous relative to
     * {@link #MAX_LOOKAHEAD}, so a player who keeps going never drops the regions
     * being baked for them — only one who turns around or logs off does.
     */
    private static final int STALE_MARGIN = MAX_LOOKAHEAD + 2;

    /** Ignore movement below this many blocks per scan as "not travelling". */
    private static final double MIN_TRAVEL = 4.0;

    /** A jump further than this in one scan is a teleport, not travel. */
    private static final double TELEPORT_JUMP = 512.0;

    private final ArnisPlugin plugin;
    private final World world;
    private final File worldDir;
    private final BakeService bakeService;
    private final int radius;
    private final int maxPerScan;
    private final int maxRepairsPerScan;
    private final double leadSeconds;
    private final double scanSeconds;
    private final Map<UUID, int[]> lastSeen = new HashMap<>();

    public PlayerTracker(ArnisPlugin plugin, World world, BakeService bakeService,
                         int radius, int maxPerScan, int maxRepairsPerScan,
                         double leadSeconds, int intervalTicks) {
        this.plugin = plugin;
        this.world = world;
        this.worldDir = world.getWorldFolder();
        this.bakeService = bakeService;
        this.radius = radius;
        this.maxPerScan = maxPerScan;
        this.maxRepairsPerScan = maxRepairsPerScan;
        this.leadSeconds = leadSeconds;
        this.scanSeconds = Math.max(1, intervalTicks) / 20.0;
    }

    @Override
    public void run() {
        List<Player> players = world.getPlayers();
        if (players.isEmpty()) {
            return;
        }
        Set<Long> loaded = loadedRegions();

        // Publish where everyone is, so the pool can rank new work by distance and
        // drop work nobody is heading for.
        List<long[]> playerRegions = new ArrayList<>(players.size());
        for (Player player : players) {
            playerRegions.add(new long[] {
                player.getLocation().getBlockX() >> 9,
                player.getLocation().getBlockZ() >> 9
            });
        }
        bakeService.setPlayerRegions(playerRegions);

        int submitted = 0;
        for (Player player : players) {
            int prx = player.getLocation().getBlockX() >> 9;
            int prz = player.getLocation().getBlockZ() >> 9;

            // Ahead of the player first. A symmetric radius spends most of its budget
            // on regions behind and beside them, which is exactly the wrong shape for
            // someone travelling in a straight line faster than regions can bake.
            for (long k : predictedRegions(player)) {
                if (submitted >= maxPerScan) {
                    break;
                }
                int rx = BakeService.rx(k);
                int rz = BakeService.rz(k);
                if (bakeService.shouldSkip(rx, rz) || loaded.contains(k)) {
                    continue;
                }
                bakeService.submit(worldDir, rx, rz, null);
                submitted++;
            }

            // Expand ring by ring so the closest ungenerated regions bake first.
            for (int r = 1; r <= radius && submitted < maxPerScan; r++) {
                for (int dz = -r; dz <= r && submitted < maxPerScan; dz++) {
                    for (int dx = -r; dx <= r && submitted < maxPerScan; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue; // only the outermost ring at this radius
                        }
                        int rx = prx + dx;
                        int rz = prz + dz;
                        if (bakeService.shouldSkip(rx, rz)
                                || loaded.contains(BakeService.key(rx, rz))) {
                            continue;
                        }
                        bakeService.submit(worldDir, rx, rz, null);
                        submitted++;
                    }
                }
            }
        }

        repair(players, loaded);

        // Anything still queued well outside everyone's reach is work for terrain
        // no one is going to see; drop it so the pool spends its time nearer home.
        int discarded = bakeService.pruneQueue(radius + STALE_MARGIN);
        if (discarded > 0) {
            plugin.getLogger().fine("Dropped " + discarded + " queued bake(s) nobody is heading for.");
        }
    }

    /**
     * The regions this player is heading for, nearest first.
     *
     * <p>Speed comes from where they were at the last scan rather than
     * {@code getVelocity()}, which is client-controlled and unreliable for players.
     * The lookahead is however many regions they will cross in {@code leadSeconds} —
     * chosen to cover a cold bake — so a walking player gets one region of lead and
     * someone sprint-flying gets several, without either wasting the scan budget.
     *
     * <p>Each step also includes the two laterally adjacent regions, so a gentle turn
     * does not immediately arrive somewhere unbaked.
     */
    private List<Long> predictedRegions(Player player) {
        int x = player.getLocation().getBlockX();
        int z = player.getLocation().getBlockZ();
        int[] previous = lastSeen.put(player.getUniqueId(), new int[] {x, z});
        if (leadSeconds <= 0 || previous == null) {
            return List.of();
        }

        double dx = x - previous[0];
        double dz = z - previous[1];
        double travelled = Math.sqrt(dx * dx + dz * dz);
        if (travelled < MIN_TRAVEL || travelled > TELEPORT_JUMP) {
            return List.of(); // standing still, or teleported: the ring handles it
        }

        double blocksPerSecond = travelled / scanSeconds;
        int lookahead = (int) Math.min(MAX_LOOKAHEAD,
                Math.max(1, Math.ceil(blocksPerSecond * leadSeconds / 512.0)));

        // Unit heading, and the perpendicular used for the lateral pair.
        double ux = dx / travelled;
        double uz = dz / travelled;

        List<Long> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        seen.add(BakeService.key(x >> 9, z >> 9)); // never the region they are in
        for (int step = 1; step <= lookahead; step++) {
            double ahead = step * 512.0;
            addRegion(out, seen, x + ux * ahead, z + uz * ahead);
            addRegion(out, seen, x + ux * ahead - uz * 512.0, z + uz * ahead + ux * 512.0);
            addRegion(out, seen, x + ux * ahead + uz * 512.0, z + uz * ahead - ux * 512.0);
        }
        return out;
    }

    private void addRegion(List<Long> out, Set<Long> seen, double x, double z) {
        long k = BakeService.key(((int) Math.floor(x)) >> 9, ((int) Math.floor(z)) >> 9);
        if (seen.add(k)) {
            out.add(k);
        }
    }

    /**
     * Re-bake regions the server generated, nearest player first, once nothing has
     * them loaded. Capped per scan so repairs never starve the prefetch that stops
     * players from creating more of them.
     */
    private void repair(List<Player> players, Set<Long> loaded) {
        if (maxRepairsPerScan <= 0) {
            return;
        }

        // Rank first, then test: distance is arithmetic, while the skip and loaded
        // checks are only worth doing for the few regions that could be queued.
        List<long[]> candidates = new ArrayList<>();
        for (long k : bakeService.dirtyRegions()) {
            int distance = distanceToNearestPlayer(players, BakeService.rx(k), BakeService.rz(k));
            if (distance <= radius + REPAIR_MARGIN) {
                candidates.add(new long[] {distance, k});
            }
        }
        candidates.sort(Comparator.comparingLong(c -> c[0]));

        int queued = 0;
        for (long[] candidate : candidates) {
            if (queued >= maxRepairsPerScan) {
                break;
            }
            long k = candidate[1];
            final int rx = BakeService.rx(k);
            final int rz = BakeService.rz(k);
            if (loaded.contains(k) || bakeService.shouldSkip(rx, rz)) {
                continue;
            }
            plugin.getLogger().info("Re-baking region " + rx + "," + rz
                    + " - the server generated it before arnis got there.");
            bakeService.rebake(worldDir, rx, rz, ok -> {
                if (ok) {
                    plugin.reloadRegionChunks(rx, rz);
                }
            }, BakeService.Priority.REPAIR);
            queued++;
        }
    }

    /** Chebyshev distance in regions from the closest player. */
    private int distanceToNearestPlayer(List<Player> players, int rx, int rz) {
        int best = Integer.MAX_VALUE;
        for (Player player : players) {
            int prx = player.getLocation().getBlockX() >> 9;
            int prz = player.getLocation().getBlockZ() >> 9;
            best = Math.min(best, Math.max(Math.abs(prx - rx), Math.abs(prz - rz)));
        }
        return best;
    }

    /**
     * The regions holding at least one loaded chunk — an exact answer taken once per
     * scan, rather than sampling chunks per candidate region. Baking over a region
     * the server has open is what corrupts region files, so this must not guess.
     */
    private Set<Long> loadedRegions() {
        Set<Long> out = new HashSet<>();
        for (Chunk chunk : world.getLoadedChunks()) {
            out.add(BakeService.key(chunk.getX() >> 5, chunk.getZ() >> 5));
        }
        return out;
    }
}
