package net.infnetwork.snowball.craftov.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zips the player data (profiles and market) into plugins/Craftov/backups: one copy a day and one
 * on every shutdown. The two kinds are kept separately (backup.keep-daily, backup.keep-shutdown),
 * and the daily copy is only made once the newest one is a full interval old, so a day of
 * redeploys neither pushes the older daily copies out nor adds new ones. Saves are written
 * atomically elsewhere, so a copy taken mid-session is a consistent snapshot of what was on disk.
 */
public final class Backups {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** File-name tag of the daily copies (kept from older versions so their copies count too). */
    private static final String DAILY = "auto";

    private final Plugin plugin;

    public Backups(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        AtomicFiles.cleanTemp(folder().toPath());
        long delay = Math.max(1, plugin.getConfig().getLong("backup.first-delay-minutes", 10) * 60 * 20);
        // Checked every ten minutes off the main thread; daily() decides whether a copy is due.
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::daily, delay, 20L * 60 * 10);
    }

    private File folder() {
        return new File(plugin.getDataFolder(), "backups");
    }

    /** Makes the daily copy if the newest one is at least backup.interval-hours old (or missing). */
    private void daily() {
        long interval = Math.max(1, plugin.getConfig().getLong("backup.interval-hours", 24)) * 3_600_000L;
        File newest = newest(folder(), DAILY);
        if (newest == null || System.currentTimeMillis() - takenAt(newest) >= interval) {
            run(DAILY);
        }
    }

    /** When a backup was taken, from the stamp in its name (copying the file changes its mtime). */
    private static long takenAt(File zip) {
        try {
            return LocalDateTime.parse(zip.getName().substring("craftov-".length(), "craftov-yyyyMMdd-HHmmss".length()), STAMP)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException ex) {
            return zip.lastModified();
        }
    }

    /** Writes one backup now; safe to call from any thread. */
    public synchronized void run(String reason) {
        if (!plugin.getConfig().getBoolean("backup.enabled", true)) {
            return;
        }
        File root = plugin.getDataFolder();
        File folder = folder();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            return;
        }
        Path zip = folder.toPath().resolve("craftov-" + LocalDateTime.now().format(STAMP) + "-" + reason + ".zip");
        // Written under a temp name and renamed when complete, so a failed copy never sits among
        // the good ones (or counts towards how many are kept).
        Path tmp = zip.resolveSibling(zip.getFileName() + AtomicFiles.TEMP_SUFFIX);
        try {
            try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream z = new ZipOutputStream(out)) {
                for (String name : new String[]{"data", "players", "market.json"}) {
                    add(z, root.toPath(), root.toPath().resolve(name));
                }
            }
            Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | UncheckedIOException ex) {
            plugin.getLogger().warning("Backup failed: " + ex.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // removed on the next start
            }
            return;
        }
        prune(folder, reason, DAILY.equals(reason)
                ? plugin.getConfig().getInt("backup.keep-daily", 7)
                : plugin.getConfig().getInt("backup.keep-shutdown", 5));
    }

    private static void add(ZipOutputStream z, Path root, Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> files = Files.walk(path)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    // A save's temp file is renamed away any moment, and is never the data anyway.
                    if (!AtomicFiles.isTemp(file.getFileName().toString())) {
                        put(z, root, file);
                    }
                }
            }
        } else {
            put(z, root, path);
        }
    }

    private static void put(ZipOutputStream z, Path root, Path file) throws IOException {
        byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (NoSuchFileException ex) {
            return; // deleted since it was listed
        }
        z.putNextEntry(new ZipEntry(root.relativize(file).toString().replace(File.separatorChar, '/')));
        z.write(content);
        z.closeEntry();
    }

    private static File[] backups(File folder, String reason) {
        File[] zips = folder.listFiles((dir, name) -> name.startsWith("craftov-") && name.endsWith("-" + reason + ".zip"));
        return zips == null ? new File[0] : zips;
    }

    private static @Nullable File newest(File folder, String reason) {
        return Arrays.stream(backups(folder, reason)).max(Comparator.comparing(File::getName)).orElse(null);
    }

    private static void prune(File folder, String reason, int keep) {
        File[] zips = backups(folder, reason);
        if (zips.length <= keep) {
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
