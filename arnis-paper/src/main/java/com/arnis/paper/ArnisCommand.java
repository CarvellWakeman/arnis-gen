package com.arnis.paper;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /arnis} admin command.
 *
 * <ul>
 *   <li>{@code /arnis status} — show generator config and bake-pool stats.
 *   <li>{@code /arnis prewarm [radius]} — bake the regions within {@code radius}
 *       (in regions) around the sender (or world spawn) now, via the bake pool.
 *   <li>{@code /arnis goto <lat> <lng>} — teleport to the in-game location of a
 *       real-world coordinate, baking everything the arrival view reaches first.
 *   <li>{@code /arnis reload [radius]} — reload baked region chunks around you
 *       from disk (e.g. after a prewarm) without a restart.
 *   <li>{@code /arnis rebake [radius]} — force-regenerate the regions around you,
 *       even ones already on disk (repairs void or stale terrain).
 * </ul>
 */
public final class ArnisCommand implements TabExecutor {

    private static final String USAGE = "Usage: /arnis <status|prewarm [radius]"
            + "|goto <lat> <lng>|reload [radius]|rebake [radius]>";

    private static final List<String> SUBCOMMANDS =
            List.of("status", "prewarm", "goto", "reload", "rebake");

    /** Suggested radii — small values, since each region is a bake. */
    private static final List<String> RADII = List.of("1", "2", "3");

    private final ArnisPlugin plugin;

