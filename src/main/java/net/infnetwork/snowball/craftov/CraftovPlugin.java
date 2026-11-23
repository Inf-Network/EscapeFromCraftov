package net.infnetwork.snowball.craftov;

import net.infnetwork.snowball.craftov.ai.AiService;
import net.infnetwork.snowball.craftov.combat.DamageService;
import net.infnetwork.snowball.craftov.combat.DownedService;
import net.infnetwork.snowball.craftov.combat.HealthService;
import net.infnetwork.snowball.craftov.combat.HitboxHistory;
import net.infnetwork.snowball.craftov.combat.StaminaService;
import net.infnetwork.snowball.craftov.command.CraftovCommand;
import net.infnetwork.snowball.craftov.economy.MarketService;
import net.infnetwork.snowball.craftov.economy.TraderService;
import net.infnetwork.snowball.craftov.escort.EscortService;
import net.infnetwork.snowball.craftov.gui.MenuListener;
import net.infnetwork.snowball.craftov.gui.MenuService;
import net.infnetwork.snowball.craftov.hud.BetterHudBridge;
import net.infnetwork.snowball.craftov.hud.BetterHudHook;
import net.infnetwork.snowball.craftov.hud.HudService;
import net.infnetwork.snowball.craftov.inventory.CarryListener;
import net.infnetwork.snowball.craftov.inventory.CarryService;
import net.infnetwork.snowball.craftov.item.ItemFactory;
import net.infnetwork.snowball.craftov.item.ItemRegistry;
import net.infnetwork.snowball.craftov.lobby.LobbyService;
import net.infnetwork.snowball.craftov.loot.LootService;
import net.infnetwork.snowball.craftov.map.MapRegistry;
import net.infnetwork.snowball.craftov.map.WorldService;
import net.infnetwork.snowball.craftov.match.MatchService;
import net.infnetwork.snowball.craftov.operator.OperatorService;
import net.infnetwork.snowball.craftov.pack.PackService;
import net.infnetwork.snowball.craftov.profile.ProfileService;
import net.infnetwork.snowball.craftov.raid.RaidManager;
import net.infnetwork.snowball.craftov.raid.Tier;
import net.infnetwork.snowball.craftov.rank.CollectionService;
import net.infnetwork.snowball.craftov.rank.RankService;
import net.infnetwork.snowball.craftov.weapon.InspectService;
import net.infnetwork.snowball.craftov.weapon.ThrowableService;
import net.infnetwork.snowball.craftov.weapon.TriggerService;
import net.infnetwork.snowball.craftov.weapon.WeaponService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Team;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;

/** Craftov: an extraction-shooter game mode for Paper 1.21.11. */
public final class CraftovPlugin extends JavaPlugin {

    private static CraftovPlugin instance;

    /** Data files copied from the jar only when missing, and so never updated by a new jar. */
    private static final List<String> DATA_FILES = List.of("items.yml", "loot.yml");
    private static final Pattern REVISION = Pattern.compile("(?m)^revision:\\s*(\\d+)");

    private ItemRegistry registry;
    private ItemFactory items;
    private ProfileService profiles;
    private net.infnetwork.snowball.craftov.profile.MailService mail;
    private CarryService carry;
    private LootService loot;
    private MapRegistry maps;
    private WorldService worlds;
    private DamageService damage;
    private HitboxHistory hitboxes;
    private HealthService health;
    private StaminaService stamina;
    private TriggerService triggers;
    private net.infnetwork.snowball.craftov.weapon.StanceService stance;
    private net.infnetwork.snowball.craftov.weapon.TacticalLight lights;
    private net.infnetwork.snowball.craftov.replay.ReplayService replays;
    private net.infnetwork.snowball.craftov.raid.RaidEvents events;
    private net.infnetwork.snowball.craftov.ai.DroneService drones;
    private net.infnetwork.snowball.craftov.economy.InsuranceService insurance;
    private net.infnetwork.snowball.craftov.economy.HideoutService hideout;
    private net.infnetwork.snowball.craftov.util.Backups backups;
    private net.infnetwork.snowball.craftov.economy.BountyService bounties;
    private net.infnetwork.snowball.craftov.rank.SeasonService season;
    private net.infnetwork.snowball.craftov.lobby.TutorialService tutorial;
    private net.infnetwork.snowball.craftov.task.TaskService tasks;
    private net.infnetwork.snowball.craftov.luck.LuckService luck;
    private DownedService downed;
    private WeaponService weapons;
    private net.infnetwork.snowball.craftov.weapon.FovService fov;
    private RaidManager raids;
    private AiService ai;
    private LobbyService lobby;
    private net.infnetwork.snowball.craftov.lobby.RangeService range;
    private MatchService match;
    private TraderService traders;
    private MarketService market;
    private OperatorService operators;
    private RankService ranks;
    private CollectionService collection;
    private EscortService escorts;
    private HudService hud;
    private net.infnetwork.snowball.craftov.hud.KillFeedService killFeed;
    private MenuService menus;
    private net.infnetwork.snowball.craftov.map.editor.EditorService editor;
    private BetterHudHook betterHud = BetterHudHook.NONE;
    private final Map<String, Tier> tiers = new LinkedHashMap<>();

