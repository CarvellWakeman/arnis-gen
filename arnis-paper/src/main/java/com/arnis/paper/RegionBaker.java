package com.arnis.paper;

import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the arnis Rust engine as a subprocess to bake a single Minecraft region
 * into a world's {@code region/} directory.
 *
 * <p>Baking fetches map data over the network and does heavy CPU work, so
 * {@link #bake} must be called off the server main thread.
 */
public final class RegionBaker {

    private static final String RESULT_PREFIX = "ARNIS_BAKE_RESULT ";

    private final Plugin plugin;
    private final ArnisConfig config;

    public RegionBaker(Plugin plugin, ArnisConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /** Outcome of a bake: whether it succeeded and the raw result/error detail. */
    public static final class Result {
        public final boolean ok;
        public final String detail;

        Result(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    /**
     * Bakes region {@code (rx, rz)} into {@code worldDir}. Blocking — invoke from
     * an async task, never the main thread.
     */
    public Result bake(File worldDir, int rx, int rz) {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.arnisBinary);
        cmd.add("--bake-region");
        cmd.add(rx + "," + rz);
        cmd.add("--origin");
        cmd.add(config.originLat + "," + config.originLng);
        cmd.add("--output-dir");
        cmd.add(worldDir.getAbsolutePath());
        cmd.add("--scale");
        cmd.add(Double.toString(config.scale));
        cmd.add("--bake-margin");
        cmd.add(Integer.toString(config.bakeMargin));
        cmd.add("--ground-level");
        cmd.add(Integer.toString(config.groundLevel));
        // Shared vertical mapping so regions line up vertically.
        cmd.add("--vertical-scale");
        cmd.add(Double.toString(config.verticalScale));
        cmd.add("--elevation-base");
        cmd.add(Double.toString(config.elevationBase));

        plugin.getLogger().info("Baking region " + rx + "," + rz + "...");
        long start = System.currentTimeMillis();

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String resultLine = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith(RESULT_PREFIX)) {
                        resultLine = line.substring(RESULT_PREFIX.length());
                    }
                }
            }

            int code = process.waitFor();
            long ms = System.currentTimeMillis() - start;

            if (code == 0 && resultLine != null && resultLine.contains("\"status\":\"ok\"")) {
                plugin.getLogger().info("Baked region " + rx + "," + rz + " in " + ms + " ms");
                return new Result(true, resultLine);
            }

            String detail = "arnis bake failed for region " + rx + "," + rz
                    + " (exit " + code + ")"
                    + (resultLine != null ? ": " + resultLine : " (no result line)");
            plugin.getLogger().warning(detail);
            return new Result(false, detail);
        } catch (IOException e) {
            // Almost always: the arnis executable could not be found or run. Keep it
            // to a single actionable line rather than a stack trace — this is the
            // expected first-run state until 'arnis-binary' is configured.
            String detail = "Could not run the arnis executable '" + config.arnisBinary
                    + "': " + e.getMessage()
                    + ". Set 'arnis-binary' in the plugin config to the absolute path of"
                    + " the arnis executable and restart (see SERVER_SETUP.md).";
            plugin.getLogger().warning(detail);
            return new Result(false, detail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("arnis bake for region " + rx + "," + rz + " was interrupted.");
            return new Result(false, "interrupted");
        }
    }
}
