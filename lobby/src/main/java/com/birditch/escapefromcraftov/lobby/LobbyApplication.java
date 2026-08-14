package com.birditch.escapefromcraftov.lobby;

import com.birditch.escapefromcraftov.api.CraftovPermissions;
import com.birditch.escapefromcraftov.api.network.MessageBroker;
import com.birditch.escapefromcraftov.api.profile.ProfileService;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.bukkit.BungeeConnector;
import com.birditch.escapefromcraftov.bukkit.CoreConfigMapper;
import com.birditch.escapefromcraftov.bukkit.Messages;
import com.birditch.escapefromcraftov.bukkit.PaperCraftovScheduler;
import com.birditch.escapefromcraftov.bukkit.RedisConfigMapper;
import com.birditch.escapefromcraftov.bukkit.ShutdownSteps;
import com.birditch.escapefromcraftov.bukkit.YamlResources;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.time.SystemTimeSource;
import com.birditch.escapefromcraftov.core.database.Database;
import com.birditch.escapefromcraftov.core.database.DatabaseConfig;
import com.birditch.escapefromcraftov.core.database.HibernateDatabase;
import com.birditch.escapefromcraftov.cosmetics.HibernateCosmeticsService;
import com.birditch.escapefromcraftov.lobby.command.LobbyCommand;
import com.birditch.escapefromcraftov.lobby.command.LobbyCommandContext;
import com.birditch.escapefromcraftov.lobby.listener.ConnectionDeps;
import com.birditch.escapefromcraftov.lobby.listener.LobbyConnectionListener;
import com.birditch.escapefromcraftov.lobby.listener.LobbyProtectionListener;
import com.birditch.escapefromcraftov.lobby.matchmaking.QueueDeps;
import com.birditch.escapefromcraftov.lobby.matchmaking.QueueService;
import com.birditch.escapefromcraftov.lobby.menu.LobbyMenuController;
import com.birditch.escapefromcraftov.lobby.menu.MenuDeps;
import com.birditch.escapefromcraftov.lobby.menu.MarketMenuController;
import com.birditch.escapefromcraftov.lobby.menu.MarketMenuDeps;
import com.birditch.escapefromcraftov.lobby.menu.RaidPreparationController;
import com.birditch.escapefromcraftov.lobby.hud.LobbySidebarService;
import com.birditch.escapefromcraftov.lobby.party.InMemoryPartyService;
import com.birditch.escapefromcraftov.lobby.party.PartyService;
import com.birditch.escapefromcraftov.redis.JedisMessageBroker;
import com.birditch.escapefromcraftov.redis.JsonCodec;
import com.birditch.escapefromcraftov.redis.RedisAssignmentStore;
import com.birditch.escapefromcraftov.redis.RedisConfig;
import com.birditch.escapefromcraftov.redis.RedisConnector;
import com.birditch.escapefromcraftov.redis.RedisInstanceDirectory;
import com.birditch.escapefromcraftov.profile.DefaultProfileService;
import com.birditch.escapefromcraftov.profile.HibernateProfileRepository;
import com.birditch.escapefromcraftov.profile.HibernateStarterKitService;
import com.birditch.escapefromcraftov.inventory.HibernatePlayerInventoryService;
import com.birditch.escapefromcraftov.item.DefaultItems;
import com.birditch.escapefromcraftov.item.SimpleItemRegistry;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.inventory.PlayerInventoryService;
import com.birditch.escapefromcraftov.bukkit.CraftovItemStacks;
import com.birditch.escapefromcraftov.bukkit.TrMenuBridge;
import com.birditch.escapefromcraftov.settings.HibernatePlayerSettingsService;
import com.birditch.escapefromcraftov.vendor.HibernateMilitaryVendorService;
import com.birditch.escapefromcraftov.vendor.MilitaryVendorService;
import com.birditch.escapefromcraftov.market.HibernatePlayerMarketService;
import com.birditch.escapefromcraftov.market.PlayerMarketService;
import com.birditch.escapefromcraftov.report.HibernateRaidReportService;
import com.birditch.escapefromcraftov.lobby.report.LobbyRaidReports;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.jspecify.annotations.Nullable;