    public static CraftovPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        net.infnetwork.snowball.craftov.util.Nms.init(getLogger());
        net.infnetwork.snowball.craftov.util.Notify.start(this);
        long writeDelay = net.infnetwork.snowball.craftov.util.AtomicFiles.testDelayMs();
        if (writeDelay > 0) {
            getLogger().warning("Test hook on: every data file write waits " + writeDelay
                    + " ms (-Dcraftov.test.write-delay-ms). Never start a live server with it.");
        }
        saveDefaultConfig();
        if (ConfigMigration.migrate(getDataFolder(), getResource("config.yml"), getLogger())) {
            reloadConfig();
        }
        saveResourceIfMissing("items.yml");
        saveResourceIfMissing("loot.yml");
        for (OutdatedData old : outdatedData()) {
            getLogger().warning(old.file() + " is older than the one in this jar (revision " + old.local() + ", jar "
                    + old.bundled() + "): its prices and loot stay as they were. Back it up, replace it with the jar's"
                    + " copy (or merge the changes and set revision: " + old.bundled() + "), then /cv reload.");
        }
        loadTiers();
        registry = new ItemRegistry(getLogger());
        if (registry.load(new File(getDataFolder(), "items.yml")) != null) {
            // Running without an item table would empty every loadout it captures: stay off until
            // the file is fixed instead.
            getLogger().severe("Craftov is disabled because items.yml could not be loaded. Fix it and restart.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        items = new ItemFactory(registry);
        loot = new LootService(items, getLogger());
        loot.load(new File(getDataFolder(), "loot.yml"));
        profiles = new ProfileService(this);
        try {
            profiles.start();
        } catch (IOException ex) {
            getLogger().severe("Could not prepare profile storage: " + ex.getMessage());
        }
        mail = new net.infnetwork.snowball.craftov.profile.MailService(this);
        carry = new CarryService(this);
        maps = new MapRegistry(new File(getDataFolder(), "maps"), getLogger());
        maps.load();
        worlds = new WorldService(this, maps);
        worlds.cleanupStale();
        cleanupTeams();
        damage = new DamageService(this);
        hitboxes = new HitboxHistory(this);
        health = new HealthService(this);
        stamina = new StaminaService(this);
        downed = new DownedService(this);
        weapons = new WeaponService(this);
        fov = new net.infnetwork.snowball.craftov.weapon.FovService(this);
        triggers = new TriggerService(this);
        stance = new net.infnetwork.snowball.craftov.weapon.StanceService(this);
        lights = new net.infnetwork.snowball.craftov.weapon.TacticalLight(this);
        replays = new net.infnetwork.snowball.craftov.replay.ReplayService(this);
        events = new net.infnetwork.snowball.craftov.raid.RaidEvents(this);
        drones = new net.infnetwork.snowball.craftov.ai.DroneService(this);
        insurance = new net.infnetwork.snowball.craftov.economy.InsuranceService(this);
        hideout = new net.infnetwork.snowball.craftov.economy.HideoutService(this);
        bounties = new net.infnetwork.snowball.craftov.economy.BountyService(this);
        season = new net.infnetwork.snowball.craftov.rank.SeasonService(this);
        tutorial = new net.infnetwork.snowball.craftov.lobby.TutorialService(this);
        ai = new AiService(this);
        raids = new RaidManager(this);
        lobby = new LobbyService(this);
        range = new net.infnetwork.snowball.craftov.lobby.RangeService(this);
        match = new MatchService(this);
        traders = new TraderService(this);
        market = new MarketService(this);
        operators = new OperatorService(this);
        ranks = new RankService(this);
        collection = new CollectionService(this);
        escorts = new EscortService(this);
        tasks = new net.infnetwork.snowball.craftov.task.TaskService(this);
        luck = new net.infnetwork.snowball.craftov.luck.LuckService(this);
        hud = new HudService(this);
        killFeed = new net.infnetwork.snowball.craftov.hud.KillFeedService(this);
        menus = new MenuService(this);
        editor = new net.infnetwork.snowball.craftov.map.editor.EditorService(this);
        var adminGuard = new net.infnetwork.snowball.craftov.security.AdminGuardService(this);
        adminGuard.start();

        // Registration order matters for same-priority handlers: weapons flush before menus move items.
        register(adminGuard, editor, triggers, weapons, new MenuListener(this), new CarryListener(this, carry), damage, health, stamina, downed,
                raids, ai, lobby, range, operators, stance, lights, replays, drones, new ThrowableService(this),
                new InspectService(this), market, profiles, killFeed, fov);

        PluginCommand command = getCommand("craftov");
        CraftovCommand executor = new CraftovCommand(this);
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        PluginCommand party = getCommand("party");
        if (party != null) {
            party.setExecutor(executor);
            party.setTabCompleter(executor);
        }

        worlds.ensureBuiltins();
        maps.load();
        maps.warnCloseSpawns();
        maps.warnLiveProblems(Math.max(1, getConfig().getInt("match.max-squads", 4)));
        lobby.start();
        range.start();
        stance.start();
        lights.start();
        hitboxes.start();
        backups = new net.infnetwork.snowball.craftov.util.Backups(this);
        backups.start();
        market.start();
        escorts.start();
        new PackService(this).install();
        hookBetterHud();

        for (Player player : Bukkit.getOnlinePlayers()) {
            lobby.enterLobby(player);
            hud.onJoin(player);
        }
        getLogger().info("Craftov enabled: " + registry.all().size() + " items, " + maps.all().size() + " maps, "
                + tiers.size() + " difficulty tiers.");
    }

    @Override
    public void onDisable() {
        // Every step on its own: one throwing must not skip the refunds, saves and backup after it.
        step("network hooks", this, () -> net.infnetwork.snowball.craftov.util.Nms.shutdown(Bukkit.getOnlinePlayers()));
        step("betterhud", betterHud, () -> betterHud.shutdown());
        step("editor", editor, () -> editor.shutdown());
        step("replays", replays, () -> replays.shutdown());
        step("drones", drones, () -> drones.shutdown());
        step("raids", raids, () -> raids.shutdown());
        step("escorts", escorts, () -> escorts.shutdown());
        step("range", range, () -> range.shutdown());
        for (Player player : Bukkit.getOnlinePlayers()) {
            step("loadout of " + player.getName(), lobby, () -> {
                if (lobby.isLobby(player.getWorld())) {
                    lobby.captureLoadout(player);
                }
            });
        }
        // Offline edits made while sending everyone home and queued market saves (both go through
        // the profile writer) finish landing before the final saves. Should that wait time out, the
        // final saves are still the newest copies: an older one that comes up later is dropped.
        step("queued profile writes", profiles, () -> profiles.awaitWrites());
        step("market", market, () -> market.saveSync());
        step("profiles", profiles, () -> profiles.shutdown());
        step("backup", backups, () -> backups.run("shutdown"));
        step("teams", this, this::cleanupTeams);
    }

    /**
     * Runs one shutdown step if its service was created, logging instead of throwing. A linkage
     * error counts too: after the jar was replaced on disk a step can fail to load a class.
     */
    private void step(String name, @Nullable Object service, Runnable action) {
        if (service == null) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException | LinkageError ex) {
            getLogger().log(Level.SEVERE, "Shutdown step '" + name + "' failed; carrying on with the rest", ex);
        }
    }

