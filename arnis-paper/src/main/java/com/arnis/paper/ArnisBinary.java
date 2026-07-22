package com.arnis.paper;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Locates the arnis executable for the {@code arnis-binary} config setting.
 *
 * <p>An absolute path is used as-is. Anything else — a relative path like
 * {@code target/release/arnis}, or a bare {@code arnis} — is looked for relative to
 * the plugin data folder, the server directory, and their parent directories, then
 * on {@code PATH}. That makes one config file work for a server checked out inside
 * the arnis repository regardless of where the repository lives, and on either OS:
 * the {@code .exe} suffix is added on Windows and dropped elsewhere, so a config
 * written on one platform still resolves on the other.
 *
 * <p>If the configured path itself is not found, its file name is searched for in
 * the same places — a stale absolute path from another machine still resolves.
 */
public final class ArnisBinary {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");

    /** Subdirectories of each search root where a built arnis binary usually sits. */
    private static final String[] BUILD_DIRS = {"", "target/release", "target/debug", "bin"};

    /** How far up from the data folder / server directory to look for the repository root. */
    private static final int MAX_PARENTS = 5;

    private ArnisBinary() {}

    /** Where a resolved binary came from, and whether it can actually be run. */
    public static final class Resolved {
        /** The command handed to {@link ProcessBuilder} — an absolute path once located. */
        public final String command;
        /** Whether an executable was actually located (on disk or on {@code PATH}). */
        public final boolean found;
        /** Human-readable account of the resolution, for the startup log. */
        public final String note;

        Resolved(String command, boolean found, String note) {
            this.command = command;
            this.found = found;
            this.note = note;
        }
    }

    /**
     * Resolves {@code configured} to a runnable command.
     *
     * @param configured the raw {@code arnis-binary} config value
     * @param dataFolder the plugin's data folder ({@code plugins/ArnisGen})
     */
    public static Resolved resolve(String configured, File dataFolder) {
        String value = (configured == null || configured.trim().isEmpty()) ? "arnis" : configured.trim();
        List<File> roots = searchRoots(dataFolder);

        // 1. The configured path itself: absolute as given, relative against each root.
        File direct = new File(value);
        if (direct.isAbsolute()) {
            File hit = firstExisting(files(variants(value)));
            if (hit != null) {
                return new Resolved(hit.getPath(), true, describe(value, hit));
            }
        } else {
            for (File root : roots) {
                File hit = firstExisting(resolveAll(root, variants(value)));
                if (hit != null) {
                    return new Resolved(hit.getPath(), true, describe(value, hit));
                }
            }
        }

        // 2. The file name alone, in the usual build locations under each root. Catches a
        // path pointing at a checkout that has moved (or at the other OS's build dir).
        String name = direct.getName();
        for (File root : roots) {
            for (String sub : BUILD_DIRS) {
                File dir = sub.isEmpty() ? root : new File(root, sub);
                File hit = firstExisting(resolveAll(dir, variants(name)));
                if (hit != null) {
                    return new Resolved(hit.getPath(), true, describe(value, hit));
                }
            }
        }

        // 3. PATH.
        File onPath = firstExisting(pathCandidates(name));
        if (onPath != null) {
            return new Resolved(onPath.getPath(), true, "'" + value + "' -> " + onPath.getPath() + " (on PATH)");
        }

        return new Resolved(value, false, "'" + value + "' was not found (searched the configured path, "
                + roots.size() + " directories around the server, and PATH)");
    }

    private static String describe(String configured, File hit) {
        String resolved = hit.getPath();
        if (resolved.equals(configured)) {
            return "'" + configured + "'";
        }
        return "'" + configured + "' -> " + resolved;
    }

    /**
     * Search roots, nearest first: the plugin data folder, then its ancestors (which
     * covers {@code plugins/} and the server directory), then the working directory and
     * its ancestors. Ancestors are included so a server run from inside a checkout finds
     * {@code target/release/arnis} at the repository root.
     */
    private static List<File> searchRoots(File dataFolder) {
        Set<String> seen = new LinkedHashSet<>();
        List<File> roots = new ArrayList<>();
        if (dataFolder != null) {
            addChain(roots, seen, dataFolder);
        }
        addChain(roots, seen, new File("").getAbsoluteFile());
        return roots;
    }

    private static void addChain(List<File> roots, Set<String> seen, File start) {
        File dir = canonical(start);
        for (int i = 0; dir != null && i <= MAX_PARENTS; i++) {
            if (seen.add(dir.getPath())) {
                roots.add(dir);
            }
            dir = dir.getParentFile();
        }
    }

    /**
     * Platform spellings of a binary name: {@code .exe} (and the other executable
     * suffixes) added on Windows, stripped elsewhere.
     */
    private static List<String> variants(String value) {
        List<String> out = new ArrayList<>(4);
        out.add(value);
        String lower = value.toLowerCase(Locale.ROOT);
        if (WINDOWS) {
            if (!lower.endsWith(".exe") && !lower.endsWith(".cmd") && !lower.endsWith(".bat")) {
                out.add(value + ".exe");
                out.add(value + ".cmd");
                out.add(value + ".bat");
            }
        } else if (lower.endsWith(".exe")) {
            out.add(value.substring(0, value.length() - 4));
        }
        return out;
    }

    private static List<File> resolveAll(File dir, List<String> names) {
        List<File> out = new ArrayList<>(names.size());
        for (String n : names) {
            out.add(new File(dir, n));
        }
        return out;
    }

    private static List<File> files(List<String> names) {
        List<File> out = new ArrayList<>(names.size());
        for (String n : names) {
            out.add(new File(n));
        }
        return out;
    }

    private static File firstExisting(List<File> candidates) {
        for (File f : candidates) {
            if (f.isFile()) {
                return canonical(f);
            }
        }
        return null;
    }

    private static List<File> pathCandidates(String name) {
        List<File> out = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) {
            return out;
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            out.addAll(resolveAll(new File(entry), variants(name)));
        }
        return out;
    }

    private static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (Exception e) {
            return f.getAbsoluteFile();
        }
    }

    /**
     * Whether a located binary lacks the executable bit — the usual state of a Linux
     * build that was copied or unpacked without preserving permissions.
     */
    public static boolean needsExecutableBit(String command) {
        if (WINDOWS) {
            return false;
        }
        File f = new File(command);
        return f.isFile() && !f.canExecute();
    }
}
