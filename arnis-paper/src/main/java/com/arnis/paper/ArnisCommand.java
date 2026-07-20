package com.arnis.paper;

import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;

/**
 * The {@code /arnis} admin command.
 *
 * <ul>
 *   <li>{@code /arnis status} — show generator config and bake-pool stats.
 *   <li>{@code /arnis prewarm [radius]} — bake the regions within {@code radius}
 *       (in regions) around the sender (or world spawn) now, via the bake pool.
 * </ul>
 */
public final class ArnisCommand implements CommandExecutor {

    private final ArnisPlugin plugin;

    public ArnisCommand(ArnisPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /arnis <status|prewarm [radius]>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "status":
                return status(sender);
            case "prewarm":
                return prewarm(sender, args);
            default:
                sender.sendMessage("Unknown subcommand. Usage: /arnis <status|prewarm [radius]>");
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
}