    private void hookBetterHud() {
        if (Bukkit.getPluginManager().getPlugin("BetterHud") == null || !getConfig().getBoolean("hud.betterhud", true)) {
            return;
        }
        try {
            BetterHudBridge bridge = new BetterHudBridge(this, hud.data());
            bridge.register();
            betterHud = bridge;
            hud.setBetterHud(true);
            Bukkit.getScheduler().runTaskTimer(this, bridge::refreshPointers, 20L, 10L);
            getLogger().info("BetterHud detected: Craftov HUD placeholders registered.");
        } catch (Throwable ex) {
            getLogger().warning("BetterHud integration failed, falling back to the vanilla HUD: " + ex);
        }
    }

    private void register(Listener... listeners) {
        for (Listener listener : listeners) {
            Bukkit.getPluginManager().registerEvents(listener, this);
        }
    }

    private void saveResourceIfMissing(String name) {
        if (!new File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
    }

    /** A data file in the plugin folder whose revision is below the jar's copy. */
    public record OutdatedData(String file, int local, int bundled) {
    }

    /**
     * Data files in the plugin folder that are older than the jar's copy. items.yml and loot.yml
     * are copied only when missing, so after an update that changes prices or loot weights the
     * server would keep playing by its old copy without a word. Each file starts with
     * {@code revision: n}, raised whenever servers need the change; a file without it counts as 0.
     */
    public List<OutdatedData> outdatedData() {
        List<OutdatedData> out = new ArrayList<>();
        for (String name : DATA_FILES) {
            int bundled;
            try (InputStream in = getResource(name)) {
                bundled = in == null ? 0 : revision(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ex) {
                continue;
            }
            File file = new File(getDataFolder(), name);
            int local;
            try {
                local = revision(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            } catch (IOException ex) {
                continue; // missing or unreadable: the loader reports that itself
            }
            if (local < bundled) {
                out.add(new OutdatedData(name, local, bundled));
            }
        }
        return out;
    }

    private static int revision(String text) {
        Matcher m = REVISION.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private void cleanupTeams() {
        for (Team team : new ArrayList<>(Bukkit.getScoreboardManager().getMainScoreboard().getTeams())) {
            if (team.getName().startsWith("cvR")) {
                team.unregister();
            }
        }
    }

    public void loadTiers() {
        tiers.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("tiers");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(id);
                if (s != null) {
                    tiers.put(id, Tier.read(id, s));
                }
            }
        }
        if (tiers.isEmpty()) {
            tiers.put("NORMAL", new Tier("NORMAL", "常规", "#7FD1AE", 0, 0, 0.8, 0.55, 0, false, 1.0, 0));
        }
    }

    public Map<String, Tier> tiers() {
        return tiers;
    }

    public @Nullable Tier tier(String id) {
        return tiers.get(id);
    }

    public ItemRegistry registry() { return registry; }
    public ItemFactory items() { return items; }
    public ProfileService profiles() { return profiles; }
    public net.infnetwork.snowball.craftov.profile.MailService mail() { return mail; }
    public CarryService carry() { return carry; }
    public LootService loot() { return loot; }
    public MapRegistry maps() { return maps; }
    public WorldService worlds() { return worlds; }
    public DamageService damage() { return damage; }
    public HitboxHistory hitboxes() { return hitboxes; }
    public HealthService health() { return health; }
    public StaminaService stamina() { return stamina; }
    public TriggerService triggers() { return triggers; }
    public net.infnetwork.snowball.craftov.weapon.StanceService stance() { return stance; }
    public net.infnetwork.snowball.craftov.weapon.TacticalLight lights() { return lights; }
    public net.infnetwork.snowball.craftov.replay.ReplayService replays() { return replays; }
    public net.infnetwork.snowball.craftov.raid.RaidEvents events() { return events; }
    public net.infnetwork.snowball.craftov.ai.DroneService drones() { return drones; }
    public net.infnetwork.snowball.craftov.economy.InsuranceService insurance() { return insurance; }
    public net.infnetwork.snowball.craftov.economy.HideoutService hideout() { return hideout; }
    public net.infnetwork.snowball.craftov.economy.BountyService bounties() { return bounties; }
    public net.infnetwork.snowball.craftov.rank.SeasonService season() { return season; }
    public net.infnetwork.snowball.craftov.lobby.TutorialService tutorial() { return tutorial; }

    /** Whether the given player may use the free item catalog (test mode or admin). */
    public boolean canUseCatalog(Player player) {
        return getConfig().getBoolean("test.enabled", false) || player.hasPermission("craftov.admin");
    }

    /**
     * Whether the player may see and queue for this map. Only published maps (not an editor draft)
     * are open, and test maps (the proving ground, whose armory hands out one of everything) need
     * the same access as the catalog.
     */
    public boolean canPlay(Player player, net.infnetwork.snowball.craftov.map.MapDef map) {
        return map.enabled() && (!map.test() || canUseCatalog(player));
    }
    public DownedService downed() { return downed; }
    public WeaponService weapons() { return weapons; }
    public net.infnetwork.snowball.craftov.weapon.FovService fov() { return fov; }
    public RaidManager raids() { return raids; }
    public AiService ai() { return ai; }
    public LobbyService lobby() { return lobby; }
    public net.infnetwork.snowball.craftov.lobby.RangeService range() { return range; }
    public MatchService match() { return match; }
    public TraderService traders() { return traders; }
    public MarketService market() { return market; }
    public OperatorService operators() { return operators; }
    public RankService ranks() { return ranks; }
    public CollectionService collection() { return collection; }
    public EscortService escorts() { return escorts; }
    public net.infnetwork.snowball.craftov.task.TaskService tasks() { return tasks; }
    public net.infnetwork.snowball.craftov.luck.LuckService luck() { return luck; }
    public HudService hud() { return hud; }
    public net.infnetwork.snowball.craftov.hud.KillFeedService killFeed() { return killFeed; }
    public MenuService menus() { return menus; }
    public net.infnetwork.snowball.craftov.map.editor.EditorService editor() { return editor; }
    public BetterHudHook betterHud() { return betterHud; }
}
