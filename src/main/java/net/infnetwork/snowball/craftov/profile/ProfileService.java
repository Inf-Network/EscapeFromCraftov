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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * JSON-file profile storage. Profiles of online players stay cached; offline profiles are loaded
 * on demand (market payouts, escort settlements) and written straight back, and stay in memory
 * until that write has landed so the next change builds on this one.
 */
public final class ProfileService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Plugin plugin;
    private final Path dir;
    private final Map<UUID, Profile> cache = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final Map<UUID, Summary> index = new HashMap<>();
    /**
     * Offline profiles changed through {@link #edit} or just {@link #unload unloaded}, kept until
     * every write queued for them has reached the disk. Without this, back-to-back edits (several
     * market listings of one offline seller returned or sold in the same tick, or a sale right as
     * the seller logs off) each re-read a file the previous write had not replaced yet, and all but
     * one of the changes were lost. Main thread only.
     */
    private final Map<UUID, Pending> offline = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Craftov-ProfileIO");
        t.setDaemon(true);
        return t;
    });

    /** An offline profile and the number of its writes still queued on the io thread. */
    private record Pending(Profile profile, AtomicInteger writes) {
    }

    public record Summary(UUID uuid, String name, int rankPoints, int level, long extracted, long kills, long money,
                          long marketSales, long marketIncome) {
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
            // A profile edited while offline may still be on its way to the disk: take that copy.
            Pending pending = offline.remove(uuid);
            profile = pending != null ? pending.profile() : load(uuid);
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

    /**
     * Reads a profile without caching it: the live one when its owner is online, otherwise a copy
     * from disk that must not be changed (use {@link #edit} for that).
     */
    public @Nullable Profile peek(UUID uuid) {
        Profile profile = cache.get(uuid);
        if (profile != null) {
            return profile;
        }
        Pending pending = offline.get(uuid);
        return pending != null ? pending.profile() : load(uuid);
    }

    /**
     * Account level without reading the disk: the live profile when its owner is online or was
     * just edited, otherwise the last saved summary; 0 for an unknown account.
     */
    public int level(UUID uuid) {
        Profile profile = cache.get(uuid);
        if (profile != null) {
            return profile.level;
        }
        Pending pending = offline.get(uuid);
        if (pending != null) {
            return pending.profile().level;
        }
        Summary summary = index.get(uuid);
        return summary == null ? 0 : summary.level();
    }

    /** Applies a mutation to a profile whether or not its owner is online. */
    public void edit(UUID uuid, Consumer<Profile> mutation) {
        Profile profile = cache.get(uuid);
        if (profile != null) {
            mutation.accept(profile);
            markDirty(uuid);
            return;
        }
        Pending pending = offline.get(uuid);
        if (pending == null) {
            Profile loaded = load(uuid);
            if (loaded == null) {
                return;
            }
            pending = new Pending(loaded, new AtomicInteger());
            offline.put(uuid, pending);
        }
        mutation.accept(pending.profile());
        writeAsync(pending.profile(), pending.writes());
    }

    public void markDirty(UUID uuid) {
        dirty.add(uuid);
    }

    public void unload(UUID uuid) {
        Profile profile = cache.remove(uuid);
        dirty.remove(uuid);
        if (profile != null) {
            // Kept like an offline edit until the write lands: a payout or a returned listing in
            // the same moment must build on this copy, not on the older file still on disk.
            Pending pending = new Pending(profile, new AtomicInteger());
            offline.put(uuid, pending);
            writeAsync(profile, pending.writes());
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
        // Only this thread queues writes, so a count of 0 means the file holds the latest copy.
        offline.values().removeIf(pending -> pending.writes().get() == 0);
    }

    public void shutdown() {
        // Let queued writes finish first: an older copy still in the queue would otherwise land
        // on top of the final save below.
        io.shutdown();
        try {
            io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        for (Profile profile : cache.values()) {
            writeSync(GSON.toJson(profile), profile.uuid);
        }
        dirty.clear();
        offline.clear();
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
        if (p.ips == null) p.ips = new ArrayList<>();
        if (p.unlockedOperators == null || p.unlockedOperators.isEmpty()) {
            p.unlockedOperators = new java.util.LinkedHashSet<>(List.of("vanguard", "medic", "recon", "engineer"));
        }
        if (p.name == null) p.name = "";
        if (p.operator == null) p.operator = "vanguard";
    }

    private void writeAsync(Profile profile) {
        writeAsync(profile, null);
    }

    /** Queues a snapshot of {@code profile}; {@code inFlight} counts it until it is on disk. */
    private void writeAsync(Profile profile, @Nullable AtomicInteger inFlight) {
        String json = GSON.toJson(profile);
        UUID uuid = profile.uuid;
        index.put(uuid, summarize(profile));
        if (inFlight != null) {
            inFlight.incrementAndGet();
        }
        io.execute(() -> {
            try {
                writeSync(json, uuid);
            } finally {
                if (inFlight != null) {
                    inFlight.decrementAndGet();
                }
            }
        });
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
        return new Summary(p.uuid, p.name, p.rankPoints, p.level, p.stat("extracted_value"), p.stat("kills"), p.money,
                p.stat("market_sales"), p.stat("market_income"));
    }
}