/// Boots and tears down the lobby runtime: configs, Redis infrastructure,
/// proxy connector, PostgreSQL player data, matchmaking queue, listeners and
/// commands. Redis carries only transient cross-server coordination.
///
/// A dead Redis at boot never aborts startup: the probe logs SEVERE, the
/// plugin stays enabled and every Redis-backed service recovers on its own
/// once the connection returns. Both [#start] and [#stop] are main-thread only.
public final class LobbyApplication {

  private static final String PLAY_PERMISSION = CraftovPermissions.LOBBY_PLAY;
  private static final Duration REDIS_PROBE_TIMEOUT = Duration.ofSeconds(3);

  private final CraftovLobbyPlugin plugin;
  private final Logger logger;

  private @Nullable ExecutorService ioExecutor;
  private @Nullable Database database;
  private @Nullable ProfileService profileService;
  private @Nullable RedisConnector redisConnector;
  private @Nullable MessageBroker broker;
  private @Nullable QueueService queueService;
  private @Nullable BungeeConnector bungeeConnector;
  private @Nullable LobbyMenuController menus;
  private @Nullable MarketMenuController marketMenus;
  private @Nullable RaidPreparationController raidPreparation;
  private @Nullable ScheduledTask marketExpiryTask;
  private @Nullable LobbySidebarService sidebar;

  public LobbyApplication(CraftovLobbyPlugin plugin) {
    this.plugin = Preconditions.checkNotNull(plugin, "plugin");
    this.logger = plugin.getLogger();
  }

