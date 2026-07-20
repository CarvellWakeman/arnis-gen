package com.arnis.paper;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Arrays;

/**
 * The {@code /arnis} admin command.
 *
 * <ul>
 *   <li>{@code /arnis status} — show generator config and bake-pool stats.
 *   <li>{@code /arnis prewarm [radius]} — bake the regions within {@code radius}
 *       (in regions) around the sender (or world spawn) now, via the bake pool.
 *   <li>{@code /arnis goto <lat> <lng>} — teleport to the in-game location of a
 *       real-world coordinate, baking that region first if needed.
 *   <li>{@code /arnis reload [radius]} — reload baked region chunks around you
 *       from disk (e.g. after a prewarm) without a restart.
 * </ul>
 */
public final class ArnisCommand implements CommandExecutor {

    private static final String USAGE =
            "Usage: /arnis <status|prewarm [radius]|goto <lat> <lng>|reload [radius]>";

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
            default:
                sender.sendMessage("Unknown subcommand. " + USAGE);
                return true;
        }
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
                ? "on (radius " + c.prefetchRadius + ", " + c.workers + " workers)"
                : "off"));
        if (svc != null) {
            sender.sendMessage("  regions baked: " + svc.bakedCount()
                    + " (in flight: " + svc.inFlightCount()
                    + ", ok: " + svc.completedCount()
                    + ", failed: " + svc.failedCount() + ")");
        }
        sender.sendMessage("  arnis binary: " + c.arnisBinary);
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

        if (svc.isKnown(rx, rz)) {
            sender.sendMessage(String.format("Going to %.5f,%.5f -> MC %d,%d (region %d,%d)...",
                    lat, lng, x, z, rx, rz));
        } else {
            sender.sendMessage(String.format(
                    "Baking region %d,%d for %.5f,%.5f -> MC %d,%d; will arrive when ready...",
                    rx, rz, lat, lng, x, z));
        }

        // submit() fires immediately if already baked, or when the (possibly
        // already in-flight) bake finishes.
        svc.submit(worldDir, rx, rz, ok -> {
            if (!ok) {
                sender.sendMessage("Bake failed for that location.");
                return;
            }
            plugin.reloadRegionChunks(rx, rz);
            arriveAt(sender, world, lat, lng, x, z);
        });
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
