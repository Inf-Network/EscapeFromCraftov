package net.infnetwork.snowball.craftov.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zips the player data (profiles and market) into plugins/Craftov/backups once a day and on shutdown,
 * keeping the newest few. Saves are written atomically elsewhere, so a copy taken mid-session is a
 * consistent snapshot of what was on disk.
 */
public final class Backups {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Plugin plugin;

    public Backups(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long hours = Math.max(1, plugin.getConfig().getLong("backup.interval-hours", 24));
        long period = hours * 60 * 60 * 20;
        // First copy ten minutes after start, then every interval, off the main thread.
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> run("auto"), 20L * 60 * 10, period);
    }

    /** Writes one backup now; safe to call from any thread. */
    public synchronized void run(String reason) {
        if (!plugin.getConfig().getBoolean("backup.enabled", true)) {
            return;
        }
        File root = plugin.getDataFolder();
        File folder = new File(root, "backups");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            return;
        }
        File zip = new File(folder, "craftov-" + LocalDateTime.now().format(STAMP) + "-" + reason + ".zip");
        try (OutputStream out = Files.newOutputStream(zip.toPath()); ZipOutputStream z = new ZipOutputStream(out)) {
            for (String name : new String[]{"data", "players", "market.json"}) {
                add(z, root.toPath(), root.toPath().resolve(name));
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Backup failed: " + ex.getMessage());
            return;
        }
        prune(folder, plugin.getConfig().getInt("backup.keep", 7));
    }

    private static void add(ZipOutputStream z, Path root, Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> files = Files.walk(path)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    put(z, root, file);
                }
            }
        } else {
            put(z, root, path);
        }
    }

    private static void put(ZipOutputStream z, Path root, Path file) throws IOException {
        z.putNextEntry(new ZipEntry(root.relativize(file).toString().replace(File.separatorChar, '/')));
        Files.copy(file, z);
        z.closeEntry();
    }

    private static void prune(File folder, int keep) {
        File[] zips = folder.listFiles((dir, name) -> name.startsWith("craftov-") && name.endsWith(".zip"));
        if (zips == null || zips.length <= keep) {
            return;
        }
        Arrays.sort(zips, Comparator.comparing(File::getName));
        for (int i = 0; i < zips.length - keep; i++) {
            if (!zips[i].delete()) {
                zips[i].deleteOnExit();
            }
        }
    }
}