  /// Called once from [CraftovLobbyPlugin#onEnable]; any thrown exception aborts
  /// startup and the plugin disables itself.
  public void start() {
    banner();
    BootConfig configs = loadConfigs();
    LobbyConfig config = configs.lobby();
    Messages messages = configs.messages();
    TrMenuBridge trMenu = new TrMenuBridge(plugin);
    trMenu.requireAvailable();
    boolean installed = trMenu.installDefault(
        "trmenu/escapefromcraftov_lobby_main.yml", "escapefromcraftov_lobby_main.yml");
    installed |= trMenu.installDefault(
        "trmenu/escapefromcraftov_lobby_settings.yml", "escapefromcraftov_lobby_settings.yml");
    installed |= trMenu.installDefault(
        "trmenu/escapefromcraftov_lobby_cosmetics.yml", "escapefromcraftov_lobby_cosmetics.yml");
    installed |= trMenu.installDefault(
        "trmenu/escapefromcraftov_lobby_warehouse.yml", "escapefromcraftov_lobby_warehouse.yml");
    installed |= trMenu.installDefault(
        "trmenu/escapefromcraftov_lobby_preparation.yml",
        "escapefromcraftov_lobby_preparation.yml");
    installed |= trMenu.installDefault(
        "trmenu/escapefromcraftov_lobby_vendor.yml", "escapefromcraftov_lobby_vendor.yml");
    installed |= trMenu.installDefault(
        "trmenu/escapefromcraftov_lobby_vendor_sell.yml",
        "escapefromcraftov_lobby_vendor_sell.yml");
    installed |= trMenu.installDefault(
        "trmenu/escapefromcraftov_lobby_market.yml", "escapefromcraftov_lobby_market.yml");
    installed |= trMenu.installDefault(
        "trmenu/escapefromcraftov_lobby_market_mine.yml",
        "escapefromcraftov_lobby_market_mine.yml");
    installed |= trMenu.installDefault(
        "trmenu/escapefromcraftov_lobby_market_list.yml",
        "escapefromcraftov_lobby_market_list.yml");
    installed |= trMenu.installDefault(
        "trmenu/escapefromcraftov_lobby_market_confirm.yml",
        "escapefromcraftov_lobby_market_confirm.yml");
    if (installed) trMenu.reloadMenus();
    trMenu.requireMenusLoaded("escapefromcraftov_lobby_main", "escapefromcraftov_lobby_settings",
        "escapefromcraftov_lobby_cosmetics", "escapefromcraftov_lobby_warehouse",
        "escapefromcraftov_lobby_preparation",
        "escapefromcraftov_lobby_vendor", "escapefromcraftov_lobby_vendor_sell",
        "escapefromcraftov_lobby_market", "escapefromcraftov_lobby_market_mine",
        "escapefromcraftov_lobby_market_list", "escapefromcraftov_lobby_market_confirm");
    logger.info("Menu provider: " + trMenu.status()
        + " (all EscapeFromCraftov inventory menus are rendered by TrMenu)");
    PaperCraftovScheduler scheduler = new PaperCraftovScheduler(plugin);
    SystemTimeSource timeSource = new SystemTimeSource();
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    ioExecutor = executor;
    Database db = createDatabase(configs.database());
    database = db;
    HibernateProfileRepository profileRepository = new HibernateProfileRepository(db, executor);
    ProfileService profiles =
        new DefaultProfileService(profileRepository, timeSource, logger);
    profileService = profiles;
    HibernatePlayerSettingsService settings =
        new HibernatePlayerSettingsService(db, executor);
    HibernateCosmeticsService cosmetics =
        new HibernateCosmeticsService(db, executor, LobbyCosmetics.catalog());
    SimpleItemRegistry itemRegistry = new SimpleItemRegistry();
    DefaultItems.registerAll(itemRegistry);
    CraftovItemStacks itemStacks = new CraftovItemStacks(itemRegistry);
    PlayerInventoryService inventories = new HibernatePlayerInventoryService(
        db, executor, itemRegistry);
    MilitaryVendorService vendor = new HibernateMilitaryVendorService(
        db, executor, itemRegistry, timeSource);
    PlayerMarketService market = new HibernatePlayerMarketService(db, executor, timeSource);
    LobbyRaidReports raidReports = new LobbyRaidReports(
        new HibernateRaidReportService(db, executor), scheduler);
    marketExpiryTask = scheduler.runTimer(Duration.ofSeconds(10), Duration.ofMinutes(1), () ->
        market.expireDue().exceptionally(error -> {
          logger.log(Level.WARNING, "回收过期交易行挂单失败", error);
          return 0;
        }));
    HibernateStarterKitService starterKit = new HibernateStarterKitService(
        db, executor, itemRegistry, timeSource);
    LobbyDataGate dataGate = new LobbyDataGate();
    RedisConnector redis = new RedisConnector(configs.redis());
    redisConnector = redis;
    JsonCodec codec = new JsonCodec();
    RedisInstanceDirectory directory = new RedisInstanceDirectory(redis, codec, executor, logger);
    RedisAssignmentStore assignments = new RedisAssignmentStore(redis, codec, executor);
    JedisMessageBroker messageBroker = new JedisMessageBroker(redis, logger);
    broker = messageBroker;
    BungeeConnector bungee = new BungeeConnector(plugin);
    bungeeConnector = bungee;
    QueueService queue = new QueueService(new QueueDeps(directory, assignments, messageBroker,
        new BukkitPlayerGateway(plugin, bungee, messages), scheduler, timeSource, settings,
        dataGate, config, codec, logger));
    queueService = queue;
    queue.start();
    // Process-local by design for the first Party loop. The PartyService API
    // keeps commands ready for a Redis implementation when Lobby is replicated.
    PartyService parties = new InMemoryPartyService();
    MenuDeps menuDeps =
        new MenuDeps(settings, cosmetics, inventories, profiles, vendor, itemRegistry,
            itemStacks, queue, scheduler, config, dataGate, messages, plugin, logger);
    LobbyMenuController menuController = new LobbyMenuController(menuDeps, trMenu);
    menus = menuController;
    RaidPreparationController preparationController = new RaidPreparationController(
        inventories, itemRegistry, scheduler, config, messages, trMenu, logger);
    raidPreparation = preparationController;
    MarketMenuController marketMenuController = new MarketMenuController(new MarketMenuDeps(
        market, inventories, profiles, itemRegistry, itemStacks, scheduler, dataGate,
        messages, logger), trMenu);
    marketMenus = marketMenuController;
    ConnectionDeps connectionDeps = new ConnectionDeps(plugin, scheduler, messages, profiles,
        starterKit, settings, cosmetics, inventories, queue, parties, dataGate,
        menuController::openMain, logger);
    registerListeners(connectionDeps, menuController, marketMenuController,
        preparationController, config);
    LobbySidebarService sidebarService = new LobbySidebarService(plugin, profiles, inventories,
        queue, dataGate, scheduler, messages, config, logger);
    sidebarService.start();
    sidebar = sidebarService;
    registerPermissions();
    registerCommands(new LobbyCommandContext(
        plugin, queue, directory, messages, config, dataGate,
        menuController, marketMenuController, preparationController, raidReports, parties));
    logSummary(config, configs.database(), configs.redis(), directory);
  }

