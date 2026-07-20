package com.arnis.paper;

import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code /arnis} admin command.
 *
 * <ul>
 *   <li>{@code /arnis status} — show generator config and how many regions are baked.
 *   <li>{@code /arnis prewarm [radius]} — bake the regions within {@code radius}
 *       (in regions) around the sender (or world spawn) ahead of exploration.
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

        int regionFiles = 0;
        if (world != null) {
            File regionDir = new File(world.getWorldFolder(), "region");
            File[] files = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
            if (files != null) {
                regionFiles = files.length;
            }
        }

        sender.sendMessage("Arnis generator:");
        sender.sendMessage("  world: " + c.worldName + (world != null ? " (loaded)" : " (not loaded)"));
        sender.sendMessage("  origin: " + c.originLat + ", " + c.originLng + " -> MC (0,0)");
        sender.sendMessage("  scale: " + c.scale + " blocks/m, margin: " + c.bakeMargin);
        sender.sendMessage("  baked region files: " + regionFiles);
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

        List<int[]> regions = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                regions.add(new int[] {centerRx + dx, centerRz + dz});
            }
        }

        sender.sendMessage("Prewarming " + regions.size() + " region(s) around region "
                + centerRx + "," + centerRz + " (this runs in the background)...");
        plugin.prewarmAsync(world, regions, sender);
        return true;
    }
}
