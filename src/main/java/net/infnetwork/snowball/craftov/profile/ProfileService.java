package net.infnetwork.snowball.craftov.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.infnetwork.snowball.craftov.util.AtomicFiles;
import net.infnetwork.snowball.craftov.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
 *
 * <p>A profile file that exists but cannot be read is never replaced: it stays where it is, a copy
 * goes next to it as {@code <uuid>.json.corrupt-<time>}, and its owner cannot log in until an admin
 * repairs the file (or deletes it to start the account over). Changes meant for such an account
 * are written to the log instead of being dropped silently.
 */
public final class ProfileService implements Listener {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String LOCKED = "<#FF4D4D>你的档案读取失败，为免覆盖原有数据已暂停登录。\n\n"
            + "<#9AA0A6>原文件已原样保留，请联系管理员修复。";

    private final Plugin plugin;
    private final Path dir;
    private final Map<UUID, Profile> cache = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final Map<UUID, Summary> index = new HashMap<>();
    /**
     * Offline profiles changed through {@link #edit} or just unloaded on logout, kept until every
     * write queued for them has reached the disk. Without this, back-to-back edits (several market
     * listings of one offline seller returned or sold in the same tick) each re-read a file the
     * previous write had not replaced yet, and all but one of the changes were lost. Main thread only.
     */
    private final Map<UUID, Pending> offline = new HashMap<>();
    /**
     * Stand-ins cached for an online player whose file could not be read (until the kick lands):
     * never written, so the broken file is not replaced by an empty account. Main thread only.
     */
    private final Set<Profile> placeholders = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Craftov-ProfileIO");
        t.setDaemon(true);
        return t;
    });

    /** An offline profile and the number of its writes still queued on the io thread. */
    private record Pending(Profile profile, AtomicInteger writes) {
    }

    /** What leaderboards and start-up checks need of every saved profile, kept without loading it. */
    public record Summary(UUID uuid, String name, int rankPoints, int level, long extracted, long kills, long money,
                          long marketSales, long marketIncome, long escortEscrow) {
    }

    public ProfileService(Plugin plugin) {
        this.plugin = plugin;
        this.dir = plugin.getDataFolder().toPath().resolve("data/players");
    }

    public void start() throws IOException {
        Files.createDirectories(dir);
        AtomicFiles.cleanTemp(dir);
        File[] files = dir.toFile().listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                Profile profile = read(file.toPath());
                if (profile != null) {
                    index.put(profile.uuid, summarize(profile));
                } else {
                    quarantine(file.toPath());
                }
            }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushDirty, 200L, 200L);
    }

    /** Keeps the owner of an unreadable profile out before they reach the lobby (async thread). */
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        Path file = file(event.getUniqueId());
        if (Files.exists(file) && read(file) == null) {
            quarantine(file);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Text.mm(LOCKED));
        }
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
                if (Files.exists(file(uuid))) {
                    lockOut(profile);
                }
            }
            cache.put(uuid, profile);
        }
        if (name != null && !name.isEmpty()) {
            profile.name = name;
        }
        return profile;
    }

    /**
     * The file is there but unreadable (it broke after the login check, or the plugin was reloaded
     * with its owner online): the session runs on a stand-in that is never saved, and the player
     * is sent away on the next tick, before the stand-in can get a starter kit or be played on.
     */
    private void lockOut(Profile placeholder) {
        placeholder.receivedStarter = true;
        placeholders.add(placeholder);
        UUID uuid = placeholder.uuid;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.kick(Text.mm(LOCKED));
            }
        });
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

    /**
     * Applies a mutation to a profile whether or not its owner is online. When the profile cannot
     * be loaded the change is written to the log, so an admin can apply it by hand.
     */
    public void edit(UUID uuid, Consumer<Profile> mutation) {
        Profile profile = cache.get(uuid);
        if (profile != null) {
            if (placeholders.contains(profile)) {
                logLostEdit(uuid, mutation);
                return;
            }
            mutation.accept(profile);
            markDirty(uuid);
            return;
        }
        Pending pending = offline.get(uuid);
        if (pending == null) {
            Profile loaded = load(uuid);
            if (loaded == null) {
                logLostEdit(uuid, mutation);
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
        if (profile != null && !placeholders.remove(profile)) {
            // Until this write lands, a market sale or escort payout to the player who just left
            // (or their next login) must build on this copy: the file still holds an older one,
            // and a change built on that would be written over the whole session.
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
            if (!placeholders.contains(profile)) {
                writeSync(GSON.toJson(profile), profile.uuid);
            }
        }
        dirty.clear();
        offline.clear();
    }

    public List<Summary> leaderboard(Comparator<Summary> order, int limit) {
        for (Profile profile : cache.values()) {
            if (!placeholders.contains(profile)) {
                index.put(profile.uuid, summarize(profile));
            }
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

    private Path file(UUID uuid) {
        return dir.resolve(uuid + ".json");
    }

    /** The saved profile; null when there is none or it cannot be read (see {@link #quarantine}). */
    private @Nullable Profile load(UUID uuid) {
        Path file = file(uuid);
        if (!Files.exists(file)) {
            return null;
        }
        Profile profile = read(file);
        if (profile == null) {
            quarantine(file);
        }
        return profile;
    }

    /** Parses a profile file; null (and a log line) when it is empty, malformed or has no uuid. Any thread. */
    private @Nullable Profile read(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Profile profile = GSON.fromJson(reader, Profile.class);
            if (profile == null || profile.uuid == null) {
                plugin.getLogger().severe("Could not read profile " + file.getFileName() + ": "
                        + (profile == null ? "the file is empty" : "it has no uuid"));
                return null;
            }
            normalize(profile);
            return profile;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().severe("Could not read profile " + file.getFileName() + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * Keeps a copy of an unreadable profile next to it as {@code <name>.corrupt-<time>}, once per
     * distinct content (a player retrying or a restart adds nothing). The file itself is left
     * alone: nothing writes it while it cannot be read, and its owner is kept out until it is
     * repaired. Any thread.
     */
    private synchronized void quarantine(Path file) {
        String name = file.getFileName().toString();
        try {
            byte[] content = Files.readAllBytes(file);
            Path copy = null;
            try (DirectoryStream<Path> copies = Files.newDirectoryStream(dir, name + ".corrupt-*")) {
                for (Path existing : copies) {
                    if (Arrays.equals(Files.readAllBytes(existing), content)) {
                        copy = existing;
                        break;
                    }
                }
            }
            if (copy == null) {
                copy = dir.resolve(name + ".corrupt-" + LocalDateTime.now().format(STAMP));
                try {
                    Files.copy(file, copy);
                } catch (FileAlreadyExistsException ex) {
                    copy = dir.resolve(copy.getFileName() + "-" + System.nanoTime());
                    Files.copy(file, copy);
                }
            }
            plugin.getLogger().severe("==== Profile " + name + " is unreadable. It will not be overwritten and its"
                    + " owner cannot log in until it is repaired (or deleted to start that account over). A copy is"
                    + " kept as " + copy.getFileName() + ". ====");
        } catch (IOException ex) {
            plugin.getLogger().severe("==== Profile " + name + " is unreadable and could not be copied aside ("
                    + ex.getMessage() + "). It will not be overwritten and its owner cannot log in until it is repaired. ====");
        }
    }

    /**
     * Logs a change for a profile that could not be loaded: the fields it sets when replayed on an
     * empty profile, which for counters (money, stats) is the amount it adds.
     */
    private void logLostEdit(UUID uuid, Consumer<Profile> mutation) {
        String change;
        try {
            Profile blank = new Profile(uuid, "");
            JsonObject before = GSON.toJsonTree(blank).getAsJsonObject();
            mutation.accept(blank);
            JsonObject after = GSON.toJsonTree(blank).getAsJsonObject();
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, JsonElement> entry : after.entrySet()) {
                if (!entry.getValue().equals(before.get(entry.getKey()))) {
                    out.append(out.isEmpty() ? "" : ", ").append(entry.getKey()).append('=').append(entry.getValue());
                }
            }
            change = out.isEmpty() ? "(nothing visible)" : out.toString();
        } catch (RuntimeException ex) {
            change = "(could not be replayed: " + ex + ")";
        }
        String why = Files.exists(file(uuid)) ? "is unreadable" : "does not exist";
        plugin.getLogger().severe("Profile " + uuid + " " + why + "; this change to it was NOT saved, apply it by hand"
                + " (amounts are what it adds): " + change);
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
        if (placeholders.contains(profile)) {
            return;
        }
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
            AtomicFiles.write(file(uuid), json);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save profile " + uuid + ": " + ex.getMessage());
        }
    }

    private static Summary summarize(Profile p) {
        return new Summary(p.uuid, p.name, p.rankPoints, p.level, p.stat("extracted_value"), p.stat("kills"), p.money,
                p.stat("market_sales"), p.stat("market_income"), p.escortEscrow);
    }

    /** Saved profiles matching {@code filter}, from the summaries (no disk reads). */
    public List<Summary> summaries(java.util.function.Predicate<Summary> filter) {
        return index.values().stream().filter(filter).toList();
    }
}