  /// Guarded teardown; each step failure is logged and the remaining steps run.
  public void stop() {
    ShutdownSteps steps = new ShutdownSteps(logger);
    steps.run("stop market expiry worker", () -> {
      if (marketExpiryTask != null) {
        marketExpiryTask.cancel();
        marketExpiryTask = null;
      }
    });
    steps.run("stop queue", () -> {
      if (queueService != null) {
        queueService.stop();
      }
    });
    steps.run("stop dynamic Lobby Sidebar", () -> {
      if (sidebar != null) {
        sidebar.close();
        sidebar = null;
      }
    });
    steps.run("close TrMenu lobby controller", () -> {
      if (menus != null) {
        menus.close();
        menus = null;
      }
    });
    steps.run("close TrMenu market controller", () -> {
      if (marketMenus != null) {
        marketMenus.close();
        marketMenus = null;
      }
    });
    steps.run("close TrMenu raid preparation controller", () -> {
      if (raidPreparation != null) {
        raidPreparation.close();
        raidPreparation = null;
      }
    });
    steps.run("save profiles", this::saveAllProfiles);
    steps.run("unregister proxy channel", () -> {
      if (bungeeConnector != null) {
        bungeeConnector.unregister();
      }
    });
    steps.run("close message broker", () -> {
      if (broker != null) {
        broker.close();
      }
    });
    steps.run("close redis", () -> {
      if (redisConnector != null) {
        redisConnector.close();
      }
    });
    ExecutorService executor = ioExecutor;
    if (executor != null) {
      steps.shutdownExecutor("shut down IO executor", executor);
    }
    steps.run("close database", () -> {
      if (database != null) {
        database.close();
      }
    });
    steps.run("unregister listeners", () -> HandlerList.unregisterAll(plugin));
    steps.run("cancel scheduled tasks", () -> Bukkit.getScheduler().cancelTasks(plugin));
    logger.info("EscapeFromCraftovLobby 已卸载。");
  }

  private BootConfig loadConfigs() {
    var configYaml = YamlResources.loadMainConfig(
        plugin, Map.of("database", "database.yml", "redis", "redis.yml"));
    LobbyConfig config = LobbyConfigMapper.read(configYaml);
    config.validate();
    DatabaseConfig databaseConfig = CoreConfigMapper.readDatabaseConfig(configYaml);
    databaseConfig.validate();
    RedisConfig redisConfig = RedisConfigMapper.read(configYaml);
    redisConfig.validate();
    Messages messages =
        new Messages(CoreConfigMapper.readMessages(YamlResources.loadOrSaveDefault(plugin, "messages.yml")));
    return new BootConfig(config, databaseConfig, redisConfig, messages);
  }

