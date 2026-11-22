package net.infnetwork.snowball.craftov.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * JSON-file profile storage. Profiles of online players stay cached; offline profiles are loaded
 * on demand (market payouts, escort settlements) and written straight back, and stay in memory
 * until that write has landed so the next change builds on this one.
 *
 * <p>A profile file that exists but cannot be read is never replaced: it stays where it is, a copy
 * goes next to it as {@code <uuid>.json.corrupt-<time>}, and its owner cannot log in until an admin
 * repairs the file (or deletes it to start the account over). A change that has to happen to such
 * an account anyway (an escort settlement, an insurance claim) goes into a journal next to it,
 * {@code <uuid>.json.unsaved}, one line per change in the order they happened, and the account
 * stays locked while that file exists: the admin applies the lines to the repaired file and
 * deletes the journal, so a repaired file can never be played (or have its escrow returned) on
 * without them.
 */
public final class ProfileService implements Listener {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** One-line JSON for the journal, in the same form the profile file uses. */
    private static final Gson LINE = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String JOURNAL = ".unsaved";
    private static final String LOCKED = "<danger>你的档案读取失败，为免覆盖原有数据已暂停登录。\n\n"
            + "<muted>原文件已原样保留，请联系管理员修复。";

    private final Plugin plugin;
    private final Path dir;
    private final Map<UUID, Profile> cache = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final Map<UUID, Summary> index = new HashMap<>();
    /**
     * Offline profiles changed through {@link #edit} or just {@link #unload unloaded}, used instead
     * of the file while a write queued for them has not reached the disk. Without this, back-to-back
     * edits (several market listings of one offline seller returned or sold in the same tick, a
     * sale right as the seller logs off, or the shutdown restoring a player who disconnected
     * mid-raid, paying out their insurance and refunding their escort contract one after another)
     * each re-read a file the previous write had not replaced yet, and all but one of the changes
     * were lost. Once the writes have landed the file is read again, so a file edited by hand
     * while its owner is offline still counts. Main thread only.
     */
    private final Map<UUID, Pending> offline = new HashMap<>();
    /**
     * Stand-ins cached for an online player whose file could not be read (until the kick lands):
     * never written, so the broken file is not replaced by an empty account. Main thread only.
     */
    private final Set<Profile> placeholders = Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * Profiles found unreadable, with the size and time of the file at that read. Such a file is
     * read (and reported) again only once it has changed, so a player retrying the login or the
     * market checking a broken seller every tick does not re-read it and every corrupt copy each
     * time. Any thread.
     */
    private final Map<UUID, Stamp> unreadable = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Craftov-ProfileIO");
        t.setDaemon(true);
        return t;
    });

    /** An offline profile and the number of its writes still queued on the io thread. */
    private record Pending(Profile profile, AtomicInteger writes) {
    }

    /** Size and modification time of a file, to notice it was changed (repaired). */
    private record Stamp(long modified, long size) {
        static @Nullable Stamp of(Path file) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                return new Stamp(attrs.lastModifiedTime().toMillis(), attrs.size());
            } catch (IOException ex) {
                return null;
            }
        }
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
                    broken(file.toPath());
                }
            }
        }
        String[] journals = dir.toFile().list((d, name) -> name.endsWith(".json" + JOURNAL));
        if (journals != null && journals.length > 0) {
            Arrays.sort(journals);
            plugin.getLogger().severe("==== " + journals.length + " profile(s) have changes that could not be saved to them,"
                    + " waiting in data/players: " + String.join(", ", journals) + ". Their owners cannot log in until each"
                    + " file's lines are applied to the profile next to it and the file is deleted. ====");
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushDirty, 200L, 200L);
    }

    /** Keeps the owner of an unreadable profile out before they reach the lobby (async thread). */
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        String why = Files.exists(journal(uuid)) ? "has changes waiting in " + journal(uuid).getFileName()
                : Files.exists(file(uuid)) && load(uuid) == null ? "is unreadable" : null;
        if (why != null) {
            plugin.getLogger().warning("Refused the login of " + event.getName() + ": profile " + uuid + ".json " + why + ".");
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
            Pending pending = inFlight(uuid);
            offline.remove(uuid);
            profile = pending != null ? pending.profile() : load(uuid);
            if (profile == null) {
                profile = new Profile(uuid, name);
                if (Files.exists(file(uuid)) || Files.exists(journal(uuid))) {
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
        Pending pending = inFlight(uuid);
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
        Pending pending = inFlight(uuid);
        if (pending != null) {
            return pending.profile().level;
        }
        Summary summary = index.get(uuid);
        return summary == null ? 0 : summary.level();
    }

    /**
     * Applies a mutation to a profile whether or not its owner is online. When the profile cannot
     * be used, {@code change} goes into the account's journal instead (see the class comment). It
     * tells an admin what to do to the file by hand, so it has to state the mutation exactly:
     * {@code field +n} or {@code field -n} for an amount added to or taken from the current value,
     * {@code field = value} for a value that replaces it, and every item in full as JSON.
     */
    public void edit(UUID uuid, Supplier<String> change, Consumer<Profile> mutation) {
        if (!apply(uuid, mutation)) {
            hold(uuid, change);
        }
    }

    /**
     * Like {@link #edit}, but a change for an account that is locked (its file unreadable, or a
     * journal waiting) is not journaled: this returns false and changes nothing, so the caller
     * keeps what it was about to hand over (a market listing) and tries again later.
     */
    public boolean tryEdit(UUID uuid, Supplier<String> change, Consumer<Profile> mutation) {
        if (apply(uuid, mutation)) {
            return true;
        }
        if (Files.exists(file(uuid)) || Files.exists(journal(uuid))) {
            return false;
        }
        hold(uuid, change); // no such account: only logged
        return true;
    }

    /**
     * The one place a profile is changed for {@link #edit} and {@link #tryEdit}: the live profile
     * is marked for the next flush, an offline one is written now and kept in {@link #offline}
     * until that write has landed. False, changing nothing, when the profile cannot be used.
     */
    private boolean apply(UUID uuid, Consumer<Profile> mutation) {
        Profile profile = editable(uuid);
        if (profile == null) {
            return false;
        }
        mutation.accept(profile);
        Pending pending = offline.get(uuid);
        if (pending != null && pending.profile() == profile) {
            writeAsync(profile, pending.writes());
        } else {
            markDirty(uuid);
        }
        return true;
    }

    /**
     * Whether {@code uuid}'s profile can be changed now: it exists, can be read and has no journal.
     * Cheap for a file already found broken, which is only read again once it has changed.
     */
    public boolean canEdit(UUID uuid) {
        return editable(uuid) != null;
    }

    /**
     * The profile changes to {@code uuid} are made on: the live one, or an offline one kept in
     * {@link #offline} while a write of it is queued (otherwise loaded from the file now, and
     * dropped again once nothing of it is left to write); null when it cannot be used or does not
     * exist.
     */
    private @Nullable Profile editable(UUID uuid) {
        Profile profile = cache.get(uuid);
        if (profile != null) {
            return placeholders.contains(profile) ? null : profile;
        }
        Pending pending = inFlight(uuid);
        if (pending == null) {
            Profile loaded = load(uuid);
            if (loaded == null) {
                return null;
            }
            pending = new Pending(loaded, new AtomicInteger());
            offline.put(uuid, pending);
        }
        return pending.profile();
    }

    /**
     * The in-memory copy of an offline profile while a write of it is still queued, else null.
     * Only this thread queues writes, so a count of 0 means the file holds that copy.
     */
    private @Nullable Pending inFlight(UUID uuid) {
        Pending pending = offline.get(uuid);
        if (pending == null || pending.writes().get() > 0) {
            return pending;
        }
        offline.remove(uuid);
        return null;
    }

    /** A value as one line of JSON, the way the profile file holds it (for change descriptions). */
    public static String json(Object value) {
        return LINE.toJson(value);
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

    /**
     * Runs another file write (the market's) on the profile writer, after every write queued so far
     * and before any queued later, so a change that spans both files decides which of them reaches
     * the disk first. Once the writer has stopped (shutdown, after {@link #awaitWrites}) it runs at
     * once. Main thread only.
     */
    public void queueWrite(Runnable write) {
        if (io.isShutdown()) {
            write.run();
            return;
        }
        io.execute(write);
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

    /**
     * Stops taking writes and waits up to 10 s for the queued ones to land. Runs before the final
     * save: an older copy still in the queue would otherwise land on top of it.
     */
    public void awaitWrites() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Queued profile writes did not finish in time.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Final save of every cached profile (stand-ins excepted), after {@link #awaitWrites}. */
    public void shutdown() {
        if (!io.isShutdown()) {
            awaitWrites();
        }
        for (Profile profile : cache.values()) {
            if (placeholders.contains(profile)) {
                continue;
            }
            // One profile that cannot be written must not cost the others theirs.
            try {
                writeSync(GSON.toJson(profile), profile.uuid);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not save profile " + profile.uuid, ex);
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

    private Path journal(UUID uuid) {
        return dir.resolve(uuid + ".json" + JOURNAL);
    }

    /**
     * The saved profile; null when there is none or it cannot be used: the file is unreadable (see
     * {@link #quarantine}) or changes wait in its journal. Any thread.
     */
    private @Nullable Profile load(UUID uuid) {
        Path file = file(uuid);
        if (!Files.exists(file)) {
            unreadable.remove(uuid);
            return null;
        }
        if (Files.exists(journal(uuid))) {
            return null;
        }
        Stamp stamp = Stamp.of(file);
        if (stamp != null && stamp.equals(unreadable.get(uuid))) {
            return null; // still the broken content reported before
        }
        Profile profile = read(file);
        if (profile == null) {
            broken(file);
        } else {
            unreadable.remove(uuid);
        }
        return profile;
    }

    /** Remembers an unreadable profile file (see {@link #unreadable}) and copies it aside. Any thread. */
    private void broken(Path file) {
        Stamp stamp = Stamp.of(file);
        String name = file.getFileName().toString();
        try {
            UUID uuid = UUID.fromString(name.substring(0, name.length() - ".json".length()));
            if (stamp != null) {
                unreadable.put(uuid, stamp);
            }
        } catch (IllegalArgumentException ignored) {
            // not named after a player: nothing will look it up
        }
        quarantine(file);
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
     * A change for a profile that cannot be used: appended to its journal (created with a short
     * explanation on top), which keeps the account locked until an admin has applied it. With no
     * file and no journal there is no account to apply it to (an admin deleted it to start over):
     * the change is only logged.
     */
    private void hold(UUID uuid, Supplier<String> change) {
        String what;
        try {
            what = change.get();
        } catch (RuntimeException ex) {
            what = "(could not be described: " + ex + ")";
        }
        Path journal = journal(uuid);
        boolean fresh = !Files.exists(journal);
        if (fresh && !Files.exists(file(uuid))) {
            plugin.getLogger().warning("Profile " + uuid + " does not exist; this change to it was dropped: " + what);
            return;
        }
        String text = (fresh ? "# Changes that could not be saved to " + uuid + ".json because it could not be read.\n"
                + "# Its owner cannot log in while this file exists. Repair the profile (plugins/Craftov/backups\n"
                + "# has earlier copies), apply every line below to it in order, then delete this file.\n"
                + "# \"+n\" or \"-n\" changes the current value by that much, \"= value\" replaces it.\n" : "")
                + LocalDateTime.now().format(WHEN) + "  " + what + "\n";
        try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
            plugin.getLogger().severe("Profile " + uuid + ".json cannot be used; this change was NOT saved to it and"
                    + " waits in " + journal.getFileName() + ": " + what);
        } catch (IOException ex) {
            plugin.getLogger().severe("Profile " + uuid + ".json cannot be used; this change was NOT saved to it and"
                    + " could not be written to " + journal.getFileName() + " either (" + ex.getMessage() + "). Apply it"
                    + " by hand before its owner logs in again: " + what);
        }
    }

    private static void normalize(Profile p) {
        if (p.stash == null) p.stash = new ArrayList<>();
        if (p.loadout == null) p.loadout = new HashMap<>();
        if (p.secure == null) p.secure = new ArrayList<>();
        if (p.mailbox == null) p.mailbox = new ArrayList<>();
        if (p.claims == null) p.claims = new ArrayList<>();
        p.claims.removeIf(c -> c == null || c.item == null);
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