    public ArnisCommand(ArnisPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(USAGE);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "status":
                return status(sender);
            case "prewarm":
                return prewarm(sender, args);
            case "goto":
                return gotoLocation(sender, args);
            case "reload":
                return reload(sender, args);
            case "rebake":
                return rebake(sender, args);
            default:
                sender.sendMessage("Unknown subcommand. " + USAGE);
                return true;
        }
    }

    /**
     * Completions for {@code /arnis}: the subcommands, then per-subcommand arguments.
     *
     * <p>{@code goto} is offered the configured origin, so tabbing through it produces
     * a coordinate that is guaranteed to be in range — the common case for a first
     * visit, and a reminder of where the world is anchored.
     *
     * <p>Returns an empty list rather than {@code null} where nothing fits: {@code null}
     * makes Bukkit fall back to completing online player names, which is never useful
     * here.
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length <= 1) {
            return matching(args.length == 0 ? "" : args[0], SUBCOMMANDS);
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "prewarm":
            case "reload":
            case "rebake":
                return args.length == 2 ? matching(args[1], RADII) : List.of();
            case "goto":
                ArnisConfig c = plugin.config();
                if (args.length == 2) {
                    return matching(args[1], List.of(String.valueOf(c.originLat)));
                }
                if (args.length == 3) {
                    return matching(args[2], List.of(String.valueOf(c.originLng)));
                }
                return List.of();
            default:
                return List.of();
        }
    }

    /** The options starting with {@code prefix}, case-insensitively. */
    private static List<String> matching(String prefix, List<String> options) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(options.size());
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(option);
            }
        }
        return out;
    }

    private boolean status(CommandSender sender) {
        ArnisConfig c = plugin.config();
        World world = plugin.arnisWorld();
        BakeService svc = plugin.bakeService();

        sender.sendMessage("Arnis generator:");
        sender.sendMessage("  world: " + c.worldName + (world != null ? " (loaded)" : " (not loaded)"));
        sender.sendMessage("  origin: " + c.originLat + ", " + c.originLng + " -> MC (0,0)");
        sender.sendMessage("  scale: " + c.scale + " blocks/m, margin: " + c.bakeMargin);
        sender.sendMessage("  streaming: " + (c.streamingEnabled
                ? "on (radius " + c.prefetchRadius + ", " + c.workers + " workers, lookahead "
                        + (c.leadSeconds > 0 ? c.leadSeconds + "s" : "off") + ")"
                : "off"));
        sender.sendMessage("  barrier: " + (c.barrier && c.streamingEnabled ? "on" : "off")
                + ", safe-teleport: " + (c.safeTeleport && c.streamingEnabled ? "on" : "off"));
        if (svc != null) {
            sender.sendMessage("  regions baked: " + svc.bakedCount()
                    + " (in flight: " + svc.inFlightCount()
                    + ", ok: " + svc.completedCount()
                    + ", failed: " + svc.failedCount() + ")");
            sender.sendMessage("  queued: " + svc.queuedCount()
                    + " (dropped as stale: " + svc.droppedCount() + ")");
            sender.sendMessage("  awaiting repair: " + svc.dirtyCount()
                    + (c.repairUnbaked ? "" : " [repair disabled]"));
        }
        sender.sendMessage("  arnis binary: " + c.arnisBinaryNote
                + (c.arnisBinaryFound ? "" : " [NOT FOUND - baking disabled]"));
        return true;
    }

    private boolean prewarm(CommandSender sender, String[] args) {
        World world = plugin.arnisWorld();
        if (world == null) {
            sender.sendMessage("Arnis world is not loaded.");
            return true;
        }

        int radius = 1;
        if (args.length >= 2) {
            try {
                radius = Math.max(0, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                sender.sendMessage("Radius must be a whole number.");
                return true;
            }
        }

        // Center on the sender if they are standing in the arnis world, else spawn.
        int centerX;
        int centerZ;
        if (sender instanceof Player player && player.getWorld().equals(world)) {
            centerX = player.getLocation().getBlockX();
            centerZ = player.getLocation().getBlockZ();
        } else {
            centerX = world.getSpawnLocation().getBlockX();
            centerZ = world.getSpawnLocation().getBlockZ();
        }

        int centerRx = centerX >> 9;
        int centerRz = centerZ >> 9;
        File worldDir = world.getWorldFolder();
        BakeService svc = plugin.bakeService();

        int queued = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int rx = centerRx + dx;
                int rz = centerRz + dz;
                if (svc.isKnown(rx, rz)) {
                    continue;
                }
                final int frx = rx;
                final int frz = rz;
                // Prewarm may target regions the server holds resident, so reload them.
                svc.submit(worldDir, rx, rz, ok -> {
                    if (ok) {
                        plugin.reloadRegionChunks(frx, frz);
                    }
                });
                queued++;
            }
        }

        sender.sendMessage("Queued " + queued + " region(s) for baking around region "
                + centerRx + "," + centerRz + " (progress in the console).");
        return true;
    }

    private boolean gotoLocation(CommandSender sender, String[] args) {
        World world = plugin.arnisWorld();
        if (world == null) {
            sender.sendMessage("Arnis world is not loaded.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /arnis goto <lat> <lng>");
            return true;
        }

        // Accept "lat,lng" or "lat lng".
        String[] parts = String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                .trim()
                .split("[,\\s]+");
        double lat;
        double lng;
        try {
            lat = Double.parseDouble(parts[0]);
            lng = Double.parseDouble(parts[1]);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            sender.sendMessage("Could not parse coordinates. Usage: /arnis goto <lat> <lng>");
            return true;
        }

        ArnisConfig c = plugin.config();
        int[] xz = new Projection(c.originLat, c.originLng, c.scale).forward(lat, lng);
        int x = xz[0];
        int z = xz[1];
        int rx = x >> 9;
        int rz = z >> 9;

        BakeService svc = plugin.bakeService();
        File worldDir = world.getWorldFolder();

        // Bake everything the player's view will reach, not just the region they
        // land in. A teleport lands at an arbitrary point, so it is usually within
        // view distance of a region border; baking only the destination leaves the
        // neighbours to be generated as void the instant the player arrives, and
        // that void is then persisted and never re-baked.
        List<int[]> regions = plugin.regionsAroundView(x, z);
        int pending = 0;
        for (int[] r : regions) {
            if (!svc.isKnown(r[0], r[1])) {
                pending++;
            }
        }

        if (pending == 0) {
            sender.sendMessage(String.format("Going to %.5f,%.5f -> MC %d,%d (region %d,%d)...",
                    lat, lng, x, z, rx, rz));
        } else {
            sender.sendMessage(String.format(
                    "Baking %d region(s) around %.5f,%.5f -> MC %d,%d; will arrive when ready...",
                    pending, lat, lng, x, z));
        }

        // Arrive only once every region is done, so the server never generates void
        // where the player is about to look. submit() fires immediately for regions
        // already baked, or when the (possibly already in-flight) bake finishes;
        // every callback runs on the main thread, so these counters need no locking.
        int[] remaining = {regions.size()};
        boolean[] destOk = {true};
        for (int[] r : regions) {
            final int frx = r[0];
            final int frz = r[1];
            svc.submit(worldDir, frx, frz, ok -> {
                if (!ok && frx == rx && frz == rz) {
                    destOk[0] = false;
                }
                if (--remaining[0] > 0) {
                    return;
                }
                for (int[] done : regions) {
                    plugin.reloadRegionChunks(done[0], done[1]);
                }
                if (!destOk[0]) {
                    sender.sendMessage("Bake failed for that location.");
                    return;
                }
                arriveAt(sender, world, lat, lng, x, z);
            }, BakeService.Priority.WAITING);
        }
        return true;
    }

    private void arriveAt(CommandSender sender, World world, double lat, double lng, int x, int z) {
        if (sender instanceof Player player) {
            world.loadChunk(x >> 4, z >> 4);
            int y = world.getHighestBlockYAt(x, z) + 1;
            player.teleport(new Location(world, x + 0.5, y, z + 0.5));
            player.sendMessage(String.format("Arrived at %.5f,%.5f -> MC %d,%d,%d", lat, lng, x, y, z));
        } else {
            // Console has no location to teleport; report the mapping instead.
            sender.sendMessage(String.format("%.5f,%.5f -> MC x=%d z=%d (region %d,%d)",
                    lat, lng, x, z, x >> 9, z >> 9));
        }
    }

    /**
     * Force-regenerate the regions around the sender, ignoring the "already baked"
     * set. This is the repair path for regions whose file exists but whose contents
     * are wrong: void the server generated before arnis got there, or terrain baked
     * by an older arnis (a changed origin, scale, or vertical datum).
     */
    private boolean rebake(CommandSender sender, String[] args) {
        World world = plugin.arnisWorld();
        if (world == null) {
            sender.sendMessage("Arnis world is not loaded.");
            return true;
        }

        int radius = 1;
        if (args.length >= 2) {
            try {
                radius = Math.max(0, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                sender.sendMessage("Radius must be a whole number.");
                return true;
            }
        }

        int centerX;
        int centerZ;
        if (sender instanceof Player player && player.getWorld().equals(world)) {
            centerX = player.getLocation().getBlockX();
            centerZ = player.getLocation().getBlockZ();
        } else {
            centerX = world.getSpawnLocation().getBlockX();
            centerZ = world.getSpawnLocation().getBlockZ();
        }
        int centerRx = centerX >> 9;
        int centerRz = centerZ >> 9;
        File worldDir = world.getWorldFolder();
        BakeService svc = plugin.bakeService();

        int queued = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                final int rx = centerRx + dx;
                final int rz = centerRz + dz;
                // Drop the server's resident copy first, unsaved, so it cannot write
                // its stale chunks back over the file arnis is about to replace.
                plugin.reloadRegionChunks(rx, rz);
                svc.rebake(worldDir, rx, rz, ok -> {
                    if (ok) {
                        plugin.reloadRegionChunks(rx, rz);
                    }
                });
                queued++;
            }
        }

        sender.sendMessage("Re-baking " + queued + " region(s) around region "
                + centerRx + "," + centerRz + " (progress in the console).");
        sender.sendMessage("If terrain still looks stale afterwards, restart the server: "
                + "the server can hold a cached region-file handle that misses arnis's write.");
        return true;
    }

    private boolean reload(CommandSender sender, String[] args) {
        World world = plugin.arnisWorld();
        if (world == null) {
            sender.sendMessage("Arnis world is not loaded.");
            return true;
        }
        int radius = 1;
        if (args.length >= 2) {
            try {
                radius = Math.max(0, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                sender.sendMessage("Radius must be a whole number.");
                return true;
            }
        }

        int centerX;
        int centerZ;
        if (sender instanceof Player player && player.getWorld().equals(world)) {
            centerX = player.getLocation().getBlockX();
            centerZ = player.getLocation().getBlockZ();
        } else {
            centerX = world.getSpawnLocation().getBlockX();
            centerZ = world.getSpawnLocation().getBlockZ();
        }
        int centerRx = centerX >> 9;
        int centerRz = centerZ >> 9;

        int reloaded = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                plugin.reloadRegionChunks(centerRx + dx, centerRz + dz);
                reloaded++;
            }
        }
        sender.sendMessage("Reloaded " + reloaded + " region(s) around region "
                + centerRx + "," + centerRz + " from disk.");
        return true;
    }
}