  private Database createDatabase(DatabaseConfig databaseConfig) {
    try {
      return new HibernateDatabase(databaseConfig);
    } catch (RuntimeException e) {
      logger.severe(() -> "无法连接 PostgreSQL 数据库("
          + databaseConfig.host() + ":" + databaseConfig.port()
          + "/" + databaseConfig.database() + ")。Lobby 与长期玩家数据强依赖 PostgreSQL，本次启动已中止。");
      throw e;
    }
  }

  private void saveAllProfiles() throws Exception {
    ProfileService profiles = profileService;
    if (profiles != null) {
      profiles.saveAll().get(10, TimeUnit.SECONDS);
    }
  }

  private void registerListeners(ConnectionDeps connectionDeps,
      LobbyMenuController menuController,
      MarketMenuController marketMenuController,
      RaidPreparationController preparationController,
      LobbyConfig config) {
    PluginManager manager = plugin.getServer().getPluginManager();
    manager.registerEvents(new LobbyConnectionListener(connectionDeps), plugin);
    manager.registerEvents(new LobbyProtectionListener(config), plugin);
    manager.registerEvents(menuController, plugin);
    manager.registerEvents(marketMenuController, plugin);
    manager.registerEvents(preparationController, plugin);
  }

  private void registerPermissions() {
    PluginManager manager = plugin.getServer().getPluginManager();
    addPermission(manager, new Permission(
        PLAY_PERMISSION, "Allows queueing for craftovs from the lobby.", PermissionDefault.TRUE));
    addPermission(manager, new Permission(
        CraftovPermissions.ADMIN, "Bypasses lobby protection.", PermissionDefault.OP));
  }

  private static void addPermission(PluginManager manager, Permission permission) {
    if (manager.getPermission(permission.getName()) == null) {
      manager.addPermission(permission);
    }
  }

  private void registerCommands(LobbyCommandContext context) {
    plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
      for (LiteralCommandNode<CommandSourceStack> root : LobbyCommand.build(context)) {
        event.registrar().register(root);
      }
    });
  }

  private void banner() {
    logger.info("========================================");
    logger.info("  Escape from Craftov · 逃离块科夫 — 大厅");
    logger.info("========================================");
  }

  private void logSummary(LobbyConfig config, DatabaseConfig databaseConfig,
      RedisConfig redisConfig,
      RedisInstanceDirectory directory) {
    boolean redisUp = probeRedis(directory, redisConfig);
    logger.info(() -> "EscapeFromCraftovLobby v" + plugin.getPluginMeta().getVersion()
        + ",Minecraft " + Bukkit.getMinecraftVersion()
        + "(Java " + Runtime.version().feature() + ")");
    logger.info(() -> "Redis:" + redisConfig.host() + ":" + redisConfig.port()
        + (redisUp ? " 连接正常" : " 连接失败"));
    logger.info(() -> "PostgreSQL:" + databaseConfig.host() + ":" + databaseConfig.port()
        + "/" + databaseConfig.database() + " 连接正常");
    logger.info(() -> "匹配轮询:" + config.queueTickSeconds()
        + " 秒 / 装扮:" + LobbyCosmetics.catalog().size() + " 款");
    logger.info("EscapeFromCraftovLobby 启动完成。");
  }

  /// One-time blocking probe at boot (acceptable, mirrors the game server's
  /// schema init); a failure only downgrades the summary line to FAILED.
  private boolean probeRedis(RedisInstanceDirectory directory, RedisConfig redisConfig) {
    try {
      directory.activeServers().get(REDIS_PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logRedisDown(redisConfig, e);
      return false;
    } catch (ExecutionException | TimeoutException e) {
      logRedisDown(redisConfig, e);
      return false;
    }
  }

  private void logRedisDown(RedisConfig redisConfig, Exception cause) {
    logger.log(Level.SEVERE, "无法连接 Redis(" + redisConfig.host() + ":"
        + redisConfig.port() + ")。大厅保持运行,但匹配已暂停;Redis 恢复后自动继续。", cause);
  }

  private record BootConfig(
      LobbyConfig lobby, DatabaseConfig database, RedisConfig redis, Messages messages) {}
}
