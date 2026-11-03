package net.infnetwork.snowball.craftov.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * JSON-file profile storage. Profiles of online players stay cached; offline profiles are loaded
 * on demand (market payouts, escort settlements) and written straight back.
 */
public final class ProfileService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Plugin plugin;
    private final Path dir;
    private final Map<UUID, Profile> cache = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final Map<UUID, Summary> index = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Craftov-ProfileIO");
        t.setDaemon(true);
        return t;
    });

    public record Summary(UUID uuid, String name, int rankPoints, int level, long extracted, long kills) {
    }

    public ProfileService(Plugin plugin) {
        this.plugin = plugin;
        this.dir = plugin.getDataFolder().toPath().resolve("data/players");
    }

    public void start() throws IOException {
        Files.createDirectories(dir);
        File[] files = dir.toFile().listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                Profile profile = read(file.toPath());
                if (profile != null && profile.uuid != null) {
                    index.put(profile.uuid, summarize(profile));
                }
            }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushDirty, 200L, 200L);
    }

    public Profile get(Player player) {
        return get(player.getUniqueId(), player.getName());
    }

    public Profile get(UUID uuid, String name) {
        Profile profile = cache.get(uuid);
        if (profile == null) {
            profile = load(uuid);
            if (profile == null) {
                profile = new Profile(uuid, name);
            }
            cache.put(uuid, profile);
        }
        if (name != null && !name.isEmpty()) {
            profile.name = name;
        }
        return profile;
    }

    public @Nullable Profile cached(UUID uuid) {
        return cache.get(uuid);
    }

    /** Applies a mutation to a profile whether or not its owner is online. */
    public void edit(UUID uuid, Consumer<Profile> mutation) {
        Profile profile = cache.get(uuid);
        if (profile != null) {
            mutation.accept(profile);
            markDirty(uuid);
            return;
        }
        Profile offline = load(uuid);
        if (offline == null) {
            return;
        }
        mutation.accept(offline);
        writeAsync(offline);
    }

    public void markDirty(UUID uuid) {
        dirty.add(uuid);
    }

    public void unload(UUID uuid) {
        Profile profile = cache.remove(uuid);
        dirty.remove(uuid);
        if (profile != null) {
            writeAsync(profile);
        }
    }

    public void saveNow(UUID uuid) {
        Profile profile = cache.get(uuid);
        if (profile != null) {
            dirty.remove(uuid);
            writeAsync(profile);
        }
    }

    private void flushDirty() {
        for (UUID uuid : new ArrayList<>(dirty)) {
            Profile profile = cache.get(uuid);
            if (profile != null) {
                writeAsync(profile);
            }
        }
        dirty.clear();
    }

    public void shutdown() {
        for (Profile profile : cache.values()) {
            writeSync(GSON.toJson(profile), profile.uuid);
        }
        dirty.clear();
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public List<Summary> leaderboard(Comparator<Summary> order, int limit) {
        for (Profile profile : cache.values()) {
            index.put(profile.uuid, summarize(profile));
        }
        List<Summary> all = new ArrayList<>(index.values());
        all.sort(order);
        return all.subList(0, Math.min(limit, all.size()));
    }

    public Collection<Profile> online() {
        return cache.values();
    }

    public @Nullable UUID findByName(String name) {
        for (Summary summary : index.values()) {
            if (summary.name().equalsIgnoreCase(name)) {
                return summary.uuid();
            }
        }
        for (Profile profile : cache.values()) {
            if (profile.name.equalsIgnoreCase(name)) {
                return profile.uuid;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ io

    private @Nullable Profile load(UUID uuid) {
        Path file = dir.resolve(uuid + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        return read(file);
    }

    private @Nullable Profile read(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Profile profile = GSON.fromJson(reader, Profile.class);
            if (profile != null) {
                normalize(profile);
            }
            return profile;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().severe("Could not read profile " + file.getFileName() + ": " + ex.getMessage());
            return null;
        }
    }

    private static void normalize(Profile p) {
        if (p.stash == null) p.stash = new ArrayList<>();
        if (p.loadout == null) p.loadout = new HashMap<>();
        if (p.secure == null) p.secure = new ArrayList<>();
        if (p.mailbox == null) p.mailbox = new ArrayList<>();
        if (p.collected == null) p.collected = new java.util.LinkedHashSet<>();
        if (p.collectionRewards == null) p.collectionRewards = new java.util.LinkedHashSet<>();
        if (p.stats == null) p.stats = new HashMap<>();
        if (p.presets == null) p.presets = new ArrayList<>();
        if (p.reports == null) p.reports = new ArrayList<>();
        if (p.unlockedOperators == null || p.unlockedOperators.isEmpty()) {
            p.unlockedOperators = new java.util.LinkedHashSet<>(List.of("vanguard", "medic", "recon", "engineer"));
        }
        if (p.name == null) p.name = "";
        if (p.operator == null) p.operator = "vanguard";
    }

    private void writeAsync(Profile profile) {
        String json = GSON.toJson(profile);
        UUID uuid = profile.uuid;
        index.put(uuid, summarize(profile));
        io.execute(() -> writeSync(json, uuid));
    }

    private void writeSync(String json, UUID uuid) {
        try {
            Path target = dir.resolve(uuid + ".json");
            Path tmp = dir.resolve(uuid + ".json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save profile " + uuid + ": " + ex.getMessage());
        }
    }

    private static Summary summarize(Profile p) {
        return new Summary(p.uuid, p.name, p.rankPoints, p.level, p.stat("extracted_value"), p.stat("kills"));
    }
}
