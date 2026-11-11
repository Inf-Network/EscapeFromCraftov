package net.infnetwork.snowball.craftov;

import net.infnetwork.snowball.craftov.ai.AiService;
import net.infnetwork.snowball.craftov.combat.DamageService;
import net.infnetwork.snowball.craftov.combat.DownedService;
import net.infnetwork.snowball.craftov.combat.HealthService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Craftov: an extraction-shooter game mode for Paper 1.21.11. */
public final class CraftovPlugin extends JavaPlugin {

    private static CraftovPlugin instance;

    private ItemRegistry registry;
    private ItemFactory items;
    private ProfileService profiles;
    private CarryService carry;
    private LootService loot;
    private MapRegistry maps;
    private WorldService worlds;
    private DamageService damage;
    private HealthService health;
    private StaminaService stamina;
    private TriggerService triggers;
    private net.infnetwork.snowball.craftov.weapon.StanceService stance;
    private net.infnetwork.snowball.craftov.weapon.TacticalLight lights;
    private net.infnetwork.snowball.craftov.replay.ReplayService replays;
    private net.infnetwork.snowball.craftov.raid.RaidEvents events;
    private net.infnetwork.snowball.craftov.task.TaskService tasks;
    private DownedService downed;
    private WeaponService weapons;
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
        saveDefaultConfig();
        saveResourceIfMissing("items.yml");
        saveResourceIfMissing("loot.yml");
        loadTiers();
        registry = new ItemRegistry(getLogger());
        registry.load(new File(getDataFolder(), "items.yml"));
        items = new ItemFactory(registry);
        loot = new LootService(items, getLogger());
        loot.load(new File(getDataFolder(), "loot.yml"));
        profiles = new ProfileService(this);
        try {
            profiles.start();
        } catch (IOException ex) {
            getLogger().severe("Could not prepare profile storage: " + ex.getMessage());
        }
        carry = new CarryService(items);
        maps = new MapRegistry(new File(getDataFolder(), "maps"), getLogger());
        maps.load();
        worlds = new WorldService(this, maps);
        worlds.cleanupStale();
        cleanupTeams();
        damage = new DamageService(this);
        health = new HealthService(this);
        stamina = new StaminaService(this);
        downed = new DownedService(this);
        weapons = new WeaponService(this);
        triggers = new TriggerService(this);
        stance = new net.infnetwork.snowball.craftov.weapon.StanceService(this);
        lights = new net.infnetwork.snowball.craftov.weapon.TacticalLight(this);
        replays = new net.infnetwork.snowball.craftov.replay.ReplayService(this);
        events = new net.infnetwork.snowball.craftov.raid.RaidEvents(this);
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
        hud = new HudService(this);
        menus = new MenuService(this);
        editor = new net.infnetwork.snowball.craftov.map.editor.EditorService(this);

        // Registration order matters for same-priority handlers: weapons flush before menus move items.
        register(editor, triggers, weapons, new MenuListener(this), new CarryListener(this, carry), damage, health, stamina, downed,
                raids, ai, lobby, range, operators, stance, lights, replays, new ThrowableService(this));

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
        lobby.start();
        range.start();
        stance.start();
        lights.start();
        market.start();
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
        if (editor != null) {
            editor.shutdown();
        }
        if (replays != null) {
            replays.shutdown();
        }
        if (raids != null) {
            raids.shutdown();
        }
        if (escorts != null) {
            escorts.shutdown();
        }
        if (range != null) {
            range.shutdown();
        }
        if (lobby != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (lobby.isLobby(player.getWorld())) {
                    lobby.captureLoadout(player);
                }
            }
        }
        if (market != null) {
            market.saveSync();
        }
        if (profiles != null) {
            profiles.shutdown();
        }
        cleanupTeams();
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
            tiers.put("NORMAL", new Tier("NORMAL", "常规", "#5BD16B", 0, 0, 0.8, 0.55, 0, false, 1.0, 0));
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
    public CarryService carry() { return carry; }
    public LootService loot() { return loot; }
    public MapRegistry maps() { return maps; }
    public WorldService worlds() { return worlds; }
    public DamageService damage() { return damage; }
    public HealthService health() { return health; }
    public StaminaService stamina() { return stamina; }
    public TriggerService triggers() { return triggers; }
    public net.infnetwork.snowball.craftov.weapon.StanceService stance() { return stance; }
    public net.infnetwork.snowball.craftov.weapon.TacticalLight lights() { return lights; }
    public net.infnetwork.snowball.craftov.replay.ReplayService replays() { return replays; }
    public net.infnetwork.snowball.craftov.raid.RaidEvents events() { return events; }

    /** Whether the given player may use the free item catalog (test mode or admin). */
    public boolean canUseCatalog(Player player) {
        return getConfig().getBoolean("test.enabled", false) || player.hasPermission("craftov.admin");
    }
    public DownedService downed() { return downed; }
    public WeaponService weapons() { return weapons; }
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
    public HudService hud() { return hud; }
    public MenuService menus() { return menus; }
    public net.infnetwork.snowball.craftov.map.editor.EditorService editor() { return editor; }
    public BetterHudHook betterHud() { return betterHud; }
}
