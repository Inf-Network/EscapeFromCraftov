package net.infnetwork.snowball.craftov.pack;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Installs the bundled CraftEngine resource folder and BetterHud HUD layout into those plugins'
 * folders, then asks CraftEngine to rebuild and resend its pack when anything changed.
 */
public final class PackService {

    private static final String CE_PREFIX = "pack/craftov/";
    private static final String BH_PREFIX = "betterhud/";

    private final Plugin plugin;

    public PackService(Plugin plugin) {
        this.plugin = plugin;
    }

    public void install() {
        boolean ce = Bukkit.getPluginManager().getPlugin("CraftEngine") != null;
        boolean bh = Bukkit.getPluginManager().getPlugin("BetterHud") != null;
        boolean changed = false;
        try {
            if (ce) {
                changed |= extract(CE_PREFIX, new File(plugin.getDataFolder().getParentFile(), "CraftEngine/resources/craftov"));
            } else {
                plugin.getLogger().warning("CraftEngine is not installed: Craftov models, textures and sounds will be missing. "
                        + "Install CraftEngine so players download the resource pack automatically.");
            }
            if (bh) {
                File bhFolder = new File(plugin.getDataFolder().getParentFile(), "BetterHud");
                changed |= extract(BH_PREFIX, bhFolder);
                changed |= configureBetterHud(new File(bhFolder, "config.yml"));
                switchOffSamples(bhFolder);
            }
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not install resource pack files: " + ex.getMessage());
        }
        if (changed && ce && plugin.getConfig().getBoolean("pack.auto-reload", true)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                plugin.getLogger().info("Craftov resources updated, rebuilding the CraftEngine resource pack...");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ce reload all");
            }, 40L);
        }
    }

    private boolean extract(String prefix, File target) throws IOException {
        File jarFile;
        try {
            jarFile = new File(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException ex) {
            throw new IOException(ex);
        }
        List<JarEntry> entries = new ArrayList<>();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException(ex);
        }
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> all = jar.entries();
            while (all.hasMoreElements()) {
                JarEntry entry = all.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith(prefix)) {
                    entries.add(entry);
                }
            }
            entries.sort((a, b) -> a.getName().compareTo(b.getName()));
            for (JarEntry entry : entries) {
                digest.update(entry.getName().getBytes(StandardCharsets.UTF_8));
                try (InputStream in = jar.getInputStream(entry)) {
                    digest.update(in.readAllBytes());
                }
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            File marker = new File(target, ".craftov-hash");
            if (marker.exists() && Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim().equals(hash)) {
                return false;
            }
            for (JarEntry entry : entries) {
                Path out = target.toPath().resolve(entry.getName().substring(prefix.length()));
                Files.createDirectories(out.getParent());
                try (InputStream in = jar.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.createDirectories(target.toPath());
            Files.writeString(marker.toPath(), hash, StandardCharsets.UTF_8);
            plugin.getLogger().info("Installed " + entries.size() + " files into " + target);
            return true;
        }
    }

    /** Makes BetterHud show the Craftov HUD by default and merge into CraftEngine's pack. */
    private boolean configureBetterHud(File config) throws IOException {
        if (!config.exists()) {
            return false;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(config);
        boolean changed = false;
        if (!List.of("craftov_hud", "craftov_crosshair").equals(yaml.getStringList("default-hud"))) {
            yaml.set("default-hud", List.of("craftov_hud", "craftov_crosshair"));
            changed = true;
        }
        if (!List.of("craftov_compass").equals(yaml.getStringList("default-compass"))) {
            yaml.set("default-compass", List.of("craftov_compass"));
            changed = true;
        }
        // Every HUD refresh is a multi-kilobyte boss-bar packet; 10 per second is plenty for ammo and timers.
        if (yaml.getInt("tick-speed", 1) < 2) {
            yaml.set("tick-speed", 2);
            changed = true;
        }
        if (!yaml.getBoolean("merge-with-external-resources", false)) {
            yaml.set("merge-with-external-resources", true);
            changed = true;
        }
        if (changed) {
            yaml.save(config);
        }
        return changed;
    }

    /**
     * BetterHud writes a few samples into its data folder when the folder is new, never again after
     * that, and reads them only after Craftov is enabled, so what is switched off here is off from
     * this start-up on. Two of them show up in a raid: a compass shown to everyone by default, where
     * Craftov has its own, and an English "Health: x / y" bar with the target's head that pops up at
     * the top left whenever anyone hits a mob (#134). Nothing to rebuild in the resource pack.
     */
    private void switchOffSamples(File folder) throws IOException {
        File sample = new File(folder, "compasses/default_compass.yml");
        if (sample.exists()) {
            YamlConfiguration compass = YamlConfiguration.loadConfiguration(sample);
            if (compass.getBoolean("default_compass.default", false)) {
                compass.set("default_compass.default", false);
                compass.save(sample);
            }
        }
        // BetterHud skips files whose names start with a hyphen. Renamed rather than deleted, so the
        // sample is still at hand: an admin who wants it back gives it some other name (as
        // entity-popup.yml it would be switched off again at the next start).
        File popup = new File(folder, "popups/entity-popup.yml");
        if (popup.exists()) {
            Path off = popup.toPath().resolveSibling("-entity-popup.yml");
            Files.move(popup.toPath(), off, StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Switched off BetterHud's sample entity health popup: renamed it to popups/" + off.getFileName());
        }
    }
}
